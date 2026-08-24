package com.hanaagent.core.llm

import com.hanaagent.core.mood.MoodEvent
import com.hanaagent.core.mood.MoodParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 模型请求构造与流式解析。
 *
 * 这两块是「能对话」的最后一环，也是两家 API 差异的全部收口处 ——
 * 上层不应该知道当前接的是 Anthropic 还是 OpenAI 兼容端点。
 */
class LlmClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val messages = listOf(
        ChatPayload.ChatMessage.text("user", "你好"),
        ChatPayload.ChatMessage.text("assistant", "你好呀"),
    )

    // ── 请求构造 ─────────────────────────────────────────────

    @Test
    fun `Anthropic 把 system 放在顶层字段`() {
        val payload = ChatPayload.build(
            api = ChatPayload.Api.ANTHROPIC_MESSAGES,
            model = "claude-x",
            systemPrompt = "SYSTEM-MARKER",
            messages = messages,
        )
        assertEquals("SYSTEM-MARKER", payload["system"]?.jsonPrimitive?.content)
        // messages 里不应混入 system 角色
        val roles = payload["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        assertEquals(listOf("user", "assistant"), roles)
    }

    @Test
    fun `OpenAI 把 system 作为第一条消息`() {
        val payload = ChatPayload.build(
            api = ChatPayload.Api.OPENAI_COMPLETIONS,
            model = "gpt-x",
            systemPrompt = "SYSTEM-MARKER",
            messages = messages,
        )
        assertTrue(payload["system"] == null, "OpenAI 形态不应有顶层 system 字段")
        val list = payload["messages"]!!.jsonArray
        assertEquals("system", list[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("SYSTEM-MARKER", list[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(listOf("system", "user", "assistant"), list.map { it.jsonObject["role"]!!.jsonPrimitive.content })
    }

    @Test
    fun `system 为空时两家都不注入空段`() {
        val anthropic = ChatPayload.build(ChatPayload.Api.ANTHROPIC_MESSAGES, "m", "", messages)
        assertTrue(anthropic["system"] == null)

        val openai = ChatPayload.build(ChatPayload.Api.OPENAI_COMPLETIONS, "m", "", messages)
        assertEquals(
            listOf("user", "assistant"),
            openai["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `Anthropic 必须带 max_tokens —— 它没有服务端默认值`() {
        val payload = ChatPayload.build(ChatPayload.Api.ANTHROPIC_MESSAGES, "m", "s", messages)
        assertEquals(
            ChatPayload.DEFAULT_ANTHROPIC_MAX_TOKENS,
            payload["max_tokens"]!!.jsonPrimitive.content.toInt(),
        )
        // OpenAI 不填就用服务端默认，不该硬塞一个
        val openai = ChatPayload.build(ChatPayload.Api.OPENAI_COMPLETIONS, "m", "s", messages)
        assertTrue(openai["max_tokens"] == null)
    }

    @Test
    fun `图片按各家形态序列化`() {
        val withImage = listOf(
            ChatPayload.ChatMessage(
                "user",
                listOf(
                    ChatPayload.ChatContent.Text("看看这张图"),
                    ChatPayload.ChatContent.Image("QUJD", "image/jpeg"),
                ),
            ),
        )

        val anthropic = ChatPayload.build(ChatPayload.Api.ANTHROPIC_MESSAGES, "m", "s", withImage)
        val aBlock = anthropic["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[1].jsonObject
        assertEquals("image", aBlock["type"]!!.jsonPrimitive.content)
        val source = aBlock["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", source["media_type"]!!.jsonPrimitive.content)
        assertEquals("QUJD", source["data"]!!.jsonPrimitive.content)

        val openai = ChatPayload.build(ChatPayload.Api.OPENAI_COMPLETIONS, "m", "s", withImage)
        // OpenAI 形态下 messages[0] 是 system（content 为字符串），用户消息在 [1]
        val oBlock = openai["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray[1].jsonObject
        assertEquals("image_url", oBlock["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/jpeg;base64,QUJD",
            oBlock["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `认证头与路径按各家形态给`() {
        val a = ChatPayload.headers(ChatPayload.Api.ANTHROPIC_MESSAGES, "KEY")
        assertEquals("KEY", a["x-api-key"])
        assertEquals(ChatPayload.ANTHROPIC_API_VERSION, a["anthropic-version"])
        assertTrue(a["Authorization"] == null, "Anthropic 不用 Bearer")

        val o = ChatPayload.headers(ChatPayload.Api.OPENAI_COMPLETIONS, "KEY")
        assertEquals("Bearer KEY", o["Authorization"])

        assertEquals("/v1/messages", ChatPayload.path(ChatPayload.Api.ANTHROPIC_MESSAGES))
        assertEquals("/chat/completions", ChatPayload.path(ChatPayload.Api.OPENAI_COMPLETIONS))
    }

    // ── 图片预算 ─────────────────────────────────────────────

    @Test
    fun `图片超限时给出可读原因而不是静默丢弃`() {
        val oversized = ChatPayload.ChatContent.Image("A".repeat(5 * 1024 * 1024))
        val reason = ChatPayload.ImagePolicy.checkBudget(listOf(oversized))
        assertTrue(reason != null, "单图超限应被拦下")
        assertTrue("第 1 张" in reason, "应指明是哪一张：$reason")

        // 总量超限
        val each = ChatPayload.ChatContent.Image("A".repeat(4 * 1024 * 1024))
        val many = List(7) { each }
        val totalReason = ChatPayload.ImagePolicy.checkBudget(many)
        assertTrue(totalReason != null && "总预算" in totalReason, "总量超限应被拦下：$totalReason")

        // 正常大小放行
        assertEquals(null, ChatPayload.ImagePolicy.checkBudget(listOf(ChatPayload.ChatContent.Image("QUJD"))))
    }

    @Test
    fun `图片策略常量与上游一致`() {
        assertEquals(2000, ChatPayload.ImagePolicy.MAX_WIDTH)
        assertEquals(2000, ChatPayload.ImagePolicy.MAX_HEIGHT)
        assertEquals(80, ChatPayload.ImagePolicy.JPEG_QUALITY)
        assertEquals(24L * 1024 * 1024, ChatPayload.ImagePolicy.TOTAL_BASE64_BUDGET_BYTES)
    }

    // ── 流式解析 ─────────────────────────────────────────────

    private fun anthropicStream() = listOf(
        """event: content_block_delta
data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"你好"}}

""",
        """data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"呀"}}

data: {"type":"message_stop"}

""",
    )

    private fun openAiStream() = listOf(
        """data: {"choices":[{"delta":{"content":"你好"}}]}

""",
        """data: {"choices":[{"delta":{"content":"呀"}}]}

data: [DONE]

""",
    )

    @Test
    fun `两家的流都归一成同一套事件`() {
        val a = LlmStream.parse(ChatPayload.Api.ANTHROPIC_MESSAGES, anthropicStream().asSequence())
        assertEquals("你好呀", LlmStream.collectText(a))
        assertTrue(a.last() is LlmStream.Event.Done)

        val o = LlmStream.parse(ChatPayload.Api.OPENAI_COMPLETIONS, openAiStream().asSequence())
        assertEquals("你好呀", LlmStream.collectText(o))
        assertTrue(o.last() is LlmStream.Event.Done)
    }

    @Test
    fun `思考内容走独立通道，不混进正文`() {
        val anthropic = LlmStream.parse(
            ChatPayload.Api.ANTHROPIC_MESSAGES,
            sequenceOf(
                """data: {"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"先想想"}}

data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"答案"}}

""",
            ),
        )
        assertEquals("答案", LlmStream.collectText(anthropic), "思考内容不能混进正文")
        assertTrue(anthropic.any { it is LlmStream.Event.ThinkingDelta })

        // DeepSeek / Kimi 等把思考放在 reasoning_content
        val openai = LlmStream.parse(
            ChatPayload.Api.OPENAI_COMPLETIONS,
            sequenceOf("""data: {"choices":[{"delta":{"reasoning_content":"先想想","content":"答案"}}]}

"""),
        )
        assertEquals("答案", LlmStream.collectText(openai))
        assertTrue(openai.any { it is LlmStream.Event.ThinkingDelta })
    }

    @Test
    fun `任意分片粒度下解析结果一致`() {
        // 网络切片位置完全不可控，解析器不能对它做任何假设
        for ((api, stream) in listOf(
            ChatPayload.Api.ANTHROPIC_MESSAGES to anthropicStream(),
            ChatPayload.Api.OPENAI_COMPLETIONS to openAiStream(),
        )) {
            val whole = LlmStream.parse(api, sequenceOf(stream.joinToString("")))
            val perChar = LlmStream.parse(api, stream.joinToString("").map { it.toString() }.asSequence())
            assertEquals(
                LlmStream.collectText(whole),
                LlmStream.collectText(perChar),
                "$api 在逐字符分片下结果不同",
            )
            assertEquals(whole.size, perChar.size, "$api 在逐字符分片下事件数不同")
        }
    }

    @Test
    fun `对端报错被识别为错误事件而不是空流`() {
        val events = LlmStream.parse(
            ChatPayload.Api.OPENAI_COMPLETIONS,
            sequenceOf("""data: {"error":{"message":"rate limit exceeded"}}

"""),
        )
        val error = events.filterIsInstance<LlmStream.Event.Error>().firstOrNull()
        assertTrue(error != null, "错误载荷必须变成 Error 事件，否则表现为「模型没回话」")
        assertEquals("rate limit exceeded", error.message)
    }

    @Test
    fun `心跳与未知帧被安全忽略`() {
        val events = LlmStream.parse(
            ChatPayload.Api.ANTHROPIC_MESSAGES,
            sequenceOf(
                """: ping

data: {"type":"message_start","message":{"id":"x"}}

data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"正文"}}

""",
            ),
        )
        assertEquals("正文", LlmStream.collectText(events))
    }

    // ── 与 MoodParser 串起来 ─────────────────────────────────

    @Test
    fun `流式正文接进 MoodParser 后内省块被正确切出`() {
        // 这是真实链路：SSE → 正文增量 → MoodParser → 界面。
        // 内省块的标签几乎必然被 SSE 分帧切断，这里验证两级解析串起来仍然正确。
        val chunks = listOf(
            """data: {"choices":[{"delta":{"content":"<mo"}}]}

""",
            """data: {"choices":[{"delta":{"content":"od>Vibe: 累"}}]}

""",
            """data: {"choices":[{"delta":{"content":"</mo"}}]}

""",
            """data: {"choices":[{"delta":{"content":"od>\n那我们慢慢来。"}}]}

data: [DONE]

""",
        )

        val parser = MoodParser()
        val moodEvents = mutableListOf<MoodEvent>()
        val framer = LlmStream.SseFramer()
        for (chunk in chunks) {
            for (frame in framer.feed(chunk)) {
                for (event in LlmStream.mapFrame(ChatPayload.Api.OPENAI_COMPLETIONS, frame)) {
                    if (event is LlmStream.Event.TextDelta) parser.feed(event.text) { moodEvents += it }
                }
            }
        }
        parser.flush { moodEvents += it }

        val mood = moodEvents.filterIsInstance<MoodEvent.MoodText>().joinToString("") { it.data }
        val text = moodEvents.filterIsInstance<MoodEvent.Text>().joinToString("") { it.data }
        assertEquals("Vibe: 累", mood, "内省内容被切错了")
        assertEquals("那我们慢慢来。", text, "正文被切错了")
    }
}
