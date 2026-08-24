package com.hanaagent.android

import android.app.Application

/**
 * 应用入口。
 *
 * 目前是空壳：Stage 1 会在这里装配 :core 的运行时（模型客户端、会话存储、
 * 记忆调度），Stage 3 会挂上 WorkManager 的补偿式维护。
 */
class HanaApplication : Application()
