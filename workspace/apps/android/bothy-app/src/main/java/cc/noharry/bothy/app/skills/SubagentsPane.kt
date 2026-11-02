package cc.noharry.bothy.app.skills
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.SettingsGroup
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.switchColors
import cc.noharry.bothy.app.model.ProviderConfig
import cc.noharry.bothy.app.model.hasDeepSeekKey

/**
 * Settings → 子 agent (owner 2026-10-17: 子 agent 在设置里面单列一条): whether the agent starts subagents, how they
 * search and whether they may control the device, then the kinds of subagent it may name, each with its tools; tapping one
 * opens its page ([SubagentKindPage]). Each applies from the next spawn.
 */
@Composable
internal fun SubagentsPane() {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    open?.let { id ->
        SubagentKindPage(id, onBack = { open = null })
        return
    }
    SubagentsOverview(onOpen = { open = it })
}

@Composable
private fun SubagentsOverview(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val settings by BothySubagents.settings.collectAsState()
    val save = { next: SubagentSettings -> saveSubagentSettings(context, next) }
    SettingsGroup(
        title = stringResource(R.string.skills_subagents),
        subtitle = stringResource(R.string.skills_subagents_subtitle),
    ) {
        Column {
            SubagentSwitchRow(
                stringResource(R.string.skills_auto_spawn), settings.enabled,
                stringResource(R.string.skills_auto_spawn_on),
                stringResource(R.string.skills_auto_spawn_off),
            ) { save(settings.copy(enabled = it)) }
            SubagentSwitchRow(
                stringResource(R.string.skills_search_api), settings.searchApi,
                stringResource(R.string.skills_search_api_on),
                stringResource(R.string.skills_search_api_off),
            ) { save(settings.copy(searchApi = it)) }
            SubagentSwitchRow(
                stringResource(R.string.skills_device_control), settings.allowDeviceControl,
                stringResource(R.string.skills_device_control_on),
                stringResource(R.string.skills_device_control_off),
            ) { save(settings.copy(allowDeviceControl = it)) }
            val model = settings.model.ifBlank { stringResource(R.string.skills_same_as_main) }
            Text(
                pluralStringResource(R.plurals.skills_subagent_model, MAX_CHILD_LOOKUPS, model, MAX_CHILD_LOOKUPS),
                Modifier.padding(Dimens.spaceSm),
                style = ConsoleType.meta,
                color = ConsoleColors.muted,
            )
        }
    }
    SettingsGroup(
        title = stringResource(R.string.skills_kinds),
        subtitle = stringResource(R.string.skills_kinds_subtitle),
    ) {
        SubagentKinds(onOpen)
    }
}

@Composable
private fun SubagentSwitchRow(title: String, checked: Boolean, on: String, off: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(Dimens.spaceSm), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = ConsoleType.body, color = ConsoleColors.text)
            Text(if (checked) on else off, style = ConsoleType.meta, color = ConsoleColors.muted)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = switchColors())
    }
}

/** Each kind with its switch and the tools it may call; tapping one opens its page. */
@Composable
private fun SubagentKinds(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    var kinds by remember { mutableStateOf(loadSubagentKinds(context)) }
    val deepSeekLookup = remember { ProviderConfig.load(context.applicationContext).hasDeepSeekKey(context) }
    Column {
        kinds.forEachIndexed { index, kind ->
            if (index > 0) HorizontalDivider(color = ConsoleColors.borderSubtle)
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(kind.id) }.padding(Dimens.spaceSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
                    val label = stringResource(kind.label)
                    Text("$label · ${kind.id}", style = ConsoleType.body, color = ConsoleColors.text)
                    SubagentToolChips(kind.tools)
                    val loaded = when {
                        !kind.enabled -> R.string.skills_kind_not_loaded
                        kind.requiresDeepSeekLookup && !deepSeekLookup -> R.string.skills_kind_needs_deepseek
                        else -> R.string.skills_kind_loaded
                    }
                    val status = stringResource(loaded)
                    Text(
                        pluralStringResource(R.plurals.skills_kind_status, kind.maxLookups, kind.maxLookups, status),
                        style = ConsoleType.meta,
                        color = ConsoleColors.muted,
                    )
                }
                Switch(kind.enabled, onCheckedChange = {
                    saveSubagentKind(context, kind.id, it)
                    kinds = loadSubagentKinds(context)
                }, colors = switchColors())
                Text("›", Modifier.padding(start = Dimens.spaceMd), style = ConsoleType.panelTitle, color = ConsoleColors.muted)
            }
        }
    }
}

/** A type's tools as small tags; a red dot marks the ones that change something (as on the type's page). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubagentToolChips(tools: Set<SubagentToolGroup>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
        SubagentToolGroup.entries.filter { it in tools }.forEach { group ->
            Row(
                Modifier
                    .background(ConsoleColors.accentSoft, RoundedCornerShape(Dimens.radiusSm))
                    .padding(horizontal = Dimens.spaceXsMd, vertical = Dimens.spaceXxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXxs),
            ) {
                Text(stringResource(group.screenLabel), style = ConsoleType.meta, color = ConsoleColors.accent)
                if (group.changes) ChangesDot()
            }
        }
        if (tools.isEmpty()) {
            Text(stringResource(R.string.skills_no_tools), style = ConsoleType.meta, color = ConsoleColors.danger)
        }
    }
}

/** The red dot beside a tool that changes something. */
@Composable
internal fun ChangesDot() {
    Box(Modifier.size(Dimens.spaceXsMd).background(ConsoleColors.danger, CircleShape))
}
