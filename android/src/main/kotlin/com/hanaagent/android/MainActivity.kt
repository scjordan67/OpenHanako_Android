package com.hanaagent.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hanaagent.android.memory.Fts5Probe
import com.hanaagent.core.persona.PersonaAssets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stage 0 的落地页 —— 不是产品界面，是**诊断页**。
 *
 * 它做两件事：证明 `:core` 的资产在设备上读得出来，以及把 [Fts5Probe] 的结果
 * 显示出来。Spike A 留下的那个问题（系统 SQLite 到底有没有 FTS5）需要在真机上
 * 回答，而这是最省事的回答方式：装上、打开、看一眼。
 *
 * Stage 1 会把这里换成真正的聊天界面。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DiagnosticsScreen()
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsScreen() {
    var probeSummary by remember { mutableStateOf("正在探测 SQLite / FTS5…") }

    LaunchedEffect(Unit) {
        probeSummary = withContext(Dispatchers.IO) { Fts5Probe.run().summary() }
    }

    val personaLines = remember {
        val yuan = PersonaAssets.read(PersonaAssets.Layer.YUAN, PersonaAssets.DEFAULT_YUAN)
        buildString {
            appendLine("默认源: ${PersonaAssets.DEFAULT_YUAN}")
            appendLine("内置源: ${PersonaAssets.BUILT_IN_YUAN.joinToString(" / ")}")
            appendLine("打包资产: ${PersonaAssets.listAll().size} 份")
            appendLine("源模板长度: ${yuan?.length ?: 0} 字符")
            appendLine("MOOD 协议: " + if (yuan?.contains("<mood>") == true) "在" else "缺失")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("HanaAgent · Stage 0 诊断", style = MaterialTheme.typography.titleLarge)
        Text("\n人格资产", style = MaterialTheme.typography.titleMedium)
        Text(personaLines, style = MaterialTheme.typography.bodyMedium)
        Text("\nSQLite / FTS5 探针", style = MaterialTheme.typography.titleMedium)
        Text(probeSummary, style = MaterialTheme.typography.bodyMedium)
    }
}
