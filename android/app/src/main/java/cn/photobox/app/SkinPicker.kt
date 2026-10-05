package cn.photobox.app

import android.app.Activity
import android.content.DialogInterface

/**
 * 换皮肤弹窗：**点选即生效**。
 *
 * 按用户要求移除了预览区与「取消 / 应用」按钮——
 * 点任意一套皮肤立即应用并关闭弹窗，不做二次确认。
 */
object SkinPicker {

    fun show(a: Activity, onApply: (String) -> Unit) {
        CrashGuard.safe(a, "换肤失败") {
            if (a.isFinishing || a.isDestroyed) return

            val names = Skins.ALL.map { it.name }.toTypedArray()
            val cur = SkinNow.skin.key
            val start = Skins.ALL.indexOfFirst { it.key == cur }.coerceAtLeast(0)

            val listener = DialogInterface.OnClickListener { _, w ->
                val sk = Skins.ALL.getOrNull(w) ?: return@OnClickListener
                CrashGuard.guard { onApply(sk.key) }
            }

            val d = android.app.AlertDialog.Builder(a)
                .setTitle("换皮肤")
                .setSingleChoiceItems(names, start, listener)
                .create()
            try {
                if (!a.isFinishing && !a.isDestroyed) d.show()
            } catch (e: Throwable) {
                CrashGuard.log(e)
            }
        }
    }
}
