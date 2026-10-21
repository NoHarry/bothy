package cc.noharry.bothy.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun DebugBasicToolsPane() {
    val context = LocalContext.current
    var tools by remember { mutableStateOf(loadDebugBasicTools(context)) }
    Column(
        Modifier.widthIn(max = 880.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceMd),
    ) {
        Text("独立于 MCP 和 skill · 自动保存，下次请求生效 · 当前任务不受影响", color = DebugColors.muted)
        AndroidUseRow()
        HorizontalDivider(color = DebugColors.borderSubtle)
        tools.forEach { tool ->
            BasicToolRow(tool) { enabled ->
                saveDebugBasicTool(context, tool.name, enabled)
                tools = loadDebugBasicTools(context)
            }
            HorizontalDivider(color = DebugColors.borderSubtle)
        }
    }
}

/** 操作屏幕: Android Use tools (android_observe / android_action) over the accessibility service. */
@Composable
private fun AndroidUseRow() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(loadDebugAndroidUseEnabled(context)) }
    var status by remember { mutableStateOf(debugAndroidUseStatus(context)) }
    BasicToolRow(
        DebugBasicTool("操作屏幕", "android_observe / android_action：查看屏幕、点击、滑动、输入；没有专用接口时才用（打开应用、回主页也先找接口）。", enabled),
        stateText = status,
    ) { checked ->
        saveDebugAndroidUseEnabled(context, checked)
        enabled = checked
        status = debugAndroidUseStatus(context)
    }
}

@Composable
private fun BasicToolRow(tool: DebugBasicTool, stateText: String? = null, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(DebugDimens.spaceMd), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tool.name, style = DebugType.body, color = DebugColors.text)
            Text(tool.description, style = DebugType.meta, color = DebugColors.muted)
            Text(stateText ?: if (tool.required) "固定启用" else if (tool.enabled) "已启用" else "已关闭", color = DebugColors.muted)
        }
        Switch(
            checked = tool.enabled,
            onCheckedChange = onToggle,
            enabled = !tool.required,
            modifier = Modifier.semantics { contentDescription = "${tool.name} 开关" },
            colors = debugSwitchColors(),
        )
    }
}
