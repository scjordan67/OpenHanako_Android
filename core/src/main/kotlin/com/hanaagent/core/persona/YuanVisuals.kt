package com.hanaagent.core.persona

/**
 * 每个「源」的视觉标识 —— 上游 `shared/yuan-visuals.ts` 的移植。
 *
 * 内省块的标题就是这里的 `符号 + 标签`：hanako 是「✿ MOOD」，butter 是「❊ PULSE」，
 * ming 是「◈ REFLECT」。这三组值与人格模板里规定的标签一一对应
 * （[com.hanaagent.core.mood.InternalMoodBlock.TAGS]），改一边不改另一边，
 * 界面上就会出现"标题写着 MOOD、解析器认的却是 pulse"这种对不上的情况。
 */
object YuanVisuals {

    /** 认不出的源一律回落到 hanako —— 与上游 `FALLBACK_YUAN` 一致。 */
    const val FALLBACK_YUAN = "hanako"

    /**
     * @param accent 该源的固有强调色。
     *   注意 hanako 在界面上**不用**这个值：上游的
     *   `.moodWrapper[data-yuan="hanako"] { --mood-accent: var(--accent) }`
     *   让它跟随当前主题的 `--accent`，只有 butter 和 ming 用固定色。
     *   见 [moodAccent]。
     */
    data class Visual(
        val yuan: String,
        val symbol: String,
        val moodLabel: String,
        val accent: String,
        val avatar: String,
    )

    val ALL: Map<String, Visual> = linkedMapOf(
        "hanako" to Visual("hanako", "✿", "MOOD", "#537D96", "Hanako.png"),
        "butter" to Visual("butter", "❊", "PULSE", "#5BA88C", "Butter.png"),
        "ming" to Visual("ming", "◈", "REFLECT", "#8BA4B4", "Ming.png"),
    )

    /** 归一化源 id：去空白、转小写、认不出就回落。 */
    fun normalize(yuan: String?): String {
        val key = yuan.orEmpty().trim().lowercase()
        return if (key in ALL) key else FALLBACK_YUAN
    }

    fun of(yuan: String?): Visual = ALL.getValue(normalize(yuan))

    /** 内省块的标题，例如「✿ MOOD」。 */
    fun moodLabel(yuan: String?): String = of(yuan).let { "${it.symbol} ${it.moodLabel}" }

    /**
     * 内省块该用的强调色。
     *
     * hanako 跟随主题的 `--accent`（所以换主题时它的内省块颜色也跟着变），
     * butter 与 ming 用各自的固定色。这是上游 `Chat.module.css` 里那三条
     * `.moodWrapper[data-yuan=…]` 规则的等价物。
     *
     * @param themeAccent 当前主题的 `--accent` 值
     * @return CSS 颜色字符串，交给 [com.hanaagent.core.theme.CssColor] 解析
     */
    fun moodAccent(yuan: String?, themeAccent: String): String =
        if (normalize(yuan) == "hanako") themeAccent else of(yuan).accent
}
