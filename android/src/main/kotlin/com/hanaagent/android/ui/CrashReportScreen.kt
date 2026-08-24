package com.hanaagent.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 崩溃上报页。
 *
 * 刻意**不用** HanaTheme、不读 ThemeAssets、不碰 :core 的任何东西 —— 只用 Compose 自带的
 * MaterialTheme 默认配色。理由是：这个页面存在的意义就是显示"某个东西崩了"，
 * 而崩掉的很可能正是主题加载或资产读取本身。上报路径依赖了可能出故障的部件，
 * 就会在最需要它的时候一起哑掉。
 *
 * 同理它由 MainActivity 在**进入应用主体之前**判断显示，不挂在聊天页里面 ——
 * 启动路径上的崩溃根本走不到聊天页。
 */
@Composable
fun CrashReportScreen(
    trace: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(16.dp),
            ) {
                Text("上次是崩溃退出的", style = MaterialTheme.typography.titleMedium)
                Text(
                    "把下面这段完整发出去就能定位。最要紧的是 Caused by: 那一行。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Row(modifier = Modifier.padding(vertical = 12.dp)) {
                    Button(onClick = {
                        clipboard.setText(AnnotatedString(trace))
                        copied = true
                    }) {
                        Text(if (copied) "已复制" else "复制全部")
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text("清除并继续")
                    }
                }

                Text(
                    text = trace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}
