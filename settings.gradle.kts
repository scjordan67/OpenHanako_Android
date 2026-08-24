pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "OpenHanakoAndroid"

// :core 是纯 Kotlin/JVM —— 不依赖 Android SDK，因此可以在任何装了 JDK 的机器上
// 编译与测试（包括没有 Android SDK 的 CI 容器）。可移植的那半个 HanaAgent
// （人格装配、MoodParser、记忆传送带、搜索降级、限流、模型能力判定）全部住在这里。
include(":core")

// :android 是薄壳 —— Compose 界面、WebView 搜索宿主、SQLite/FTS5、WorkManager、SAF。
// 需要 Android SDK 才能构建，故默认不纳入。设置 ANDROID_SDK_ROOT（或 local.properties
// 里的 sdk.dir）后，用 -Pandroid.enabled=true 打开。
if (providers.gradleProperty("android.enabled").orNull == "true") {
    include(":android")
}
