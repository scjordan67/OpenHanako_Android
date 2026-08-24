package com.hanaagent.android.data

import android.content.Context
import com.hanaagent.core.llm.ChatPayload
import com.hanaagent.core.llm.EndpointConfig
import com.hanaagent.core.persona.PersonaAssets
import com.hanaagent.core.theme.ThemeAssets

/**
 * 设置的落盘。
 *
 * 用 SharedPreferences 而不是 DataStore：一共七个字段，为此多拉一个依赖不划算。
 *
 * ## 关于 API key 的存放
 *
 * key 是明文存在应用私有目录里的。在没 root 的设备上别的应用读不到它，这与绝大多数
 * 同类应用的做法一致。**没有**额外加密 —— EncryptedSharedPreferences 那套需要
 * 引入 androidx.security（近年基本停更），而且密钥仍然在同一台设备的 Keystore 里，
 * 对"设备已被物理接管"这个威胁模型并没有实质帮助。
 *
 * 如果以后要连记忆库一起加密，那是一个单独的决定（密钥放哪、要不要设开机口令），
 * 到时候一并处理，不在这里偷偷做一半。
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("hana-settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    /** 用户填的原文，不是整理后的 —— 设置页要把用户填的样子原样显示回去。 */
    var baseUrlInput: String
        get() = prefs.getString(KEY_BASE_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    /** null 表示按主机名推断，见 [EndpointConfig]。 */
    var apiOverride: ChatPayload.Api?
        get() = prefs.getString(KEY_API_SHAPE, null)?.let { name ->
            ChatPayload.Api.entries.firstOrNull { it.name == name }
        }
        set(value) = prefs.edit().putString(KEY_API_SHAPE, value?.name).apply()

    var themeId: String
        get() = prefs.getString(KEY_THEME, ThemeAssets.defaultTheme).orEmpty()
            .takeIf { it in ThemeAssets.themeIds } ?: ThemeAssets.defaultTheme
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    var yuan: String
        get() = prefs.getString(KEY_YUAN, PersonaAssets.DEFAULT_YUAN).orEmpty()
            .takeIf { it in PersonaAssets.BUILT_IN_YUAN } ?: PersonaAssets.DEFAULT_YUAN
        set(value) = prefs.edit().putString(KEY_YUAN, value).apply()

    var userName: String
        get() = prefs.getString(KEY_USER_NAME, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()

    /** 配置齐了才能发请求；缺什么返回人话，都齐返回 null。 */
    fun missingPiece(): String? = EndpointConfig.validate(baseUrlInput, apiKey, model)

    /** 整理后的端点；配置不全时为 null。 */
    fun endpoint(): EndpointConfig.Resolved? =
        if (missingPiece() != null) null else EndpointConfig.resolve(baseUrlInput, apiOverride)

    private companion object {
        const val KEY_API_KEY = "apiKey"
        const val KEY_BASE_URL = "baseUrl"
        const val KEY_MODEL = "model"
        const val KEY_API_SHAPE = "apiShape"
        const val KEY_THEME = "theme"
        const val KEY_YUAN = "yuan"
        const val KEY_USER_NAME = "userName"
    }
}
