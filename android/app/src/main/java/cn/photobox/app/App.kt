package cn.photobox.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration

/**
 * 应用入口。
 *
 * 崩溃防护在这里安装，而不是 MainActivity.onCreate——
 * 后者太晚：窗口创建、资源解析等阶段的异常根本捕获不到，
 * 表现就是"启动页闪退且看不到任何日志"。
 *
 * 同时注册内存回调（N1）：系统内存紧张时按比例释放缩略图缓存，
 * 避免大量图片场景下被系统杀掉。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
        CrashGuard.guard { SkinNow.load(this) }
        CrashGuard.guard { Store.loadSettings(this) }
        registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                CrashGuard.guard {
                    when {
                        // 后台：整体释放
                        level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> Thumbs.clear()
                        // 中等压力：压到一半
                        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> Thumbs.trim()
                        // 临界：直接清空
                        level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> Thumbs.clear()
                        else -> Unit
                    }
                }
            }

            override fun onConfigurationChanged(c: Configuration) {}
            override fun onLowMemory() { CrashGuard.guard { Thumbs.clear() } }
        })
    }
}
