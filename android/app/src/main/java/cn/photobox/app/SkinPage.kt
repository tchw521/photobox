package cn.photobox.app

import android.content.Intent
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView

/**
 * 皮肤设置页：内置方案 + 自定义配色 + 背景设置。
 *
 * 结构对应参考界面：
 *   内置方案（网格）→ 自定义配色（主色 / 辅色 / 明暗 / 底色 / 主色明度 / 背景亮度）
 *   → 背景设置（选图 / 清除 / 浓度遮罩）→ 应用 / 恢复默认
 *
 * 【重要】本页外层是 ScrollView，**不使用 RecyclerView**：
 * 嵌套 RecyclerView 且高度 wrap_content 时，部分设备上测量结果恒为 0，
 * 表现为九宫格与色板「整片不显示」。改用 LinearLayout 手工分行可彻底规避。
 *
 * 所有配色由代码完成，复用 Glass 与 SkinNow，不引入额外资源。
 */
class SkinPage(private val act: MainActivity, private val root: View) {

    /** 内置方案。 */
    private val builtin = Skins.ALL

    /** 自定义色板：12 个常用色。 */
    private val palette = intArrayOf(
        0xFF059669.toInt(), 0xFF0284C7.toInt(), 0xFF7C3AED.toInt(),
        0xFFDB2777.toInt(), 0xFFD97706.toInt(), 0xFF047857.toInt(),
        0xFFDC2626.toInt(), 0xFF0891B2.toInt(), 0xFF4F46E5.toInt(),
        0xFF16A34A.toInt(), 0xFFCA8A04.toInt(), 0xFF9333EA.toInt(),
    )

    fun bind() {
        CrashGuard.guard {
            bindBuiltin()
            bindSwatchGrid(R.id.accentGrid, true)
            bindSwatchGrid(R.id.accent2Grid, false)
            bindControls()
            bindBackground()
            bindButtons()
            styleAll()
        }
    }

    /** 统一着色：标题用强调色，标签用正文色，按钮复用 Glass。 */
    private fun styleAll() {
        val s = SkinNow.skin
        listOf(R.id.skinSectionBuiltin, R.id.skinSectionCustom, R.id.skinSectionBg)
            .forEach { id -> root.findViewById<TextView>(id)?.setTextColor(s.accent) }
        listOf(
            R.id.lblAccent, R.id.lblAccent2, R.id.lblMode, R.id.lblBase,
            R.id.lblAccentLevel, R.id.lblBrightness, R.id.lblBgDim
        ).forEach { id -> root.findViewById<TextView>(id)?.setTextColor(s.text) }
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
        val box = root.findViewById<LinearLayout>(R.id.skinGrid) ?: return
        box.removeAllViews()
        val cols = 3
        var row: LinearLayout? = null
        builtin.forEachIndexed { i, sk ->
            if (i % cols == 0) {
                row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                box.addView(row)
            }
            val cell = LayoutInflater.from(act).inflate(R.layout.item_skin_card, row, false)
            cell.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            val cur = SkinNow.skin.key == sk.key
            cell.findViewById<View>(R.id.skinSwatch).background =
                Glass.block(sk, sk.accent, cur)
            cell.findViewById<TextView>(R.id.skinName).apply {
                text = sk.name
                setTextColor(sk.text)
            }
            cell.background = Glass.card(sk, 14f)
            // 点击即生效
            cell.setOnClickListener { CrashGuard.guard { act.applySkinNow(sk.key) } }
            row?.addView(cell)
        }
    }

    // ------------------------------------------------------------ 色板
    private fun bindSwatchGrid(id: Int, isAccent: Boolean) {
        val box = root.findViewById<LinearLayout>(id) ?: return
        box.removeAllViews()
        val cols = 6
        var row: LinearLayout? = null
        palette.forEachIndexed { i, c ->
            if (i % cols == 0) {
                row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                box.addView(row)
            }
            val cell = LayoutInflater.from(act).inflate(R.layout.item_swatch, row, false)
            cell.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            val sw = cell.findViewById<View>(R.id.swatch)
            val sel = if (isAccent) Store.csAccent == c else Store.csAccent2 == c
            sw.background = Glass.block(SkinNow.skin, c, sel)
            sw.setOnClickListener {
                CrashGuard.guard {
                    if (isAccent) Store.csAccent = c else Store.csAccent2 = c
                    Store.saveSettings(act)
                    bindSwatchGrid(R.id.accentGrid, true)
                    bindSwatchGrid(R.id.accent2Grid, false)
                }
            }
            row?.addView(cell)
        }
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
        tintSeek(R.id.seekAccentLevel, R.id.lblAccentLevel, "主色明度") { Store.csAccentLevel = it }
        tintSeek(R.id.seekBrightness, R.id.lblBrightness, "背景亮度") { Store.csBrightness = it }
        tintSeek(R.id.seekBgDim, R.id.lblBgDim, "图片浓度遮罩") {
            Store.bgDim = it
            applyBgPreviewAlpha()
        }
    }

    /** 滑块统一：着色 + 初值 + 拖动时把数值显示在标签右侧。 */
    private fun tintSeek(seekId: Int, labelId: Int, title: String, onValue: (Int) -> Unit) {
        val s = SkinNow.skin
        val sb = root.findViewById<SeekBar>(seekId) ?: return
        val lb = root.findViewById<TextView>(labelId)
        val init = when (seekId) {
            R.id.seekAccentLevel -> Store.csAccentLevel
            R.id.seekBrightness -> Store.csBrightness
            else -> Store.bgDim
        }
        sb.progress = init
        lb?.text = "$title  $init%"
        lb?.setTextColor(s.text)
        sb.thumbTintList = android.content.res.ColorStateList.valueOf(s.accent)
        sb.progressTintList = android.content.res.ColorStateList.valueOf(s.accent)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(x: SeekBar, v: Int, f: Boolean) {
                onValue(v)
                lb?.text = "$title  $v%"
            }
            override fun onStartTrackingTouch(x: SeekBar) {}
            override fun onStopTrackingTouch(x: SeekBar) { Store.saveSettings(act) }
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
            CrashGuard.guard { pv.setImageURI(android.net.Uri.parse(Store.bgUri)) }
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
                Store.csBrightness = 40
                Store.csAccentLevel = 60
                Store.bgUri = ""
                Store.bgDim = 34
                Store.saveSettings(act)
                act.applySkinNow("mint")
            }
        }
    }
}
