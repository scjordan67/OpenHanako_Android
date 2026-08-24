plugins {
    alias(libs.plugins.android.application)
    // Kotlin Android 插件**不写版本号**。它和 :core 用的 kotlin.jvm 同在
    // org.jetbrains.kotlin:kotlin-gradle-plugin 这一个 jar 里，而根项目声明
    // kotlin.jvm 时已经把这个 jar 挂上了 buildscript classpath —— Gradle 只为
    // 「org.jetbrains.kotlin.jvm」这个 id 记了版本，对同 jar 里的
    // 「org.jetbrains.kotlin.android」是"在 classpath 上但版本未知"。此时再带
    // 版本号请求，AlreadyOnClasspathPluginResolver 会因为无法校验兼容性而直接抛错。
    // 不写版本即可，反正是同一个 jar，版本必然与 kotlin.jvm 一致。
    id("org.jetbrains.kotlin.android")
    // Compose 编译器插件是独立 artifact（compose-compiler-gradle-plugin），
    // 不在上面那个 jar 里，所以照常带版本号解析。
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
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    androidTestImplementation(libs.androidx.test.junit)
}
