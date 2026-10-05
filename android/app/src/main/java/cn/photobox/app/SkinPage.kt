package cn.photobox.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 皮肤设置页：内置方案 + 自定义配色 + 背景设置。
 *
 * 结构对应参考界面：
 *   内置方案（九宫格）→ 自定义配色（主色 / 辅色 / 明暗 / 底色 / 主色明度 / 背景亮度）
 *   → 背景设置（选图 / 清除 / 浓度遮罩）→ 应用 / 恢复默认
 *
 * 所有颜色控件由代码着色，复用 Glass 与 SkinNow，不引入额外资源。
 */
class SkinPage(private val act: MainActivity, private val root: View) {

    /** 内置方案色板：取各自主色做展示。 */
    private val builtin = Skins.ALL

    /** 自定义色板：12 个常用色。 */
    private val palette = intArrayOf(
        0xFF059669.toInt(), 0xFF0284C7.toInt(), 0xFF7C3AED.toInt(),
        0xFFDB2777.toInt(), 0xFFD97706.toInt(), 0xFF047857.toInt(),
        0xFFDC2626.toInt(), 0xFF0891B2.toInt(), 0xFF4F46E5.toInt(),
        0xFF16A34A.toInt(), 0xFFCA8A04.toInt(), 0xFF9333EA.toInt(),
    )

    private var pickAccent = true

    fun bind() {
        CrashGuard.guard {
            bindBuiltin()
            bindPalette()
            bindControls()
            bindBackground()
            bindButtons()
            styleAll()
        }
    }

    private fun styleAll() {
        val s = SkinNow.skin
        listOf(
            R.id.skinSectionBuiltin, R.id.skinSectionCustom, R.id.skinSectionBg
        ).forEach { id ->
            root.findViewById<TextView>(id)?.setTextColor(s.accent)
        }
        listOf(
            R.id.lblAccent, R.id.lblAccent2, R.id.lblMode, R.id.lblBase,
            R.id.lblAccentLevel, R.id.lblBrightness, R.id.lblBgDim
        ).forEach { id ->
            root.findViewById<TextView>(id)?.setTextColor(s.text)
        }
        listOf(R.id.btnPickBg, R.id.btnClearBg, R.id.btnResetSkin).forEach { id ->
            root.findViewById<Button>(id)?.apply {
                setTextColor(s.text)
                background = Glass.card(s, 12f)
            }
        }
        root.findViewById<Button>(R.id.btnApplyCustom)?.apply {
            setTextColor(Color.WHITE)
            background = Glass.block(s, s.accent, true)
        }
    }

    // ------------------------------------------------------------ 内置方案
    private fun bindBuiltin() {
        val rv = root.findViewById<RecyclerView>(R.id.skinGrid) ?: return
        rv.layoutManager = GridLayoutManager(act, 3)
        rv.isNestedScrollingEnabled = false
        rv.adapter = object : RecyclerView.Adapter<BuiltinVH>() {
            override fun onCreateViewHolder(p: ViewGroup, t: Int) = BuiltinVH(
                LayoutInflater.from(p.context).inflate(R.layout.item_skin_card, p, false)
            )

            override fun getItemCount() = builtin.size

            override fun onBindViewHolder(h: BuiltinVH, i: Int) {
                CrashGuard.guard {
                    val sk = builtin[i]
                    val cur = SkinNow.skin.key == sk.key
                    h.swatch.background = Glass.block(sk, sk.accent, cur)
                    h.name.text = sk.name
                    h.name.setTextColor(sk.text)
                    h.itemView.background = Glass.card(sk, 14f)
                    // 点击即生效：不再有预览区与确认按钮
                    h.itemView.setOnClickListener {
                        CrashGuard.guard { act.applySkinNow(sk.key) }
                    }
                }
            }
        }
    }

    private class BuiltinVH(v: View) : RecyclerView.ViewHolder(v) {
        val swatch: View = v.findViewById(R.id.skinSwatch)
        val name: TextView = v.findViewById(R.id.skinName)
    }

    // ------------------------------------------------------------ 色板
    private fun bindPalette() {
        bindSwatchGrid(R.id.accentGrid, true)
        bindSwatchGrid(R.id.accent2Grid, false)
    }

