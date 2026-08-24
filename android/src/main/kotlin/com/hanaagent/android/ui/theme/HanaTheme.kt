package com.hanaagent.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanaagent.core.theme.CssColor
import com.hanaagent.core.theme.CssLength
import com.hanaagent.core.theme.ThemeAssets

/**
 * 把上游的设计 token 接到 Compose 上。
 *
 * token 本身是从上游 css 逐字导出的资产（见 [ThemeAssets]），这一层只做两件事：
 * 按当前主题查表，以及把 CSS 的值换算成 Compose 的 [Dp] / [TextUnit] / [Color]。
 *
 * ## 为什么不直接套 MaterialTheme
 *
 * Material 3 的配色角色（primary / surface / onSurface…）和这套设计的 token 不是
 * 一一对应的 —— 硬映射会丢掉像 `--mood-border`、`--user-bg`、`--jian-note-bg` 这种
 * 没有 Material 对应物的语义色，而它们恰恰是这个界面看起来像 HanaAgent 的原因。
 *
 * 所以走 [LocalHanaColors]：完整的 token 表原样可用，同时**也**填一个 MaterialTheme
 * 供 Material 组件（TextField、Ripple 之类）取色，两边指向同一批颜色。
 */
class HanaColors internal constructor(private val tokens: Map<String, String>) {

    /** 按 token 名取色；取不到时用 [fallback]，不静默给黑色。 */
    fun color(token: String, fallback: Color = Color.Unspecified): Color {
        val raw = tokens[token] ?: return fallback
        val argb = CssColor.parse(raw, tokens) ?: return fallback
        return Color(argb)
    }

    fun dp(token: String, fallback: Dp = 0.dp): Dp =
        tokens[token]?.let { CssLength.parse(it) }?.dp ?: fallback

    fun sp(token: String, fallback: TextUnit): TextUnit =
        tokens[token]?.let { CssLength.parse(it) }?.sp ?: fallback

    /** 原始 token 值，给需要自己解释的地方（渐变、纹理）。 */
    fun raw(token: String): String? = tokens[token]

    // ── 常用的几个，省得到处写字符串 ──────────────────────

    val background: Color get() = color("--bg")
    val card: Color get() = color("--bg-card", background)
    val sidebar: Color get() = color("--sidebar-bg", background)
    val text: Color get() = color("--text")
    val textMuted: Color get() = color("--text-muted", text)
    val textLight: Color get() = color("--text-light", textMuted)
    val accent: Color get() = color("--accent")
    val accentHover: Color get() = color("--accent-hover", accent)
    val border: Color get() = color("--border")
    val userBubble: Color get() = color("--user-bg", card)
    val moodBackground: Color get() = color("--mood-bg", card)
    val moodBorder: Color get() = color("--mood-border", border)
    val danger: Color get() = color("--danger")

    /** 当前主题的 `--accent` 原始字符串 —— 交给 YuanVisuals.moodAccent 用。 */
    val accentRaw: String get() = tokens["--accent"].orEmpty()

    // 尺度
    val space4: Dp get() = dp("--space-4", 4.dp)
    val space8: Dp get() = dp("--space-8", 8.dp)
    val space12: Dp get() = dp("--space-12", 12.dp)
    val space16: Dp get() = dp("--space-16", 16.dp)
    val space24: Dp get() = dp("--space-24", 24.dp)
    val radiusSm: Dp get() = dp("--radius-sm", 5.dp)
    val radiusMd: Dp get() = dp("--radius-md", 8.dp)
    val radiusChatSurface: Dp get() = dp("--radius-chat-surface", 16.dp)

    // 字号
    val fsBody: TextUnit get() = sp("--fs-body", 14.4f.sp)
    val fsUi: TextUnit get() = sp("--fs-ui", 13.1f.sp)
    val fsCaption: TextUnit get() = sp("--fs-caption", 12.5f.sp)
}

val LocalHanaColors: ProvidableCompositionLocal<HanaColors> = compositionLocalOf {
    error("HanaColors 还没提供 —— 界面必须包在 HanaTheme { } 里")
}

/** 当前主题 id，供需要按源/主题分支的地方读取。 */
val LocalThemeId: ProvidableCompositionLocal<String> = compositionLocalOf {
    ThemeAssets.defaultTheme
}

/**
 * @param themeId 主题 id；传 null 表示**跟随系统** —— 浅色用
 *   [ThemeAssets.autoLightDefault]（暖纸），深色用 [ThemeAssets.autoDarkDefault]（midnight），
 *   与上游的 auto 行为一致。
 */
@Composable
fun HanaTheme(
    themeId: String? = null,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val resolvedId = themeId
        ?: if (systemDark) ThemeAssets.autoDarkDefault else ThemeAssets.autoLightDefault

    // 认不出的 id 回落到默认主题而不是崩掉：主题是从设置里读的，
    // 换版本删掉某套主题时不该让应用打不开
    val theme = remember(resolvedId) {
        ThemeAssets.theme(resolvedId) ?: ThemeAssets.theme(ThemeAssets.defaultTheme)!!
    }
    val colors = remember(theme) { HanaColors(theme.tokens) }

    // Material 组件（TextField、Ripple…）也要能取到对的颜色，
    // 所以两套并存，指向同一批 token
    val scheme = remember(colors, theme.dark) {
        val base = if (theme.dark) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = colors.accent,
            onPrimary = colors.background,
            background = colors.background,
            onBackground = colors.text,
            surface = colors.card,
            onSurface = colors.text,
            surfaceVariant = colors.sidebar,
            onSurfaceVariant = colors.textMuted,
            outline = colors.border,
            error = colors.danger,
        )
    }

    CompositionLocalProvider(
        LocalHanaColors provides colors,
        LocalThemeId provides theme.id,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
