package cc.noharry.bothy.app.console
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.a2a.A2aProfilesPane
import cc.noharry.bothy.app.companion.CompanionPane
import cc.noharry.bothy.app.console.ui.SelectableOptionRow
import cc.noharry.bothy.app.evidence.HealthPane
import cc.noharry.bothy.app.image.ImageModelPane
import cc.noharry.bothy.app.mcp.McpProvidersPane
import cc.noharry.bothy.app.memory.MemoryPane
import cc.noharry.bothy.app.runtime.BasicToolsPane
import cc.noharry.bothy.app.runtime.JevToolSearchPane
import cc.noharry.bothy.app.runtime.RuntimeSkillsPane
import cc.noharry.bothy.app.session.SettingsSessionGroup
import cc.noharry.bothy.app.skills.SubagentsPane
import cc.noharry.bothy.app.usage.UsagePane
import cc.noharry.bothy.app.voice.VoiceSettingsPane
import cc.noharry.bothy.app.voice.realtime.RealtimePane

internal enum class SettingsSection(@StringRes val label: Int) {
    GENERAL(R.string.settings_section_general),
    MODELS(R.string.settings_section_models),
    IMAGE_MODEL(R.string.settings_section_image_model),
    VOICE(R.string.settings_section_voice),
    COMPANION(R.string.settings_section_companion),
    REALTIME(R.string.settings_section_realtime),
    USAGE(R.string.settings_section_usage),
    MEMORY(R.string.settings_section_memory),
    CAPABILITIES(R.string.settings_section_capabilities),
    SUBAGENTS(R.string.settings_section_subagents),
    CONNECTIONS(R.string.settings_section_connections),
    DATA(R.string.settings_section_data),
    CHECKS(R.string.settings_section_checks),
}

@Composable
internal fun SettingsShell(state: ServerState, actions: ConsoleActions, onBack: () -> Unit, modifier: Modifier) {
    var sectionName by rememberSaveable { mutableStateOf(SettingsSection.GENERAL.name) }
    // A phone lists the sections and opens one at a time (owner 2026-11-02: 做一套手机适配的界面).
    var opened by rememberSaveable { mutableStateOf(false) }
    val section = SettingsSection.valueOf(sectionName)
    val open = { next: SettingsSection -> sectionName = next.name; opened = true }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val rail = maxWidth >= SETTINGS_RAIL_BREAKPOINT
        val inSection = !rail && opened
        BackHandler(enabled = inSection) { opened = false }
        Column(Modifier.fillMaxSize().padding(Dimens.spaceMd)) {
            if (inSection) {
                val settings = stringResource(R.string.common_settings)
                SettingsNavBar(stringResource(section.label), settings, stringResource(R.string.common_back), { opened = false })
            } else {
                SettingsNavBar(stringResource(R.string.common_settings), null, stringResource(R.string.settings_exit), onBack)
            }
            Spacer(Modifier.height(Dimens.spaceMd))
            HorizontalDivider(color = ConsoleColors.borderSubtle)
            Spacer(Modifier.height(Dimens.spaceMd))
            Box(Modifier.weight(1f)) {
                when {
                    rail -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Dimens.spaceLg)) {
                        SettingsSectionList(section, Modifier.width(SETTINGS_RAIL_WIDTH), open)
                        Box(Modifier.weight(1f)) { SettingsSectionContent(section, state, actions, open) }
                    }
                    inSection -> SettingsSectionContent(section, state, actions, open)
                    else -> SettingsSectionList(null, Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), open)
                }
            }
        }
    }
}

/** The sections: a rail beside the open one on a wide screen, the whole page on a phone ([selected] null). */
@Composable
private fun SettingsSectionList(selected: SettingsSection?, modifier: Modifier, onOpen: (SettingsSection) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
        SettingsSection.entries.forEach { item ->
            SelectableOptionRow(stringResource(item.label), selected == item, { onOpen(item) })
        }
    }
}

/** One section's page; also a phone's settings page for the section ([cc.noharry.bothy.app.phone.PhoneSettings]). */
@Composable
internal fun SettingsSectionContent(
    section: SettingsSection,
    state: ServerState,
    actions: ConsoleActions,
    openSection: (SettingsSection) -> Unit,
) {
    when (section) {
        SettingsSection.GENERAL -> SettingsScrollableContent {
            SettingsThemeGroup()
            SettingsLayoutGroup()
            SettingsLanguageGroup()
            SettingsRuntimeGroup(state, actions)
            SettingsSessionGroup(state, actions)
        }
        SettingsSection.DATA -> SettingsScrollableContent {
            SettingsWorkspaceGroup(state, actions)
            SettingsRuntimeDataGroup(actions)
        }
        SettingsSection.MODELS -> SettingsModelsContent(state, actions)
        SettingsSection.IMAGE_MODEL -> ImageModelPane()
        SettingsSection.VOICE -> VoiceSettingsPane()
        SettingsSection.COMPANION -> CompanionPane()
        SettingsSection.REALTIME -> RealtimePane()
        SettingsSection.USAGE -> UsagePane()
        SettingsSection.MEMORY -> MemoryPane(actions.clearMemoriesAndRuntimeData)
        SettingsSection.CAPABILITIES -> SettingsCapabilitiesContent()
        SettingsSection.SUBAGENTS -> SettingsScrollableContent { SubagentsPane() }
        SettingsSection.CONNECTIONS -> SettingsConnectionsContent(state, actions)
        SettingsSection.CHECKS -> HealthPane(openSection)
    }
}

