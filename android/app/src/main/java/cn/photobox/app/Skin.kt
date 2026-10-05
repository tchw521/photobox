package cn.photobox.app

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable

/**
 * 皮肤：全部以代码中的颜色值定义，**不在 XML 里使用主题属性 ?attr**。
 *
 * 这样做的原因：ColorStateList / drawable XML 中的 `?attr/` 由资源框架解析并缓存，
 * 解析时机不受 setTheme() 控制，在部分设备上 inflate 时直接抛异常，
 * 是此前多次崩溃的根因。改为代码持有颜色后，主题解析完全可控。
 *
 * 换肤流程：SkinNow.apply → 存偏好 → Activity.recreate() → 各页面重新取色绘制。
 */
data class Skin(
    val key: String,
    val name: String,
    // 背景三段渐变
    val bgTop: Int, val bgMid: Int, val bgBottom: Int,
    // 两处径向光晕
    val glow: Int, val glow2: Int,
    // 玻璃层
    val glass: Int, val glassStrong: Int, val stroke: Int,
    // 语义色
    val accent: Int, val accentSoft: Int,
    val text: Int, val textDim: Int,
    val danger: Int, val ok: Int,
)

object Skins {
    val ALL = listOf(
        Skin("aurora", "极光紫",
            0xFF1E0B38.toInt(), 0xFF14082A.toInt(), 0xFF0A0416.toInt(),
            0x4DC084FC.toInt(), 0x2E6366F1.toInt(),
            0x1FFFFFFF.toInt(), 0x2EFFFFFF.toInt(), 0x2BFFFFFF.toInt(),
            0xFFC084FC.toInt(), 0x3DC084FC.toInt(),
            0xFFF7F3FF.toInt(), 0xFFA08FC4.toInt(),
            0xFFFB7185.toInt(), 0xFF4ADE80.toInt()),
        Skin("ink", "墨夜黑",
            0xFF1A1A1E.toInt(), 0xFF121215.toInt(), 0xFF08080A.toInt(),
            0x2664E3E3.toInt(), 0x1A8B7FD4.toInt(),
            0x1CFFFFFF.toInt(), 0x2AFFFFFF.toInt(), 0x26FFFFFF.toInt(),
            0xFFE8E8EC.toInt(), 0x33E8E8EC.toInt(),
            0xFFF2F2F5.toInt(), 0xFF97979F.toInt(),
            0xFFEF4444.toInt(), 0xFF22C55E.toInt()),
        Skin("dawn", "晨曦金",
            0xFF3A2210.toInt(), 0xFF28170B.toInt(), 0xFF160C06.toInt(),
            0x4DFBBF24.toInt(), 0x2EE0559F.toInt(),
            0x22FFFFFF.toInt(), 0x30FFFFFF.toInt(), 0x2EFFFFFF.toInt(),
            0xFFFCD34D.toInt(), 0x3DFCD34D.toInt(),
            0xFFFFF8EE.toInt(), 0xFFC4A580.toInt(),
            0xFFF87171.toInt(), 0xFF86EFAC.toInt()),
        Skin("glacier", "冰川蓝",
            0xFF0E2A44.toInt(), 0xFF0A1E31.toInt(), 0xFF050F1A.toInt(),
            0x4D38BDF8.toInt(), 0x2E22D3EE.toInt(),
            0x1FFFFFFF.toInt(), 0x2EFFFFFF.toInt(), 0x2BFFFFFF.toInt(),
            0xFF7DD3FC.toInt(), 0x3D7DD3FC.toInt(),
            0xFFEFF8FF.toInt(), 0xFF87A5C0.toInt(),
            0xFFFB7185.toInt(), 0xFF34D399.toInt()),
        Skin("sakura", "樱雾粉",
            0xFF3B1428.toInt(), 0xFF2A0E1D.toInt(), 0xFF15070F.toInt(),
            0x4DF472B6.toInt(), 0x2EC084FC.toInt(),
            0x22FFFFFF.toInt(), 0x30FFFFFF.toInt(), 0x2EFFFFFF.toInt(),
            0xFFF9A8D4.toInt(), 0x3DF9A8D4.toInt(),
            0xFFFFF2F7.toInt(), 0xFFC490AC.toInt(),
            0xFFFB7185.toInt(), 0xFF86EFAC.toInt()),
        Skin("mint", "薄荷绿",
            0xFF0E2B22.toInt(), 0xFF0A1F18.toInt(), 0xFF05100C.toInt(),
            0x4D34D399.toInt(), 0x2E14B8A6.toInt(),
            0x1FFFFFFF.toInt(), 0x2EFFFFFF.toInt(), 0x2BFFFFFF.toInt(),
            0xFF6EE7B7.toInt(), 0x3D6EE7B7.toInt(),
            0xFFEFFFF6.toInt(), 0xFF81AF9D.toInt(),
            0xFFFB7185.toInt(), 0xFF4ADE80.toInt()),
    )

