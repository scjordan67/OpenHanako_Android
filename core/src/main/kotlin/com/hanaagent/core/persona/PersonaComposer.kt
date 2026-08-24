package com.hanaagent.core.persona

/**
 * 三层人格的合成 —— 上游 `Agent.personality` getter 的移植。
 *
 * 人格不是一个 prompt 字符串，而是三份模板叠出来的：
 *
 * | 层 | 回答的问题 | 来源 |
 * |----|-----------|------|
 * | identity | 我是谁 | `identity-templates/<yuan>.md` |
 * | yuan（源） | 我怎么想 | `yuan/<yuan>.md` |
 * | AGENTS.md | 我怎么说话、怎么做事 | `agents-templates/<yuan>.md` |
 *
 * 三层之间用空行分隔，顺序固定。合成结果直接作为
 * [com.hanaagent.core.prompt.SystemPromptBuilder.Input.persona] 送进 system prompt。
 *
 * ## 为什么这一步单独存在
 *
 * 「源」这一层里写着内省协议（hanako 的 MOOD 四池、butter 的 PULSE、ming 的
 * REFLECT），它规定模型必须在开口前先写一段内心独白，并用什么标签包住。
 * [com.hanaagent.core.mood.MoodParser] 能不能切出内省块，取决于这一层有没有
 * 被完整送进 prompt —— 少了它，模型不会产出标签，内省块的整套机制就静默失效，
 * 而表面上"还能聊天"。所以三层缺一不可，[compose] 对缺失层是显式报错而不是跳过。
 */
object PersonaComposer {

    /**
     * 合成三层人格。
     *
     * @param yuan 源的 id，见 [PersonaAssets.BUILT_IN_YUAN]
     * @param userName 用户称呼，替换模板里的 `{{userName}}`
     * @param agentName Agent 名字，替换 `{{agentName}}`
     * @param agentId Agent 目录名，替换 `{{agentId}}`
     * @param locale 只有 `en` 开头走英文模板，其余回落中文原件
     * @param publicVariant 用 `agents-public-templates` 而不是 `agents-templates`：
     *   导出角色卡分享给别人时用的公开版行为守则
     *
     * @throws IllegalStateException 任一层缺失。宁可启动即报错，也不要装配出一个
     *   缺了内省协议的人格 —— 后者的表现是"能聊天但没有内心独白"，很难归因。
     */
    fun compose(
        yuan: String = PersonaAssets.DEFAULT_YUAN,
        userName: String,
        agentName: String,
        agentId: String,
        locale: String = "zh",
        publicVariant: Boolean = false,
    ): String {
        val agentsLayer =
            if (publicVariant) PersonaAssets.Layer.AGENTS_PUBLIC else PersonaAssets.Layer.AGENTS

        val identity = requireLayer(PersonaAssets.Layer.IDENTITY, yuan, locale)
        val yuanText = requireLayer(PersonaAssets.Layer.YUAN, yuan, locale)
        val agents = requireLayer(agentsLayer, yuan, locale)

        val fill = fillTemplate(userName, agentName, agentId)
        // 顺序与分隔符与上游一致：identity → yuan → AGENTS.md，两两之间一个空行
        return fill(identity) + "\n\n" + fill(yuanText) + "\n\n" + fill(agents)
    }

    /**
     * 占位符替换。
     *
     * 三个占位符出现在人格模板正文里，例如「你和{{userName}}是认识很久的人」。
     * 没替换干净的话，模型会照字面把 `{{userName}}` 当成用户的名字念出来。
     */
    private fun fillTemplate(userName: String, agentName: String, agentId: String): (String) -> String =
        { text ->
            text.replace("{{userName}}", userName)
                .replace("{{agentName}}", agentName)
                .replace("{{agentId}}", agentId)
        }

    private fun requireLayer(layer: PersonaAssets.Layer, yuan: String, locale: String): String =
        PersonaAssets.read(layer, yuan, locale)
            ?: throw IllegalStateException(
                "人格资产缺失：${layer.dir}/$yuan.md。三层缺一不可 —— " +
                    "少了「源」这一层，内省协议不会进 prompt，MOOD 机制会静默失效。",
            )

    /** 合成结果里是否还残留未替换的占位符。用于自检。 */
    fun hasUnfilledPlaceholders(composed: String): Boolean =
        PLACEHOLDER_RE.containsMatchIn(composed)

    private val PLACEHOLDER_RE = Regex("\\{\\{\\s*\\w+\\s*}}")
}
