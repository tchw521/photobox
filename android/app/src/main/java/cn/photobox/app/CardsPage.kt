package cn.photobox.app

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.max

/**
 * 卡片页：把待整理照片叠放成卡片堆，用四向手势快速处理。
 *
 * 手势（v1.2 基线）：
 * - 左滑 → 上一张
 * - 右滑 → 下一张
 * - 上滑 → 清理到回收站
 * - 下滑 → 收藏
 * - 长按后（或长按并下滑）→ 弹出相册列表归类
 *
 * 两个关键稳定性处理：
 * 1. ACTION_DOWN 时 requestDisallowInterceptTouchEvent(true)，
 *    防止父容器在 MOVE 中抢走事件导致手势被 ACTION_CANCEL 中断。
 * 2. 长按生效后抬手即弹归类菜单，不必强求下滑，容错更高。
 */
class CardsPage(private val act: MainActivity, private val root: View) {

    private var stage: View? = null
    private var c0: ImageView? = null
    private var c1: ImageView? = null
    private var c2: ImageView? = null
    private var tip: TextView? = null
    private var progress: TextView? = null
    private var albumBar: RecyclerView? = null

    private var queue: MutableList<Photo> = mutableListOf()
    private var idx = 0
    private var done = 0
    private var bound = false

    private val handler = Handler(Looper.getMainLooper())
    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private var longMode = false
    private var moved = false

    companion object {
        private const val THRESHOLD = 90f
        private const val LONG_MS = 450L
        private const val TIP = "左滑上一张 · 右滑下一张 · 上滑回收 · 下滑收藏 · 长按归类"
    }

    fun bind() {
        CrashGuard.guard { bindInner() }
    }

    private fun bindInner() {
        bound = false
        stage = root.findViewById(R.id.stage)
        c0 = root.findViewById(R.id.c0)
        c1 = root.findViewById(R.id.c1)
        c2 = root.findViewById(R.id.c2)
        tip = root.findViewById(R.id.tip)
        progress = root.findViewById(R.id.progress)
        albumBar = root.findViewById(R.id.albumBar)

        applySkin()
        queue = act.cardPhotos().toMutableList()
        idx = 0
        done = 0

        attachGesture()
        bindAlbums()
        bindButtons()
        render()
        bound = true
    }

    /** 卡片页配色。 */
    private fun applySkin() {
        val s = SkinNow.skin
        tip?.setTextColor(s.textDim)
        progress?.setTextColor(s.text)
        c0?.background = Glass.card(s, 16f)
        c1?.background = Glass.card(s, 16f)
        c2?.background = Glass.card(s, 16f)
    }

    private fun bindButtons() {
        val s = SkinNow.skin
        root.findViewById<View>(R.id.btnPrev)?.setOnClickListener { CrashGuard.guard { prev() } }
        root.findViewById<View>(R.id.btnSkip)?.setOnClickListener { CrashGuard.guard { skip() } }
        root.findViewById<View>(R.id.btnTrash)?.setOnClickListener {
            CrashGuard.guard { current()?.let { dropTrash(it) } }
        }
        val modeBtn = root.findViewById<android.widget.Button>(R.id.btnMode)
        modeBtn?.setOnClickListener {
            CrashGuard.guard {
                Store.cardModeMove = !Store.cardModeMove
                Store.saveSettings(act)
                updateModeButton()
            }
        }
        updateModeButton()
        listOf(R.id.btnPrev, R.id.btnSkip, R.id.btnTrash, R.id.btnMode).forEach { id ->
            val b = root.findViewById<android.widget.Button>(id)
            b?.setTextColor(s.text)
            b?.background = Glass.solid(s.glass, 8f)
        }
    }

    private fun updateModeButton() {
        root.findViewById<android.widget.Button>(R.id.btnMode)?.text =
            if (Store.cardModeMove) "移动模式" else "复制模式"
    }

    /** 底部相册栏：点击即把当前照片归档到该相册。 */
    private fun bindAlbums() {
        val rv = albumBar ?: return
        rv.layoutManager = LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false)
        val names = act.allAlbumNames()
        val items = listOf("＋ 新建图集") + names
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(p: android.view.ViewGroup, t: Int) =
                object : RecyclerView.ViewHolder(Ui.row(act, "", SkinNow.skin.text, {})) {}

