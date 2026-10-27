package cc.noharry.bothy.app.skills
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.SettingsGroup
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.switchColors
import cc.noharry.bothy.app.console.ui.Markdown

/**
 * A subagent type's page (owner 2026-10-18: 把它可用的工具也标出来，点击一个子智能体能进他的详情页): whether it is
 * offered, what the main agent reads about it, its model and search, the tools it may call, its share of pages and
 * searches, and its instructions. Tools and share apply from its next spawn; the instructions ship in the APK.
 */
@Composable
internal fun SubagentKindPage(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by BothySubagents.settings.collectAsState()
    var kind by remember(id) { mutableStateOf(loadSubagentKinds(context).first { it.id == id }) }
    val reload = { kind = loadSubagentKinds(context).first { it.id == id } }
    val save = { tools: Set<SubagentToolGroup>, lookups: Int ->
        saveSubagentKindTools(context, id, tools, lookups)
        reload()
    }
    BackHandler(onBack = onBack)
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceLg)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
            Text("‹ 子 agent", Modifier.clickable(onClick = onBack).padding(vertical = Dimens.spaceXs), style = ConsoleType.body, color = ConsoleColors.accent)
            Text("›", style = ConsoleType.body, color = ConsoleColors.muted)
            Text(kind.label, style = ConsoleType.body, color = ConsoleColors.text)
        }
        KindBasics(kind, settings) {
            saveSubagentKind(context, id, it)
            reload()
        }
        KindTools(kind, settings.allowDeviceControl) { tools -> save(tools, kind.maxLookups) }
        KindLookups(kind) { lookups -> save(kind.tools, lookups) }
        val instructions = remember(id) { subagentKindText(context, kind) }
        SettingsGroup(title = "提示词", subtitle = "runtime-workspace/agents/${kind.id}/AGENT.md，随 APK 打包，这里只读。") {
            Markdown(instructions, Modifier.padding(Dimens.spaceMd))
        }
        val defaults = subagentKindDefaults(id)
        if (defaults != null && (defaults.tools != kind.tools || defaults.maxLookups != kind.maxLookups)) {
            TextButton(onClick = {
                resetSubagentKindTools(context, id)
                reload()
            }) {
                Text("恢复默认工具和次数（${defaults.toolLabels()} · 最多 ${defaults.maxLookups} 次）", style = ConsoleType.body, color = ConsoleColors.accent)
            }
        }
    }
}

/** Whether it is offered, what the main agent reads about it, and the model and search it follows. */
@Composable
private fun KindBasics(kind: SubagentKind, settings: SubagentSettings, onEnabled: (Boolean) -> Unit) {
    SettingsGroup(title = kind.label, subtitle = kind.id) {
        Column {
            Row(Modifier.fillMaxWidth().padding(Dimens.spaceSm), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("加载这个类型", style = ConsoleType.body, color = ConsoleColors.text)
                    Text(
                        if (kind.enabled) "开：主 agent 可以在 spawn_agent 里写 agent: ${kind.id}" else "关：不出现在 spawn_agent 里，技能把要求写全交给通用子 agent",
                        style = ConsoleType.meta,
                        color = ConsoleColors.muted,
                    )
                }
                Switch(kind.enabled, onCheckedChange = onEnabled, colors = switchColors())
            }
            KindInfoRow("给主 agent 看的说明", kind.description)
            KindInfoRow("模型", settings.model.ifBlank { "跟主 agent 一样" } + "（在「子 agent」里设置）")
            KindInfoRow(
                "搜索",
                when {
                    !kind.searches -> "不搜索：没勾「联网搜索」"
                    kind.searchApi ?: settings.searchApi -> "DeepSeek Search 接口（在「子 agent」里设置）"
                    else -> "和主 agent 一样（在「子 agent」里设置）"
                },
            )
        }
    }
}

