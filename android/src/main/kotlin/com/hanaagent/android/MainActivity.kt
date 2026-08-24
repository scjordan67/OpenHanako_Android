package com.hanaagent.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hanaagent.android.data.AppSettings
import com.hanaagent.android.data.SessionStore
import com.hanaagent.android.ui.chat.ChatScreen
import com.hanaagent.android.ui.chat.ChatState
import com.hanaagent.android.ui.settings.SettingsScreen
import com.hanaagent.android.ui.theme.HanaTheme
import com.hanaagent.android.ui.theme.LocalHanaColors

private enum class Screen { CHAT, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HanaApp(AppSettings(applicationContext)) }
    }
}

@Composable
private fun HanaApp(settings: AppSettings) {
    // 主题改了要立刻生效，所以单独拿一个 state 托着，而不是每次去读 prefs
    var themeId by remember { mutableStateOf(settings.themeId) }
    var screen by remember { mutableStateOf(Screen.CHAT) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val state = remember {
        ChatState(settings, SessionStore(context))
    }
    // restore() 是 suspend 的：读会话文件与投影分支放在 IO 线程，别卡住首帧
    LaunchedEffect(Unit) { state.restore() }

    HanaTheme(themeId = themeId) {
        val colors = LocalHanaColors.current
        Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
            when (screen) {
                Screen.CHAT -> ChatScreen(
                    state = state,
                    onOpenSettings = { screen = Screen.SETTINGS },
                )

                Screen.SETTINGS -> {
                    BackHandler { screen = Screen.CHAT }
                    SettingsScreen(
                        settings = settings,
                        onBack = { screen = Screen.CHAT },
                        onChanged = {
                            themeId = settings.themeId
                            state.refreshBlocker()
                        },
                    )
                }
            }
        }
    }
}
