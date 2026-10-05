package cn.photobox.app

import android.app.Activity
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 换皮肤弹窗：顶部实时预览 + 单选列表。
 *
 * 预览区用**当前选中皮肤的真实配色**渲染一张玻璃卡片与一枚主按钮，
 * 点选即刻换色，点「应用」才真正写入并重建界面。
 * 这样不必反复退出重进就能比较各套皮肤的实际观感。
 *
 * 与页面其他部分共用 Glass / Skin，不额外引入资源。
 */
object SkinPicker {

    fun show(a: Activity, onApply: (String) -> Unit) {
        CrashGuard.safe(a, "换肤失败") {
            if (a.isFinishing || a.isDestroyed) return

            val label = TextView(a).apply {
                text = "当前效果"
                textSize = 14f
                setPadding(28, 18, 28, 8)
            }
            val card = TextView(a).apply {
                text = "user@example.com"
                textSize = 13f
                setPadding(22, 20, 22, 20)
            }
            val btn = TextView(a).apply {
                text = "主按钮"
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(22, 18, 22, 18)
            }
            val preview = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 12, 20, 16)
                addView(label)
                addView(card)
            }
            preview.addView(LinearLayout(a).apply {
                setPadding(0, 10, 0, 0)
                addView(btn)
            })

            var picked = SkinNow.skin
            val paint: (Skin) -> Unit = { sk ->
                label.setTextColor(sk.textDim)
                card.background = Glass.card(sk, 16f)
                card.setTextColor(sk.text)
                btn.background = Glass.block(sk, sk.accent, true)
                btn.setTextColor(sk.accent)
            }
            paint(picked)

            val names = Skins.ALL.map { it.name }.toTypedArray()
            val start = Skins.ALL.indexOfFirst { it.key == picked.key }.coerceAtLeast(0)
            val d = android.app.AlertDialog.Builder(a)
                .setTitle("换皮肤")
                .setView(preview)
                .setSingleChoiceItems(names, start) { _, w ->
                    picked = Skins.ALL.getOrNull(w) ?: picked
                    paint(picked)
                }
                .setNegativeButton("取消", null)
                .setPositiveButton("应用") { _, _ -> CrashGuard.guard { onApply(picked.key) } }
                .create()
            if (!a.isFinishing && !a.isDestroyed) {
                try {
                    d.show()
                } catch (e: Throwable) {
                    CrashGuard.log(e)
                }
            }
        }
    }
}
