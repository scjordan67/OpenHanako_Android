plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // Android 插件**不在这里声明**。即使写 `apply false`，Gradle 仍会去解析插件
    // 坐标，而 AGP 只在 Google 的仓库（dl.google.com）上 —— 开发容器的出口策略
    // 挡了这个域，于是连 :core 的构建都会被拖垮。
    //
    // 改由 android/build.gradle.kts 自己声明：那个文件只在
    // `-Pandroid.enabled=true` 把模块包含进来时才求值，所以没有 SDK 的环境
    // 完全不受影响。见 settings.gradle.kts。
}
