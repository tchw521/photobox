package cn.photobox.app

import android.app.RecoverableSecurityException

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import java.io.File
import java.io.FileOutputStream

/**
 * 照片仓库：全部通过 MediaStore 访问，不递归遍历文件夹，
 * 不常驻任何 Bitmap，扫描速度和数据量无关地保持低内存。
 */
object Repo {

    fun uriOf(p: Photo) = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, p.id)

    /**
     * 扫描全部图片。
     *
     * 要点：
     * - 一律用 getColumnIndex（返回 -1）而非 getColumnIndexOrThrow（抛异常）。
     *   BUCKET_DISPLAY_NAME 在部分系统 / OEM 上可能不在结果集中，
     *   用 OrThrow 版本会直接抛出并导致启动崩溃。
     * - 整体包 try/catch，任何异常都降级为空列表，绝不把异常抛给 UI。
     */
    fun scan(c: Context): List<Photo> {
        val out = ArrayList<Photo>(512)
        try {
            val proj = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            )
            c.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                MediaStore.Images.Media.DATE_MODIFIED + " DESC"
            )?.use { cur ->
                val iId = cur.getColumnIndex(MediaStore.Images.Media._ID)
                val iName = cur.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val iSize = cur.getColumnIndex(MediaStore.Images.Media.SIZE)
                val iDate = cur.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                val iBucket = cur.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                if (iId < 0) return out                      // 关键列缺失，直接返回空
                while (cur.moveToNext()) {
                    val id = cur.getLong(iId)
                    if (id <= 0L) continue
                    // bucket 列缺失或为空时回退为「未分类」，不再 continue 丢照片
                    val album = if (iBucket >= 0) (cur.getString(iBucket) ?: "") else ""
                    out.add(
                        Photo(
                            id = id,
                            name = if (iName >= 0) (cur.getString(iName) ?: "") else "",
                            album = album.ifBlank { "未分类" },
                            size = if (iSize >= 0) cur.getLong(iSize) else 0L,
                            dateSec = if (iDate >= 0) cur.getLong(iDate) else 0L,
                            path = "",
                        )
                    )
                }
            }
        } catch (e: Throwable) {
            CrashGuard.log(e)
        }
        return out
    }

    /** 归类：把照片复制到目标相册（Pictures/<album>），原图随后可清理。 */
    fun copyToAlbum(c: Context, p: Photo, album: String): Boolean {
        return try {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, p.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/*")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$album")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                ?: return false
            c.contentResolver.openInputStream(uriOf(p))?.use { src ->
                c.contentResolver.openOutputStream(uri)?.use { dst -> src.copyTo(dst) }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                cv.clear()
                cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                c.contentResolver.update(uri, cv, null, null)
            }
            true
        } catch (e: RecoverableSecurityException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 真正移动：直接改写媒体库的 RELATIVE_PATH，把照片从原图集挪到目标图集。
     * 不是「复制 + 删除原图」，所以原图不会进回收站。
     */
    fun moveToAlbum(c: Context, p: Photo, album: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$album")
            }
            c.contentResolver.update(uriOf(p), cv, null, null) > 0
        } catch (e: RecoverableSecurityException) {
            throw e                       // 交给 Ui.write 转系统授权弹窗
        } catch (e: Exception) {
            false
        }
    }

    /** 清理到回收站：先复制进私有目录，再删除系统媒体。 */
    fun moveToTrash(c: Context, p: Photo, onConsent: ((android.app.RecoverableSecurityException) -> Unit)? = null): TrashItem? {
        val dir = File(c.filesDir, "trash").apply { mkdirs() }
        val item = TrashItem(
            id = "${System.currentTimeMillis()}_${p.id}",
            name = p.name, album = p.album, size = p.size,
            at = System.currentTimeMillis() / 1000,
            file = File(dir, "${System.currentTimeMillis()}_${p.id}_${p.name}").absolutePath
        )
        return try {
            FileOutputStream(item.file).use { out ->
                c.contentResolver.openInputStream(uriOf(p))?.use { it.copyTo(out) }
            }
            try {
                deleteFromSystem(c, p)
            } catch (e: android.app.RecoverableSecurityException) {
                // Android 11+ 删除他方媒体需用户确认
                onConsent?.invoke(e)
            }
            item
        } catch (e: RecoverableSecurityException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** 从系统相册删除；Android 11+ 可能需要用户授权，由调用方捕获处理。 */
    fun deleteFromSystem(c: Context, p: Photo) {
        c.contentResolver.delete(uriOf(p), null, null)
    }

    /** 还原：把回收站文件写回系统相册。 */
    fun restore(c: Context, item: TrashItem): Boolean {
        return try {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, item.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/*")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/${item.album}")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                ?: return false
            c.contentResolver.openOutputStream(uri)?.use { dst ->
                File(item.file).inputStream().use { it.copyTo(dst) }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                cv.clear()
                cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                c.contentResolver.update(uri, cv, null, null)
            }
            File(item.file).delete()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun trashThumb(c: Context, item: TrashItem, px: Int): Bitmap? = decodeFile(item.file, px)

    /** 通用降采样解码：按目标尺寸计算 inSampleSize，避免整图进内存。 */
    fun decodeFile(path: String, px: Int): Bitmap? {
        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opt)
        opt.inJustDecodeBounds = false
        opt.inSampleSize = calcSample(opt.outWidth, opt.outHeight, px)
        opt.inPreferredConfig = Bitmap.Config.RGB_565
        return BitmapFactory.decodeFile(path, opt)
    }

    fun decodeStream(c: Context, p: Photo, px: Int): Bitmap? {
        val uri = uriOf(p)
        val head = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, head) }
        val opt = BitmapFactory.Options().apply {
            inSampleSize = calcSample(head.outWidth, head.outHeight, px)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
    }

    fun systemThumb(c: Context, p: Photo, px: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                c.contentResolver.loadThumbnail(uriOf(p), Size(px, px), null)
            } catch (e: Exception) {
                null
            }
        } else null

    private fun calcSample(w: Int, h: Int, px: Int): Int {
        var s = 1
        val max = maxOf(w, h)
        while (max / (s * 2) > px) s *= 2
        return s.coerceIn(1, 32)
    }
}
