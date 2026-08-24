package com.hanaagent.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.hanaagent.android.ui.theme.LocalHanaColors

/**
 * 一条消息。
 *
 * 用户与 Agent 的排版是不对称的，这是照着上游来的：用户的话装在气泡里靠右，
 * Agent 的话不装气泡、平铺在左侧。理由是 Agent 的回复通常长得多，套气泡会把整屏
 * 挤成一条窄柱；而且"平铺"读起来更像在看一封信而不是在看聊天记录 —— 那正是
 * HanaAgent 想要的感觉。
 */
@Composable
fun UserMessage(text: String, modifier: Modifier = Modifier) {
    val colors = LocalHanaColors.current
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = colors.space4),
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            text = text,
            color = colors.text,
            fontSize = colors.fsBody,
            lineHeight = colors.fsBody * 1.6f,
            modifier = Modifier
                // 留出左边距，长消息也不会顶满整行
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(colors.radiusChatSurface))
                .background(colors.userBubble)
                .padding(horizontal = colors.space16, vertical = colors.space12),
        )
    }
}

/**
 * Agent 的一条回复：内省块在上，正文在下。
 *
 * 顺序是有讲究的 —— 模型是先写内心独白再开口的，界面按同样的顺序呈现，
 * 展开内省块时读起来才是"她先这么想，然后这么说"。
 */
@Composable
fun AssistantMessage(
    yuan: String,
    mood: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHanaColors.current
    Column(modifier = modifier.fillMaxWidth().padding(vertical = colors.space4)) {
        MoodBlock(yuan = yuan, text = mood)
        if (body.isNotBlank()) {
            Text(
                text = body,
                color = colors.text,
                fontSize = colors.fsBody,
                lineHeight = colors.fsBody * 1.75f,
                modifier = Modifier.padding(vertical = colors.space4),
            )
        }
    }
}
