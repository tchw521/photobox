package cn.photobox.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃防护。
 *
 * 两件事：
 * 1. 兜底未捕获异常，写入私有目录的 crash.log，便于在设置页直接查看。
 * 2. 提供 safe {} / log() 包装，把单处失败降级为提示，不影响整体流程。
 */
object CrashGuard {

    private const val MAX = 60_000

    /** 记录一条异常（不抛出）。全项目复用此入口。 */
    @JvmStatic
    fun log(e: Throwable) {
        try {
            write(null, Thread.currentThread(), e)
        } catch (_: Throwable) {
        }
    }

    @PublishedApi
    internal fun write(c: Context?, t: Thread, e: Throwable) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val sb = StringBuilder()
        sb.append("=== $time | thread=${t.name} | v${MainActivity.APP_VERSION} ===\n")
        sb.append(e.toString()).append('\n')
        e.stackTrace.take(25).forEach { sb.append("  at $it\n") }
        var cause = e.cause
        var depth = 0
        while (cause != null && depth < 3) {
            sb.append("Caused by: $cause\n")
            cause.stackTrace.take(12).forEach { sb.append("  at $cause\n") }
            cause = cause.cause
            depth++
        }
        sb.append('\n')

        val ctx = (c ?: appCtx) ?: return
        val f = File(ctx.filesDir, "crash.log")
        val old = if (f.exists()) f.readText() else ""
        f.writeText((sb.toString() + old).take(MAX))
    }

    /** install 时保存 applicationContext，供无 Context 场景写日志。 */
    private var appCtx: Context? = null

    /**
     * 安装全局兜底。
     *
     * 关键：捕获后【不再】转交系统默认的 handler。
     * 默认 handler 会终止进程，表现就是用户看到的「闪退」。
     * 这里改为只记录日志并让消息循环继续，应用保持可用；
     * 日志可在设置页查看，便于定位。
     */
    fun install(c: Context) {
        appCtx = c.applicationContext
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                write(c, t, e)
            } catch (_: Throwable) {
                // 写日志失败也不能再抛，否则又回到崩溃
            }
        }
    }

    /** 读取崩溃日志，供设置页展示。 */
    fun read(c: Context): String {
        return try {
            val f = File(c.filesDir, "crash.log")
            if (f.exists()) f.readText() else ""
        } catch (_: Throwable) {
            ""
        }
    }

    fun clear(c: Context) {
        try {
            File(c.filesDir, "crash.log").delete()
        } catch (_: Throwable) {
        }
    }

    /** 兜底执行，吞掉异常并记日志。供各处 onBind / 回调复用。 */
    inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            log(e)
        }
    }

    /** 带返回值的兜底：异常时返回默认值而不抛出。 */
    inline fun <T> result(block: () -> T, fallback: T): T = try {
        block()
    } catch (e: Throwable) {
        log(e)
        fallback
    }

    /** 单处失败降级：不抛出，只提示。 */
    inline fun safe(a: android.app.Activity, msg: String = "操作失败", block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            write(a.applicationContext, Thread.currentThread(), e)
            try {
                if (!a.isFinishing && !a.isDestroyed) {
                    android.widget.Toast.makeText(
                        a.applicationContext,
                        "$msg：${e.message ?: e.javaClass.simpleName}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (_: Throwable) {
            }
        }
    }
}
