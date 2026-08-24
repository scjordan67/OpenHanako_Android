package com.hanaagent.android.data

import android.content.Context
import com.hanaagent.core.llm.ChatPayload
import com.hanaagent.core.session.SessionJsonl
import com.hanaagent.core.session.SessionMessages
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * 会话的落盘 —— 追加式 JSONL，与上游同一种格式。
 *
 * 为什么是追加而不是"保存整个会话"：JSONL 里任何"修改"都是新增一条以旧条目为父的
 * 记录（[SessionJsonl] 的分支模型）。追加天然抗中断 —— 写到一半断电，最多丢最后
 * 一行，前面的对话完好；而重写整个文件时断电会把整段历史一起赔进去。
 *
 * 对一个用来存长期关系的应用，"最坏情况丢多少"比"写得快不快"重要得多。
 */
class SessionStore(context: Context, sessionId: String = DEFAULT_SESSION) {

    private val file: File = File(File(context.filesDir, "sessions"), "$sessionId.jsonl")

    /** 当前分支上的消息，按顺序。文件不存在时是空的。 */
    fun load(): List<SessionJsonl.Message> {
        val entries = SessionJsonl.readFile(file)
        if (entries.isEmpty()) return emptyList()
        return SessionJsonl.projectCurrentBranch(entries).messages
    }

    /** 当前分支最后一条的 id —— 新消息以它为父。 */
    private fun currentLeafId(): String? {
        val entries = SessionJsonl.readFile(file)
        if (entries.isEmpty()) return null
        return SessionJsonl.projectCurrentBranch(entries).selectedLeafId
    }

    /** 追加一条用户消息，返回它的 id。 */
    fun appendUser(text: String, images: List<ChatPayload.ChatContent.Image> = emptyList()): String {
        val id = newId()
        SessionJsonl.append(
            file,
            SessionMessages.userEntry(
                id = id,
                parentId = currentLeafId(),
                text = text,
                images = images,
                timestamp = now(),
            ),
        )
        return id
    }

    /**
     * 追加一条助手回复。
     *
     * [rawText] 必须是模型的**原始**输出（含内省标签）—— 下次加载时重放 MoodParser
     * 才能还原出同样的切分。存切好的正文会让内省块永久消失。
     */
    fun appendAssistant(rawText: String, parentId: String?): String {
        val id = newId()
        SessionJsonl.append(
            file,
            SessionMessages.assistantEntry(
                id = id,
                parentId = parentId ?: currentLeafId(),
                rawText = rawText,
                timestamp = now(),
            ),
        )
        return id
    }

    /** 把当前分支翻成模型消息。内省块原样带回，见 [SessionMessages]。 */
    fun history(): List<ChatPayload.ChatMessage> = SessionMessages.toChatMessages(load())

    fun clear() {
        file.delete()
    }

    val exists: Boolean get() = file.exists()

    private fun newId(): String = UUID.randomUUID().toString()
    private fun now(): String = Instant.now().toString()

    companion object {
        const val DEFAULT_SESSION = "default"
    }
}
