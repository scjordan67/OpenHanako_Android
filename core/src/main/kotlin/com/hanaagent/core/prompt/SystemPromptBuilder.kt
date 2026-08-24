package com.hanaagent.core.prompt

import com.hanaagent.core.memory.LogicalDay
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * System prompt 装配 —— 上游 `Agent.buildSystemPrompt()` 的移植。
 *
 * ## 顺序不是随手排的
 *
 * 拼接顺序是按「KV cache / prompt cache 都按**严格前缀**匹配」这一事实倒推出来的：
 * 静态段全部前置，会自动漂移的段全部后置，中间是一条 [CACHE_BOUNDARY_MARKER]
 * 标出来的分界线。
 *
 * - **静态前缀**：平台、环境、用户档案、人格、样貌、行为指南。这些只在用户改档案
 *   或换人格时变，属于事件驱动，跨 session 能稳定命中缓存。
 * - **动态尾部**：记忆和时间戳。记忆被后台编译推动，时间每次构建都在走，
 *   它们才是真正的漂移源。放在末尾，前面的前缀就不会被它们带着一起失效。
 *
 * 用户档案和人格段刻意放在**静态前缀**里：它们只在用户主动修改时才变，
 * 放到尾部只会白白撑大动态区。而 AGENTS.md 排在用户档案**之后**，是因为人格
 * 模板里有「你和{{userName}}是认识很久的人」这类引用 —— 叙事上要先告诉模型
 * "用户是谁"，再告诉它"你是谁、你和用户什么关系"。
 *
 * [SystemPromptSectionOrderTest] 锁住这条分界线，防止有人把稳定段挪回尾部、
 * 或把动态段提到前缀里。
 *
 * ## 与桌面版的差异
 *
 * 这个移植版砍掉了"操作电脑"的能力，所以上游这几段不再注入，因为它们描述的工具
 * 在这里不存在，留着只会让模型去调用不存在的东西：
 *
 * - Session 文件与交付（`stage_files` / `materialize` / fileId 契约）
 * - 可见 UI 上下文（`current_status(ui_context)`）
 * - subagent 协作、团队花名册
 * - 本机应用控制（Computer Use）
 * - 主动技能获取（`install_skill` 要写文件）
 *
 * 「网页工具优先级」保留但改成两级：上游是 web_search → web_fetch → browser 三级，
 * 这里没有可见浏览器工具。
 */
class SystemPromptBuilder(private val input: Input) {

    /**
     * 装配所需的全部输入。
     *
     * 刻意做成一个数据类而不是从某个 Agent 对象上到处取：prompt 装配是纯函数，
     * 输入明确才能测得住顺序契约。
     */
    data class Input(
        val locale: String,
        /** 用户名。空值由 [resolvedUserName] 兜底成中性称呼。 */
        val userName: String? = null,
        /** `user.md` 的内容，用户的自我描述。 */
        val userProfile: String? = null,
        /** 三层人格拼好的结果（identity + yuan + AGENTS.md）。 */
        val persona: String,
        /** 视觉模型生成的样貌自述；null 表示没有或不注入。 */
        val appearance: String? = null,
        /** 记忆总开关。关闭时整块记忆相关的 prompt 都不注入。 */
        val memoryEnabled: Boolean = true,
        /** `pinned.md`：用户明确要求记住的内容。 */
        val pinnedMemory: String? = null,
        /** `memory.md`：记忆传送带的产物。 */
        val memory: String? = null,
        /** 会话开始时刻。 */
        val now: ZonedDateTime,
        /** Android 版本与设备信息，用于「执行环境」段。 */
        val deviceDescription: String = "Android",
    ) {
        val isZh: Boolean get() = locale.startsWith("zh", ignoreCase = true)

        /**
         * 名字解析：显式值 → 语言兜底。
         *
         * 末端有兜底所以这一行总会出现；没配过名字时给的是"用户"/"User"
         * 这种中性称呼，与 prompt 其它位置对用户的称呼保持一致。
         */
        val resolvedUserName: String
            get() = userName?.trim()?.takeIf { it.isNotEmpty() } ?: if (isZh) "用户" else "User"
    }

