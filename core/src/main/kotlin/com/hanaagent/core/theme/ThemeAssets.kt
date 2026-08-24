package com.hanaagent.core.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * 11 套主题的设计 token —— 从上游逐字导出的资产。
 *
 * 这个移植版存在的理由是「喜欢它的设计」，配色是那个设计的一部分。所以主题和人格
 * 模板、搜索提取脚本一样按**资产**处理：由 `tools/themes/generate.mjs` 从上游的
 * `desktop/src/themes/` 下的 css 导出，sha256 锁住，不手抄。
 *
 * 手抄的问题不是麻烦，是**漂移了也没人看得出来** —— 没有哪个测试会失败，只是用久了
 * 觉得"好像哪里不太一样"。
 *
 * ## 层叠模型
 *
 * 照搬 CSS 的：`styles.css` 的 `:root` 给出 81 个基线 token，每套主题文件只覆盖其中
 * 一部分（35–43 个）。所以 [tokensOf] 返回的是 **baseline ⊕ 该主题的覆盖**。
 * 直接用主题文件里那 35 个会缺一大半，界面上表现为一堆元素没有颜色。
 */
object ThemeAssets {

    private const val ASSET_PATH = "/assets/theme/themes.json"

    private val json = Json { ignoreUnknownKeys = true }

    private val root: JsonObject by lazy {
        val bytes = ThemeAssets::class.java.getResourceAsStream(ASSET_PATH)?.use { it.readBytes() }
            ?: throw IllegalStateException(
                "主题资产缺失：$ASSET_PATH。重跑 tools/themes/generate.mjs <上游仓库路径>",
            )
        json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }

    /** 一套主题。[tokens] 已经是合并过基线的完整集合。 */
    data class Theme(
        val id: String,
        val dark: Boolean,
        val backgroundColor: String,
        val tokens: Map<String, String>,
    )

    /** 默认主题 id（上游的 warm-paper / 暖纸）。 */
    val defaultTheme: String get() = root["defaultTheme"]!!.jsonPrimitive.content

    /** 跟随系统时，浅色/深色各自用哪一套。 */
    val autoLightDefault: String get() = root["autoLightDefault"]!!.jsonPrimitive.content
    val autoDarkDefault: String get() = root["autoDarkDefault"]!!.jsonPrimitive.content

    /** 全部主题 id，顺序与上游注册表一致。 */
    val themeIds: List<String> by lazy { root["themes"]!!.jsonObject.keys.toList() }

    /** `styles.css` 的 `:root` 基线。 */
    val baseline: Map<String, String> by lazy { root["baseline"]!!.jsonObject.toStringMap() }

    /** 读一套主题；id 不存在返回 null。 */
    fun theme(id: String): Theme? {
        val entry = root["themes"]!!.jsonObject[id]?.jsonObject ?: return null
        return Theme(
            id = id,
            dark = entry["dark"]!!.jsonPrimitive.content.toBoolean(),
            backgroundColor = entry["backgroundColor"]!!.jsonPrimitive.content,
            tokens = baseline + entry["tokens"]!!.jsonObject.toStringMap(),
        )
    }

    /** 某套主题的完整 token 集合（基线 ⊕ 覆盖）。 */
    fun tokensOf(id: String): Map<String, String> =
        theme(id)?.tokens ?: throw IllegalArgumentException("没有这套主题：$id")

    /** 资产本身的 sha256，用于锁文件。 */
    fun sha256(): String {
        val bytes = ThemeAssets::class.java.getResourceAsStream(ASSET_PATH)!!.use { it.readBytes() }
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private fun JsonObject.toStringMap(): Map<String, String> =
        entries.associate { (key, value) -> key to value.jsonPrimitive.content }
}

/**
 * 把 CSS 的颜色值解析成 ARGB。
 *
 * 单独放在这里是因为 token 的值**不全是颜色** —— 81 个基线 token 里混着 `0.5rem`
 * 这样的间距、`url(data:image/svg+xml…)` 的纹理、以及 `193, 116, 92` 这种给
 * `rgba(var(--accent-rgb), .5)` 用的裸三元组。调用方按 token 的用途自己选择解析方式，
 * 解析不了的返回 null 而不是抛异常或悄悄给个黑色。
 */
object CssColor {

    /** var() 展开的最大层数 —— 挡住 token 互相引用形成的环。 */
    private const val MAX_VAR_DEPTH = 8

    /**
     * 解析成 ARGB（0xAARRGGBB）。
     *
     * 支持 `#rgb` / `#rrggbb` / `#rrggbbaa`、`rgb()` / `rgba()`，以及 `var(--x)` 指向
     * 另一个 token 时的展开。裸三元组（`193, 116, 92`）**不**当成颜色 —— 它在 CSS 里
     * 从来不单独用，只作为 `rgba()` 的参数，单独解析出来大概率是误用。
     *
     * @param tokens 同一套主题的完整 token 表，用于展开 `var()`
     * @return ARGB，解析不了返回 null
     */
    fun parse(value: String, tokens: Map<String, String> = emptyMap()): Int? =
        parse(value, tokens, MAX_VAR_DEPTH)

