package com.hanaagent.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.hanaagent.android.data.AppSettings
import com.hanaagent.android.ui.theme.LocalHanaColors
import com.hanaagent.core.llm.EndpointConfig
import com.hanaagent.core.persona.YuanVisuals
import com.hanaagent.core.theme.CssColor
import com.hanaagent.core.theme.ThemeAssets

/**
 * 设置页。
 *
 * 接口地址那一栏底下实时显示**拼出来的完整请求地址** —— 这是刻意的：填错了
 * 只会收到 404，而 404 的提示看不出是多了还是少了一个路径段。把最终地址摆出来，
 * 用户自己一眼就能对照文档看出问题。
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    val colors = LocalHanaColors.current

    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var baseUrl by remember { mutableStateOf(settings.baseUrlInput) }
    var model by remember { mutableStateOf(settings.model) }
    var userName by remember { mutableStateOf(settings.userName) }
    var themeId by remember { mutableStateOf(settings.themeId) }
    var yuan by remember { mutableStateOf(settings.yuan) }
    var revealKey by remember { mutableStateOf(false) }

    fun persist() {
        settings.apiKey = apiKey
        settings.baseUrlInput = baseUrl
        settings.model = model
        settings.userName = userName
        settings.themeId = themeId
        settings.yuan = yuan
        onChanged()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(colors.space16),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("设置", color = colors.text, fontSize = colors.fsBody)
            TextButton(onClick = { persist(); onBack() }) {
                Text("完成", color = colors.accent, fontSize = colors.fsUi)
            }
        }

        Field(label = "接口地址", value = baseUrl, onChange = { baseUrl = it }, keyboard = KeyboardType.Uri) {
            val resolved = EndpointConfig.resolve(baseUrl, settings.apiOverride)
            Text(
                text = resolved?.let { "实际请求：${it.fullUrl}\n形态：${it.api.id}" }
                    ?: "还看不出是个网址",
                color = if (resolved == null) colors.danger else colors.textMuted,
                fontSize = colors.sp("--fs-hint", colors.fsCaption),
                modifier = Modifier.padding(top = colors.space4),
            )
        }

        Field(
            label = "API key",
            value = apiKey,
            onChange = { apiKey = it },
            visual = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
        ) {
            TextButton(onClick = { revealKey = !revealKey }) {
                Text(
                    if (revealKey) "隐藏" else "显示",
                    color = colors.accent,
                    fontSize = colors.sp("--fs-hint", colors.fsCaption),
                )
            }
        }

        Field(label = "模型名", value = model, onChange = { model = it })
        Field(label = "你的称呼", value = userName, onChange = { userName = it }) {
            Text(
                "留空的话她会用中性称呼",
                color = colors.textMuted,
                fontSize = colors.sp("--fs-hint", colors.fsCaption),
                modifier = Modifier.padding(top = colors.space4),
            )
        }

        Text(
            "源",
            color = colors.textMuted,
            fontSize = colors.fsCaption,
            modifier = Modifier.padding(top = colors.space16),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(colors.space8)) {
            // 可选的源集中定义在 AppSettings —— 过滤规则写两遍迟早会漂移
            for (id in AppSettings.SELECTABLE_YUAN) {
                val selected = id == yuan
                Text(
                    text = YuanVisuals.moodLabel(id),
                    color = if (selected) colors.accent else colors.textMuted,
                    fontSize = colors.fsUi,
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(colors.radiusSm))
                        .border(
                            width = 1.dp,
                            color = if (selected) colors.accent else colors.border,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(colors.radiusSm),
                        )
                        .clickable { yuan = id }
                        .padding(horizontal = colors.space12, vertical = colors.space8),
                )
            }
        }

        Text(
            "主题",
            color = colors.textMuted,
            fontSize = colors.fsCaption,
            modifier = Modifier.padding(top = colors.space16),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(top = colors.space8),
            horizontalArrangement = Arrangement.spacedBy(colors.space8),
        ) {
            items(ThemeAssets.themeIds) { id ->
                val theme = ThemeAssets.theme(id)!!
                val swatch = Color(CssColor.parse(theme.backgroundColor) ?: 0)
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(swatch)
                        .border(
                            width = if (id == themeId) 2.dp else 1.dp,
                            color = if (id == themeId) colors.accent else colors.border,
                            shape = CircleShape,
                        )
                        .clickable { themeId = id; persist() },
                )
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    visual: VisualTransformation = VisualTransformation.None,
    keyboard: KeyboardType = KeyboardType.Text,
    below: (@Composable () -> Unit)? = null,
) {
    val colors = LocalHanaColors.current
    Column(modifier = Modifier.padding(top = colors.space16)) {
        Text(label, color = colors.textMuted, fontSize = colors.fsCaption)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            visualTransformation = visual,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.fillMaxWidth().padding(top = colors.space4),
        )
        below?.invoke()
    }
}
