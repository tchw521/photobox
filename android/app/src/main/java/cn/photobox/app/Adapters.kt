package cn.photobox.app

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 侧边栏相册项：气泡包裹，只露前两字，超长跑马灯滚动。
 * 颜色全部取自 SkinNow.skin，不使用 ?attr。
 */
class AlbumAdapter(
    private val onClick: (String) -> Unit,
    private val onLongClick: ((String) -> Unit)? = null,
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
        if (i !in rows.indices) return
        CrashGuard.guard { bind(h, i) }
    }

    /**
     * 默认显示名称前 4 个字，超出以「…」收尾。
     * 相比自动滚动，静态截断更易读：一眼能扫完整排图集名。
     * 长名称可在长按时通过菜单操作识别。
     */
    private fun shortName(name: String): String =
        if (!Store.nameMarquee || name.length <= 4) name else name.take(4) + "…"

    /**
     * 每个相册分配稳定的专属色。
     * 用 hashCode 取模映射到一组预设色，同一相册每次启动颜色一致，
     * 便于在长列表中靠颜色快速定位。
     */
    private fun albumColor(key: String): Int {
        if (key == MainActivity.KEY_ALL || key == MainActivity.KEY_FAV) return SkinNow.skin.accent
        if (key == MainActivity.KEY_TRASH) return SkinNow.skin.danger
        return PALETTE[(key.hashCode() and 0x7FFFFFFF) % PALETTE.size]
    }

    companion object {
        /** 相册配色池：与六套皮肤解耦，保证任何皮肤下都可辨识。 */
        private val PALETTE = intArrayOf(
            0xFFA855F7.toInt(), 0xFF38BDF8.toInt(), 0xFF34D399.toInt(),
            0xFFFBBF24.toInt(), 0xFFFB7185.toInt(), 0xFF818CF8.toInt(),
            0xFF2DD4BF.toInt(), 0xFFF472B6.toInt(), 0xFF4ADE80.toInt(),
        )
    }

    private fun bind(h: H, i: Int) {
        val r = rows[i]
        val s = SkinNow.skin
        h.name.text = shortName(r.label)
        // 气泡改为方框并撑满侧栏宽度；每个相册用专属色描边与着色
        val tone = albumColor(r.key)
        h.name.background = Glass.block(s, tone, r.selected)
        h.name.setTextColor(if (r.selected) tone else s.text)
        h.count.text = if (r.count > 0) r.count.toString() else ""
        h.count.setTextColor(if (r.selected) tone else s.textDim)
        h.itemView.setOnClickListener { CrashGuard.guard { onClick(r.key) } }
        h.itemView.setOnLongClickListener {
            onLongClick?.let { cb -> CrashGuard.guard { cb(r.key) } }
            true
        }
        // 长按可看全名（Toast 提示）
        h.itemView.setOnTouchListener { _, _ -> false }
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
        if (i !in items.indices) return
        CrashGuard.guard { bindChip(h, i) }
    }

    private fun bindChip(h: H, i: Int) {
        val m = items[i]
        val s = SkinNow.skin
        h.t.text = m
        val on = m == current
        h.t.setTextColor(if (on) s.accent else s.textDim)
        h.t.background = Glass.bubble(s, on)
        h.t.setOnClickListener {
            CrashGuard.guard {
                current = if (current == m) null else m
                notifyDataSetChanged()
                onClick(current)
            }
        }
    }
}

/** 照片宫格 / 列表共用适配器，靠 LayoutManager 切换形态。 */
class PhotoAdapter(
    private val c: Context,
    private val grid: Boolean,
    private val onClick: (Photo, Int) -> Unit,
    private val onLongClick: (Photo, Int, View) -> Boolean,
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
        if (i !in data.indices) return
        CrashGuard.guard { bindPhoto(h, i) }
    }

    private fun bindPhoto(h: H, i: Int) {
        val p = data[i]
        val s = SkinNow.skin
        val px = if (grid) 220 else 110
        h.thumb?.let { Thumbs.into(c, p, px, it) }
        val sel = selected.contains(p.id)
        h.check?.visibility = if (sel && selectMode) View.VISIBLE else View.GONE
        h.check?.setImageResource(R.drawable.ic_check)
        h.check?.setColorFilter(s.accent)
        h.mask?.visibility = if (sel && selectMode) View.VISIBLE else View.GONE
        h.mask?.setBackgroundColor(s.accentSoft)
        h.rowRoot?.background = Glass.card(s, 12f)
        h.name?.text = p.name
        h.name?.setTextColor(s.text)
        h.meta?.text = "${p.album} · ${p.dateText} · ${formatSize(p.size)}"
        h.meta?.setTextColor(s.textDim)
        h.itemView.setOnClickListener { CrashGuard.guard { onClick(p, i) } }
        h.itemView.setOnLongClickListener { onLongClick(p, i, h.itemView) }
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
        if (i !in data.indices) return
        CrashGuard.guard { bindTrash(h, i) }
    }

    private fun bindTrash(h: H, i: Int) {
        val item = data[i]
        val s = SkinNow.skin
        Thumbs.file(item.file, 120, h.thumb)
        h.name.text = item.name
        h.name.setTextColor(s.text)
        h.meta.text = "${item.album} · ${formatSize(item.size)}"
        h.meta.setTextColor(s.textDim)
        h.restore.setOnClickListener { CrashGuard.guard { onRestore(item) } }
        if (h.restore is android.widget.Button) {
            (h.restore as android.widget.Button).setTextColor(s.text)
            h.restore.background = Glass.solid(s.glass, 8f)
        }
    }
}