    fun build(): String {
        val parts = mutableListOf<String>()
        val zh = input.isZh

        // ── 静态前缀 ────────────────────────────────────────────

        parts += if (zh) {
            "你运行在 HanaAgent 平台上（原名 OpenHanako），由 liliMozi 开发。项目主页：https://github.com/liliMozi/openhanako"
        } else {
            "You are running on the HanaAgent platform (formerly OpenHanako), developed by liliMozi. Project page: https://github.com/liliMozi/openhanako"
        }

        parts += section(
            if (zh) "# 执行环境" else "# Environment",
            environmentNote(),
        )

        parts += section(
            if (zh) "# 用户档案" else "# User Profile",
            buildList {
                add(if (zh) "以下是用户的自我描述。" else "The following is the user's self-description.")
                add(if (zh) "用户的名字叫：${input.resolvedUserName}" else "The user's name is: ${input.resolvedUserName}")
                input.userProfile?.trim()?.takeIf { it.isNotEmpty() }?.let { add(""); add(it) }
            }.joinToString("\n"),
        )

        // 人格排在用户档案之后：模板里有「你和{{userName}}是认识很久的人」这类引用
        parts += input.persona

        input.appearance?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }

        parts += if (zh) {
            "\n你的所有文本输出都会直接展示给用户。每次回复都必须包含面向用户的正文内容，不允许只产生内部思考就结束回复。"
        } else {
            "\nAll your text output is displayed directly to the user. Every response must contain user-facing content; do not end a response with only internal thinking."
        }

        parts += actionDiscipline(zh)
        parts += webToolPriority(zh)

        // ── cache 分界线 ────────────────────────────────────────
        // 以下内容会自动漂移（后台编译更新记忆、时间戳每次构建都在走），
        // 统一放在末尾以保护前面静态前缀的 cache 命中率。
        parts += CACHE_BOUNDARY_MARKER

        memoryBlock(zh)?.let { parts += it }
        parts += sessionClock(zh)

