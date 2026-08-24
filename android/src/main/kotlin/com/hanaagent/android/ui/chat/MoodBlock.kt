package com.hanaagent.android.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.hanaagent.android.ui.theme.LocalHanaColors
import com.hanaagent.core.persona.YuanVisuals
import com.hanaagent.core.theme.CssColor

/**
 * 内省块 —— 上游 `MoodBlock.tsx` + `Chat.module.css` 的移植。
 *
 * 模型被人格要求在开口前先写一段内心独白，[com.hanaagent.core.mood.MoodParser]
 * 把它从正文里切出来，这里负责单独排版。它**不是**对话的一部分，视觉上要明显退后：
 * 斜体、降透明度、左侧一道细强调线、默认折叠。
 *
 * 逐条对着上游的样式：
 *
 * | 上游 | 这里 |
 * |---|---|
 * | `useState(false)` | 默认折叠 |
 * | `.moodSummary { opacity: .6; font-style: italic }` | 标题行 60% 透明 + 斜体 |
 * | `.moodArrow` → `.moodArrowOpen { rotate(90deg) }` | `›` 展开时转 90° |
 * | `.moodBlock { border-left: 2px solid var(--mood-accent); opacity: .7 }` | 左侧 2dp 强调线 + 70% 透明 |
 * | `.moodWrapper[data-yuan=…]` | [YuanVisuals.moodAccent] |
 *
 * 标题文字是「✿ MOOD」/「❊ PULSE」/「◈ REFLECT」，由源决定；hanako 的强调色跟随
 * 当前主题，另外两个用固定色。
 */
@Composable
fun MoodBlock(
    yuan: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return

    val colors = LocalHanaColors.current
    // 上游默认是收起的：内心独白是"想看才看"的东西，默认展开会把正文挤下去
    var open by remember { mutableStateOf(false) }

    val accent = remember(yuan, colors.accentRaw) {
        CssColor.parse(YuanVisuals.moodAccent(yuan, colors.accentRaw))
            ?.let { Color(it) }
            ?: colors.accent
    }
    val arrowRotation by animateFloatAsState(
        targetValue = if (open) 90f else 0f,
        label = "moodArrow",
    )

    Column(modifier = modifier.padding(vertical = colors.space4)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { open = !open }
                .alpha(0.6f),
        ) {
            Text(
                text = "›",
                color = accent,
                fontSize = colors.fsCaption,
                modifier = Modifier.rotate(arrowRotation),
            )
            Text(
                text = " " + YuanVisuals.moodLabel(yuan),
                color = accent,
                fontSize = colors.fsUi,
                fontStyle = FontStyle.Italic,
            )
        }

        AnimatedVisibility(
            visible = open,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            // height(IntrinsicSize.Min) 是必须的：Row 的高度由文字撑开，
            // 直接给左边那道线 fillMaxHeight() 时它拿到的最大高度约束是无穷，
            // 结果是高度 0 —— 线看不见，而且不会报任何错。
            Row(
                modifier = Modifier
                    .padding(top = colors.space4)
                    .height(IntrinsicSize.Min),
            ) {
                // border-left: 2px solid var(--mood-accent)
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(accent),
                )
                Text(
                    text = text,
                    color = colors.textMuted,
                    fontSize = colors.fsCaption,
                    fontStyle = FontStyle.Italic,
                    lineHeight = colors.fsCaption * 1.6f,
                    modifier = Modifier
                        .alpha(0.7f)
                        .padding(horizontal = colors.space16, vertical = colors.space8),
                )
            }
        }
    }
}
