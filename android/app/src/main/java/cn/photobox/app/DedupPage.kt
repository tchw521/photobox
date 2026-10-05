package cn.photobox.app

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.LinkedHashSet

/**
 * 相似照片查重页。
 *
 * 判重：**文件大小相同 + 拍摄时间相差 10 秒内**。不解码像素，万张照片也能秒出结果。
 * 每组默认保留第一张（时间最早），其余标记为冗余。
 *
 * 复用 Ui.async 后台执行、Thumbs 统一加载、Store 回收站，
 * 与页面其他模块共用同一套工具层，不另起炉灶。
 */
class DedupPage(private val act: MainActivity, private val root: View) {

    private var groups: List<List<Photo>> = emptyList()
    /** 被用户手动保留的照片 id；默认每组第一张。 */
    private val keep = LinkedHashSet<Long>()

    private var summary: TextView? = null
    private var list: RecyclerView? = null

    fun bind() {
        CrashGuard.guard { bindInner() }
    }

    private fun bindInner() {
        summary = root.findViewById(R.id.dedupSummary)
        list = root.findViewById(R.id.dedupList)
        list?.layoutManager = LinearLayoutManager(act)
        list?.setItemViewCacheSize(6)
        list?.recycledViewPool?.setMaxRecycledViews(0, 12)

        root.findViewById<Button>(R.id.btnRefreshDup)?.setOnClickListener {
            CrashGuard.guard { detect() }
        }
        root.findViewById<Button>(R.id.btnCleanDup)?.setOnClickListener {
            CrashGuard.guard { clean() }
        }
        styleButtons()
        detect()
    }

    private fun styleButtons() {
        val s = SkinNow.skin
        listOf(R.id.btnCleanDup, R.id.btnRefreshDup).forEach { id ->
            val b = root.findViewById<Button>(id)
            b?.setTextColor(s.text)
            b?.background = Glass.solid(s.glass, 8f)
        }
    }

    private fun detect() {
        Ui.async(act, io = {
            CrashGuard.result({ Repo.duplicates(act) }, emptyList<List<Photo>>())
        }, ui = { g ->
            CrashGuard.guard {
                groups = g
                keep.clear()
                g.forEach { it.firstOrNull()?.let { p -> keep.add(p.id) } }
                render()
            }
        })
    }

    private fun render() {
        val s = SkinNow.skin
        val redundant = redundantPhotos()
        val bytes = groups.sumOf { Repo.wastedBytes(it) }
        summary?.text = if (groups.isEmpty()) {
            "未检测到重复照片"
        } else {
            "${groups.size} 组重复 · 可清理 ${redundant.size} 张 · 释放 ${formatSize(bytes)}"
        }
        summary?.setTextColor(s.textDim)
        list?.adapter = GroupAdapter()
    }

    /**
     * 所有被标记为冗余的照片。
     * 规则很简单：每组内**不在 keep 集合中的即为冗余**，
     * keep 默认含每组第一张，用户点按可改选保留对象。
     */
    private fun redundantPhotos(): List<Photo> =
        groups.flatMap { grp -> grp.filter { !keep.contains(it.id) } }

    private fun clean() {
        val targets = redundantPhotos()
        if (targets.isEmpty()) {
            Ui.toast(act, "没有可清理的照片")
            return
        }
        Ui.confirm(
            act, "清理冗余",
            "将 ${targets.size} 张移入回收站（可还原），保留每组选中的一张。",
            okText = "清理"
        ) {
            act.trashPhotosPublic(targets) { n ->
                Ui.toast(act, "已清理 $n 张")
                CrashGuard.guard { detect() }
            }
        }
    }

    // ------------------------------------------------------------ 适配器
    private inner class GroupAdapter : RecyclerView.Adapter<GroupAdapter.H>() {

        inner class H(v: View) : RecyclerView.ViewHolder(v) {
            val meta: TextView = v.findViewById(R.id.dupMeta)
            val thumbs: RecyclerView = v.findViewById(R.id.dupThumbs)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            H(LayoutInflater.from(p.context).inflate(R.layout.item_dedup, p, false))

        override fun getItemCount() = groups.size

        override fun onBindViewHolder(h: H, i: Int) {
            if (i !in groups.indices) return
            CrashGuard.guard { bindGroup(h, i) }
        }

        private fun bindGroup(h: H, i: Int) {
            val grp = groups[i]
            val s = SkinNow.skin
            h.meta.text = "${grp.size} 张 · ${formatSize(grp.first().size)} · 可释放 ${formatSize(Repo.wastedBytes(grp))}"
            h.meta.setTextColor(s.textDim)
            h.itemView.background = Glass.card(s, 12f)
            h.thumbs.layoutManager =
                LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false)
            h.thumbs.adapter = object : RecyclerView.Adapter<ThumbVH>() {
                override fun onCreateViewHolder(p: ViewGroup, t: Int) = ThumbVH(
                    LayoutInflater.from(p.context).inflate(R.layout.item_dup_thumb, p, false)
                )

                override fun getItemCount() = grp.size

                override fun onBindViewHolder(vh: ThumbVH, j: Int) {
                    CrashGuard.guard { bindThumb(vh, grp, j) }
                }
            }
        }

        private fun bindThumb(vh: ThumbVH, grp: List<Photo>, j: Int) {
            val p = grp[j]
            val s = SkinNow.skin
            Thumbs.into(act, p, 140, vh.img)
            val isKeep = keep.contains(p.id)
            vh.flag.text = if (isKeep) "保留" else "待清理"
            vh.flag.setBackgroundColor(if (isKeep) s.ok else s.danger)
            vh.flag.setTextColor(if (isKeep) s.bgBottom else s.text)
            // 点按切换保留对象：保留被点的，取消同组其他
            vh.itemView.setOnClickListener {
                CrashGuard.guard {
                    keep.clear()
                    groups.forEach { it.firstOrNull()?.let { f -> keep.add(f.id) } }
                    keep.removeAll(grp.map { it.id }.toSet())
                    keep.add(p.id)
                    notifyDataSetChanged()
                    render()
                }
            }
        }
    }

    private inner class ThumbVH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.dupImg)
        val flag: TextView = v.findViewById(R.id.dupFlag)
    }
}