        return parts.joinToString("\n")
    }

    /**
     * 记忆规则 + 置顶记忆 + 记忆正文。
     *
     * 记忆规则只注入一次；置顶和记忆两段只放内容。三者都没有内容时整块跳过 ——
     * 空的记忆段对模型是纯噪声。
     */
    private fun memoryBlock(zh: Boolean): String? {
        if (!input.memoryEnabled) return null

        val pinned = input.pinnedMemory?.trim().orEmpty()
        val memory = input.memory?.trim().orEmpty()
        val hasMemory = memory.isNotEmpty() && memory != "（暂无记忆）" && memory != "(No memory yet)"
        if (pinned.isEmpty() && !hasMemory) return null

        val name = input.resolvedUserName
        val rules = if (zh) {
            listOf(
                "",
                "## 记忆使用规则",
                "",
                "记忆和用户档案是你内化的背景知识。你和${name}是认识很久的人，这些事你本来就知道。",
                "",
                "- **只有当${name}提到相关内容，记忆才参与**，而且方式是无声的：影响你的角度、语气、判断，不出现在文字里。${name}没提起的话题，不要主动从记忆里翻出来讲。",
                "- **永远不要让${name}感觉到\"记忆\"这个东西的存在。** 禁止\"我记得\"\"你之前说过\"\"根据记忆\"这类表述，除非${name}主动问\"你还记得 xxx 吗\"。",
                "- **记忆可能过时，当前对话永远优先。** 信息冲突时以对话为准，不要用旧记忆纠正${name}。",
            )
        } else {
            listOf(
                "",
                "## Memory Rules",
                "",
                "Memories and the user profile are internalized background knowledge. You and $name have known each other for a long time — you already know these things.",
                "",
                "- **Memory participates only when $name brings up something related**, and silently: shaping your angle, tone, and judgment without appearing in the text. Don't pull up topics $name hasn't raised.",
                "- **Never let $name sense that \"memory\" exists as a thing.** Never say \"I remember,\" \"you mentioned before,\" or \"based on my memory\" — unless $name explicitly asks \"do you remember xxx.\"",
                "- **Memory can be outdated; the current conversation always takes priority.** On conflict, follow the conversation; don't correct $name with old memories.",
            )
        }.joinToString("\n")

        val block = StringBuilder(rules)
        if (pinned.isNotEmpty()) {
            block.append(
                section(
                    if (zh) "# 置顶记忆" else "# Pinned Memories",
                    if (zh) "用户主动要求你记住的内容，始终保留。你可以读写这些记忆。\n\n$pinned"
                    else "Content the user explicitly asked you to remember. Always retained. You can read and write these memories.\n\n$pinned",
                ),
            )
        }
        if (hasMemory) {
            block.append(
                section(
                    if (zh) "# 记忆" else "# Memory",
                    if (zh) "以下这些是从过往对话积累的记忆。\n\n$memory"
                    else "The following are memories accumulated from past conversations.\n\n$memory",
                ),
            )
        }
        return block.toString()
    }

    /**
     * 会话时间戳 + 日界线说明。
     *
     * 明确告诉模型这是**快照**而不是实时钟：否则长会话里它会拿开头的时间当"现在"，
     * 聊到深夜还以为是下午。日界线 04:00 与记忆传送带用的是同一个定义。
     */
    private fun sessionClock(zh: Boolean): String {
        val formatted = DateTimeFormatter
            .ofPattern("EEEE, MMMM d, yyyy 'at' HH:mm zzz", Locale.US)
            .format(input.now)
        return buildString {
            append("\nSession started at: ").append(formatted).append("\n")
            append(
                if (zh) "这是会话开始时刻的快照，不会随对话推进更新。需要知道现在的时间时，用工具查询。"
                else "This is a snapshot from when the session started and does not advance. When you need the current time, use a tool to query it.",
            )
            append("\n")
            append(
                if (zh) "你的一天从 0${LogicalDay.DAY_BOUNDARY_HOUR}:00 开始。0${LogicalDay.DAY_BOUNDARY_HOUR}:00 之前的对话属于前一天。"
                else "Your day starts at 0${LogicalDay.DAY_BOUNDARY_HOUR}:00. Conversations before 0${LogicalDay.DAY_BOUNDARY_HOUR}:00 belong to the previous day.",
            )
        }
    }

    private fun environmentNote(): String = buildString {
        appendLine("<environment_context>")
        appendLine("  <platform>android</platform>")
        appendLine("  <device>${input.deviceDescription}</device>")
        // 明确告诉模型这里没有文件系统和命令行能力，省得它去试
        appendLine("  <capabilities>chat, memory, web_search, web_fetch, image_input</capabilities>")
        appendLine("  <no_filesystem>true</no_filesystem>")
        appendLine("  <no_shell>true</no_shell>")
        append("</environment_context>")
    }

    private fun actionDiscipline(zh: Boolean): String = if (zh) {
        "\n## 行动纪律\n\n" +
            "方案失败时，先诊断原因再换方向：读错误信息、检查假设、做针对性修复；不要盲目重试同一动作，也不要因一次失败放弃可行方案。\n" +
            "你没有文件系统和命令行能力。需要用户提供内容时直接说，不要假装读过某个文件。"
    } else {
        "\n## Action Discipline\n\n" +
            "When an approach fails, diagnose before switching tactics: read the error, check your assumptions, try a focused fix; don't blindly retry the identical action, and don't abandon a viable approach after a single failure.\n" +
            "You have no filesystem or shell access. When you need content from the user, ask for it directly; never pretend to have read a file."
    }

    private fun webToolPriority(zh: Boolean): String = if (zh) {
        "\n## 网页工具优先级\n\n" +
            "获取网页信息按此顺序选择工具：1. **web_search** 查找信息、获取 URL；2. **web_fetch** 已知 URL、提取页面文字。\n" +
            "取不到内容时如实说明，不要凭印象编造页面内容。"
    } else {
        "\n## Web Tool Priority\n\n" +
            "Choose web tools in this order: 1. **web_search** to find information and URLs; 2. **web_fetch** to extract text from a known URL.\n" +
            "If content cannot be retrieved, say so plainly; never fabricate page contents from memory."
    }

    private fun section(title: String, content: String): String =
        listOf("", "---", "", title, "", content).joinToString("\n")

    companion object {
        /**
         * cache 分界线的标记。
         *
         * 它以注释形式留在 prompt 里而不是只存在于代码结构中，是为了让开发者视图
         * （以及这个契约测试）能直接定位分界线在哪。对模型是无害的一行。
         */
        const val CACHE_BOUNDARY_MARKER: String = "\n<!-- cache-boundary -->"
    }
}
