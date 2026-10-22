package cn.photobox.app

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs

/**
 * 全屏图片查看器：单击图库照片后进入。
 *
 * 能力：
 * - **双击缩放**：1 倍 ↔ 2.5 倍，带动画
 * - **捏合缩放**：1~5 倍，手指中心为锚点
 * - **拖动平移**：放大后可拖动查看细节
 * - **左右滑动切换**：仅在未放大时生效，避免与拖动冲突
 * - **下滑 / 单击空白关闭**
 *
 * 直接以 ContentView 的形式叠在 Activity 上，不新建 Activity、
 * 不引入图片加载库，复用既有的 Thumbs 高清解码与 Glass 玻璃质感。
 */
class PhotoViewer(
    private val act: MainActivity,
    private val list: List<Photo>,
    private var index: Int,
    private val onDelete: (Photo) -> Unit,
) {

    private var root: FrameLayout? = null
    private var image: ImageView? = null
    private var caption: TextView? = null
    private var scale = 1f
    private var panX = 0f
    private var panY = 0f

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var swiping = false
    private var dragging = false

    private lateinit var scaleDetector: ScaleGestureDetector

    companion object {
        private const val MAX_SCALE = 5f
        private const val DOUBLE_TAP_SCALE = 2.5f
        private const val SWIPE = 60f      // 切换阈值
    }

    fun show() {
        CrashGuard.guard { build() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun build() {
        val s = SkinNow.skin
        val box = FrameLayout(act).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xF2000000.toInt())
        }

        val img = ImageView(act).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val cap = TextView(act).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply { setMargins(24, 24, 24, 48) }
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setTextColor(s.text)
        }

        box.addView(img)
        box.addView(cap)
        root = box
        image = img
        caption = cap

        scaleDetector = ScaleGestureDetector(act, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                scale = (scale * d.scaleFactor).coerceIn(1f, MAX_SCALE)
                applyTransform()
                return true
            }
        })

        // 双击缩放与滑动切换统一在 setupGesture 里处理
        img.isClickable = true
        setupGesture(img)

        val old = act.findViewById<FrameLayout>(R.id.viewerHost)
        if (old != null) (old.parent as? ViewGroup)?.removeView(old)
        val host = FrameLayout(act).apply { id = R.id.viewerHost }
        host.addView(box)
        act.addContentView(
            host,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        render()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGesture(img: ImageView) {
        var lastTap = 0L
        img.setOnTouchListener { _, e ->
            scaleDetector.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastTap < 280) toggleZoom()
                lastTap = now
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                swiping = scale <= 1.02f
                dragging = scale > 1.02f
            } else if (e.actionMasked == MotionEvent.ACTION_MOVE) {
                val dx = e.x - lastX
                val dy = e.y - lastY
                if (dragging && scale > 1.02f) {
                    panX += dx; panY += dy
                    applyTransform()
                }
                lastX = e.x; lastY = e.y
            } else if (e.actionMasked == MotionEvent.ACTION_UP ||
                e.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                val dx = e.x - downX
                val dy = e.y - downY
                if (scale <= 1.02f && e.actionMasked == MotionEvent.ACTION_UP) {
                    when {
                        dy > SWIPE * 1.6f && abs(dy) > abs(dx) -> dismiss()
                        abs(dx) > SWIPE && abs(dx) > abs(dy) ->
                            if (dx < 0) step(1) else step(-1)
                        abs(dx) < 12f && abs(dy) < 12f -> dismiss()
                    }
                }
                dragging = false
            }
            true
        }
    }

    private fun toggleZoom() {
        val target = if (scale > 1.2f) 1f else DOUBLE_TAP_SCALE
        val from = scale
        ValueAnimator.ofFloat(from, target).apply {
            duration = 180
            addUpdateListener {
                scale = it.animatedValue as Float
                if (scale <= 1.02f) { panX = 0f; panY = 0f }
                applyTransform()
            }
            start()
        }
    }

    private fun applyTransform() {
        image?.scaleX = scale
        image?.scaleY = scale
        image?.translationX = panX
        image?.translationY = panY
    }

    private fun step(delta: Int) {
        val n = index + delta
        if (n !in list.indices) return
        index = n
        scale = 1f; panX = 0f; panY = 0f
        render()
    }

    private fun render() {
        val p = list.getOrNull(index) ?: return
        // 复用高清解码：目标边长按屏幕长边 ×3 封顶 4096
        Thumbs.into(act, p, CardsPage.ORIGINAL, image ?: return)
        caption?.text = "${p.name}\n${p.album} · ${p.dateText} · ${formatSize(p.size)}" +
            "\n双击缩放 · 左右滑动切换 · 下滑关闭"
    }

    private fun dismiss() {
        CrashGuard.guard {
            val host = act.findViewById<FrameLayout>(R.id.viewerHost)
            (host?.parent as? ViewGroup)?.removeView(host)
            root = null; image = null; caption = null
        }
    }
}
