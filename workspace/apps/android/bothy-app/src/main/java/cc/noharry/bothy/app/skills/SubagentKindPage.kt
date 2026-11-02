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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.R
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
            Text(
                stringResource(R.string.skills_back_to_subagents),
                Modifier.clickable(onClick = onBack).padding(vertical = Dimens.spaceXs),
                style = ConsoleType.body,
                color = ConsoleColors.accent,
            )
            Text("›", style = ConsoleType.body, color = ConsoleColors.muted)
            Text(stringResource(kind.label), style = ConsoleType.body, color = ConsoleColors.text)
        }
        KindBasics(kind, settings) {
            saveSubagentKind(context, id, it)
            reload()
        }
        KindTools(kind, settings.allowDeviceControl) { tools -> save(tools, kind.maxLookups) }
        KindLookups(kind) { lookups -> save(kind.tools, lookups) }
        val instructions = remember(id) { subagentKindText(context, kind) }
        SettingsGroup(stringResource(R.string.skills_prompt), stringResource(R.string.skills_prompt_subtitle, kind.id)) {
            Markdown(instructions, Modifier.padding(Dimens.spaceMd))
        }
        val defaults = subagentKindDefaults(id)
        if (defaults != null && (defaults.tools != kind.tools || defaults.maxLookups != kind.maxLookups)) {
            val separator = stringResource(R.string.skills_list_separator)
            val tools = SubagentToolGroup.entries.filter { it in defaults.tools }
                .map { stringResource(it.screenLabel) }.joinToString(separator)
            TextButton(onClick = {
                resetSubagentKindTools(context, id)
                reload()
            }) {
                Text(
                    pluralStringResource(R.plurals.skills_reset_tools, defaults.maxLookups, tools, defaults.maxLookups),
                    style = ConsoleType.body,
                    color = ConsoleColors.accent,
                )
            }
        }
    }
}

/** Whether it is offered, what the main agent reads about it, and the model and search it follows. */
@Composable
private fun KindBasics(kind: SubagentKind, settings: SubagentSettings, onEnabled: (Boolean) -> Unit) {
    SettingsGroup(title = stringResource(kind.label), subtitle = kind.id) {
        Column {
            Row(Modifier.fillMaxWidth().padding(Dimens.spaceSm), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.skills_kind_load),
                        style = ConsoleType.body,
                        color = ConsoleColors.text,
                    )
                    Text(
                        if (kind.enabled) {
                            stringResource(R.string.skills_kind_on, kind.id)
                        } else {
                            stringResource(R.string.skills_kind_off)
                        },
                        style = ConsoleType.meta,
                        color = ConsoleColors.muted,
                    )
                }
                Switch(kind.enabled, onCheckedChange = onEnabled, colors = switchColors())
            }
            KindInfoRow(stringResource(R.string.skills_kind_description), kind.description)
            val model = settings.model.ifBlank { stringResource(R.string.skills_same_as_main) }
            KindInfoRow(
                stringResource(R.string.skills_kind_model),
                stringResource(R.string.skills_kind_model_value, model),
            )
            KindInfoRow(
                stringResource(R.string.skills_kind_search),
                stringResource(
                    when {
                        !kind.searches -> R.string.skills_kind_no_search
                        kind.searchApi ?: settings.searchApi -> R.string.skills_kind_search_api
                        else -> R.string.skills_kind_search_same
                    },
                ),
            )
        }
    }
}

/** The tool groups it may call, then what only the main agent has, greyed. */
@Composable
private fun KindTools(kind: SubagentKind, deviceControlAllowed: Boolean, onChange: (Set<SubagentToolGroup>) -> Unit) {
    SettingsGroup(
        title = stringResource(R.string.skills_kind_tools),
        subtitle = stringResource(R.string.skills_kind_tools_subtitle),
    ) {
        Column {
            val offNote = stringResource(R.string.skills_device_control_off_note)
            KindToolGrid(SubagentToolGroup.entries) { group ->
                KindToolCell(
                    label = stringResource(group.screenLabel),
                    detail = stringResource(group.detail),
                    checked = group in kind.tools,
                    changes = group.changes,
                    note = offNote.takeIf {
                        group == SubagentToolGroup.DEVICE_CONTROL && group in kind.tools && !deviceControlAllowed
                    },
                ) { on -> onChange(if (on) kind.tools + group else kind.tools - group) }
            }
            HorizontalDivider(color = ConsoleColors.borderSubtle)
            Text(
                stringResource(R.string.skills_main_agent_only),
                Modifier.padding(start = Dimens.spaceMd, top = Dimens.spaceSm),
                style = ConsoleType.meta,
                color = ConsoleColors.muted,
            )
            KindToolGrid(DEBUG_MAIN_AGENT_ONLY_TOOLS) { (label, detail) ->
                KindToolCell(
                    label = stringResource(label),
                    detail = stringResource(detail),
                    checked = false,
                    enabled = false,
                )
            }
        }
    }
}

@Composable
private fun KindLookups(kind: SubagentKind, onChange: (Int) -> Unit) {
    val looksUp = kind.tools.any { it == SubagentToolGroup.SEARCH || it == SubagentToolGroup.FETCH }
    SettingsGroup(
        title = stringResource(R.string.skills_lookups),
        subtitle = stringResource(R.string.skills_lookups_subtitle),
    ) {
        Row(Modifier.fillMaxWidth().padding(Dimens.spaceSm), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (looksUp) {
                    pluralStringResource(R.plurals.skills_lookups_max, kind.maxLookups, kind.maxLookups)
                } else {
                    stringResource(R.string.skills_lookups_unused)
                },
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
