package cn.photobox.app

import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 设置页的皮肤选择适配器：色卡 + 名称，选中项带强调色描边。
 * 复用 Skins.ALL 单一数据源，不额外维护颜色副本。
 */
class SkinAdapter(
    private val current: String,
    private val picked: (String) -> Boolean,   // 返回 true 表示接受这次切换
    private val onPick: (Skin) -> Unit,
) : RecyclerView.Adapter<SkinAdapter.H>() {

    class H(v: View) : RecyclerView.ViewHolder(v) {
        val swatch: View = v.findViewById(R.id.swatch)
        val name: TextView = v.findViewById(R.id.skinName)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        H(LayoutInflater.from(p.context).inflate(R.layout.item_skin, p, false))

    override fun getItemCount() = Skins.ALL.size

    override fun onBindViewHolder(h: H, i: Int) {
        val s = Skins.ALL[i]
        val on = s.key == current
        h.swatch.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(s.swatchTop, s.swatchAccent, s.swatchBottom)
        ).apply {
            cornerRadius = 26f
            setStroke(if (on) 5 else 0, if (on) s.swatchAccent else 0)
        }
        h.name.text = s.name

        // 选中态与未选中态文字色取自当前主题，跟随皮肤
        val tv = TypedValue()
        val theme = h.itemView.context.theme
        theme.resolveAttribute(
            if (on) R.attr.accentColor else R.attr.textColorDim, tv, true
        )
        h.name.setTextColor(tv.data)
        h.itemView.setOnClickListener { if (picked(s.key)) onPick(s) }
    }
}
