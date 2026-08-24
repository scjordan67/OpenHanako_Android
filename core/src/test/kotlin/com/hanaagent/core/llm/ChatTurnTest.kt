package com.hanaagent.core.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 一次完整的对话回合。
 *
 * 前面每一环都单独测过了，这里测的是**接缝**：字节分片、SSE 分帧、内省块切分、
 * 两家 API 的差异叠在一起时是否还成立。接缝处的 bug 有个共同特点 —— 单元测试
 * 全绿，真跑起来才出问题。
 */
class ChatTurnTest {

    private val endpoint = ChatTurn.Endpoint(
        api = ChatPayload.Api.ANTHROPIC_MESSAGES,
        baseUrl = "https://api.example.com",
        model = "claude-test",
        apiKey = "sk-test",
    )

    // ── 构造 SSE 响应 ────────────────────────────────────────

    private fun anthropicSse(vararg pieces: String, stop: Boolean = true): String = buildString {
        for (piece in pieces) {
            val escaped = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(),
                kotlinx.serialization.json.JsonPrimitive(piece))
            append("""data: {"type":"content_block_delta","delta":{"type":"text_delta","text":$escaped}}""")
            append("\n\n")
        }
        if (stop) append("data: {\"type\":\"message_stop\"}\n\n")
    }

    private fun openAiSse(vararg pieces: String): String = buildString {
        for (piece in pieces) {
            val escaped = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(),
                kotlinx.serialization.json.JsonPrimitive(piece))
            append("""data: {"choices":[{"delta":{"content":$escaped}}]}""")
            append("\n\n")
        }
        append("data: [DONE]\n\n")
    }

    /** 按固定字节数切片的 MockEngine —— 模拟网络给出的任意分片。 */
    private fun clientOf(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpClient =
        HttpClient(
            MockEngine { _ ->
                respond(
                    content = ByteReadChannel(body.toByteArray(Charsets.UTF_8)),
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        )

    // ── 核心不变式：内省块不进正文 ───────────────────────────

    @Test
    fun `内省块被切出来，一个字都不进正文`() = runTest {
        val sse = anthropicSse("<mood>", "有点开心\n因为她回来了", "</mood>", "你回来啦。")
        val events = mutableListOf<ChatTurn.Event>()
        val outcome = ChatTurn(clientOf(sse), endpoint).run("系统提示", listOf(user("在吗")), events::add)

        assertTrue(outcome.succeeded, "不该失败：${outcome.error}")
        assertEquals("你回来啦。", outcome.body.trim())
        assertTrue("有点开心" in outcome.mood, "内省内容没被收集：${outcome.mood}")
        assertTrue("mood" !in outcome.body, "内省标签漏进了正文：${outcome.body}")
        assertTrue("有点开心" !in outcome.body, "内省内容漏进了正文：${outcome.body}")

        // 事件流也必须分开：Body 事件里不能出现内省内容
        val bodyFromEvents = events.filterIsInstance<ChatTurn.Event.Body>().joinToString("") { it.text }
        assertTrue("有点开心" !in bodyFromEvents, "Body 事件里混进了内省内容")
        assertTrue(events.any { it is ChatTurn.Event.MoodStart })
        assertTrue(events.any { it is ChatTurn.Event.MoodEnd })
    }

    @Test
    fun `raw 保留原始输出 —— 重放能还原同样的切分`() = runTest {
        // 存进 JSONL 的是 raw。如果只存切好的正文，内省块就永久丢了，
        // 下次加载这条消息时界面上的内心独白会凭空消失。
        val sse = anthropicSse("<mood>想了想</mood>", "好啊。")
        val outcome = ChatTurn(clientOf(sse), endpoint).run("s", listOf(user("走吗")))

        assertEquals("<mood>想了想</mood>好啊。", outcome.raw)

        // 把 raw 重放一遍，切分结果必须与首次一致
        val replayBody = StringBuilder()
        val replayMood = StringBuilder()
        val parser = com.hanaagent.core.mood.MoodParser()
        val sink: (com.hanaagent.core.mood.MoodEvent) -> Unit = { event ->
            when (event) {
                is com.hanaagent.core.mood.MoodEvent.Text -> replayBody.append(event.data)
                is com.hanaagent.core.mood.MoodEvent.MoodText -> replayMood.append(event.data)
                else -> Unit
            }
        }
        parser.feed(outcome.raw, sink)
        parser.flush(sink)

        assertEquals(outcome.body, replayBody.toString(), "重放得到的正文与首次不一致")
        assertEquals(outcome.mood, replayMood.toString(), "重放得到的内省块与首次不一致")
    }

    // ── 分片不变性 ───────────────────────────────────────────

    @Test
    fun `逐字节送达时结果不变 —— 中文与标签都会被切开`() = runTest {
        val sse = anthropicSse("<mood>", "她问了个很难的问题", "</mood>", "让我想想……")

        val whole = ChatTurn(clientOf(sse), endpoint).run("s", listOf(user("q")))

        // 每次只给 1 字节：汉字被切成三刀，标签也被切开
        val byByte = HttpClient(
            MockEngine { _ ->
                respond(
                    content = ByteReadChannel(sse.toByteArray(Charsets.UTF_8)),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        )
        val chunked = ChatTurn(byByte, endpoint).run("s", listOf(user("q")))

        assertEquals(whole.body, chunked.body)
        assertEquals(whole.mood, chunked.mood)
        assertTrue('�' !in chunked.body, "正文里出现了替换字符，UTF-8 被切坏了")
        assertEquals("让我想想……", chunked.body.trim())
    }

    @Test
    fun `直接喂字节的状态机与整体解析一致`() = runTest {
        // 绕开 HTTP，直接对 Collector 逐字节喂 —— 这是分片不变性最严格的形式
        val sse = anthropicSse("<mood>细碎的念头</mood>", "嗯，我在。")
        val bytes = sse.toByteArray(Charsets.UTF_8)

        val collector = ChatTurn.Collector(ChatPayload.Api.ANTHROPIC_MESSAGES) {}
        for (b in bytes) collector.onBytes(byteArrayOf(b), 1)
        collector.onStreamEnd()
        val outcome = collector.finish()

        assertEquals("嗯，我在。", outcome.body)
        assertEquals("细碎的念头", outcome.mood)
        assertEquals("<mood>细碎的念头</mood>嗯，我在。", outcome.raw)
    }

    // ── 两家 API ─────────────────────────────────────────────

    @Test
    fun `OpenAI 形态走同一条路径`() = runTest {
        val openAi = endpoint.copy(api = ChatPayload.Api.OPENAI_COMPLETIONS)
        val sse = openAiSse("<mood>", "内心戏", "</mood>", "好的。")
        val outcome = ChatTurn(clientOf(sse), openAi).run("s", listOf(user("hi")))

        assertTrue(outcome.succeeded, "不该失败：${outcome.error}")
        assertEquals("好的。", outcome.body)
        assertEquals("内心戏", outcome.mood)
    }

    @Test
    fun `api 形态决定用哪套映射 —— 不能默认成 Anthropic`() = runTest {
        // 曾经写错过：Collector 的 api 有默认值但没被赋值，OpenAI 端点会静默
        // 解析不出任何内容（表现为「模型不回话」，毫无线索）
        val sse = openAiSse("只有 OpenAI 形态能解出来")
        val asOpenAi = ChatTurn(clientOf(sse), endpoint.copy(api = ChatPayload.Api.OPENAI_COMPLETIONS))
            .run("s", listOf(user("hi")))
        assertEquals("只有 OpenAI 形态能解出来", asOpenAi.body)

        // 同样的载荷用 Anthropic 映射解不出正文 —— 证明上面那条真的走了 OpenAI 分支
        val asAnthropic = ChatTurn(clientOf(sse), endpoint).run("s", listOf(user("hi")))
        assertEquals("", asAnthropic.body)
    }

    @Test
    fun `请求按端点形态构造：system 位置与认证头`() = runTest {
        val captured = mutableListOf<io.ktor.client.request.HttpRequestData>()
        fun spyClient(api: ChatPayload.Api) = HttpClient(
            MockEngine { request ->
                captured += request
                respond(
                    content = ByteReadChannel(
                        if (api == ChatPayload.Api.ANTHROPIC_MESSAGES) anthropicSse("ok") else openAiSse("ok"),
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        )

        ChatTurn(spyClient(ChatPayload.Api.ANTHROPIC_MESSAGES), endpoint).run("人格在这里", listOf(user("hi")))
        val anthropic = captured.removeAt(0)
        assertEquals("/v1/messages", anthropic.url.encodedPath)
        assertEquals("sk-test", anthropic.headers["x-api-key"], "Anthropic 用 x-api-key")
        assertEquals(ChatPayload.ANTHROPIC_API_VERSION, anthropic.headers["anthropic-version"])
        val anthropicBody = Json.parseToJsonElement(bodyText(anthropic)).jsonObject
        assertEquals("人格在这里", anthropicBody["system"]?.jsonPrimitive?.content, "Anthropic 的 system 是顶层字段")

        val openAiEndpoint = endpoint.copy(api = ChatPayload.Api.OPENAI_COMPLETIONS)
        ChatTurn(spyClient(ChatPayload.Api.OPENAI_COMPLETIONS), openAiEndpoint).run("人格在这里", listOf(user("hi")))
        val openAi = captured.removeAt(0)
        assertEquals("/chat/completions", openAi.url.encodedPath)
        assertEquals("Bearer sk-test", openAi.headers["Authorization"], "OpenAI 用 Bearer")
        val openAiBody = Json.parseToJsonElement(bodyText(openAi)).jsonObject
        val first = openAiBody["messages"]!!.jsonArray[0].jsonObject
        assertEquals("system", first["role"]?.jsonPrimitive?.content, "OpenAI 的 system 是第一条消息")
    }

    @Test
    fun `content-type 不会因为同时设置而重复`() = runTest {
        val captured = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val client = HttpClient(
            MockEngine { request ->
                captured += request
                respond(
                    ByteReadChannel(anthropicSse("ok")), HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        )
        ChatTurn(client, endpoint).run("s", listOf(user("hi")))
        // ktor 把 body 的 content-type 单独管理；这里确认没有额外塞进 headers 里
        assertEquals(
            0,
            captured[0].headers.getAll(HttpHeaders.ContentType).orEmpty().size,
            "content-type 被同时用 contentType() 和 headers 设了两遍",
        )
    }

    // ── 失败要说人话 ─────────────────────────────────────────

    @Test
    fun `HTTP 错误翻成能据以行动的一句话`() = runTest {
        val cases = mapOf(
            401 to "API key",
            404 to "模型名",
            429 to "限流",
            500 to "服务端",
        )
        for ((status, expectedHint) in cases) {
            val client = HttpClient(MockEngine { respondError(HttpStatusCode.fromValue(status)) })
            val events = mutableListOf<ChatTurn.Event>()
            val outcome = ChatTurn(client, endpoint).run("s", listOf(user("hi")), events::add)

            assertFalse(outcome.succeeded, "HTTP $status 应当算失败")
            assertNotNull(outcome.error)
            assertTrue(
                expectedHint in outcome.error!!,
                "HTTP $status 的说明里没有「$expectedHint」：${outcome.error}",
            )
            assertTrue(events.any { it is ChatTurn.Event.Failed }, "没有发出 Failed 事件")
        }
    }

    @Test
    fun `错误响应体里的原文被带出来`() = runTest {
        val body = """{"error":{"type":"invalid_request_error","message":"max_tokens: must be >= 1"}}"""
        val client = HttpClient(
            MockEngine {
                respond(body, HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
        val outcome = ChatTurn(client, endpoint).run("s", listOf(user("hi")))
        assertTrue(
            "max_tokens" in outcome.error.orEmpty(),
            "对端给的具体原因被丢掉了：${outcome.error}",
        )
    }

    @Test
    fun `流中途的错误帧被识别`() = runTest {
        val sse = buildString {
            append(anthropicSse("说到一半", stop = false))
            append("""data: {"error":{"message":"overloaded_error"}}""")
            append("\n\n")
        }
        val outcome = ChatTurn(clientOf(sse), endpoint).run("s", listOf(user("hi")))

        assertFalse(outcome.succeeded)
        assertTrue("overloaded" in outcome.error.orEmpty(), "错误原文没带出来：${outcome.error}")
        // 已经吐出来的半句要保留 —— 界面上那半句已经给用户看过了
        assertEquals("说到一半", outcome.body, "已产出的正文不该因为后续报错而丢失")
    }

    @Test
    fun `连接异常不抛出去，翻成 Failed`() = runTest {
        val client = HttpClient(MockEngine { throw java.io.IOException("Connection reset by peer") })
        val events = mutableListOf<ChatTurn.Event>()
        val outcome = ChatTurn(client, endpoint).run("s", listOf(user("hi")), events::add)

        assertFalse(outcome.succeeded)
        assertTrue("Connection reset" in outcome.error.orEmpty(), "原始原因被吞了：${outcome.error}")
        assertTrue(events.any { it is ChatTurn.Event.Failed })
    }

    // ── 流被截断 ─────────────────────────────────────────────

    @Test
    fun `流在内省块中途断掉时补上 MoodEnd`() = runTest {
        // 否则界面永远停在"内省中"，正文区一片空白
        val sse = anthropicSse("<mood>刚开了个头就断", stop = false)
        val events = mutableListOf<ChatTurn.Event>()
        val outcome = ChatTurn(clientOf(sse), endpoint).run("s", listOf(user("hi")), events::add)

        assertTrue(events.any { it is ChatTurn.Event.MoodStart })
        assertTrue(events.any { it is ChatTurn.Event.MoodEnd }, "内省块没收尾，界面会卡在内省状态")
        assertTrue("刚开了个头就断" in outcome.mood)
    }

    @Test
    fun `没有内省块的普通回复也能正常走完`() = runTest {
        // 不是每次回复都带内省块；没有的时候正文不能被吃掉
        val outcome = ChatTurn(clientOf(anthropicSse("就是一句普通的话。")), endpoint)
            .run("s", listOf(user("hi")))
        assertEquals("就是一句普通的话。", outcome.body)
        assertEquals("", outcome.mood)
        assertTrue(outcome.succeeded)
    }

    @Test
    fun `思考通道不进正文`() = runTest {
        val sse = buildString {
            append("""data: {"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"内部推理"}}""")
            append("\n\n")
            append(anthropicSse("对外的回答"))
        }
        val events = mutableListOf<ChatTurn.Event>()
        val outcome = ChatTurn(clientOf(sse), endpoint).run("s", listOf(user("hi")), events::add)

        assertEquals("对外的回答", outcome.body)
        assertEquals("内部推理", outcome.thinking)
        assertTrue("内部推理" !in outcome.body, "思考内容漏进了正文")
        assertTrue(events.any { it is ChatTurn.Event.Thinking })
    }

    // ── 辅助 ─────────────────────────────────────────────────

    private fun user(text: String) = ChatPayload.ChatMessage.text("user", text)

    private fun bodyText(request: io.ktor.client.request.HttpRequestData): String =
        (request.body as io.ktor.http.content.TextContent).text
}
