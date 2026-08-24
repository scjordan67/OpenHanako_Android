package com.hanaagent.android.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hanaagent.android.data.CrashLog
import com.hanaagent.android.ui.theme.LocalHanaColors
import kotlinx.coroutines.launch

/**
 * 聊天界面。
 *
 * 版式上刻意保持克制 —— 没有头像、没有时间戳、没有气泡尾巴。上游的视觉语言是
 * 「一本手抄本」而不是「一个聊天软件」，堆装饰会把那个感觉冲掉。
 */
@Composable
fun ChatScreen(
    state: ChatState,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHanaColors.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }

    val streaming = state.streamingBody.isNotEmpty() || state.streamingMood.isNotEmpty()

    // 上次闪退的堆栈。显示在最上面而不是折叠起来 —— 它出现就说明有事故，
    // 而这是我唯一能拿到崩溃现场的途径（logcat 用户拿不到）。
    val context = androidx.compose.ui.platform.LocalContext.current
    var crash by remember { mutableStateOf(CrashLog.lastCrash(context)) }

    // 新消息或流式增量到达时贴着底部；用户往回翻时不打断
    LaunchedEffect(state.messages.size, state.streamingBody) {
        val last = state.messages.size + if (streaming) 1 else 0
        if (last > 0) listState.animateScrollToItem(last - 1)
    }

    Column(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = colors.space16),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("花子", color = colors.text, fontSize = colors.fsBody)
            TextButton(onClick = onOpenSettings) {
                Text("设置", color = colors.textMuted, fontSize = colors.fsUi)
            }
        }

        crash?.let { trace ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = colors.space16, vertical = colors.space8),
            ) {
                Text(
                    text = "上次是崩溃退出的。把下面这段发给我就能定位：",
                    color = colors.danger,
                    fontSize = colors.fsUi,
                )
                Text(
                    text = trace,
                    color = colors.textMuted,
                    fontSize = colors.sp("--fs-hint", colors.fsCaption),
                    modifier = Modifier
                        .padding(top = colors.space4)
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                )
                TextButton(onClick = { CrashLog.clear(context); crash = null }) {
                    Text("知道了，清除", color = colors.accent, fontSize = colors.fsUi)
                }
            }
        }

        // 会话文件读不动：明确说出来，并给一条出路。不自动删 —— 里面是全部对话，
        // 就算现在读不出来也可能只是最后一行写坏了，手工救回来完全有可能。
        state.brokenSession?.let { reason ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = colors.space16, vertical = colors.space8),
            ) {
                Text(
                    text = "之前的会话读不动了：$reason",
                    color = colors.danger,
                    fontSize = colors.fsUi,
                )
                TextButton(onClick = { scope.launch { state.setAsideBrokenSession() } }) {
                    Text(
                        "挪开它，重新开始（文件保留，不删）",
                        color = colors.accent,
                        fontSize = colors.fsUi,
                    )
                }
            }
        }

        state.blocker?.let { blocker ->
            Text(
                text = "$blocker —— 点右上角「设置」补上",
                color = colors.danger,
                fontSize = colors.fsUi,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = colors.space16, vertical = colors.space8)
                    .clickable { onOpenSettings() },
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = colors.space16),
        ) {
            items(state.messages.size) { index ->
                val message = state.messages[index]
                when {
                    message.role == "user" -> UserMessage(message.body)
                    message.failed != null -> FailedMessage(message)
                    else -> AssistantMessage(message.yuan, message.mood, message.body)
                }
            }
            if (streaming) {
                item {
                    AssistantMessage(
                        yuan = state.messages.lastOrNull()?.yuan ?: "hanako",
                        mood = state.streamingMood,
                        body = state.streamingBody,
                    )
                }
            }
        }

        InputArea(
            draft = draft,
            onDraftChange = { draft = it },
            enabled = !state.busy,
            onSend = {
                val text = draft
                draft = ""
                scope.launch { state.send(text) }
            },
        )
    }
}

/**
 * 失败的一轮。
 *
 * 已经吐出来的半句正文要留着 —— 那半句用户已经看见了，抹掉会让人以为自己看错了。
 */
@Composable
private fun FailedMessage(message: UiMessage) {
    val colors = LocalHanaColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = colors.space4)) {
        if (message.mood.isNotBlank() || message.body.isNotBlank()) {
            AssistantMessage(message.yuan, message.mood, message.body)
        }
        Text(
            text = message.failed.orEmpty(),
            color = colors.danger,
            fontSize = colors.fsUi,
            modifier = Modifier.padding(vertical = colors.space4),
        )
    }
}

@Composable
private fun InputArea(
    draft: String,
    onDraftChange: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
) {
    val colors = LocalHanaColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = colors.space16, vertical = colors.space8),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            enabled = enabled,
            placeholder = {
                Text("说点什么", color = colors.textMuted, fontSize = colors.fsBody)
            },
            shape = RoundedCornerShape(colors.radiusChatSurface),
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onSend,
            enabled = enabled && draft.isNotBlank(),
            modifier = Modifier.padding(start = colors.space8),
        ) {
            Text(
                text = if (enabled) "发送" else "…",
                color = if (enabled && draft.isNotBlank()) colors.accent else colors.textMuted,
                fontSize = colors.fsUi,
            )
        }
    }
}
