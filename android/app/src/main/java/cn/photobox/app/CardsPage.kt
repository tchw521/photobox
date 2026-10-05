package cn.photobox.app

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.abs

/**
 * 卡片页：照片堆叠浏览 + 四向手势 + 长按下滑归类。
 *
 * 手势定义：
 *   左滑 → 上一张      右滑 → 下一张
 *   上滑 → 清理到回收站 下滑 → 收藏
 *   长按后下滑 → 归类到相册
 *
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
    private lateinit var albumList: RecyclerView

    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private var longMode = false
    private var moved = false
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val THRESHOLD = 90f
        private const val LONG_MS = 450L
        private const val TIP = "左滑上一张 · 右滑下一张 · 上滑回收 · 下滑收藏 · 长按下滑归类"
    }

    private val longRunnable = Runnable {
        if (dragging && !moved) {
            longMode = true
            tip.text = "按住并下滑 → 归类到相册"
            c0.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).start()
        }
    }

    fun bind() {
        stage = root.findViewById(R.id.stage)
        c0 = root.findViewById(R.id.card0)
        c1 = root.findViewById(R.id.card1)
        c2 = root.findViewById(R.id.card2)
        tip = root.findViewById(R.id.cardTip)
        nameView = root.findViewById(R.id.cardName)
        progress = root.findViewById(R.id.progress)
        btnMode = root.findViewById(R.id.btnMode)
        albumList = root.findViewById(R.id.cardAlbums)

        queue = act.cardPhotos().toMutableList()
        idx = 0
        done = 0

        btnMode.text = if (Store.cardModeMove) "移动" else "复制"
        btnMode.setOnClickListener {
            Store.cardModeMove = !Store.cardModeMove
            Store.saveSettings(act)
            btnMode.text = if (Store.cardModeMove) "移动" else "复制"
        }

        root.findViewById<Button>(R.id.btnPrev).setOnClickListener { prev() }
        root.findViewById<Button>(R.id.btnSkip).setOnClickListener { skip() }
        root.findViewById<Button>(R.id.btnTrash).setOnClickListener { current()?.let { dropTrash(it) } }

        tip.text = TIP
        bindAlbums()
        attachGesture()
        render()
    }

    // ------------------------------------------------------------ 底部相册栏
    private fun bindAlbums() {
        albumList.layoutManager = LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false)
        val names = act.cardPhotos().map { it.album }.distinct().sorted()
        // 第 0 项为「新建图集」
        val total = names.size + 1
        albumList.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
                val tv = TextView(act).apply {
                    setPadding(20, 16, 20, 16); textSize = 12f
                    setTextColor(act.resources.getColor(R.color.text, null))
                    background = act.getDrawable(R.drawable.bg_bubble)
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }

            override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                val tv = h.itemView as TextView
                if (i == 0) {
                    tv.text = "＋ 新建图集"
                    tv.setTextColor(act.resources.getColor(R.color.accent, null))
                    tv.setOnClickListener { current()?.let { newAlbum(it) } }
                } else {
                    val n = names[i - 1]
                    tv.text = "📁 $n"
                    tv.setOnClickListener { current()?.let { classify(it, n) } }
                }
            }

            override fun getItemCount() = total
        }
    }

    private fun newAlbum(p: Photo) {
        val input = EditText(act).apply { hint = "图集名称" }
        MaterialAlertDialogBuilder(act)
            .setTitle("新建图集")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("创建并归类") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isBlank()) return@setPositiveButton
                classify(p, n)
            }.show()
    }

    // ------------------------------------------------------------ 手势
    @SuppressLint("ClickableViewAccessibility")
    private fun attachGesture() {
        c0.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX
                    startY = e.rawY
                    dragging = true
                    longMode = false
                    moved = false
                    handler.postDelayed(longRunnable, LONG_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startX
                    val dy = e.rawY - startY
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        if (!moved) {
                            moved = true
                            handler.removeCallbacks(longRunnable)
                        }
                    }
                    if (abs(dx) > abs(dy)) {
                        c0.translationX = dx
                        c0.translationY = 0f
                    } else {
                        c0.translationY = dy
                        c0.translationX = 0f
                    }
                    c0.alpha = (1f - maxOf(abs(dx), abs(dy)) / 320f).coerceIn(0.4f, 1f)
                    tip.text = hint(dx, dy)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longRunnable)
                    val dx = e.rawX - startX
                    val dy = e.rawY - startY
                    val up = e.actionMasked == MotionEvent.ACTION_UP
                    dragging = false
                    resetCard()
                    if (up) settle(dx, dy)
                    true
                }
                else -> false
            }
        }
    }

    private fun hint(dx: Float, dy: Float): String = when {
        longMode && dy >= THRESHOLD -> "松手 → 归类到相册"
        abs(dx) > abs(dy) && dx <= -THRESHOLD -> "松手 → 上一张"
        abs(dx) > abs(dy) && dx >= THRESHOLD -> "松手 → 下一张"
        dy <= -THRESHOLD -> "松手 → 清理到回收站"
        dy >= THRESHOLD -> "松手 → 收藏"
        else -> if (longMode) "按住并下滑 → 归类到相册" else TIP
    }

    private fun settle(dx: Float, dy: Float) {
        val p = current() ?: return
        when {
            longMode && dy >= THRESHOLD -> showMoveSheet(p)
            abs(dx) > abs(dy) && dx <= -THRESHOLD -> prev()
            abs(dx) > abs(dy) && dx >= THRESHOLD -> next()
            dy <= -THRESHOLD -> dropTrash(p)
            dy >= THRESHOLD -> dropFav(p)
        }
    }

    private fun resetCard() {
        c0.translationX = 0f
        c0.translationY = 0f
        c0.alpha = 1f
        c0.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        tip.text = TIP
    }

    // ------------------------------------------------------------ 翻页
    private fun prev() {
        if (idx > 0) {
            idx--
            render()
        } else {
            Toast.makeText(act, "已经是第一张", Toast.LENGTH_SHORT).show()
        }
    }

    private fun next() {
        if (idx + 1 < queue.size) {
            idx++
            render()
        } else {
            Toast.makeText(act, "没有更多了", Toast.LENGTH_SHORT).show()
        }
    }

    private fun current(): Photo? = queue.getOrNull(idx)

    private fun render() {
        progress.text = "$done / ${queue.size + done}"
        val cur = queue.getOrNull(idx)
        nameView.text = cur?.name ?: "全部整理完成"
        val layers = listOf(c2, c1, c0)
        layers.forEachIndexed { i, iv ->
            val p = queue.getOrNull(idx + (2 - i))
            if (p == null) {
                iv.visibility = View.GONE
            } else {
                iv.visibility = View.VISIBLE
                Thumbs.into(act, p, 480, iv)
            }
        }
        c0.rotation = 0f
        c1.rotation = -4f
        c2.rotation = 4f
        resetCard()
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
        queue.add(p)
        if (idx >= queue.size) idx = 0
        render()
    }

    // ------------------------------------------------------------ 动作
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
            Repo.moveToTrash(act, p) { e -> act.runOnUiThread { act.requestDeleteConsent(e) } }
                ?.let { items.add(it) }
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
            val ok = Repo.copyToAlbum(act, p, album)
            if (ok && Store.cardModeMove) {
                val items = Store.trash(act).toMutableList()
                Repo.moveToTrash(act, p) { e -> act.runOnUiThread { act.requestDeleteConsent(e) } }
                    ?.let { items.add(it) }
                Store.saveTrash(act, items)
            }
            act.runOnUiThread {
                Toast.makeText(act, "已归类到「$album」", Toast.LENGTH_SHORT).show()
                advance(p)
                act.afterCardAction()
                bindAlbums()
            }
        }.start()
    }

    // ------------------------------------------------------------ 归类弹窗
    private fun showMoveSheet(p: Photo) {
        val names = act.cardPhotos().map { it.album }.distinct().sorted()
        val dialog = BottomSheetDialog(act)
        val rv = RecyclerView(act).apply {
            layoutManager = LinearLayoutManager(act)
            setPadding(12, 12, 12, 12)
        }
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(p2: ViewGroup, t: Int): RecyclerView.ViewHolder {
                val tv = TextView(act).apply {
                    setPadding(24, 28, 24, 28)
                    textSize = 14f
                    setTextColor(act.resources.getColor(R.color.text, null))
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }

            override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                val tv = h.itemView as TextView
                if (i == 0) {
                    tv.text = "＋ 新建图集"
                    tv.setTextColor(act.resources.getColor(R.color.accent, null))
                    tv.setOnClickListener { dialog.dismiss(); newAlbum(p) }
                } else {
                    val n = names[i - 1]
                    tv.text = "📁 $n"
                    tv.setOnClickListener { dialog.dismiss(); classify(p, n) }
                }
            }

            override fun getItemCount() = names.size + 1
        }
        dialog.setContentView(rv)
        dialog.show()
    }
}
