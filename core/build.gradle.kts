import java.nio.file.Files

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
// `XxxTest${'$'}中文方法名${'$'}1.class`。写不出这种文件名时，编译中途报的是
// "Failed to create MD5 hash for file ... as it does not exist" —— 文件明明写出来
// 了，只是 Gradle 用 ASCII 解不出那个名字。这个错误跟真实原因八竿子打不着。
//
// 判据是**实际探一次**，而不是看 sun.jnu.encoding 的名字 —— 两者并不等价：
// Windows 的 sun.jnu.encoding 通常是 Cp1252，但 JVM 走宽字符 API，中文文件名照写
// 不误（CI 的 windows 任务一直是绿的）。按名字判断会把 Windows 误伤成失败，
// 这个坑我踩过一次。
val canWriteNonAsciiFileNames: Boolean = runCatching {
    val dir = Files.createTempDirectory("hana-filename-probe")
    try {
        val name = "内省块${'$'}1.class"
        Files.writeString(dir.resolve(name), "probe")
        // 必须回读目录：编码不支持时写入可能"成功"，但名字已被替换成 ? 一类的字符
        Files.list(dir).use { entries -> entries.anyMatch { it.fileName.toString() == name } }
    } finally {
        dir.toFile().deleteRecursively()
    }
}.getOrDefault(false)

if (!canWriteNonAsciiFileNames) {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        doFirst {
            throw GradleException(
                """
                当前 JVM 写不出带中文的文件名（sun.jnu.encoding = ${System.getProperty("sun.jnu.encoding")}），
                而本项目的测试方法名是中文，编译产物里会出现这样的文件名。

                带上 UTF-8 locale 重新跑即可：

                    LANG=C.UTF-8 ./gradlew :core:test

                （sun.jnu.encoding 由操作系统 locale 决定，不能用 -D 设置 ——
                  往 gradle.properties 里加是没用的。）
                """.trimIndent(),
            )
        }
    }
}
