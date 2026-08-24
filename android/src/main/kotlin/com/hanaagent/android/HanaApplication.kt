package com.hanaagent.android

import android.app.Application
import com.hanaagent.android.data.CrashLog

/**
 * 应用入口。
 *
 * 目前只做一件事：装上崩溃记录器。它必须在这里装 —— Application.onCreate 是整个
 * 进程里最早跑到的应用代码，装晚了就漏掉启动路径上的崩溃，而那恰恰是最难查的一类。
 *
 * Stage 3 会在这里挂上 WorkManager 的补偿式维护。
 */
class HanaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