            override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                CrashGuard.guard {
                    val tv = h.itemView as TextView
                    val s = SkinNow.skin
                    if (i == 0) {
                        tv.text = items[0]
                        tv.setTextColor(s.accent)
                        tv.setOnClickListener {
                            CrashGuard.guard {
                                val p = current()
                                if (p == null) Ui.toast(act, "没有待整理的照片")
                                else Ui.input(act, "新建图集", "图集名称") { n -> classify(p, n) }
                            }
                        }
                    } else {
                        val n = names[i - 1]
                        tv.text = "\uD83D\uDCC1 $n"
                        tv.setTextColor(s.text)
                        tv.setOnClickListener {
                            CrashGuard.guard {
                                val p = current()
                                if (p == null) Ui.toast(act, "没有待整理的照片")
                                else classify(p, n)
                            }
                        }
                    }
                    tv.background = Glass.bubble(s, false)
                }
            }

            override fun getItemCount() = items.size
        }
    }

    // ------------------------------------------------------------ 手势
    private fun attachGesture() {
        val view = c0 ?: return
        view.isClickable = true          // 保证 ImageView 稳定接收触摸序列
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!bound) return@setOnTouchListener false
                    // 防止父容器把后续 MOVE 抢走，导致滑动中途收到 ACTION_CANCEL
                    view.parent?.requestDisallowInterceptTouchEvent(true)
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
                    val far = max(abs(dx), abs(dy))
                    // 移动超过阈值则取消长按（手指抖动 12px 内不打断）
                    if (far > 12 && !moved) {
                        moved = true
                        handler.removeCallbacks(longRunnable)
                    }
                    if (abs(dx) > abs(dy)) {
                        view.translationX = dx
                        view.translationY = 0f
                    } else {
                        view.translationY = dy
                        view.translationX = 0f
                    }
                    view.alpha = (1f - far / 320f).coerceIn(0.4f, 1f)
                    if (longMode) {
                        view.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).start()
                    }
                    tip?.text = hint(dx, dy)
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
                    if (up) CrashGuard.guard { settle(dx, dy, wasLong) }
                    true
                }
                else -> false
            }
        }
    }

    private val longRunnable = Runnable {
        longMode = true
        CrashGuard.guard {
            c0?.animate()?.scaleX(1.04f)?.scaleY(1.04f)?.setDuration(120)?.start()
            tip?.text = "按住并下滑 → 归类到相册"
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
        when {
            // 长按生效后：抬手即弹归类菜单（下滑同样触发，更容错）
            wasLong -> showMoveSheet(p)
            abs(dx) > abs(dy) && dx <= -THRESHOLD -> prev()
            abs(dx) > abs(dy) && dx >= THRESHOLD -> next()
            dy <= -THRESHOLD -> dropTrash(p)
            dy >= THRESHOLD -> dropFav(p)
        }
    }

    private fun resetCard() {
        c0?.translationX = 0f
        c0?.translationY = 0f
        c0?.alpha = 1f
        c0?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start()
        tip?.text = TIP
    }

    // ------------------------------------------------------------ 翻页
    private fun prev() {
        if (idx > 0) {
            idx--
            render()
        } else Ui.toast(act, "已经是第一张")
    }

    private fun next() {
        if (idx < queue.size - 1) {
            idx++
            render()
        } else Ui.toast(act, "没有更多了")
    }

    private fun skip() {
        val p = current() ?: return
        queue.removeAt(idx)
        queue.add(p)
        render()
    }

    private fun current(): Photo? = queue.getOrNull(idx)

    private fun render() {
        CrashGuard.guard {
            progress?.text = "$done / ${queue.size + done}"
            val p = current()
            if (p == null) {
                c0?.setImageDrawable(null)
                c1?.setImageDrawable(null)
                c2?.setImageDrawable(null)
                tip?.text = "全部整理完毕"
                return
            }
            Thumbs.into(act, p, 720, c0 ?: return)
            queue.getOrNull(idx + 1)?.let { Thumbs.into(act, it, 720, c1 ?: return) }
                ?: c1?.setImageDrawable(null)
            queue.getOrNull(idx + 2)?.let { Thumbs.into(act, it, 720, c2 ?: return) }
                ?: c2?.setImageDrawable(null)
            // 叠放纵深：后层依次缩小
            c1?.apply { scaleX = 0.96f; scaleY = 0.96f; translationY = 10f }
            c2?.apply { scaleX = 0.92f; scaleY = 0.92f; translationY = 20f }
            c0?.apply { scaleX = 1f; scaleY = 1f; translationY = 0f }
        }
    }

    private fun advance(removed: Photo) {
        CrashGuard.guard {
            queue.remove(removed)
            done++
            if (idx >= queue.size) idx = (queue.size - 1).coerceAtLeast(0)
            render()
        }
    }

    // ------------------------------------------------------------ 操作
    private fun dropFav(p: Photo) {
        CrashGuard.guard {
            val fav = Store.favorites(act)
            fav.add(p.id.toString())
            Store.setFavorites(act, fav)
            Ui.toast(act, "已收藏")
            advance(p)
            act.afterCardAction()
        }
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

    private fun showMoveSheet(p: Photo) {
        Ui.albumSheet(act, act.allAlbumNames()) { name -> classify(p, name) }
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
}
