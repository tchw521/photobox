package cn.photobox.app

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/** 侧边栏相册项：气泡包裹，只露前两字，超长跑马灯。 */
class AlbumAdapter(
    private val onClick: (String) -> Unit,
) : RecyclerView.Adapter<AlbumAdapter.H>() {

    data class Row(val key: String, val label: String, val count: Int, val selected: Boolean)

    private val rows = ArrayList<Row>()

    fun submit(list: List<Row>) {
        rows.clear(); rows.addAll(list); notifyDataSetChanged()
    }

    class H(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.albumName)
        val count: TextView = v.findViewById(R.id.albumCount)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        H(LayoutInflater.from(p.context).inflate(R.layout.item_album, p, false))

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: H, i: Int) {
        val r = rows[i]
        h.name.text = r.label
        h.name.isSelected = true                      // 触发跑马灯
        h.name.background = ContextCompat.getDrawable(
            h.itemView.context,
            if (r.selected) R.drawable.bg_bubble_on else R.drawable.bg_bubble
        )
        h.name.setTextColor(
            ContextCompat.getColor(h.itemView.context, if (r.selected) R.color.text else R.color.dim)
        )
        h.count.text = if (r.count > 0) r.count.toString() else ""
        h.itemView.setOnClickListener { onClick(r.key) }
    }
}

/** 月份筛选 chip。 */
class ChipAdapter(private val onClick: (String?) -> Unit) :
    RecyclerView.Adapter<ChipAdapter.H>() {

    private val items = ArrayList<String>()
    var current: String? = null
        private set

    fun submit(list: List<String>, sel: String?) {
        items.clear(); items.addAll(list); current = sel; notifyDataSetChanged()
    }

    class H(v: View) : RecyclerView.ViewHolder(v) { val t: TextView = v as TextView }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        H(LayoutInflater.from(p.context).inflate(R.layout.item_chip, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: H, i: Int) {
        val m = items[i]
        h.t.text = m
        val on = m == current
        h.t.setTextColor(ContextCompat.getColor(h.itemView.context, if (on) R.color.accent else R.color.dim))
        h.t.setOnClickListener {
            current = if (current == m) null else m
            notifyDataSetChanged()
            onClick(current)
        }
    }
}

/** 照片宫格 / 列表共用适配器，靠 LayoutManager 切换形态。 */
class PhotoAdapter(
    private val c: Context,
    private val grid: Boolean,
    private val onClick: (Photo, Int) -> Unit,
    private val onLongClick: (Photo, Int) -> Boolean,
) : RecyclerView.Adapter<PhotoAdapter.H>() {

    private val data = ArrayList<Photo>()
    val selected = HashSet<Long>()
    var selectMode = false

    fun submit(list: List<Photo>) {
        data.clear(); data.addAll(list); notifyDataSetChanged()
    }

    fun item(i: Int) = data[i]
    fun list() = data.toList()

    class H(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView? = v.findViewById(R.id.thumb)
        val check: ImageView? = v.findViewById(R.id.check)
        val mask: View? = v.findViewById(R.id.mask)
        val name: TextView? = v.findViewById(R.id.name)
        val meta: TextView? = v.findViewById(R.id.meta)
        val rowRoot: View? = v.findViewById(R.id.rowRoot)
    }

    override fun getItemViewType(i: Int) = if (grid) 0 else 1

    override fun onCreateViewHolder(p: ViewGroup, t: Int) = H(
        LayoutInflater.from(p.context).inflate(
            if (t == 0) R.layout.item_photo_grid else R.layout.item_photo_list, p, false
        )
    )

    override fun getItemCount() = data.size

    override fun onBindViewHolder(h: H, i: Int) {
        val p = data[i]
        val px = if (grid) 220 else 110
        h.thumb?.let { Thumbs.into(c, p, px, it) }
        val sel = selected.contains(p.id)
        h.check?.visibility = if (sel && selectMode) View.VISIBLE else View.GONE
        h.mask?.visibility = if (sel && selectMode) View.VISIBLE else View.GONE
        h.rowRoot?.setBackgroundColor(
            if (sel && selectMode) Color.parseColor("#33A855F7") else Color.parseColor("#14FFFFFF")
        )
        h.name?.text = p.name
        h.meta?.text = "${p.album} · ${p.dateText} · ${formatSize(p.size)}"
        h.itemView.setOnClickListener { onClick(p, i) }
        h.itemView.setOnLongClickListener { onLongClick(p, i) }
    }
}

/** 回收站列表。 */
class TrashAdapter(
    private val c: Context,
    private val onRestore: (TrashItem) -> Unit,
) : RecyclerView.Adapter<TrashAdapter.H>() {

    private val data = ArrayList<TrashItem>()
    fun submit(l: List<TrashItem>) { data.clear(); data.addAll(l); notifyDataSetChanged() }

    class H(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val name: TextView = v.findViewById(R.id.name)
        val meta: TextView = v.findViewById(R.id.meta)
        val restore: View = v.findViewById(R.id.restore)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        H(LayoutInflater.from(p.context).inflate(R.layout.item_trash, p, false))

    override fun getItemCount() = data.size

    override fun onBindViewHolder(h: H, i: Int) {
        val it = data[i]
        Thumbs.file(it.file, 120, h.thumb)
        h.name.text = it.name
        h.meta.text = "${it.album} · ${formatSize(it.size)}"
        h.restore.setOnClickListener { onRestore(it) }
    }
}
