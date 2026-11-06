package cc.noharry.bothy.app.skills
import androidx.annotation.StringRes
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import cc.noharry.agent.appserver.protocol.CapabilityRequests
import cc.noharry.agent.appserver.protocol.DEFAULT_SUBAGENT_LOOKUPS
import cc.noharry.agent.appserver.protocol.EnabledParams
import cc.noharry.agent.appserver.protocol.SubagentSettingsView
import cc.noharry.agent.appserver.protocol.SubagentToolKind
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.SettingsGroup
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.switchColors
import cc.noharry.bothy.app.frontend.LocalServer

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
    val server = LocalServer.current
    val settings = server.state.preferences.subagents
    val save = { next: SubagentSettingsView -> server.send(CapabilityRequests.SET_SUBAGENTS, next) }
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
                pluralStringResource(R.plurals.skills_subagent_model, DEFAULT_SUBAGENT_LOOKUPS, model, DEFAULT_SUBAGENT_LOOKUPS),
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
    val server = LocalServer.current
    val kinds = server.state.preferences.subagentKinds
    val webLookup = server.state.preferences.webLookup
    Column {
        kinds.forEachIndexed { index, kind ->
            if (index > 0) HorizontalDivider(color = ConsoleColors.borderSubtle)
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(kind.id) }.padding(Dimens.spaceSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
                    Text("${kind.label} · ${kind.id}", style = ConsoleType.body, color = ConsoleColors.text)
                    SubagentToolChips(kind.tools)
                    val loaded = when {
                        !kind.enabled -> R.string.skills_kind_not_loaded
                        kind.requiresWebLookup && !webLookup -> R.string.skills_kind_needs_deepseek
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
                    server.send(CapabilityRequests.SET_SUBAGENT_KIND, EnabledParams(kind.id, it))
                }, colors = switchColors())
                Text("›", Modifier.padding(start = Dimens.spaceMd), style = ConsoleType.panelTitle, color = ConsoleColors.muted)
            }
        }
    }
}

/** A type's tools as small tags; a red dot marks the ones that change something (as on the type's page). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubagentToolChips(tools: Set<SubagentToolKind>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
        SubagentToolKind.entries.filter { it in tools }.forEach { group ->
            Row(
                Modifier
                    .background(ConsoleColors.accentSoft, RoundedCornerShape(Dimens.radiusSm))
                    .padding(horizontal = Dimens.spaceXsMd, vertical = Dimens.spaceXxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXxs),
            ) {
                Text(stringResource(group.label), style = ConsoleType.meta, color = ConsoleColors.accent)
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

/** What Settings calls a group of tools. */
@get:StringRes
internal val SubagentToolKind.label: Int
    get() = when (this) {
        SubagentToolKind.SEARCH -> R.string.skills_tool_search
        SubagentToolKind.FETCH -> R.string.skills_tool_fetch
        SubagentToolKind.READ -> R.string.skills_tool_read
        SubagentToolKind.WRITE -> R.string.skills_tool_write
        SubagentToolKind.TODO -> R.string.skills_tool_todo
        SubagentToolKind.DEVICE_QUERY -> R.string.skills_tool_device_query
        SubagentToolKind.DEVICE_CONTROL -> R.string.skills_tool_device_control
        SubagentToolKind.SERVICES -> R.string.skills_tool_services
    }

/** What Settings says a group of tools does. */
@get:StringRes
internal val SubagentToolKind.detail: Int
    get() = when (this) {
        SubagentToolKind.SEARCH -> R.string.skills_tool_search_detail
        SubagentToolKind.FETCH -> R.string.skills_tool_fetch_detail
        SubagentToolKind.READ -> R.string.skills_tool_read_detail
        SubagentToolKind.WRITE -> R.string.skills_tool_write_detail
        SubagentToolKind.TODO -> R.string.skills_tool_todo_detail
        SubagentToolKind.DEVICE_QUERY -> R.string.skills_tool_device_query_detail
        SubagentToolKind.DEVICE_CONTROL -> R.string.skills_tool_device_control_detail
        SubagentToolKind.SERVICES -> R.string.skills_tool_services_detail
    }
