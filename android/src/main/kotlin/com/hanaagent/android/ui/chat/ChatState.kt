package com.hanaagent.android.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hanaagent.android.data.AppSettings
import com.hanaagent.android.data.SessionStore
import com.hanaagent.core.llm.ChatPayload
import com.hanaagent.core.llm.ChatTurn
import com.hanaagent.core.mood.MoodParser
import com.hanaagent.core.persona.PersonaComposer
import com.hanaagent.core.prompt.SystemPromptBuilder
import com.hanaagent.core.session.SessionJsonl
import com.hanaagent.core.session.SessionMessages
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

/** 界面上的一条消息。助手的一条**同时**带正文和内省块，分开排版。 */
data class UiMessage(
    val role: String,
    val body: String,
    val mood: String = "",
    val yuan: String = "hanako",
    val failed: String? = null,
)

/**
 * 一次会话的界面状态。
 *
 * 把 :core 那几块拼成一条真正跑起来的链路：
 *
 * ```
 * 历史（SessionStore）─┐
 * 人格（PersonaComposer）─┼─► SystemPromptBuilder ─► ChatTurn ─► 流式事件 ─► 界面
 *                        ┘                                    └─► 落盘（SessionStore）
 * ```
 *
 * 刻意不做成 ViewModel：Activity 在清单里声明了 configChanges，转屏不会重建，
 * 多引入一层 lifecycle 依赖换不来什么。
 */
class ChatState(
    private val settings: AppSettings,
    private val store: SessionStore,
) {
    val messages = mutableStateListOf<UiMessage>()

    var streamingBody by mutableStateOf("")
        private set
    var streamingMood by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set

    /** 配置不全时的提示；null 表示可以发。 */
    var blocker by mutableStateOf<String?>(null)
        private set

    private val http by lazy { HttpClient(OkHttp) }

    /** 会话文件读不动时的说明；null 表示正常。 */
    var brokenSession by mutableStateOf<String?>(null)
        private set

    /**
     * 从磁盘恢复对话。内省块靠重放 MoodParser 还原，与首次收到时切分一致。
     *
     * 是 suspend 的：读文件 + 投影分支在主线程上做会卡 UI，会话长了尤其明显。
     */
    suspend fun restore() {
        val result = withContext(Dispatchers.IO) { store.load() }
        messages.clear()
        when (result) {
            is SessionStore.LoadResult.Ok -> {
                for (message in result.messages) messages += toUiMessage(message)
                brokenSession = null
            }

            is SessionStore.LoadResult.Broken -> brokenSession = result.reason
        }
        blocker = settings.missingPiece()
    }

    /** 把读不动的会话挪开重新开始。文件改名保留，不删。 */
    suspend fun setAsideBrokenSession() {
        val moved = withContext(Dispatchers.IO) { store.setAsideBroken() }
        brokenSession = null
        messages.clear()
        if (moved != null) {
            messages += UiMessage(
                role = "assistant",
                body = "上一份会话文件读不动，已挪到 $moved 保留着（没有删）。这里是新的开始。",
                yuan = settings.yuan,
            )
        }
    }

    fun refreshBlocker() {
        blocker = settings.missingPiece()
    }

    private fun toUiMessage(message: SessionJsonl.Message): UiMessage {
        val text = SessionMessages.parseContent(message.content)
            .filterIsInstance<ChatPayload.ChatContent.Text>()
            .joinToString("") { it.text }

        if (message.role != "assistant") {
            return UiMessage(role = message.role, body = text)
        }

        // 存的是原始输出，重放一遍切出正文与内省块
        val body = StringBuilder()
        val mood = StringBuilder()
        val parser = MoodParser()
        val sink: (com.hanaagent.core.mood.MoodEvent) -> Unit = { event ->
            when (event) {
                is com.hanaagent.core.mood.MoodEvent.Text -> body.append(event.data)
                is com.hanaagent.core.mood.MoodEvent.MoodText -> mood.append(event.data)
                else -> Unit
            }
        }
        parser.feed(text, sink)
        parser.flush(sink)

        return UiMessage(
            role = "assistant",
            body = body.toString().trim(),
            mood = mood.toString().trim(),
            yuan = settings.yuan,
        )
    }

    /**
     * 发一轮。
     *
     * 失败不抛出去 —— 把原因作为一条失败消息留在对话里。抛异常的话用户只会看到
     * "没反应"，而已经吐出来的半句正文也会跟着消失。
     */
    suspend fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || busy) return

        val missing = settings.missingPiece()
        if (missing != null) {
            blocker = missing
            return
        }
        val resolved = settings.endpoint() ?: run {
            blocker = "接口地址看不出是个网址"
            return
        }

        busy = true
        streamingBody = ""
        streamingMood = ""
        messages += UiMessage(role = "user", body = trimmed)

        // 整段包在 runCatching 里：PersonaComposer 对缺失的源会抛、写文件可能因磁盘满
        // 而抛、system prompt 装配也可能抛。这些抛出去就是**发消息时应用直接闪退** ——
        // 用户看到的是"点了发送然后没了"，连错误都读不到。
        // （ChatTurn 自己内部的失败已经收敛成 Outcome.error，不走这条路。）
        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                // 先落盘再发请求：请求失败时用户说过的话不该跟着丢
                val userId = store.appendUser(trimmed)
                val history = store.history()

                val persona = PersonaComposer.compose(
                    yuan = settings.yuan,
                    userName = settings.userName.ifBlank { "你" },
                    agentName = "花子",
                    agentId = "hana",
                )
                val systemPrompt = SystemPromptBuilder(
                    SystemPromptBuilder.Input(
                        locale = "zh-CN",
                        userName = settings.userName.ifBlank { null },
                        persona = persona,
                        now = ZonedDateTime.now(),
                    ),
                ).build()

                val turn = ChatTurn(
                    http,
                    ChatTurn.Endpoint(
                        api = resolved.api,
                        baseUrl = resolved.baseUrl,
                        model = settings.model,
                        apiKey = settings.apiKey,
                    ),
                )

                val result = turn.run(systemPrompt, history) { event ->
                    when (event) {
                        is ChatTurn.Event.Body -> streamingBody += event.text
                        is ChatTurn.Event.MoodText -> streamingMood += event.text
                        else -> Unit
                    }
                }

                // 只有真的收到内容才落盘：一次 401 不该在历史里留下一条空的助手消息，
                // 那会让下一轮的上下文里凭空多出一段沉默
                if (result.raw.isNotBlank()) store.appendAssistant(result.raw, userId)
                result
            }
        }.getOrElse { failure ->
            // 已经流出来的半句留着 —— 那半句用户已经看见了
            ChatTurn.Outcome(
                raw = "",
                body = streamingBody,
                mood = streamingMood,
                thinking = "",
                error = "这一轮没能走完：${failure.message ?: failure::class.simpleName}",
            )
        }

        messages += UiMessage(
            role = "assistant",
            body = outcome.body.trim(),
            mood = outcome.mood.trim(),
            yuan = settings.yuan,
            failed = outcome.error,
        )
        streamingBody = ""
        streamingMood = ""
        busy = false
    }

    fun clear() {
        store.clear()
        messages.clear()
    }
}
