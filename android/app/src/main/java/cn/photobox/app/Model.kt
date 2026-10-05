package cn.photobox.app

import android.content.Context
import android.util.TypedValue
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一张照片：全部基于 MediaStore，不持有 Bitmap，内存占用极低。 */
data class Photo(
    val id: Long,
    val name: String,
    val album: String,
    val size: Long,
    val dateSec: Long,
    val path: String,
) {
    val month: String get() = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date(dateSec * 1000))
    val dateText: String get() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(dateSec * 1000))
}

/** 回收站条目：原始文件已复制到应用私有目录，元数据存 JSON。 */
data class TrashItem(
    val id: String,
    val name: String,
    val album: String,
    val size: Long,
    val at: Long,
    val file: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("album", album)
        put("size", size); put("at", at); put("file", file)
    }

    companion object {
        fun from(o: JSONObject): TrashItem = TrashItem(
            o.optString("id"), o.optString("name"), o.optString("album"),
            o.optLong("size"), o.optLong("at"), o.optString("file")
        )
    }
}

/** 从当前主题取色，避免硬编码导致换肤后不跟随。全项目复用此函数。 */
fun resolveColor(c: Context, attr: Int): Int {
    val v = TypedValue()
    return if (c.theme.resolveAttribute(attr, v, true)) v.data else 0
}

fun formatSize(n: Long): String =
    if (n >= 1024 * 1024) String.format(Locale.getDefault(), "%.1f MB", n / 1048576.0)
    else "${(n / 1024).coerceAtLeast(1)} KB"

/** 轻量持久化：收藏 / 屏蔽 / 回收站 / 设置，全部走 SharedPreferences。 */
object Store {
    private const val P = "photobox"
    private fun sp(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)

    // ---- 收藏
    fun favorites(c: Context): MutableSet<String> = sp(c).getStringSet("fav", emptySet())!!.toMutableSet()
    fun setFavorites(c: Context, s: Set<String>) = sp(c).edit().putStringSet("fav", s).apply()

    // ---- 屏蔽图集
    fun blocked(c: Context): MutableSet<String> = sp(c).getStringSet("blocked", emptySet())!!.toMutableSet()
    fun setBlocked(c: Context, s: Set<String>) = sp(c).edit().putStringSet("blocked", s).apply()

    // ---- 回收站
    fun trash(c: Context): MutableList<TrashItem> {
        val raw = sp(c).getString("trash", "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = ArrayList<TrashItem>(arr.length())
        for (i in 0 until arr.length()) out.add(TrashItem.from(arr.getJSONObject(i)))
        return out
    }

    fun saveTrash(c: Context, list: List<TrashItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp(c).edit().putString("trash", arr.toString()).apply()
    }

    // ---- 设置
    var previewActions = true
    var defaultGrid = true
    var cardModeMove = true

    fun loadSettings(c: Context) {
        val s = sp(c)
        previewActions = s.getBoolean("previewActions", true)
        defaultGrid = s.getBoolean("defaultGrid", true)
        cardModeMove = s.getBoolean("cardModeMove", true)
    }

    fun saveSettings(c: Context) {
        sp(c).edit()
            .putBoolean("previewActions", previewActions)
            .putBoolean("defaultGrid", defaultGrid)
            .putBoolean("cardModeMove", cardModeMove)
            .apply()
    }
}
