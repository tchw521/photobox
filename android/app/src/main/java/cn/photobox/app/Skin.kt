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
            0xFF241041.toInt(), 0xFF1A0B33.toInt(), 0xFF0C0619.toInt(),
            0x5EC084FC.toInt(), 0x3A6366F1.toInt(),
            0x2AFFFFFF.toInt(), 0x3DFFFFFF.toInt(), 0x3EFFFFFF.toInt(),
            0xFFD8B4FE.toInt(), 0x4DC084FC.toInt(),
            0xFFFBF8FF.toInt(), 0xFFB3A2D6.toInt(),
            0xFFFB7185.toInt(), 0xFF4ADE80.toInt()),
        Skin("ink", "墨夜黑",
            0xFF202024.toInt(), 0xFF16161A.toInt(), 0xFF0A0A0C.toInt(),
            0x3664E3E3.toInt(), 0x248B7FD4.toInt(),
            0x24FFFFFF.toInt(), 0x36FFFFFF.toInt(), 0x38FFFFFF.toInt(),
            0xFFF5F5F7.toInt(), 0x40F5F5F7.toInt(),
            0xFFFAFAFC.toInt(), 0xFFA8A8B0.toInt(),
            0xFFF87171.toInt(), 0xFF34D399.toInt()),
        Skin("dawn", "晨曦金",
            0xFF422813.toInt(), 0xFF2E1B0D.toInt(), 0xFF180E07.toInt(),
            0x5EFBBF24.toInt(), 0x3AE0559F.toInt(),
            0x2CFFFFFF.toInt(), 0x3EFFFFFF.toInt(), 0x40FFFFFF.toInt(),
            0xFFFDE68A.toInt(), 0x4DFBBF24.toInt(),
            0xFFFFFBF4.toInt(), 0xFFD4B894.toInt(),
            0xFFFCA5A5.toInt(), 0xFFA7F3D0.toInt()),
        Skin("glacier", "冰川蓝",
            0xFF123050.toInt(), 0xFF0D2338.toInt(), 0xFF07121E.toInt(),
            0x5E38BDF8.toInt(), 0x3A22D3EE.toInt(),
            0x2AFFFFFF.toInt(), 0x3DFFFFFF.toInt(), 0x3EFFFFFF.toInt(),
            0xFFBAE6FD.toInt(), 0x4D38BDF8.toInt(),
            0xFFF8FCFF.toInt(), 0xFF9DBBD4.toInt(),
            0xFFFDA4AF.toInt(), 0xFF6EE7B7.toInt()),
        Skin("sakura", "樱雾粉",
            0xFF44182F.toInt(), 0xFF301122.toInt(), 0xFF180811.toInt(),
            0x5EF472B6.toInt(), 0x3AC084FC.toInt(),
            0x2CFFFFFF.toInt(), 0x3EFFFFFF.toInt(), 0x40FFFFFF.toInt(),
            0xFFFBCFE8.toInt(), 0x4DF472B6.toInt(),
            0xFFFFF8FB.toInt(), 0xFFD4A3BC.toInt(),
            0xFFFDA4AF.toInt(), 0xFFA7F3D0.toInt()),
        Skin("mint", "薄荷绿",
            0xFF123228.toInt(), 0xFF0D241C.toInt(), 0xFF07140E.toInt(),
            0x5E34D399.toInt(), 0x3A14B8A6.toInt(),
            0x2AFFFFFF.toInt(), 0x3DFFFFFF.toInt(), 0x3EFFFFFF.toInt(),
            0xFFA7F3D0.toInt(), 0x4D34D399.toInt(),
            0xFFF7FFFA.toInt(), 0xFF97C3B0.toInt(),
            0xFFFDA4AF.toInt(), 0xFF86EFAC.toInt()),
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
        // 顶部高光：模拟玻璃上沿折射
        val gloss = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x3AFFFFFF, 0x10FFFFFF, 0x00FFFFFF)
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
        val fill = if (selected) (tone and 0x00FFFFFF) or 0x33000000 else s.glass
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
