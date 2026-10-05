package cn.photobox.app

import android.app.Activity
import android.content.Context
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃防护。
 *
 * 做两件事：
 * 1. 兜底未捕获异常，写入私有目录的 crash.log，并尽力给出提示，
 *    避免"点了没反应、直接消失"这种无从排查的情况。
 * 2. 提供 safe {} 包装，把单处失败降级为提示，不影响整体流程。
 */
object CrashGuard {

    private const val MAX = 40_000

    fun install(c: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                write(c, t, e)
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    @PublishedApi
    internal fun write(c: Context, t: Thread, e: Throwable) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val sb = StringBuilder()
        sb.append("=== $time | thread=${t.name} | v${MainActivity.APP_VERSION} ===\n")
        sb.append(e.toString()).append('\n')
        e.stackTrace.take(30).forEach { sb.append("  at $it\n") }
        e.cause?.let {
            sb.append("Caused by: $it\n")
            it.stackTrace.take(15).forEach { l -> sb.append("  at $l\n") }
        }
        sb.append('\n')

        val f = File(c.filesDir, "crash.log")
        val old = if (f.exists()) f.readText() else ""
        f.writeText((sb.toString() + old).take(MAX))
    }

    /** 读取崩溃日志，供设置页展示。 */
    fun read(c: Context): String {
        val f = File(c.filesDir, "crash.log")
        return if (f.exists()) f.readText() else ""
    }

    fun clear(c: Context) {
        File(c.filesDir, "crash.log").delete()
    }

    /** 单处失败降级：不抛出，只提示。 */
    inline fun safe(a: Activity, msg: String = "操作失败", block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            write(a, Thread.currentThread(), e)
            try {
                Toast.makeText(a, "$msg：${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
            }
        }
    }
}
