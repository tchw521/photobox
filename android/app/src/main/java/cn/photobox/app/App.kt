package cn.photobox.app

import android.app.Application

/**
 * 应用入口。
 *
 * 崩溃防护在这里安装，而不是 MainActivity.onCreate——
 * 后者太晚：窗口创建、资源解析等阶段的异常根本捕获不到，
 * 表现就是"启动页闪退且看不到任何日志"。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
        CrashGuard.guard {
            // 皮肤提前加载一次，避免 Activity 启动时才读偏好
            SkinNow.load(this)
        }
        CrashGuard.guard { Store.loadSettings(this) }
    }
}
