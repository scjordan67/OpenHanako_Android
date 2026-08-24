plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// 纯 JVM 模块。这里禁止出现任何 Android 依赖 —— 见 settings.gradle.kts 的说明。
// Java 17 字节码：Android 的 D8/R8 对 17 的支持最成熟，而本机 JDK 是 21，
// 因此用 release=17 交叉编译，不启用 toolchain（避免联网下载 JDK）。
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // 分词：纯 Java，JVM 与 Android 同一份实现
    implementation(libs.jieba)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    // FTS5 契约验证：xerial 自带的 SQLite 编译时开了 FTS5
    testImplementation(libs.sqlite.jdbc)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}

// 早失败：JVM 写不出带中文的文件名时，给一句能照做的话。
//
// 本项目的测试方法名是中文，方法里有 lambda 时 Kotlin 会生成
// `XxxTest$中文方法名$1.class`。JVM 能否写出这种文件名由 sun.jnu.encoding 决定，
// 而它由操作系统 locale 定死，-D 改不动。locale 是 POSIX/C 时它会退化成
// ANSI_X3.4-1968，编译中途报的是 “Failed to create MD5 hash for file ... as it
// does not exist” —— 文件明明写出来了，只是 Gradle 用 ASCII 解不出那个名字。
// 这个错误跟真实原因八竿子打不着，所以在这里拦一道。
val fileNameEncoding: String = System.getProperty("sun.jnu.encoding") ?: ""
if (!fileNameEncoding.contains("UTF", ignoreCase = true)) {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        doFirst {
            throw GradleException(
                """
                当前 JVM 的文件名编码是 $fileNameEncoding，写不出带中文的 class 文件名，
                而本项目的测试方法名是中文。

                带上 UTF-8 locale 重新跑即可：

                    LANG=C.UTF-8 ./gradlew :core:test

                （sun.jnu.encoding 由操作系统 locale 决定，不能用 -D 设置 ——
                  往 gradle.properties 里加是没用的。）
                """.trimIndent(),
            )
        }
    }
}
