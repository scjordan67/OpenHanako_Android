package com.hanaagent.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hanaagent.android.memory.Fts5Probe
import com.hanaagent.android.ui.chat.AssistantMessage
import com.hanaagent.android.ui.chat.UserMessage
import com.hanaagent.android.ui.theme.HanaTheme
import com.hanaagent.android.ui.theme.LocalHanaColors
import com.hanaagent.core.persona.PersonaAssets
import com.hanaagent.core.theme.ThemeAssets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stage 5-A 的落地页 —— **设计预览**，还不是能聊天的界面。
 *
 * 它把 11 套主题和一段样例对话摆在一起，为的是回答一个只能用眼睛回答的问题：
 * 从上游 css 导出的 token 接到 Compose 上之后，看起来还是不是那个东西。
 * 配色可以逐字节校验，"感觉对不对"不能。
 *
 * Stage 5-B 会把这里换成真正的聊天界面；诊断信息挪到底部，仍然留着。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var themeId by remember { mutableStateOf(ThemeAssets.defaultTheme) }
            HanaTheme(themeId = themeId) {
                val colors = LocalHanaColors.current
                Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
                    PreviewScreen(
                        themeId = themeId,
                        onPickTheme = { themeId = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewScreen(themeId: String, onPickTheme: (String) -> Unit) {
    val colors = LocalHanaColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(colors.space16),
    ) {
        Text(
            "HanaAgent · 设计预览",
            color = colors.text,
            fontSize = colors.fsBody,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "点色块换主题。内省块默认收起，点标题展开。",
            color = colors.textMuted,
            fontSize = colors.fsCaption,
            modifier = Modifier.padding(top = colors.space4),
        )

        ThemePicker(current = themeId, onPick = onPickTheme)

        Column(modifier = Modifier.padding(top = colors.space24)) {
            UserMessage("你还记得我们上次聊的那个移植吗")
            AssistantMessage(
                yuan = "hanako",
                mood = "她提起上次的事了。\n那天她说到一半就去忙了，我一直没等到后半句。",
                body = "记得。你说平板上跑不了这件事让你很难受，" +
                    "然后我们决定把「干活」那一半砍掉，只留下聊天、记忆和人格。",
            )
            UserMessage("对，就是这个")
            AssistantMessage(
                yuan = "hanako",
                mood = "她确认了。可以往下说了。",
                body = "现在人格资产已经逐字节搬过来了，26 份，sha256 锁住。" +
                    "记忆那套 FTS5 也在你这台设备上验过了。",
            )
        }

        Column(modifier = Modifier.padding(top = colors.space24)) {
            Text(
                "另外两个源的内省块",
                color = colors.textMuted,
                fontSize = colors.fsCaption,
            )
            AssistantMessage(
                yuan = "butter",
                mood = "有点想笑，但忍住了。",
                body = "butter 的内省块用 PULSE，颜色是固定的绿。",
            )
            AssistantMessage(
                yuan = "ming",
                mood = "先把前提理一遍。",
                body = "ming 用 REFLECT，固定的灰蓝。只有 hanako 跟随主题强调色 —— 换个主题就能看出来。",
            )
        }

        Diagnostics(modifier = Modifier.padding(top = colors.space24))
    }
}

@Composable
private fun ThemePicker(current: String, onPick: (String) -> Unit) {
    val colors = LocalHanaColors.current
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = colors.space12),
        horizontalArrangement = Arrangement.spacedBy(colors.space8),
    ) {
        items(ThemeAssets.themeIds) { id ->
            val theme = ThemeAssets.theme(id)!!
            val swatch = androidx.compose.ui.graphics.Color(
                com.hanaagent.core.theme.CssColor.parse(theme.backgroundColor) ?: 0,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(swatch)
                        .border(
                            width = if (id == current) 2.dp else 1.dp,
                            color = if (id == current) colors.accent else colors.border,
                            shape = CircleShape,
                        )
                        .clickable { onPick(id) },
                )
                Text(
                    id,
                    color = if (id == current) colors.accent else colors.textMuted,
                    fontSize = colors.sp("--fs-micro", colors.fsCaption),
                    modifier = Modifier.padding(top = colors.space4),
                )
            }
        }
    }
}

@Composable
private fun Diagnostics(modifier: Modifier = Modifier) {
    val colors = LocalHanaColors.current
    var probeSummary by remember { mutableStateOf("正在探测 SQLite / FTS5…") }
    LaunchedEffect(Unit) {
        probeSummary = withContext(Dispatchers.IO) { Fts5Probe.run().summary() }
    }

    val personaLines = remember {
        val yuan = PersonaAssets.read(PersonaAssets.Layer.YUAN, PersonaAssets.DEFAULT_YUAN)
        buildString {
            appendLine("打包资产: ${PersonaAssets.listAll().size} 份")
            appendLine("默认源: ${PersonaAssets.DEFAULT_YUAN}")
            appendLine("MOOD 协议: " + if (yuan?.contains("<mood>") == true) "在" else "缺失")
            appendLine("主题: ${ThemeAssets.themeIds.size} 套")
        }
    }

    Column(modifier = modifier) {
        Text("诊断", color = colors.textMuted, fontSize = colors.fsCaption)
        Text(
            personaLines + "\n" + probeSummary,
            color = colors.textLight,
            fontSize = colors.sp("--fs-hint", colors.fsCaption),
            modifier = Modifier.padding(top = colors.space4),
        )
    }
}