    fun of(key: String): Skin = ALL.firstOrNull { it.key == key } ?: ALL[0]

    private const val P = "photobox"
    private const val K = "skin"

    fun currentKey(c: Context): String =
        c.getSharedPreferences(P, Context.MODE_PRIVATE).getString(K, "aurora") ?: "aurora"

    fun save(c: Context, key: String) {
        c.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putString(K, key).apply()
    }
}

/** 当前皮肤。启动时加载一次，全局通过 SkinNow.skin 读取。 */
object SkinNow {
    var skin: Skin = Skins.ALL[0]
        private set

    fun load(c: Context) {
        skin = Skins.of(Skins.currentKey(c))
    }

    fun apply(c: Context, key: String) {
        Skins.save(c, key)
        skin = Skins.of(key)
    }
}

/**
 * 玻璃质感绘制工具。全项目复用，避免各处重复构造 drawable。
 *
 * 玻璃的做法：半透明填充 + 顶部高光渐变 + 细描边。
 * 不用实时模糊——模糊在部分机型上吃内存且表现不一致，
 * 渐变高光能达到同样的通透观感，且零额外开销。
 */
object Glass {

    /** 应用背景：三段线性渐变 + 两处径向光晕。 */
    fun background(s: Skin): Drawable {
        val base = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(s.bgTop, s.bgMid, s.bgBottom)
        )
        val g1 = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 340f
            setGradientCenter(0.18f, 0.12f)
            colors = intArrayOf(s.glow, 0x00000000)
        }
        val g2 = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 300f
            setGradientCenter(0.88f, 0.94f)
            colors = intArrayOf(s.glow2, 0x00000000)
        }
        return LayerDrawable(arrayOf(base, g1, g2))
    }

    /** 玻璃容器：半透明底 + 顶部高光 + 描边。 */
    fun card(s: Skin, radius: Float, strong: Boolean = false): Drawable {
        val fill = if (strong) s.glassStrong else s.glass
        val body = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fill)
            setStroke(1, s.stroke)
        }
        val gloss = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x26FFFFFF, 0x0AFFFFFF, 0x00FFFFFF)
        ).apply { cornerRadius = radius }
        return LayerDrawable(arrayOf(body, gloss))
    }

    /** 胶囊气泡（侧栏相册项）。 */
    fun bubble(s: Skin, selected: Boolean): Drawable {
        val fill = if (selected) s.accentSoft else s.glass
        val edge = if (selected) s.accent else s.stroke
        val body = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(fill)
            setStroke(if (selected) 2 else 1, edge)
        }
        val gloss = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x22FFFFFF, 0x00FFFFFF)
        ).apply { cornerRadius = 999f }
        return LayerDrawable(arrayOf(body, gloss))
    }

    /** 纯色块（图标底、按钮等）。 */
    fun solid(color: Int, radius: Float): Drawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }

    /** 圆形（选中打勾）。 */
    fun oval(color: Int): Drawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    /** 色卡预览（设置页皮肤选择）。 */
    fun swatch(s: Skin): Drawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(s.bgTop, s.accent, s.bgBottom)
        ).apply { cornerRadius = 26f }
}