    private fun parse(value: String, tokens: Map<String, String>, depth: Int): Int? {
        if (depth <= 0) return null
        val text = value.trim()

        VAR_RE_INLINE.matchEntire(text)?.let { match ->
            val referenced = tokens[match.groupValues[1]] ?: return null
            return parse(referenced, tokens, depth - 1)
        }

        if (text.startsWith("#")) return parseHex(text)
        if (text.startsWith("rgb")) return parseRgbFunction(text, tokens, depth)
        return null
    }

    private fun parseHex(text: String): Int? {
        val hex = text.removePrefix("#")
        return when (hex.length) {
            // #rgb → 每位翻倍
            3 -> {
                val r = hex[0].digit() ?: return null
                val g = hex[1].digit() ?: return null
                val b = hex[2].digit() ?: return null
                argb(255, r * 17, g * 17, b * 17)
            }

            6 -> {
                val value = hex.toIntOrNull(16) ?: return null
                argb(255, (value shr 16) and 0xFF, (value shr 8) and 0xFF, value and 0xFF)
            }

            // CSS 是 #rrggbbaa（alpha 在最后），与 ARGB 的顺序不同 —— 这里换过来
            8 -> {
                val value = hex.toLongOrNull(16) ?: return null
                argb(
                    (value and 0xFF).toInt(),
                    ((value shr 24) and 0xFF).toInt(),
                    ((value shr 16) and 0xFF).toInt(),
                    ((value shr 8) and 0xFF).toInt(),
                )
            }

            else -> null
        }
    }

    private fun parseRgbFunction(text: String, tokens: Map<String, String>, depth: Int): Int? {
        val open = text.indexOf('(').takeIf { it > 0 } ?: return null
        val close = text.lastIndexOf(')').takeIf { it > open } ?: return null
        val inner = text.substring(open + 1, close)

        // rgba(var(--accent-rgb), 0.5) —— 先把 var() 展开成三元组。
        // 这里不能用 Regex.replace 的 lambda：展开失败时要整体放弃，而 lambda 里不允许
        // 非局部 return。写成循环，顺带用 guard 挡住 token 互相引用形成的环。
        var expanded = inner
        var guard = MAX_VAR_DEPTH
        while (true) {
            val match = VAR_RE_INLINE.find(expanded) ?: break
            if (guard-- <= 0) return null
            val replacement = tokens[match.groupValues[1]] ?: return null
            expanded = expanded.replaceRange(match.range, replacement)
        }

        val parts = expanded.split(',', '/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 3) return null

        val r = parts[0].toChannel() ?: return null
        val g = parts[1].toChannel() ?: return null
        val b = parts[2].toChannel() ?: return null
        val a = if (parts.size >= 4) parts[3].toAlpha() ?: return null else 255
        return argb(a, r, g, b)
    }

    /** `0-255` 或 `50%`。 */
    private fun String.toChannel(): Int? {
        val percent = removeSuffix("%")
        return if (percent.length != length) {
            percent.toDoubleOrNull()?.let { (it / 100.0 * 255).toInt().coerceIn(0, 255) }
        } else {
            toDoubleOrNull()?.let { it.toInt().coerceIn(0, 255) }
        }
    }

    /** `0-1` 或 `50%`。 */
    private fun String.toAlpha(): Int? {
        val percent = removeSuffix("%")
        return if (percent.length != length) {
            percent.toDoubleOrNull()?.let { (it / 100.0 * 255).toInt().coerceIn(0, 255) }
        } else {
            toDoubleOrNull()?.let { (it * 255).toInt().coerceIn(0, 255) }
        }
    }

    private fun Char.digit(): Int? = digitToIntOrNull(16)

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    /** `var(--x)`，允许带回退值 `var(--x, #fff)`（回退值本身不解析 —— 上游没用过）。 */
    private val VAR_RE_INLINE = Regex("""var\(\s*(--[a-z0-9-]+)\s*(?:,[^)]*)?\)""", RegexOption.IGNORE_CASE)
}

/**
 * 把 CSS 的长度值解析成像素。
 *
 * 设计系统里的间距、圆角、字号都是 `rem` 或 `px`。CSS 的 `rem` 相对根字号，
 * 浏览器默认 16px，上游没有改过根字号，所以这里用 16 作为换算基准。
 *
 * Compose 侧拿到 px 之后再转 dp / sp。**不要**在这一层就转成 dp：那是平台概念，
 * 而这个模块是纯 JVM 的。
 */
object CssLength {

    /** 1rem 等于多少 px。上游没有改过根字号，用浏览器默认值。 */
    const val ROOT_FONT_SIZE_PX = 16.0f

    /**
     * @return 像素值；不是长度（比如 `calc(...)`、`max(...)`、`none`）时返回 null
     */
    fun parse(value: String): Float? {
        val text = value.trim()
        // calc() / max() / min() 不展开：上游只在少数几个派生 token 上用，
        // 需要它们的时候由调用方自己算，比在这里做半个 CSS 引擎可靠
        if (text.isEmpty() || "(" in text) return null

        return when {
            text.endsWith("rem") -> text.removeSuffix("rem").trim().toFloatOrNull()
                ?.times(ROOT_FONT_SIZE_PX)

            text.endsWith("px") -> text.removeSuffix("px").trim().toFloatOrNull()

            // 无单位的 0
            else -> text.toFloatOrNull()?.takeIf { it == 0f }
        }
    }
}
