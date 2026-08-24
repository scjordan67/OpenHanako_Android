package com.hanaagent.core.session

import com.hanaagent.core.llm.ChatPayload
import com.hanaagent.core.mood.MoodEvent
import com.hanaagent.core.mood.MoodParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 会话存储 ↔ 模型消息。
 *
 * 这一层出错的表现是"对话接不上"：要么历史丢了，要么图片没带上，要么内省块
 * 被剥掉导致模型慢慢不再写内心独白。都不报错，只是越来越不对劲。
 */
class SessionMessagesTest {

    private fun message(role: String, contentJson: String, id: String = "x") =
        SessionJsonl.Message(
            role = role,
            content = Json.parseToJsonElement(contentJson),
            timestamp = "2026-08-24T10:00:00Z",
            entryId = id,
            lineageIndex = 0,
        )

    // ── 历史 → 模型消息 ──────────────────────────────────────

    @Test
    fun `字符串 content 变成一条文本`() {
        val result = SessionMessages.toChatMessages(listOf(message("user", "\"你好\"")))
        assertEquals(1, result.size)
        assertEquals("user", result[0].role)
        assertEquals(listOf(ChatPayload.ChatContent.Text("你好")), result[0].content)
    }

    @Test
    fun `助手历史保留内省块 —— 剥了模型会慢慢不再写`() {
        // 上游只在 Bridge 出站时剥内省标签（lib/bridge/bridge-manager.ts），
        // 模型请求那条路径上没有任何地方剥它。历史是最强的示范：把过去几轮的
        // 内心独白都剥掉再送回去，模型看到的是"我以前都不写"，很快就真的不写了。
        val raw = "<mood>有点在意</mood>我在的。"
        val result = SessionMessages.toChatMessages(listOf(message("assistant", "\"$raw\"")))
        val text = (result[0].content[0] as ChatPayload.ChatContent.Text).text
        assertEquals(raw, text, "内省块被剥掉了")
        assertTrue("<mood>" in text)
    }

    @Test
    fun `空内容的条目被丢掉 —— 有的模型对空 content 直接报 400`() {
        val messages = listOf(
            message("user", "\"\""),
            message("user", "\"   \""),
            message("user", "null"),
            message("user", "[]"),
            message("user", "\"有内容\""),
        )
        val result = SessionMessages.toChatMessages(messages)
        assertEquals(1, result.size)
        assertEquals("有内容", (result[0].content[0] as ChatPayload.ChatContent.Text).text)
    }

    @Test
    fun `Anthropic 形状的图片块被解出来`() {
        val json = """[
            {"type":"text","text":"看这张"},
            {"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"QUJD"}}
        ]"""
        val content = SessionMessages.toChatMessages(listOf(message("user", json)))[0].content
        assertEquals(2, content.size)
        assertEquals("看这张", (content[0] as ChatPayload.ChatContent.Text).text)
        val image = content[1] as ChatPayload.ChatContent.Image
        assertEquals("QUJD", image.base64)
        assertEquals("image/jpeg", image.mimeType)
    }

    @Test
    fun `OpenAI 形状的 data URL 图片也认`() {
        // 会话文件可能是从桌面端导出的，用哪种形状取决于当时接的是哪个模型
        val json = """[{"type":"image_url","image_url":{"url":"data:image/png;base64,WFla"}}]"""
        val content = SessionMessages.toChatMessages(listOf(message("user", json)))[0].content
        val image = content[0] as ChatPayload.ChatContent.Image
        assertEquals("WFla", image.base64)
        assertEquals("image/png", image.mimeType)
    }

    @Test
    fun `不是 data URL 的图片链接被放弃而不是当成 base64`() {
        // 远程 URL 塞进 base64 字段会让请求体变成一串垃圾，模型看不见图却照答
        assertNull(SessionMessages.parseDataUrl("https://example.com/a.png"))
        assertNull(SessionMessages.parseDataUrl("data:image/png,notbase64"))
        assertNull(SessionMessages.parseDataUrl("data:image/png;base64,"))
    }

    @Test
    fun `认不出的内容块被跳过而不是让整条消息作废`() {
        val json = """[{"type":"未来才有的块"},{"type":"text","text":"还认得这句"}]"""
        val content = SessionMessages.toChatMessages(listOf(message("user", json)))[0].content
        assertEquals(1, content.size)
        assertEquals("还认得这句", (content[0] as ChatPayload.ChatContent.Text).text)
    }

    // ── 写回 JSONL ───────────────────────────────────────────

