package cc.noharry.bothy.app.console
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
import androidx.compose.ui.unit.dp
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
import cc.noharry.bothy.app.session.UiState
import cc.noharry.bothy.app.skills.SubagentsPane
import cc.noharry.bothy.app.usage.UsagePane
import cc.noharry.bothy.app.voice.VoiceSettingsPane
import cc.noharry.bothy.app.voice.realtime.RealtimePane

internal enum class SettingsSection(val label: String) {
    GENERAL("通用"), MODELS("模型与服务"), IMAGE_MODEL("生图模型"), VOICE("语音"), COMPANION("陪聊"), REALTIME("端到端语音"), USAGE("用量与费用"), MEMORY("记忆"), CAPABILITIES("工具与技能"), SUBAGENTS("子 agent"), CONNECTIONS("连接"), DATA("工作区与数据"), CHECKS("检查"),
}

@Composable
internal fun SettingsShell(state: UiState, actions: ConsoleActions, onBack: () -> Unit, modifier: Modifier) {
    var sectionName by rememberSaveable { mutableStateOf(SettingsSection.GENERAL.name) }
    val section = SettingsSection.valueOf(sectionName)
    Column(modifier.fillMaxSize().padding(Dimens.spaceMd)) {
        SettingsNavBar("设置", null, "Exit settings", onBack)
        Spacer(Modifier.height(Dimens.spaceMd))
        HorizontalDivider(color = ConsoleColors.borderSubtle)
        Spacer(Modifier.height(Dimens.spaceMd))
        BoxWithConstraints(Modifier.weight(1f)) {
            if (maxWidth >= SETTINGS_RAIL_BREAKPOINT) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Dimens.spaceLg)) {
                    Column(Modifier.width(SETTINGS_RAIL_WIDTH), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
                        SettingsSection.entries.forEach { item ->
                            SelectableOptionRow(item.label, section == item, { sectionName = item.name })
                        }
                    }
                    Box(Modifier.weight(1f)) { SettingsSectionContent(section, state, actions) { sectionName = it.name } }
                }
            } else {
                Column {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        SettingsSection.entries.forEach { item ->
                            TextButton(onClick = { sectionName = item.name }) {
                                Text(item.label, color = if (item == section) ConsoleColors.accent else ConsoleColors.muted)
                            }
                        }
                    }
                    Box(Modifier.weight(1f)) { SettingsSectionContent(section, state, actions) { sectionName = it.name } }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionContent(
    section: SettingsSection,
    state: UiState,
    actions: ConsoleActions,
    openSection: (SettingsSection) -> Unit,
) {
    when (section) {
        SettingsSection.GENERAL -> SettingsScrollableContent {
            SettingsRuntimeGroup(state, actions)
            SettingsSessionGroup()
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
private fun SettingsModelsContent(state: UiState, actions: ConsoleActions) {
    var pageName by rememberSaveable { mutableStateOf(SettingsPage.PROVIDERS.name) }
    var profileId by rememberSaveable { mutableStateOf<String?>(null) }
    val navigation = SettingsNavigationState(SettingsPage.valueOf(pageName), profileId)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoPane = maxWidth >= MODEL_PANES_BREAKPOINT
        Column {
            if (!twoPane && navigation.page != SettingsPage.PROVIDERS) {
                TextButton(onClick = { pageName = SettingsPage.PROVIDERS.name; profileId = null }) { Text("‹ 服务列表") }
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
    var tab by rememberSaveable { mutableStateOf("基础工具") }
    Column(Modifier.fillMaxSize()) {
        SettingsTabs(listOf("基础工具", "运行时技能", "工具检索"), tab) { tab = it }
        Box(Modifier.weight(1f)) {
            when (tab) {
                "基础工具" -> BasicToolsPane()
                "运行时技能" -> RuntimeSkillsPane()
                else -> JevToolSearchPane()
            }
        }
    }
}

@Composable
private fun SettingsConnectionsContent(state: UiState, actions: ConsoleActions) {
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
            if (unsaved) Text("有未保存的修改", style = ConsoleType.panelTitle, color = colors.warning)
            // While edits wait, an older success note would read as if they were saved.
            val shown = note.text.takeIf { !unsaved || note.error }.orEmpty()
            Text(shown, Modifier.weight(1f), style = ConsoleType.bodySmall, color = if (note.error) colors.danger else colors.muted)
            if (unsaved) {
                OutlinedButton(
                    onClick = onDiscard,
                    shape = RoundedCornerShape(Dimens.radiusMd),
                    border = BorderStroke(1.dp, colors.border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
                ) { Text("放弃修改") }
                Button(
                    onClick = onSave,
                    shape = RoundedCornerShape(Dimens.radiusMd),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = if (colors.isDark) colors.bg else Color.White),
                ) { Text("保存") }
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
    if (embedded) Column(modifier, content = content) else PanelBox("Settings", modifier, content)
}

private val SETTINGS_RAIL_BREAKPOINT = 760.dp
private val SETTINGS_RAIL_WIDTH = 168.dp
private val MODEL_PANES_BREAKPOINT = 620.dp

private val SETTINGS_FORM_MAX_WIDTH = 920.dp
private const val UNSAVED_BAR_ALPHA = 0.12f
