package cn.photobox.app

import android.content.Context

/**
 * 皮肤（主题）定义。
 *
 * 换肤走 Android 标准的「主题属性 + setTheme + recreate」路线：
 * 每个皮肤对应一个 style（见 res/values/styles.xml），
 * style 为一组自定义 attr（bgTopColor / glassColor / accentColor …）赋值；
 * 所有 drawable 与布局都通过 ?attr 读取，因此 setTheme 后重建界面即可整套变色，
 * 不需要反射、不需要手动遍历 View、也不会有颜色漏改。
 *
 * 这里只保存皮肤的元信息（key / 名称 / 色卡预览 / style 资源 id）。
 */
data class Skin(
    val key: String,
    val name: String,
    val style: Int,          // 对应的 Theme style 资源 id
    val swatchTop: Int,      // 色卡预览渐变起点
    val swatchAccent: Int,   // 色卡预览强调色
    val swatchBottom: Int,   // 色卡预览渐变终点
)

object Skins {
    val ALL = listOf(
        Skin("aurora", "极光紫", R.style.Skin_Aurora,
            0xFF1E0B38.toInt(), 0xFFC084FC.toInt(), 0xFF0A0416.toInt()),
        Skin("ink", "墨夜黑", R.style.Skin_Ink,
            0xFF1A1A1E.toInt(), 0xFFE8E8EC.toInt(), 0xFF08080A.toInt()),
        Skin("dawn", "晨曦金", R.style.Skin_Dawn,
            0xFF3A2210.toInt(), 0xFFFCD34D.toInt(), 0xFF160C06.toInt()),
        Skin("glacier", "冰川蓝", R.style.Skin_Glacier,
            0xFF0E2A44.toInt(), 0xFF7DD3FC.toInt(), 0xFF050F1A.toInt()),
        Skin("sakura", "樱雾粉", R.style.Skin_Sakura,
            0xFF3B1428.toInt(), 0xFFF9A8D4.toInt(), 0xFF15070F.toInt()),
        Skin("mint", "薄荷绿", R.style.Skin_Mint,
            0xFF0E2B22.toInt(), 0xFF6EE7B7.toInt(), 0xFF05100C.toInt()),
    )

    fun of(key: String): Skin = ALL.firstOrNull { it.key == key } ?: ALL[0]

    fun style(key: String): Int = of(key).style

    // ---- 持久化
    private const val P = "photobox"
    private const val K = "skin"

    fun currentKey(c: Context): String =
        c.getSharedPreferences(P, Context.MODE_PRIVATE).getString(K, "aurora") ?: "aurora"

    fun save(c: Context, key: String) {
        c.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putString(K, key).apply()
    }
}

/** 当前皮肤，启动时加载一次，全局读取。 */
object SkinNow {
    var skin: Skin = Skins.ALL[0]
        private set

    fun load(c: Context) { skin = Skins.of(Skins.currentKey(c)) }

    fun apply(c: Context, key: String) {
        Skins.save(c, key)
        skin = Skins.of(key)
    }
}