    @Test
    fun `用户条目：纯文本时 content 是字符串`() {
        val entry = SessionMessages.userEntry("u1", null, "在吗", timestamp = "2026-08-24T10:00:00Z")
        assertEquals("u1", entry["id"]?.jsonPrimitive?.content)
        assertEquals("message", entry["type"]?.jsonPrimitive?.content)
        val msg = entry["message"]!!.jsonObject
        assertEquals("user", msg["role"]?.jsonPrimitive?.content)
        assertEquals("在吗", msg["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `用户条目：带图片时 content 变成块数组`() {
        val entry = SessionMessages.userEntry(
            "u2", "u1", "这是什么",
            images = listOf(ChatPayload.ChatContent.Image("QUJD", "image/jpeg")),
            timestamp = "2026-08-24T10:00:00Z",
        )
        val content = entry["message"]!!.jsonObject["content"]!!
        // 写出去的东西必须能被自己读回来
        val parsed = SessionMessages.parseContent(content)
        assertEquals(2, parsed.size)
        assertEquals("这是什么", (parsed[0] as ChatPayload.ChatContent.Text).text)
        assertEquals("QUJD", (parsed[1] as ChatPayload.ChatContent.Image).base64)
    }

    @Test
    fun `助手条目存的是原始输出 —— 重放能还原内省块`() {
        val raw = "<mood>想了想</mood>好啊。"
        val entry = SessionMessages.assistantEntry("a1", "u1", raw, "2026-08-24T10:00:01Z")
        assertEquals(raw, entry["message"]!!.jsonObject["content"]?.jsonPrimitive?.content)

        // 从存储里读回来，重放 MoodParser，切分必须与当时一致
        val restored = SessionMessages.parseContent(entry["message"]!!.jsonObject["content"])
        val text = (restored[0] as ChatPayload.ChatContent.Text).text
        val body = StringBuilder()
        val mood = StringBuilder()
        val parser = MoodParser()
        val sink: (MoodEvent) -> Unit = { event ->
            when (event) {
                is MoodEvent.Text -> body.append(event.data)
                is MoodEvent.MoodText -> mood.append(event.data)
                else -> Unit
            }
        }
        parser.feed(text, sink)
        parser.flush(sink)
        assertEquals("好啊。", body.toString())
        assertEquals("想了想", mood.toString(), "存进去再读出来，内省块应当原样还原")
    }

    @Test
    fun `写出去的条目能被 SessionJsonl 读回来并投影成消息`() {
        // 端到端：构造条目 → 序列化成 JSONL → 解析 → 投影 → 转模型消息
        val entries = listOf(
            SessionMessages.userEntry("u1", null, "在吗", timestamp = "2026-08-24T10:00:00Z"),
            SessionMessages.assistantEntry("a1", "u1", "<mood>嗯</mood>我在。", "2026-08-24T10:00:01Z"),
            SessionMessages.userEntry("u2", "a1", "那聊聊", timestamp = "2026-08-24T10:00:02Z"),
        )
        val jsonl = entries.joinToString("\n") { Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), it) }

        val projection = SessionJsonl.projectCurrentBranch(SessionJsonl.parseEntries(jsonl))
        val chat = SessionMessages.toChatMessages(projection.messages)

        assertEquals(listOf("user", "assistant", "user"), chat.map { it.role })
        assertEquals("在吗", (chat[0].content[0] as ChatPayload.ChatContent.Text).text)
        assertTrue(
            "<mood>" in (chat[1].content[0] as ChatPayload.ChatContent.Text).text,
            "助手历史的内省块应当原样带回模型",
        )
    }

    // ── 可见正文 ─────────────────────────────────────────────

    @Test
    fun `可见正文剥掉内省块与思考标签`() {
        val message = message("assistant", "\"<mood>心里嘀咕</mood><think>推理</think>说出口的话\"")
        assertEquals("说出口的话", SessionMessages.visibleText(message))
    }

    @Test
    fun `可见正文把图片折成占位符`() {
        val json = """[{"type":"text","text":"你看"},{"type":"image","source":{"data":"QUJD"}}]"""
        assertEquals("你看［图片］", SessionMessages.visibleText(message("user", json)))
    }

    @Test
    fun `落单的内省标签也剥掉`() {
        // 流被截断时会留下没闭合的标签，预览里不该出现
        assertEquals("正文", SessionMessages.stripInternalNarration("<mood>正文"))
        assertEquals("正文", SessionMessages.stripInternalNarration("</mood>正文"))
        assertEquals("正文", SessionMessages.stripInternalNarration("<pulse>x</pulse>正文"))
    }
}
