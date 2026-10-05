package cn.photobox.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 设置页的皮肤选择适配器：色卡 + 名称。
 * 复用 Skins.ALL 单一数据源，不额外维护颜色副本。
 */
class SkinAdapter(
    private val current: String,
    private val onPick: (String) -> Unit,
) : RecyclerView.Adapter<SkinAdapter.H>() {

    class H(v: View) : RecyclerView.ViewHolder(v) {
        val swatch: View = v.findViewById(R.id.swatch)
        val name: TextView = v.findViewById(R.id.skinName)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        H(LayoutInflater.from(p.context).inflate(R.layout.item_skin, p, false))

    override fun getItemCount() = Skins.ALL.size

    override fun onBindViewHolder(h: H, i: Int) {
        if (i !in Skins.ALL.indices) return
        CrashGuard.guard {
            val s = Skins.ALL[i]
            val on = s.key == current
            h.swatch.background = Glass.swatch(s)
            h.name.text = s.name
            h.name.setTextColor(if (on) SkinNow.skin.accent else SkinNow.skin.textDim)
            h.itemView.setOnClickListener { CrashGuard.guard { onPick(s.key) } }
        }
    }
}
