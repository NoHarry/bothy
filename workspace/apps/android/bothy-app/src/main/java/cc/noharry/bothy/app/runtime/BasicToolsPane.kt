package cc.noharry.bothy.app.runtime
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
import cc.noharry.bothy.app.androiduse.androidUseStatus
import cc.noharry.bothy.app.androiduse.loadAndroidUseEnabled
import cc.noharry.bothy.app.androiduse.saveAndroidUseEnabled
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.console.switchColors

@Composable
internal fun BasicToolsPane() {
    val context = LocalContext.current
    var tools by remember { mutableStateOf(loadBasicTools(context)) }
    Column(
        Modifier.widthIn(max = 880.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
    ) {
        Text("独立于 MCP 和 skill · 自动保存，下次请求生效 · 当前任务不受影响", color = ConsoleColors.muted)
        AndroidUseRow()
        HorizontalDivider(color = ConsoleColors.borderSubtle)
        tools.forEach { tool ->
            BasicToolRow(tool) { enabled ->
                saveBasicTool(context, tool.name, enabled)
                tools = loadBasicTools(context)
            }
            HorizontalDivider(color = ConsoleColors.borderSubtle)
        }
    }
}

/** 操作屏幕: Android Use tools (android_observe / android_action) over the accessibility service. */
@Composable
private fun AndroidUseRow() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(loadAndroidUseEnabled(context)) }
    var status by remember { mutableStateOf(androidUseStatus(context)) }
    BasicToolRow(
        BasicTool("操作屏幕", "android_observe / android_action：查看屏幕、点击、滑动、输入；没有专用接口时才用（打开应用、回主页也先找接口）。", enabled),
        stateText = status,
    ) { checked ->
        saveAndroidUseEnabled(context, checked)
        enabled = checked
        status = androidUseStatus(context)
    }
}

@Composable
private fun BasicToolRow(tool: BasicTool, stateText: String? = null, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(Dimens.spaceMd), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tool.name, style = ConsoleType.body, color = ConsoleColors.text)
            Text(tool.description, style = ConsoleType.meta, color = ConsoleColors.muted)
            Text(stateText ?: if (tool.required) "固定启用" else if (tool.enabled) "已启用" else "已关闭", color = ConsoleColors.muted)
        }
        Switch(
            checked = tool.enabled,
            onCheckedChange = onToggle,
            enabled = !tool.required,
            modifier = Modifier.semantics { contentDescription = "${tool.name} 开关" },
            colors = switchColors(),
        )
    }
}
