package com.hanaagent.android.data

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * 把闪退的堆栈落盘，下次启动显示出来。
 *
 * 存在的理由很直接：**崩溃现场只在 logcat 里，而用户拿不到 logcat。**
 * 反馈只能是"发消息后闪退"这五个字，而这五个字对应几十种可能。有了这个，
 * 下次崩完重开就能看到具体是哪一行抛的什么，截图即可定位。
 *
 * 只留最近一次。崩溃是要立刻处理的东西，攒一堆历史没有意义，反而占地方。
 */
object CrashLog {

    private const val FILE_NAME = "last-crash.txt"

    /**
     * 装上处理器。**必须**接着调用原来那个 —— 系统默认的处理器负责真正结束进程
     * 并上报，吞掉它会让应用卡在一个半死不活的状态，比直接崩还难受。
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        File(context.filesDir, FILE_NAME).writeText(
            buildString {
                appendLine("时间: ${Instant.now()}")
                appendLine("线程: ${thread.name}")
                appendLine()
                append(trace)
            },
        )
    }

    /** 上次崩溃的记录；没有则 null。 */
    fun lastCrash(context: Context): String? =
        File(context.applicationContext.filesDir, FILE_NAME)
            .takeIf { it.exists() }
            ?.runCatching { readText() }
            ?.getOrNull()
            ?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        File(context.applicationContext.filesDir, FILE_NAME).delete()
    }
}
