plugins {
    // 三个插件都在这里带版本号声明，一起装进 :android 自己的 classloader。
    // AGP 与 Kotlin Android 插件必须同处一个 classloader —— KGP 的
    // KotlinAndroidTarget 直接引用 AGP 的 com.android.build.gradle.api.BaseVariant，
    // 分开装载时父加载器看不见子加载器里的 AGP，会报 NoClassDefFoundError。
    // 根项目为此刻意不声明任何插件，原因见 build.gradle.kts。
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.hanaagent.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hanaagent.android"
        // minSdk 26：:core 用了 java.time（逻辑日）和 java.nio.file（jieba 用户词典），
        // 两者都要 API 26。Android 8.0 是 2017 年的，对目标设备不构成限制。
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}")
    }
}

// 放在 android {} 之外：ApplicationExtension 本身没有 kotlin 成员，写在里面实际
// 命中的是 Project 上的同名扩展 —— 能编过但读起来像是 android 的配置项。
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.work.runtime.ktx)
    // 自带 FTS5 的 SQLite。真机探针实测：系统 SQLite 3.44.5 没有 FTS5，
    // 而 FactStore 的整套检索建立在它之上，所以必须 bundle 一份。
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    androidTestImplementation(libs.androidx.test.junit)
}