/** The tool groups it may call, then what only the main agent has, greyed. */
@Composable
private fun KindTools(kind: SubagentKind, deviceControlAllowed: Boolean, onChange: (Set<SubagentToolGroup>) -> Unit) {
    SettingsGroup(
        title = "可用工具",
        subtitle = "只能调用勾上的，别的调用会被拒绝；工具列表不变，和主 agent 共用缓存。红点表示会改动东西。",
    ) {
        Column {
            KindToolGrid(SubagentToolGroup.entries) { group ->
                KindToolCell(
                    label = group.label,
                    detail = group.detail,
                    checked = group in kind.tools,
                    changes = group.changes,
                    note = "「允许子 agent 操作设备」关着，暂不生效".takeIf {
                        group == SubagentToolGroup.DEVICE_CONTROL && group in kind.tools && !deviceControlAllowed
                    },
                ) { on -> onChange(if (on) kind.tools + group else kind.tools - group) }
            }
            HorizontalDivider(color = ConsoleColors.borderSubtle)
            Text(
                "只有主 agent 能用",
                Modifier.padding(start = Dimens.spaceMd, top = Dimens.spaceSm),
                style = ConsoleType.meta,
                color = ConsoleColors.muted,
            )
            KindToolGrid(DEBUG_MAIN_AGENT_ONLY_TOOLS) { (label, detail) ->
                KindToolCell(label = label, detail = detail, checked = false, enabled = false)
            }
        }
    }
}

@Composable
private fun KindLookups(kind: SubagentKind, onChange: (Int) -> Unit) {
    val looksUp = kind.tools.any { it == SubagentToolGroup.SEARCH || it == SubagentToolGroup.FETCH }
    SettingsGroup(title = "打开网页和搜索", subtitle = "这个类型每次最多查几次，到了就用已有结果交回。") {
        Row(Modifier.fillMaxWidth().padding(Dimens.spaceSm), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (looksUp) "最多 ${kind.maxLookups} 次" else "没勾「联网搜索」和「打开网页」，这一项不起作用",
                Modifier.weight(1f),
                style = ConsoleType.body,
                color = if (looksUp) ConsoleColors.text else ConsoleColors.muted,
            )
            TextButton(onClick = { onChange(kind.maxLookups - 1) }, enabled = looksUp && kind.maxLookups > MIN_LOOKUPS) {
                Text("−", style = ConsoleType.panelTitle)
            }
            TextButton(onClick = { onChange(kind.maxLookups + 1) }, enabled = looksUp && kind.maxLookups < MAX_LOOKUPS) {
                Text("+", style = ConsoleType.panelTitle)
            }
        }
    }
}

@Composable
private fun KindInfoRow(title: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(Dimens.spaceSm)) {
        Text(title, style = ConsoleType.meta, color = ConsoleColors.muted)
        Text(value, style = ConsoleType.bodySmall, color = ConsoleColors.text)
    }
}

/** Three across on the device's screen, fewer when narrow. */
@Composable
private fun <T> KindToolGrid(items: List<T>, cell: @Composable (T) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(Dimens.spaceXs)) {
        val columns = when {
            maxWidth >= 640.dp -> 3
            maxWidth >= 400.dp -> 2
            else -> 1
        }
        Column {
            items.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { item -> Column(Modifier.weight(1f)) { cell(item) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun KindToolCell(
    label: String,
    detail: String,
    checked: Boolean,
    changes: Boolean = false,
    enabled: Boolean = true,
    note: String? = null,
    onChange: (Boolean) -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(Dimens.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled, colors = checkboxColors())
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
                Text(label, style = ConsoleType.body, color = if (enabled) ConsoleColors.text else ConsoleColors.muted)
                if (changes) ChangesDot()
            }
            Text(detail, style = ConsoleType.meta, color = ConsoleColors.muted)
            note?.let { Text(it, style = ConsoleType.meta, color = ConsoleColors.warning) }
        }
    }
}

@Composable
private fun checkboxColors(): CheckboxColors = CheckboxDefaults.colors(
    checkedColor = ConsoleColors.accent,
    uncheckedColor = ConsoleColors.muted,
    checkmarkColor = ConsoleColors.bg,
    disabledCheckedColor = ConsoleColors.border,
    disabledUncheckedColor = ConsoleColors.border,
)