    private fun bindSwatchGrid(id: Int, isAccent: Boolean) {
        val rv = root.findViewById<RecyclerView>(id) ?: return
        rv.layoutManager = GridLayoutManager(act, 6)
        rv.isNestedScrollingEnabled = false
        rv.adapter = object : RecyclerView.Adapter<SwatchVH>() {
            override fun onCreateViewHolder(p: ViewGroup, t: Int) = SwatchVH(
                LayoutInflater.from(p.context).inflate(R.layout.item_swatch, p, false)
            )

            override fun getItemCount() = palette.size

            override fun onBindViewHolder(h: SwatchVH, i: Int) {
                CrashGuard.guard {
                    val c = palette[i]
                    val sel = if (isAccent) Store.csAccent == c else Store.csAccent2 == c
                    h.swatch.background = Glass.block(SkinNow.skin, c, sel)
                    h.swatch.setOnClickListener {
                        CrashGuard.guard {
                            if (isAccent) Store.csAccent = c else Store.csAccent2 = c
                            Store.saveSettings(act)
                            rv.adapter?.notifyDataSetChanged()
                            pickAccent = isAccent
                        }
                    }
                }
            }
        }
    }

    private class SwatchVH(root: View) : RecyclerView.ViewHolder(root) {
        val swatch: View = root.findViewById(R.id.swatch)
    }

    // ------------------------------------------------------------ 控件
    private fun bindControls() {
        val s = SkinNow.skin
        val mode = root.findViewById<RadioGroup>(R.id.modeGroup)
        mode?.check(if (Store.csMode == 1) R.id.modeDark else R.id.modeLight)
        mode?.setOnCheckedChangeListener { _, id ->
            Store.csMode = if (id == R.id.modeDark) 1 else 0
            Store.saveSettings(act)
        }
        val base = root.findViewById<RadioGroup>(R.id.baseGroup)
        base?.check(if (Store.csBaseFollow) R.id.baseFollow else R.id.baseCustom)
        base?.setOnCheckedChangeListener { _, id ->
            Store.csBaseFollow = id == R.id.baseFollow
            Store.saveSettings(act)
        }
        tintRadio(mode, s)
        tintRadio(base, s)

        val al = root.findViewById<SeekBar>(R.id.seekAccentLevel)
        al?.progress = Store.csAccentLevel
        al?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, f: Boolean) {
                Store.csAccentLevel = v
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { Store.saveSettings(act) }
        })

        val br = root.findViewById<SeekBar>(R.id.seekBrightness)
        br?.progress = Store.csBrightness
        br?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, f: Boolean) {
                Store.csBrightness = v
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { Store.saveSettings(act) }
        })

        val dim = root.findViewById<SeekBar>(R.id.seekBgDim)
        dim?.progress = Store.bgDim
        dim?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, f: Boolean) {
                Store.bgDim = v
                applyBgPreviewAlpha()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { Store.saveSettings(act) }
        })
    }

    private fun tintRadio(g: RadioGroup?, s: Skin) {
        if (g == null) return
        for (i in 0 until g.childCount) {
            (g.getChildAt(i) as? android.widget.RadioButton)?.apply {
                setTextColor(s.text)
                buttonTintList = android.content.res.ColorStateList.valueOf(s.accent)
            }
        }
    }

    // ------------------------------------------------------------ 背景
    private fun bindBackground() {
        val pv = root.findViewById<ImageView>(R.id.bgPreview) ?: return
        pv.background = Glass.card(SkinNow.skin, 12f)
        if (Store.bgUri.isBlank()) {
            pv.setImageDrawable(null)
        } else {
            CrashGuard.guard {
                pv.setImageURI(android.net.Uri.parse(Store.bgUri))
            }
        }
        applyBgPreviewAlpha()

        root.findViewById<Button>(R.id.btnPickBg)?.setOnClickListener {
            CrashGuard.guard {
                val it = Intent(Intent.ACTION_PICK).apply {
                    type = "image/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                act.startActivityForResult(it, MainActivity.REQ_PICK_BG)
            }
        }
        root.findViewById<Button>(R.id.btnClearBg)?.setOnClickListener {
            CrashGuard.guard {
                Store.bgUri = ""
                Store.saveSettings(act)
                act.applyBackground()
                bindBackground()
            }
        }
    }

    private fun applyBgPreviewAlpha() {
        root.findViewById<ImageView>(R.id.bgPreview)?.imageAlpha =
            (Store.bgDim.coerceIn(0, 100) * 255 / 100)
    }

    // ------------------------------------------------------------ 按钮
    private fun bindButtons() {
        root.findViewById<Button>(R.id.btnApplyCustom)?.setOnClickListener {
            CrashGuard.guard {
                Store.saveSettings(act)
                act.applySkinNow(Skins.KEY_CUSTOM)
            }
        }
        root.findViewById<Button>(R.id.btnResetSkin)?.setOnClickListener {
            CrashGuard.guard {
                Store.csMode = 0
                Store.csAccent = 0xFF059669.toInt()
                Store.csAccent2 = 0xFF0284C7.toInt()
                Store.csBaseFollow = true
                Store.csBrightness = 45
                Store.csAccentLevel = 60
                Store.bgUri = ""
                Store.bgDim = 34
                Store.saveSettings(act)
                act.applySkinNow("mint")
            }
        }
    }
}
