package com.hanaagent.core.persona

import java.security.MessageDigest

/**
 * 人格资产的只读访问层。
 *
 * 这些 markdown 文件是从上游 HanaAgent（liliMozi/openhanako）**逐字**搬过来的，
 * 不做任何本地化改写。默认人格的措辞就是产品本身的一部分：源模板里定义的
 * MOOD/PULSE/REFLECT 四池、AGENTS.md 里的语气守则，都会直接进 system prompt。
 * 改一个字都是在改这个 Agent 是谁。
 *
 * [PersonaAssetsLockTest] 会拿 `persona-lock.sha256` 逐个校验，任何改动都会让
 * 构建失败 —— 这是刻意的护栏，不是可以顺手绕过的检查。真要跟随上游更新时，
 * 先 diff 上游、再同时更新资产与锁文件，让改动出现在 review 里。
 */
object PersonaAssets {

    /** 三层人格里的哪一层。 */
    enum class Layer(internal val dir: String) {
        /** 「源」——思维框架，决定内省标签用 mood / pulse / reflect 哪一套。 */
        YUAN("yuan"),

        /** 一句话身份定义。 */
        IDENTITY("identity-templates"),

        /** 行为守则（对应 agent 目录里的 AGENTS.md）。 */
        AGENTS("agents-templates"),

        /** 对外公开版本的行为守则，用于分享出去的角色卡。 */
        AGENTS_PUBLIC("agents-public-templates"),
    }

    /** 上游内置的四个源。kong 目前是占位（内容为空）。 */
    val BUILT_IN_YUAN: List<String> = listOf("hanako", "butter", "ming", "kong")

    /** 上游默认源。新建 Agent 未指定时用它。 */
    const val DEFAULT_YUAN: String = "hanako"

    private const val ROOT = "/assets/persona"

    /**
     * 读一份人格资产。
     *
     * @param locale BCP-47 风格的语言标签；只有 `en` 开头会走英文目录，其余一律
     *   回落到中文原件 —— 与上游 `resolvePersonaLocale` 的行为一致（上游只维护
     *   中/英两套模板，日韩繁体界面用的仍是中文人格）。
     * @return 文件内容；该层不存在这个源时返回 null（例如 kong 只有 yuan 层）。
     */
    fun read(layer: Layer, yuan: String, locale: String = "zh"): String? {
        val english = locale.startsWith("en", ignoreCase = true)
        if (english) {
            readResource("$ROOT/${layer.dir}/en/$yuan.md")?.let { return it }
        }
        return readResource("$ROOT/${layer.dir}/$yuan.md")
    }

    /** 列出打包进来的全部资产路径（相对 assets/persona/），排序稳定。 */
    fun listAll(): List<String> = LOCKED_PATHS

    /** 某份资产的 sha256（小写十六进制）；不存在则返回 null。 */
    fun sha256Of(relativePath: String): String? {
        val bytes = readResourceBytes("$ROOT/$relativePath") ?: return null
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private fun readResource(path: String): String? =
        readResourceBytes(path)?.toString(Charsets.UTF_8)

    private fun readResourceBytes(path: String): ByteArray? =
        PersonaAssets::class.java.getResourceAsStream(path)?.use { it.readBytes() }

    /**
     * 打包资产的路径清单。写成常量而不是运行时扫描 classpath：Android 上
     * 资源是打进 APK 的，没有可遍历的目录，运行时扫描在设备上必然失败。
     */
    private val LOCKED_PATHS: List<String> = buildList {
        for (layer in listOf("agents-public-templates", "agents-templates", "identity-templates")) {
            for (yuan in listOf("butter", "hanako", "ming")) {
                add("$layer/$yuan.md")
                add("$layer/en/$yuan.md")
            }
        }
        for (yuan in listOf("butter", "hanako", "kong", "ming")) {
            add("yuan/$yuan.md")
            add("yuan/en/$yuan.md")
        }
    }.sorted()
}