@Composable
private fun SettingsModelsContent(state: ServerState, actions: ConsoleActions) {
    var pageName by rememberSaveable { mutableStateOf(SettingsPage.PROVIDERS.name) }
    var profileId by rememberSaveable { mutableStateOf<String?>(null) }
    val navigation = SettingsNavigationState(SettingsPage.valueOf(pageName), profileId)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoPane = maxWidth >= MODEL_PANES_BREAKPOINT
        Column {
            if (!twoPane && navigation.page != SettingsPage.PROVIDERS) {
                TextButton(onClick = { pageName = SettingsPage.PROVIDERS.name; profileId = null }) {
                    Text(stringResource(R.string.settings_back_to_services))
                }
            }
            ProviderSettingsContent(state, actions, navigation, twoPane) { next ->
                pageName = next.page.name
                profileId = next.requestedProfileId
            }
        }
    }
}

@Composable
private fun SettingsCapabilitiesContent() {
    var tab by rememberSaveable { mutableStateOf(0) }
    val labels = listOf(
        stringResource(R.string.settings_tab_basic_tools),
        stringResource(R.string.settings_tab_runtime_skills),
        stringResource(R.string.settings_tab_tool_search),
    )
    Column(Modifier.fillMaxSize()) {
        SettingsTabs(labels, labels[tab]) { tab = labels.indexOf(it) }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> BasicToolsPane()
                1 -> RuntimeSkillsPane()
                else -> JevToolSearchPane()
            }
        }
    }
}

@Composable
private fun SettingsConnectionsContent(state: ServerState, actions: ConsoleActions) {
    var tab by rememberSaveable { mutableStateOf("MCP") }
    Column(Modifier.fillMaxSize()) {
        SettingsTabs(listOf("MCP", "A2A"), tab) { tab = it }
        if (tab == "MCP") McpProvidersPane(state, actions, {}, Modifier.weight(1f), embedded = true)
        else A2aProfilesPane(state, actions, {}, Modifier.weight(1f), embedded = true)
    }
}

@Composable
internal fun SettingsTabs(tabs: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        tabs.forEach { tab ->
            TextButton(onClick = { onSelect(tab) }) { Text(tab, color = if (selected == tab) ConsoleColors.accent else ConsoleColors.muted) }
        }
    }
}

/** A line under a settings form: what the last action did, red when it failed. */
internal data class SettingsNote(val text: String = "", val error: Boolean = false)

/**
 * The foot of a settings form whose choices apply when tapped and whose typed fields wait for 保存. With unsaved edits
 * it is a highlighted bar saying so, with 放弃修改 and 保存; otherwise it shows only the last note, if any.
 */
@Composable
internal fun SettingsSaveBar(unsaved: Boolean, note: SettingsNote, onSave: () -> Unit, onDiscard: () -> Unit) {
    if (!unsaved && note.text.isEmpty()) return
    val colors = ConsoleColors
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = if (unsaved) colors.warning else colors.borderSubtle)
        Row(
            Modifier.fillMaxWidth()
                .background(if (unsaved) colors.warning.copy(alpha = UNSAVED_BAR_ALPHA) else Color.Transparent)
                .padding(horizontal = Dimens.spaceMd, vertical = Dimens.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
        ) {
            if (unsaved) {
                Text(stringResource(R.string.settings_unsaved), style = ConsoleType.panelTitle, color = colors.warning)
            }
            // While edits wait, an older success note would read as if they were saved.
            val shown = note.text.takeIf { !unsaved || note.error }.orEmpty()
            Text(shown, Modifier.weight(1f), style = ConsoleType.bodySmall, color = if (note.error) colors.danger else colors.muted)
            if (unsaved) {
                OutlinedButton(
                    onClick = onDiscard,
                    shape = RoundedCornerShape(Dimens.radiusMd),
                    border = BorderStroke(1.dp, colors.border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
                ) { Text(stringResource(R.string.settings_discard)) }
                Button(
                    onClick = onSave,
                    shape = RoundedCornerShape(Dimens.radiusMd),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = if (colors.isDark) colors.bg else Color.White),
                ) { Text(stringResource(R.string.common_save)) }
            }
        }
    }
}

@Composable
private fun SettingsScrollableContent(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.widthIn(max = SETTINGS_FORM_MAX_WIDTH).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd), content = content)
}

@Composable
internal fun SettingsChildPanel(modifier: Modifier, embedded: Boolean, content: @Composable ColumnScope.() -> Unit) {
    if (embedded) {
        Column(modifier, content = content)
    } else {
        PanelBox(stringResource(R.string.common_settings), modifier, content)
    }
}

private val SETTINGS_RAIL_BREAKPOINT = 760.dp
private val SETTINGS_RAIL_WIDTH = 168.dp
private val MODEL_PANES_BREAKPOINT = 620.dp

private val SETTINGS_FORM_MAX_WIDTH = 920.dp
private const val UNSAVED_BAR_ALPHA = 0.12f
