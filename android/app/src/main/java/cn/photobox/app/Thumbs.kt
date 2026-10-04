package cn.photobox.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 缩略图加载器：
 * - LruCache 上限为可用内存的 1/8，自动回收
 * - RGB_565 位图，比默认 ARGB_8888 省一半内存
 * - 优先复用系统缩略图（Android 10+），无系统缩略图时按尺寸降采样
 * - 复用同一个线程池，避免大量图片时线程爆炸
 */
object Thumbs {
    private val main = Handler(Looper.getMainLooper())
    private val pool: ExecutorService = Executors.newFixedThreadPool(3)

    private val cache: LruCache<Long, Bitmap> by lazy {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
        object : LruCache<Long, Bitmap>(maxKb) {
            override fun sizeOf(key: Long, value: Bitmap) = value.byteCount / 1024
        }
    }

    fun clear() = cache.evictAll()

    /** 加载并显示；命中缓存时直接同步设置，否则异步解码。 */
    fun into(c: Context, p: Photo, px: Int, view: ImageView) {
        cache.get(p.id)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        view.tag = p.id
        pool.execute {
            val bmp = Repo.systemThumb(c, p, px) ?: Repo.decodeStream(c, p, px)
            if (bmp != null) cache.put(p.id, bmp)
            main.post {
                if (view.tag == p.id) view.setImageBitmap(bmp)
            }
        }
    }

    fun file(path: String, px: Int, view: ImageView) {
        pool.execute {
            val bmp = Repo.decodeFile(path, px)
            main.post { view.setImageBitmap(bmp) }
        }
    }
}

/** 主线程调度小工具。 */
fun post(f: () -> Unit) = Handler(Looper.getMainLooper()).post(f)
