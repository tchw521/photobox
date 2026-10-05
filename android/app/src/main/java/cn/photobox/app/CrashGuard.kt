package cn.photobox.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃防护。
 *
 * 核心原则：**区分主线程与子线程**。
 *
 * - 主线程异常会让 Looper 退出。若 handler 什么都不做，应用会停在白屏假死，
 *   用户既看不到界面也拿不到日志——比直接闪退更糟。所以主线程必须重启应用。
 * - 子线程异常不影响 UI，只记录即可，进程继续运行。
 *
 * 同时提供 safe / guard / result 三个包装，把单处失败降级，不拖垮整体流程。
 */
object CrashGuard {

    private const val MAX = 60_000

    private var appCtx: Context? = null

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
        val ctx = (c ?: appCtx) ?: return
        try {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val sb = StringBuilder()
            sb.append("=== $time | thread=${t.name} | v${MainActivity.APP_VERSION} ===\n")
            sb.append(e.javaClass.name).append(": ").append(e.message).append('\n')
            e.stackTrace.take(25).forEach { sb.append("  at $it\n") }
            var cause = e.cause
            var depth = 0
            while (cause != null && depth < 3) {
                sb.append("Caused by: ${cause.javaClass.name}: ${cause.message}\n")
                cause.stackTrace.take(12).forEach { sb.append("  at $it\n") }
                cause = cause.cause
                depth++
            }
            sb.append('\n')

            val f = File(ctx.filesDir, "crash.log")
            val old = if (f.exists()) f.readText() else ""
            f.writeText((sb.toString() + old).take(MAX))
        } catch (_: Throwable) {
        }
    }

    fun install(c: Context) {
        appCtx = c.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                write(c, t, e)
            } catch (_: Throwable) {
            }

            if (Looper.myLooper() == Looper.getMainLooper()) {
                // 主线程：Looper 已经停止，必须重启，否则白屏假死
                try {
                    restart(c)
                } catch (_: Throwable) {
                    prev?.uncaughtException(t, e)
                }
            } else {
                // 子线程：UI 仍在运行，只记录，进程继续
                // （线程池任务已各自 try/catch，能走到这里说明是别处的线程）
            }
        }
    }

    /** 重启应用回到主界面。日志已写入，用户随后可在设置页查看。 */
    private fun restart(c: Context) {
        val pm = c.packageManager
        val intent = pm.getLaunchIntentForPackage(c.packageName)
            ?: Intent(c, MainActivity::class.java)
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        val pi = PendingIntent.getActivity(
            c, 9527, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = c.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (am != null) {
            am.set(AlarmManager.RTC, System.currentTimeMillis() + 400, pi)
        } else {
            run { c.startActivity(intent) }
        }
        Process.killProcess(Process.myPid())
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

    /** 兜底执行，吞掉异常并记日志。 */
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
