package cn.photobox.app

import android.app.Activity
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
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 全项目复用的 UI 与异步工具层。
 *
 * 收敛五类重复逻辑，任何页面都应优先调用这里的方法而不是各写一份：
 *   1. 安全弹窗（dialog）—— 统一处理 Activity 已销毁、窗口 token 失效
 *   2. 相册选择弹窗（albumSheet）—— 图库多选、卡片归类共用同一份实现
 *   3. 后台执行 + 主线程回调（io/main）—— 统一线程池，避免到处 new Thread
 *   4. 媒体库写操作（write）—— 统一捕获 RecoverableSecurityException
 *   5. 轻量提示（toast）
 */
object Ui {

    private val main = Handler(Looper.getMainLooper())

    /** 复用同一个 IO 线程池，全项目不再单独 new Thread。 */
    val io: ExecutorService = Executors.newFixedThreadPool(3)

    fun main(f: () -> Unit) {
        main.post { run(f) }
    }

    /** 兜底执行：吞掉异常并记日志，防止单点失败拖垮整个流程。 */
    inline fun run(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            CrashGuard.log(e)
        }
    }

    /** Activity 是否还能安全弹窗。 */
    private fun alive(a: Activity?): Boolean =
        a != null && !a.isFinishing && !a.isDestroyed

    fun toast(c: Context, text: String) {
        run { Toast.makeText(c.applicationContext, text, Toast.LENGTH_SHORT).show() }
    }

    // ------------------------------------------------------------ 弹窗
    /**
     * 统一弹窗入口。
     *
     * 注意：必须用 apply(build) 把配置 lambda 真正应用到 builder 上。
     * 此前的写法误调用了 AlertDialog.Builder.build()（该方法是 create + show），
     * 导致配置全部丢失且多弹出一个空窗。
     */
    fun dialog(a: Activity, build: MaterialAlertDialogBuilder.() -> Unit) {
        if (!alive(a)) return
        run {
            val b = MaterialAlertDialogBuilder(a)
            b.apply(build)                 // 真正应用调用方的配置
            val d = b.create()
            // show 前再确认一次，避免 Activity 已销毁时的 BadTokenException
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
        okText: String = "确定", danger: Boolean = false,
        onOk: () -> Unit,
    ) {
        dialog(a) {
            setTitle(title)
            setMessage(message)
            setNegativeButton("取消", null)
            setPositiveButton(okText) { _: DialogInterface, _: Int -> run(onOk) }
        }
    }

    // ------------------------------------------------------------ 相册选择弹窗
    /**
     * 相册选择弹窗，图库多选移动与卡片页归类共用。
     *
     * @param names      可选相册名（含是否可新建由 create 决定）
     * @param create     为 true 时首项显示「新建图集」
     */
    fun albumSheet(
        a: Activity,
        names: List<String>,
        create: Boolean = true,
        onPick: (String) -> Unit,
    ) {
        if (!alive(a)) return
        run {
            val sheet = BottomSheetDialog(a)
            val rv = RecyclerView(a).apply {
                layoutManager = LinearLayoutManager(a)
                setPadding(16, 12, 16, 24)
            }
            rv.adapter = AlbumSheetAdapter(a, names, create, create) { name ->
                sheet.dismiss()
                // 让弹窗关闭动画结束后再执行，避免与 dismiss 抢焦点
                main.post { onPick(name) }
            }
            sheet.setContentView(rv)
            if (alive(a)) sheet.show()
        }
    }

    /** 新建图集输入框，创建完成后回调名称。 */
    fun newAlbum(a: Activity, onDone: (String) -> Unit) {
        dialog(a) {
            val input = android.widget.EditText(a).apply {
                hint = "图集名称"
                setTextColor(resolveColor(a, R.attr.textColorMain))
                setHintTextColor(resolveColor(a, R.attr.textColorDim))
            }
            setTitle("新建图集")
            setView(input)
            setNegativeButton("取消", null)
            setPositiveButton("创建") { _: DialogInterface, _: Int ->
                val n = input.text.toString().trim()
                if (n.isNotBlank()) run { onDone(n) }
            }
        }
    }

    // ------------------------------------------------------------ 后台 + 主线程
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
                if (alive(a)) run { ui(r as T) } else Unit
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

    // ------------------------------------------------------------ 小工具
    fun chip(a: Activity, text: String, onClick: View.OnClickListener): TextView =
        TextView(a).apply {
            this.text = text
            textSize = 14f
            setPadding(28, 30, 28, 30)
            setTextColor(resolveColor(a, R.attr.textColorMain))
            setOnClickListener(onClick)
        }
}

/** 相册选择弹窗的适配器，供 Ui.albumSheet 复用。 */
class AlbumSheetAdapter(
    private val a: Activity,
    private val names: List<String>,
    private val withCreate: Boolean,
    private val onCreate: Boolean,
    private val onPick: (String) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemCount() = names.size + if (withCreate) 1 else 0

    override fun onCreateViewHolder(p: android.view.ViewGroup, t: Int) =
        object : RecyclerView.ViewHolder(Ui.chip(a, "", { })) {}

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
        val tv = h.itemView as TextView
        if (withCreate && i == 0) {
            tv.text = "＋ 新建图集"
            tv.setTextColor(resolveColor(a, R.attr.accentColor))
            tv.setOnClickListener {
                if (onCreate) Ui.newAlbum(a) { name -> onPick(name) } else Unit
            }
        } else {
            val n = names[i - if (withCreate) 1 else 0]
            tv.text = "\uD83D\uDCC1 $n"
            tv.setOnClickListener { onPick(n) }
        }
    }
}
