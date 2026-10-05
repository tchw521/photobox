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
    /**
     * 六套**浅色**皮肤。
     *
     * 浅色玻璃的做法与深色相反：玻璃层用**白色半透明**叠在浅色渐变背景上，
     * 描边用低透明度深色勾边（而非白色），文字统一深色。
     * 这样既能保持通透的液态观感，又不会在亮背景下糊成一片。
     */
    val ALL = listOf(
        Skin("mint", "薄荷清新",
            0xFFF2FBF7.toInt(), 0xFFE8F6EF.toInt(), 0xFFDDEFE6.toInt(),
            0x4010B981.toInt(), 0x2A6EE7B7.toInt(),
            0xCCFFFFFF.toInt(), 0xE8FFFFFF.toInt(), 0x2E0F766E.toInt(),
            0xFF059669.toInt(), 0x3310B981.toInt(),
            0xFF12211C.toInt(), 0xFF5B7A6E.toInt(),
            0xFFDC2626.toInt(), 0xFF0F766E.toInt()),
        Skin("ocean", "深海幽蓝",
            0xFFF1F8FD.toInt(), 0xFFE6F2FB.toInt(), 0xFFD8EAF6.toInt(),
            0x400EA5E9.toInt(), 0x2A7DD3FC.toInt(),
            0xCCFFFFFF.toInt(), 0xE8FFFFFF.toInt(), 0x2E0C4A6E.toInt(),
            0xFF0284C7.toInt(), 0x330EA5E9.toInt(),
            0xFF0F1E2A.toInt(), 0xFF57738A.toInt(),
            0xFFDC2626.toInt(), 0xFF0E7490.toInt()),
        Skin("grape", "葡萄紫韵",
            0xFFF7F4FD.toInt(), 0xFFEFEAFA.toInt(), 0xFFE4DDF6.toInt(),
            0x408B5CF6.toInt(), 0x2AC4B5FD.toInt(),
            0xCCFFFFFF.toInt(), 0xE8FFFFFF.toInt(), 0x2E4C1D95.toInt(),
            0xFF7C3AED.toInt(), 0x338B5CF6.toInt(),
            0xFF1B1533.toInt(), 0xFF6E5F92.toInt(),
            0xFFDC2626.toInt(), 0xFF6D28D9.toInt()),
        Skin("sakura", "樱花粉",
            0xFFFDF3F8.toInt(), 0xFFFAE9F1.toInt(), 0xFFF6DEE9.toInt(),
            0x40EC4899.toInt(), 0x2AF9A8D4.toInt(),
            0xCCFFFFFF.toInt(), 0xE8FFFFFF.toInt(), 0x2E831843.toInt(),
            0xFFDB2777.toInt(), 0x33EC4899.toInt(),
            0xFF2A1620.toInt(), 0xFF8C6478.toInt(),
            0xFFDC2626.toInt(), 0xFFBE185D.toInt()),
        Skin("amber", "琥珀暖橙",
            0xFFFEF8F0.toInt(), 0xFFFDF1E3.toInt(), 0xFFFAE7D2.toInt(),
            0x40F59E0B.toInt(), 0x2AFCD34D.toInt(),
            0xCCFFFFFF.toInt(), 0xE8FFFFFF.toInt(), 0x2E78350F.toInt(),
            0xFFD97706.toInt(), 0x33F59E0B.toInt(),
            0xFF2A1E10.toInt(), 0xFF8A6C4A.toInt(),
            0xFFDC2626.toInt(), 0xFFB45309.toInt()),
        Skin("forest", "森林墨绿",
            0xFFF0F7F2.toInt(), 0xFFE4F0E8.toInt(), 0xFFD6E6DC.toInt(),
            0x40059669.toInt(), 0x2A34D399.toInt(),
            0xCCFFFFFF.toInt(), 0xE8FFFFFF.toInt(), 0x2E064E3B.toInt(),
            0xFF047857.toInt(), 0x33059669.toInt(),
            0xFF0E1F18.toInt(), 0xFF567A68.toInt(),
            0xFFDC2626.toInt(), 0xFF065F46.toInt()),
    )

    fun of(key: String): Skin = ALL.firstOrNull { it.key == key } ?: ALL[0]

    private const val P = "photobox"
    private const val K = "skin"

    fun currentKey(c: Context): String =
        c.getSharedPreferences(P, Context.MODE_PRIVATE).getString(K, "mint") ?: "mint"

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
        // 顶部高光：模拟玻璃上沿折射
        val gloss = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x66FFFFFF, 0x1AFFFFFF, 0x00FFFFFF)
        ).apply { cornerRadius = radius }
        // 底部反光：模拟环境反射，制造厚度感
        val bottom = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0x20FFFFFF, 0x00FFFFFF)
        ).apply { cornerRadius = radius }
        return LayerDrawable(arrayOf(body, gloss, bottom))
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

    /**
     * 方框气泡：圆角小方块，撑满容器宽度。
     * 玻璃质感 + 专属色描边，选中时填充该色并加深描边。
     */
    /**
     * 悬浮液态玻璃：用于底部导航等浮层。
     * 相比普通卡片加重底部反光，视觉上像一片浮在内容之上的玻璃。
     */
    fun floating(s: Skin, radius: Float): Drawable {
        val body = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(s.glassStrong)
            setStroke(1, s.stroke)
        }
        val gloss = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x33FFFFFF, 0x12FFFFFF, 0x00FFFFFF)
        ).apply { cornerRadius = radius }
        val bottom = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0x2AFFFFFF, 0x00FFFFFF)
        ).apply { cornerRadius = radius }
        return LayerDrawable(arrayOf(body, gloss, bottom))
    }

    fun block(s: Skin, tone: Int, selected: Boolean): Drawable {
        val fill = if (selected) (tone and 0x00FFFFFF) or 0x20000000 else s.glass
        val body = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f
            setColor(fill)
            setStroke(if (selected) 2 else 1, if (selected) tone else (tone and 0x00FFFFFF) or 0x66000000)
        }
        val gloss = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x22FFFFFF, 0x00FFFFFF)
        ).apply { cornerRadius = 10f }
        return LayerDrawable(arrayOf(body, gloss))
    }

    /** 色卡预览（设置页皮肤选择）。 */
    fun swatch(s: Skin): Drawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(s.bgTop, s.accent, s.bgBottom)
        ).apply { cornerRadius = 26f }
}
