package cn.photobox.app

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
    /** 视图是否绑定成功。绑定失败时手势整体不响应，避免空引用崩溃。 */
    private var bound = false

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
        CrashGuard.safe(act, "卡片页初始化失败") { bindInner() }
    }

    private fun bindInner() {
        bound = false
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
        c0.isClickable = true          // 保证 ImageView 稳定接收触摸序列
        bindAlbums()
        bound = true
        attachGesture()
        render()
    }

    // ------------------------------------------------------------ 底部相册栏
    /** 底部相册栏：复用 Ui.albumSheet 的同款适配器，保证两处交互一致。 */
    private fun bindAlbums() {
        albumList.layoutManager = LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false)
        albumList.adapter = AlbumSheetAdapter(act, act.allAlbumNames(), true, true) { name ->
            val p = current()
            if (p == null) {
                Ui.toast(act, "没有待整理的照片")
                return@AlbumSheetAdapter
            }
            classify(p, name)
        }
    }

    // ------------------------------------------------------------ 手势
    @SuppressLint("ClickableViewAccessibility")
    private fun attachGesture() {
        c0.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!bound) return@setOnTouchListener false
                    // 防止父容器把后续 MOVE 抢走，导致滑动中途收到 ACTION_CANCEL
                    c0.parent?.requestDisallowInterceptTouchEvent(true)
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
                    if (abs(dx) > 12 || abs(dy) > 12) {
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
                    val wasLong = longMode
                    dragging = false
                    longMode = false
                    moved = false
                    resetCard()
                    if (up) settle(dx, dy, wasLong)
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

    private fun settle(dx: Float, dy: Float, wasLong: Boolean) {
        val p = current()
        if (p == null) {
            Ui.toast(act, "没有待整理的照片")
            return
        }
        val far = maxOf(abs(dx), abs(dy)) >= THRESHOLD
        when {
            // 长按生效后：抬手即弹归类菜单，下滑同样触发（更容错）
            wasLong -> showMoveSheet(p)
            abs(dx) > abs(dy) && dx <= -THRESHOLD -> prev()
            abs(dx) > abs(dy) && dx >= THRESHOLD -> next()
            dy <= -THRESHOLD -> dropTrash(p)
            dy >= THRESHOLD -> dropFav(p)
            !far -> Unit
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
            Ui.toast(act, "已经是第一张")
        }
    }

    private fun next() {
        if (idx + 1 < queue.size) {
            idx++
            render()
        } else {
            Ui.toast(act, "没有更多了")
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
        // 叠放层次：轻微旋转 + 缩放差，营造卡片堆叠的纵深
        c0.rotation = 0f; c0.scaleX = 1f; c0.scaleY = 1f
        c1.rotation = -4f; c1.scaleX = 0.96f; c1.scaleY = 0.96f
        c2.rotation = 4f; c2.scaleX = 0.92f; c2.scaleY = 0.92f
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
        Ui.toast(act, "已收藏")
        advance(p)
        act.afterCardAction()
    }

    private fun dropTrash(p: Photo) {
        Ui.async(act, io = {
            val items = Store.trash(act).toMutableList()
            var ok = false
            Ui.write(act, {
                Repo.moveToTrash(act, p) { e -> Ui.main { act.requestDeleteConsent(e) } }?.let { items.add(it); ok = true }
                true
            }) { e -> Ui.main { act.requestDeleteConsent(e) } }
            Store.saveTrash(act, items)
            ok
        }, ui = { ok ->
            Ui.toast(act, if (ok) "已清理到回收站" else "清理失败")
            if (ok) advance(p)
            act.afterCardAction()
        })
    }

    private fun classify(p: Photo, album: String) {
        val move = Store.cardModeMove
        Ui.async(act, io = {
            var ok = false
            if (move) {
                ok = Ui.write(act, { Repo.moveToAlbum(act, p, album) }) { e -> act.requestDeleteConsent(e) }
                if (!ok) ok = Repo.copyToAlbum(act, p, album)   // 低版本退回复制
            } else {
                ok = Repo.copyToAlbum(act, p, album)
            }
            ok
        }, ui = { ok ->
            Ui.toast(act, if (ok) "已${if (move) "移动" else "复制"}到「$album」" else "操作失败")
            if (ok) advance(p)
            act.afterCardAction()
            bindAlbums()
        })
    }

    // ------------------------------------------------------------ 归类弹窗
    private fun showMoveSheet(p: Photo) {
        Ui.albumSheet(act, act.allAlbumNames()) { name -> classify(p, name) }
    }
}
