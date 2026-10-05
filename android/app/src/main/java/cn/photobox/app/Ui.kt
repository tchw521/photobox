package cn.photobox.app

import android.app.Activity
import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 全项目复用的 UI 与异步工具层。
 *
 * 收敛六类重复逻辑，任何页面都应优先调用这里的方法而不是各写一份：
 *   1. 安全弹窗（dialog / confirm / list）     —— 统一处理 Activity 已销毁
 *   2. 相册选择列表（albumSheet）              —— 图库多选与卡片归类共用
 *   3. 后台执行 + 主线程回调（async / io）      —— 统一线程池
 *   4. 媒体库写操作（write）                    —— 统一捕获授权异常
 *   5. 轻量提示（toast）
 *   6. 通用列表弹窗（listSheet）
 *
 * 弹窗一律使用系统 AlertDialog，不引入 Material 组件，减少依赖与崩溃面。
 */
object Ui {

    private val main = Handler(Looper.getMainLooper())

    /** 复用同一个 IO 线程池，全项目不再单独 new Thread。 */
    val io: ExecutorService = Executors.newFixedThreadPool(3)

    fun main(f: () -> Unit) = main.post { run(f) }

    /** 兜底执行：吞掉异常并记日志，防止单点失败拖垮整个流程。 */
    inline fun run(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            CrashGuard.log(e)
        }
    }

    private fun alive(a: Activity?): Boolean = a != null && !a.isFinishing && !a.isDestroyed

    fun toast(c: Context, text: String) {
        run { Toast.makeText(c.applicationContext, text, Toast.LENGTH_SHORT).show() }
    }

    // ------------------------------------------------------------ 弹窗
    /**
     * 统一弹窗入口。
     * show 前二次校验 Activity 状态，避免窗口 token 失效导致的 BadTokenException。
     */
    fun dialog(a: Activity, build: AlertDialog.Builder.() -> Unit) {
        if (!alive(a)) return
        run {
            val b = AlertDialog.Builder(a)
            b.build()
            val d = b.create()
            if (alive(a)) {
                try {
                    d.show()
                } catch (e: Throwable) {
                    CrashGuard.log(e)
                }
            }
        }
    }

    fun confirm(
        a: Activity, title: String, message: String,
        okText: String = "确定",
        onOk: () -> Unit,
    ) {
        dialog(a) {
            setTitle(title)
            setMessage(message)
            setNegativeButton("取消", null)
            setPositiveButton(okText) { _: DialogInterface, _: Int -> run(onOk) }
        }
    }

    /**
     * 通用列表弹窗。
     * @param items   显示项
     * @param onPick  选中回调（索引）
     */
    fun listSheet(a: Activity, title: String, items: List<String>, onPick: (Int) -> Unit) {
        if (!alive(a)) return
        dialog(a) {
            setTitle(title)
            setItems(items.toTypedArray()) { _, i -> run { onPick(i) } }
            setNegativeButton("取消", null)
        }
    }

    /** 输入弹窗。 */
    fun input(a: Activity, title: String, hint: String, onDone: (String) -> Unit) {
        val input = android.widget.EditText(a).apply {
            this.hint = hint
            setTextColor(SkinNow.skin.text)
            setHintTextColor(SkinNow.skin.textDim)
        }
        dialog(a) {
            setTitle(title)
            setView(input)
            setNegativeButton("取消", null)
            setPositiveButton("确定") { _: DialogInterface, _: Int ->
                val n = input.text.toString().trim()
                if (n.isNotBlank()) run { onDone(n) }
            }
        }
    }

    // ------------------------------------------------------------ 相册选择
    /**
     * 相册选择弹窗，图库多选移动与卡片页归类共用。
     * 首项为「新建图集」。
     */
    fun albumSheet(a: Activity, names: List<String>, onPick: (String) -> Unit) {
        if (!alive(a)) return
        val items = listOf("＋ 新建图集") + names
        dialog(a) {
            setTitle("归类到相册")
            setItems(items.toTypedArray()) { _, i ->
                run {
                    if (i == 0) {
                        input(a, "新建图集", "图集名称") { name -> onPick(name) }
                    } else {
                        onPick(names[i - 1])
                    }
                }
            }
            setNegativeButton("取消", null)
        }
    }

    // ------------------------------------------------------------ 异步
    /**
     * 后台执行 io，回主线程执行 ui。统一处理异常，绝不把异常抛回调用方。
     */
    fun <T> async(a: Activity, io: () -> T, ui: (T) -> Unit) {
        this.io.execute {
            val r: T? = try {
                io()
            } catch (e: Throwable) {
                CrashGuard.log(e); null
            }
            main.post {
                if (alive(a)) run { @Suppress("UNCHECKED_CAST") ui(r as T) }
            }
        }
    }

    fun async(a: Activity, io: () -> Unit) = async(a, { io(); Unit }, { })

    // ------------------------------------------------------------ 媒体库写操作
    /**
     * 写媒体库的安全封装。
     * Android 10+ 修改不属于本应用的文件会抛 RecoverableSecurityException，
     * 必须捕获并转交系统授权弹窗，否则直接崩溃。
     */
    fun write(
        a: Activity,
        op: () -> Boolean,
        onConsent: ((RecoverableSecurityException) -> Unit)? = null,
    ): Boolean = try {
        op()
    } catch (e: RecoverableSecurityException) {
        onConsent?.let { run { it(e) } }
        false
    } catch (e: SecurityException) {
        CrashGuard.log(e)
        false
    } catch (e: Throwable) {
        CrashGuard.log(e)
        false
    }

    // ------------------------------------------------------------ 滑动多选
    /**
     * 给 RecyclerView 装上「按住拖动划过即批量选中」。
     *
     * 实现要点：
     * 1. 只在**已进入多选模式**后生效，普通点击浏览不受影响。
     * 2. ACTION_DOWN 时锁定父容器不拦截事件，避免纵向滚动抢走触摸。
     * 3. MOVE 时按手指落点用 findChildViewUnder 找当前项，
     *    只**新增**不移除，因此划过的项会被逐个选中；
     *    反向划回不会取消，符合「扫过一片」的直觉。
     * 4. 到达列表边缘（顶部/底部 60px 内）自动滚动，便于跨屏连续选择。
     *
     * @param pick    把某一下标加入选中
     * @param changed 选中集合变化后刷新界面
     */
    fun swipeSelect(
        rv: RecyclerView,
        isSelectMode: () -> Boolean,
        pick: (Int) -> Unit,
        changed: () -> Unit,
    ) {
        var swiping = false
        var lastPos = -1
        val autoScroll = Runnable {
            CrashGuard.guard { /* 由外层 post 驱动 */ }
        }
        val touch = object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
                if (!isSelectMode()) return false
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        rv.parent?.requestDisallowInterceptTouchEvent(true)
                        swiping = true
                        lastPos = hitTest(rv, e.x, e.y)
                        if (lastPos >= 0) { pick(lastPos); changed() }
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val pos = hitTest(rv, e.x, e.y)
                        if (pos >= 0 && pos != lastPos) {
                            lastPos = pos
                            pick(pos)
                            changed()
                        }
                        // 边缘自动滚动
                        val h = rv.height
                        val edge = 60
                        when {
                            e.y < edge -> rv.scrollBy(0, -24)
                            e.y > h - edge -> rv.scrollBy(0, 24)
                        }
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        swiping = false
                        lastPos = -1
                        rv.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                return false   // 不拦截，保证单击 / 长按仍能正常触发
            }

            override fun onTouchEvent(rv: RecyclerView, e: android.view.MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(b: Boolean) {}
        }
        rv.addOnItemTouchListener(touch)
        autoScroll.run()
    }

    /** 按坐标反查列表项下标。 */
    private fun hitTest(rv: RecyclerView, x: Float, y: Float): Int {
        val v = rv.findChildViewUnder(x, y) ?: return -1
        return rv.getChildAdapterPosition(v)
    }

    // ------------------------------------------------------------ 设置行（复用）
    /**
     * 开关行。设置页所有开关统一用此方法构建，样式随皮肤，不重复写 XML。
     */
    fun switchRow(a: Activity, text: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val s = SkinNow.skin
        val row = android.widget.LinearLayout(a).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(4, 4, 4, 4)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val tv = TextView(a).apply {
            this.text = text
            textSize = 12f
            setTextColor(s.text)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val sw = android.widget.Switch(a).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, b -> run { onChange(b) } }
        }
        row.addView(tv); row.addView(sw)
        return row
    }

    /**
     * 可点击行（右侧显示当前值）。设置页所有选择项统一用此方法构建。
     */
    fun actionRow(a: Activity, text: String, value: String, onClick: () -> Unit): View {
        val s = SkinNow.skin
        val row = android.widget.LinearLayout(a).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(4, 14, 4, 14)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { run(onClick) }
        }
        val tv = TextView(a).apply {
            this.text = text
            textSize = 12f
            setTextColor(s.text)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val vv = TextView(a).apply {
            this.text = value
            textSize = 12f
            setTextColor(s.accent)
        }
        row.addView(tv); row.addView(vv)
        return row
    }

    /** 分组标题。 */
    fun section(a: Activity, text: String): View = TextView(a).apply {
        this.text = text
        textSize = 13f
        setTextColor(SkinNow.skin.accent)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 20, 0, 8)
    }

    // ------------------------------------------------------------ 小工具
    /** 通用列表行（弹窗内复用）。 */
    fun row(a: Activity, text: String, color: Int, onClick: View.OnClickListener): TextView =
        TextView(a).apply {
            this.text = text
            textSize = 14f
            setTextColor(color)
            setPadding(28, 30, 28, 30)
            setOnClickListener(onClick)
        }

    /** 横向列表（卡片页底部相册栏复用）。 */
    fun horizontal(a: Activity, rv: RecyclerView) {
        rv.layoutManager = LinearLayoutManager(a, LinearLayoutManager.HORIZONTAL, false)
    }
}
