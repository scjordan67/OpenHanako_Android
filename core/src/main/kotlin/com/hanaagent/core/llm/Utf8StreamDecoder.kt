package com.hanaagent.core.llm

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/**
 * 增量式 UTF-8 解码器 —— 网络分片可以落在一个字符的中间。
 *
 * 这个类存在的唯一理由是：HTTP 响应体是**字节**流，分片边界由网络决定，而一个
 * 汉字在 UTF-8 里占 3 个字节。如果对每一片字节单独调 `String(bytes)`，只要某片
 * 的末尾恰好切在汉字中间，那个字就会变成 `�`，而且**后面所有字节的对齐也不会
 * 出错**（UTF-8 是自同步的），所以坏掉的只是零星几个字 —— 表现为"模型偶尔吐出
 * 乱码"，看着像模型的问题，实际是解码的问题。
 *
 * 中文界面下这条几乎必然会被触发：正文里全是 3 字节字符，8KB 的读缓冲切在字符
 * 中间的概率约是 2/3。
 *
 * 用法：每读到一片字节调 [decode]，流结束时调 [flush]。跨片的半个字符会被留在
 * 内部，等下一片补齐。
 */
class Utf8StreamDecoder {

    private val decoder: CharsetDecoder = Charsets.UTF_8.newDecoder()
        // 真正残缺的字节（不是跨片切断，而是对端发来的坏数据）替换成 U+FFFD，
        // 而不是抛异常：一次对话不该因为一个坏字节整个失败。
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    /** 上一片末尾切剩的、还不足以构成一个完整字符的字节。 */
    private var carry = ByteArray(0)

    /**
     * 解码一片字节。
     *
     * @param bytes 缓冲区
     * @param length 本片的有效长度（[bytes] 常常是复用的读缓冲，只有前 length 个有效）
     * @return 本片能确定下来的字符；跨片的半个字符不在其中，留到下一次
     */
    fun decode(bytes: ByteArray, length: Int = bytes.size): String {
        if (length <= 0) return ""

        val input = if (carry.isEmpty()) {
            ByteBuffer.wrap(bytes, 0, length)
        } else {
            ByteBuffer.allocate(carry.size + length).apply {
                put(carry)
                put(bytes, 0, length)
                flip()
            }
        }

        // UTF-8 一个码点最多 4 字节，字符数不会超过字节数
        val output = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, output, false)

        // input 里没消费完的就是被切断的那半个字符
        carry = ByteArray(input.remaining()).also { input.get(it) }

        output.flip()
        return output.toString()
    }

    /**
     * 流结束时调用。
     *
     * 如果还有 carry 没消化掉，说明流是在一个字符中间断的 —— 那就是真的残缺，
     * 按替换字符输出，不静默丢弃：丢弃会让人以为消息本来就到那里为止。
     */
    fun flush(): String {
        val output = CharBuffer.allocate(carry.size + 2)
        val input = ByteBuffer.wrap(carry)
        decoder.decode(input, output, true)
        decoder.flush(output)
        carry = ByteArray(0)
        output.flip()
        return output.toString()
    }
}
