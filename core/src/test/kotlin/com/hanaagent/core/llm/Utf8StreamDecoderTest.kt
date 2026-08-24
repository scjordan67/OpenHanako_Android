package com.hanaagent.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 增量 UTF-8 解码。
 *
 * 这是移植里少数「不写就一定出错」的地方：中文正文全是 3 字节字符，网络分片切在
 * 字符中间是常态而非例外。测法与 MoodParser / SseFramer 一致 —— 任意分片粒度下
 * 结果必须与整体解码一致。
 */
class Utf8StreamDecoderTest {

    /** 覆盖 1/2/3/4 字节四种长度，外加组合字与零宽连接符。 */
    private val samples = listOf(
        "你好，我是花子。",
        "Hello, world!",
        "中英mixed混排 123 —— 破折号与省略号…",
        "emoji 测试 🌸🍮 与合成字 👨‍👩‍👧",
        "Ω≈ç√∫˜µ≤≥÷ 数学符号",
        "ばか、べつにあんたのためじゃないんだからね",
        "",
    )

    private fun decodeInChunks(text: String, chunkSize: Int): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val decoder = Utf8StreamDecoder()
        val out = StringBuilder()
        var offset = 0
        while (offset < bytes.size) {
            val n = minOf(chunkSize, bytes.size - offset)
            out.append(decoder.decode(bytes.copyOfRange(offset, offset + n), n))
            offset += n
        }
        out.append(decoder.flush())
        return out.toString()
    }

    @Test
    fun `任意分片大小的解码结果都与整体一致`() {
        for (text in samples) {
            val byteLen = text.toByteArray(Charsets.UTF_8).size
            for (chunkSize in 1..maxOf(byteLen, 1)) {
                assertEquals(
                    text,
                    decodeInChunks(text, chunkSize),
                    "分片大小 $chunkSize 时解码结果不一致（原文：$text）",
                )
            }
        }
    }

    @Test
    fun `逐字节喂入不产生替换字符`() {
        // 这条是上一条的特例，但值得单独钉住：逐字节是最坏情况，
        // 每个汉字都会被切两刀。天真实现在这里会吐出一串 U+FFFD。
        for (text in samples) {
            val result = decodeInChunks(text, 1)
            assertTrue('�' !in result, "逐字节解码出现了替换字符：$result")
            assertEquals(text, result)
        }
    }

    @Test
    fun `跨片的半个字符不会提前吐出来`() {
        // 关键行为：解码器宁可什么都不返回，也不能返回半个字符的乱码。
        // 上层（SSE 分帧、MoodParser）拿到的每一段都必须是完整字符。
        val bytes = "好".toByteArray(Charsets.UTF_8)
        assertEquals(3, bytes.size)

        val decoder = Utf8StreamDecoder()
        assertEquals("", decoder.decode(bytes.copyOfRange(0, 1), 1), "第一个字节不该产出任何字符")
        assertEquals("", decoder.decode(bytes.copyOfRange(1, 2), 1), "第二个字节仍不足以成字")
        assertEquals("好", decoder.decode(bytes.copyOfRange(2, 3), 1), "第三个字节到齐才成字")
    }

    @Test
    fun `复用读缓冲时只解码有效长度`() {
        // 实际读取用的是复用缓冲：readAvailable 返回 n，缓冲区后面是上一轮的残留。
        // 传了 length 却不遵守的话，会把陈旧数据重复吐出来。
        val buffer = ByteArray(64)
        val payload = "花子".toByteArray(Charsets.UTF_8)
        payload.copyInto(buffer)
        // 缓冲区尾部塞上垃圾，模拟上一轮残留
        for (i in payload.size until buffer.size) buffer[i] = 'X'.code.toByte()

        val decoder = Utf8StreamDecoder()
        assertEquals("花子", decoder.decode(buffer, payload.size))
    }

    @Test
    fun `流在字符中间断掉时给出替换字符而不是静默吞掉`() {
        // 连接被掐断，最后一个字只到了一半。这时候吐 U+FFFD 是对的：
        // 静默丢弃会让人以为消息本来就到那里为止。
        val bytes = "好".toByteArray(Charsets.UTF_8)
        val decoder = Utf8StreamDecoder()
        assertEquals("", decoder.decode(bytes.copyOfRange(0, 2), 2))
        assertEquals("�", decoder.flush())
    }

    @Test
    fun `真实分片场景：SSE 帧里的中文被字节流切开`() {
        // 端到端：字节流 -> UTF8 解码 -> SSE 分帧 -> 事件，逐字节喂。
        val sse = buildString {
            for (piece in listOf("你好", "，我是", "花子")) {
                append("""data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"$piece"}}""")
                append("\n\n")
            }
            append("data: {\"type\":\"message_stop\"}\n\n")
        }
        val bytes = sse.toByteArray(Charsets.UTF_8)

        val decoder = Utf8StreamDecoder()
        val framer = LlmStream.SseFramer()
        val events = mutableListOf<LlmStream.Event>()
        for (b in bytes) {
            val text = decoder.decode(byteArrayOf(b), 1)
            if (text.isEmpty()) continue
            for (frame in framer.feed(text)) {
                events += LlmStream.mapFrame(ChatPayload.Api.ANTHROPIC_MESSAGES, frame)
            }
        }
        decoder.flush().takeIf { it.isNotEmpty() }?.let { tail ->
            for (frame in framer.feed(tail)) {
                events += LlmStream.mapFrame(ChatPayload.Api.ANTHROPIC_MESSAGES, frame)
            }
        }
        for (frame in framer.flush()) events += LlmStream.mapFrame(ChatPayload.Api.ANTHROPIC_MESSAGES, frame)

        assertEquals("你好，我是花子", LlmStream.collectText(events))
        assertTrue(events.contains(LlmStream.Event.Done))
    }
}
