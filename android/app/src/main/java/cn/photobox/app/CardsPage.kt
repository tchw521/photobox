package cn.photobox.app

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 卡片页：照片堆叠浏览 + 手势归类。
 * 上滑 → 清理到回收站；下滑 → 收藏；长按 → 弹出相册列表移动。
 * 只持有当前三张的 Bitmap，内存占用恒定。
 */
class CardsPage(private val act: MainActivity, private val root: View) {

    private var queue: MutableList<Photo> = mutableListOf()
    private var idx = 0
    private var done = 0

    private lateinit var stage: View
    private lateinit var c0: ImageView
    private lateinit var c1: ImageView
    private lateinit var c2: ImageView
    private lateinit var tip: TextView
    private lateinit var nameView: TextView
    private lateinit var progress: TextView
    private lateinit var btnMode: Button

    private var startY = 0f
    private var dragging = false
    private var longFired = false
    private var downAt = 0L

    companion object { private const val THRESHOLD = 90f }

    fun bind() {
        stage = root.findViewById(R.id.stage)
        c0 = root.findViewById(R.id.card0)
        c1 = root.findViewById(R.id.card1)
        c2 = root.findViewById(R.id.card2)
        tip = root.findViewById(R.id.cardTip)
        nameView = root.findViewById(R.id.cardName)
        progress = root.findViewById(R.id.progress)
        btnMode = root.findViewById(R.id.btnMode)

        queue = act.cardPhotos().toMutableList()
        idx = 0
        done = 0

        btnMode.text = if (Store.cardModeMove) "移动" else "复制"
        btnMode.setOnClickListener {
            Store.cardModeMove = !Store.cardModeMove
            Store.saveSettings(act)
            btnMode.text = if (Store.cardModeMove) "移动" else "复制"
        }

        root.findViewById<Button>(R.id.btnPrev).setOnClickListener { if (idx > 0) { idx--; render() } }
        root.findViewById<Button>(R.id.btnSkip).setOnClickListener { skip() }
        root.findViewById<Button>(R.id.btnTrash).setOnClickListener { current()?.let { dropTrash(it) } }

        bindAlbums()
        attachGesture()
        render()
    }

    private fun bindAlbums() {
        val rv = root.findViewById<RecyclerView>(R.id.cardAlbums)
        rv.layoutManager = LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false)
        val names = act.cardPhotos().map { it.album }.distinct().sorted()
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(p: android.view.ViewGroup, t: Int): RecyclerView.ViewHolder {
                val tv = TextView(act).apply {
                    setPadding(20, 16, 20, 16); textSize = 12f
                    setTextColor(act.resources.getColor(R.color.text, null))
                    background = act.getDrawable(R.drawable.bg_bubble)
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }

            override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                val n = names[i]
                (h.itemView as TextView).text = "📁 $n"
                h.itemView.setOnClickListener { current()?.let { classify(it, n) } }
            }

            override fun getItemCount() = names.size
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachGesture() {
        c0.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = e.rawY; dragging = true; longFired = false; downAt = System.currentTimeMillis()
                    act.window.decorView.postDelayed(longPressRunnable, 480)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - startY
                    if (kotlin.math.abs(dy) > 8) longFired = true
                    c0.translationY = dy
                    c0.alpha = (1f - kotlin.math.abs(dy) / 300f).coerceIn(0.35f, 1f)
                    tip.text = when {
                        dy < -THRESHOLD -> "松手 → 清理到回收站"
                        dy > THRESHOLD -> "松手 → 收藏"
                        else -> ""
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dy = e.rawY - startY
                    dragging = false
                    c0.translationY = 0f
                    c0.alpha = 1f
                    tip.text = "上滑回收 · 下滑收藏 · 长按选择相册"
                    if (dy <= -THRESHOLD) current()?.let { dropTrash(it) }
                    else if (dy >= THRESHOLD) current()?.let { dropFav(it) }
                    true
                }
                else -> false
            }
        }
    }

    private val longPressRunnable = Runnable {
        if (dragging && !longFired) {
            longFired = true
            current()?.let { showMoveSheet(it) }
        }
    }

    private fun current(): Photo? = queue.getOrNull(idx)

    private fun render() {
        val list = queue
        progress.text = "$done / ${list.size + done}"
        val cur = list.getOrNull(idx)
        nameView.text = cur?.name ?: "全部整理完成"
        val layers = listOf(c2, c1, c0)
        layers.forEachIndexed { i, iv ->
            val p = list.getOrNull(idx + (2 - i))
            if (p == null) { iv.visibility = View.GONE; return@forEachIndexed }
            iv.visibility = View.VISIBLE
            Thumbs.into(act, p, 480, iv)
        }
        c0.rotation = 0f; c1.rotation = -4f; c2.rotation = 4f
        c0.translationY = 0f; c0.alpha = 1f
    }

    private fun advance(removed: Photo) {
        queue.remove(removed)
        if (idx >= queue.size) idx = 0
        done++
        render()
    }

    private fun skip() {
        val p = current() ?: return
        queue.remove(p)
        queue.add(p)             // 排到队尾
        if (idx >= queue.size) idx = 0
        render()
    }

    private fun dropFav(p: Photo) {
        val fav = Store.favorites(act)
        fav.add(p.id.toString())
        Store.setFavorites(act, fav)
        Toast.makeText(act, "已收藏", Toast.LENGTH_SHORT).show()
        advance(p)
        act.afterCardAction()
    }

    private fun dropTrash(p: Photo) {
        Thread {
            val items = Store.trash(act).toMutableList()
            Repo.moveToTrash(act, p) { e -> act.runOnUiThread { act.requestDeleteConsent(e) } }?.let { items.add(it) }
            Store.saveTrash(act, items)
            act.runOnUiThread {
                Toast.makeText(act, "已清理到回收站", Toast.LENGTH_SHORT).show()
                advance(p)
                act.afterCardAction()
            }
        }.start()
    }

    private fun classify(p: Photo, album: String) {
        Thread {
            Repo.copyToAlbum(act, p, album)
            act.runOnUiThread {
                Toast.makeText(act, "已归类到「$album」", Toast.LENGTH_SHORT).show()
                advance(p)
                act.afterCardAction()
            }
        }.start()
    }

    private fun showMoveSheet(p: Photo) {
        val names = act.cardPhotos().map { it.album }.distinct().sorted()
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(act)
        val rv = RecyclerView(act).apply {
            layoutManager = LinearLayoutManager(act)
            setPadding(12, 12, 12, 12)
        }
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(p2: android.view.ViewGroup, t: Int): RecyclerView.ViewHolder {
                val tv = TextView(act).apply {
                    setPadding(24, 28, 24, 28); textSize = 14f
                    setTextColor(act.resources.getColor(R.color.text, null))
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }

            override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                val n = names[i]
                (h.itemView as TextView).text = "📁 $n"
                h.itemView.setOnClickListener { dialog.dismiss(); classify(p, n) }
            }

            override fun getItemCount() = names.size
        }
        dialog.setContentView(rv)
        dialog.show()
    }
}
