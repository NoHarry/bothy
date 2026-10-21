package cc.noharry.bothy.sample

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal enum class DebugSettingsSection(val label: String) {
    GENERAL("通用"), MODELS("模型与服务"), IMAGE_MODEL("生图模型"), VOICE("语音"), COMPANION("陪聊"), REALTIME("端到端语音"), USAGE("用量与费用"), MEMORY("记忆"), CAPABILITIES("工具与技能"), SUBAGENTS("子 agent"), CONNECTIONS("连接"), DATA("工作区与数据"), CHECKS("检查"),
}

@Composable
internal fun DebugSettingsShell(state: DebugUiState, actions: DebugConsoleActions, onBack: () -> Unit, modifier: Modifier) {
    var sectionName by rememberSaveable { mutableStateOf(DebugSettingsSection.GENERAL.name) }
    val section = DebugSettingsSection.valueOf(sectionName)
    Column(modifier.fillMaxSize().padding(DebugDimens.spaceMd)) {
        SettingsNavBar("设置", null, "Exit settings", onBack)
        Spacer(Modifier.height(DebugDimens.spaceMd))
        HorizontalDivider(color = DebugColors.borderSubtle)
        Spacer(Modifier.height(DebugDimens.spaceMd))
        BoxWithConstraints(Modifier.weight(1f)) {
            if (maxWidth >= SETTINGS_RAIL_BREAKPOINT) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceLg)) {
                    Column(Modifier.width(SETTINGS_RAIL_WIDTH), verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceXs)) {
                        DebugSettingsSection.entries.forEach { item ->
                            DebugSelectableOptionRow(item.label, section == item, { sectionName = item.name })
                        }
                    }
                    Box(Modifier.weight(1f)) { SettingsSectionContent(section, state, actions) { sectionName = it.name } }
                }
            } else {
                Column {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        DebugSettingsSection.entries.forEach { item ->
                            TextButton(onClick = { sectionName = item.name }) {
                                Text(item.label, color = if (item == section) DebugColors.accent else DebugColors.muted)
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
    section: DebugSettingsSection,
    state: DebugUiState,
    actions: DebugConsoleActions,
    openSection: (DebugSettingsSection) -> Unit,
) {
    when (section) {
        DebugSettingsSection.GENERAL -> SettingsScrollableContent {
            SettingsRuntimeGroup(state, actions)
        }
        DebugSettingsSection.DATA -> SettingsScrollableContent {
            SettingsWorkspaceGroup(state, actions)
            SettingsRuntimeDataGroup(actions)
        }
        DebugSettingsSection.MODELS -> SettingsModelsContent(state, actions)
        DebugSettingsSection.IMAGE_MODEL -> DebugImageModelPane()
        DebugSettingsSection.VOICE -> DebugVoiceSettingsPane()
        DebugSettingsSection.COMPANION -> DebugCompanionPane()
        DebugSettingsSection.REALTIME -> DebugRealtimePane()
        DebugSettingsSection.USAGE -> DebugUsagePane()
        DebugSettingsSection.MEMORY -> DebugMemoryPane(actions.clearMemoriesAndRuntimeData)
        DebugSettingsSection.CAPABILITIES -> SettingsCapabilitiesContent()
        DebugSettingsSection.SUBAGENTS -> SettingsScrollableContent { DebugSubagentsPane() }
        DebugSettingsSection.CONNECTIONS -> SettingsConnectionsContent(state, actions)
        DebugSettingsSection.CHECKS -> DebugHealthPane(openSection)
    }
}

@Composable
private fun SettingsModelsContent(state: DebugUiState, actions: DebugConsoleActions) {
    var pageName by rememberSaveable { mutableStateOf(DebugSettingsPage.PROVIDERS.name) }
    var profileId by rememberSaveable { mutableStateOf<String?>(null) }
    val navigation = DebugSettingsNavigationState(DebugSettingsPage.valueOf(pageName), profileId)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoPane = maxWidth >= MODEL_PANES_BREAKPOINT
        Column {
            if (!twoPane && navigation.page != DebugSettingsPage.PROVIDERS) {
                TextButton(onClick = { pageName = DebugSettingsPage.PROVIDERS.name; profileId = null }) { Text("‹ 服务列表") }
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
                "基础工具" -> DebugBasicToolsPane()
                "运行时技能" -> DebugRuntimeSkillsPane()
                else -> DebugJevToolSearchPane()
            }
        }
    }
}

@Composable
private fun SettingsConnectionsContent(state: DebugUiState, actions: DebugConsoleActions) {
    var tab by rememberSaveable { mutableStateOf("MCP") }
    Column(Modifier.fillMaxSize()) {
        SettingsTabs(listOf("MCP", "A2A"), tab) { tab = it }
        if (tab == "MCP") DebugMcpProvidersPane(state, actions, {}, Modifier.weight(1f), embedded = true)
        else DebugA2aProfilesPane(state, actions, {}, Modifier.weight(1f), embedded = true)
    }
}

@Composable
internal fun SettingsTabs(tabs: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        tabs.forEach { tab ->
            TextButton(onClick = { onSelect(tab) }) { Text(tab, color = if (selected == tab) DebugColors.accent else DebugColors.muted) }
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
    val colors = DebugColors
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = if (unsaved) colors.warning else colors.borderSubtle)
        Row(
            Modifier.fillMaxWidth()
                .background(if (unsaved) colors.warning.copy(alpha = UNSAVED_BAR_ALPHA) else Color.Transparent)
                .padding(horizontal = DebugDimens.spaceMd, vertical = DebugDimens.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm),
        ) {
            if (unsaved) Text("有未保存的修改", style = DebugType.panelTitle, color = colors.warning)
            // While edits wait, an older success note would read as if they were saved.
            val shown = note.text.takeIf { !unsaved || note.error }.orEmpty()
            Text(shown, Modifier.weight(1f), style = DebugType.bodySmall, color = if (note.error) colors.danger else colors.muted)
            if (unsaved) {
                OutlinedButton(
                    onClick = onDiscard,
                    shape = RoundedCornerShape(DebugDimens.radiusMd),
                    border = BorderStroke(1.dp, colors.border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
                ) { Text("放弃修改") }
                Button(
                    onClick = onSave,
                    shape = RoundedCornerShape(DebugDimens.radiusMd),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = if (colors.isDark) colors.bg else Color.White),
                ) { Text("保存") }
            }
        }
    }
}

@Composable
private fun SettingsScrollableContent(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.widthIn(max = SETTINGS_FORM_MAX_WIDTH).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceMd), content = content)
}

@Composable
internal fun SettingsChildPanel(modifier: Modifier, embedded: Boolean, content: @Composable ColumnScope.() -> Unit) {
    if (embedded) Column(modifier, content = content) else DebugPanelBox("Settings", modifier, content)
}

private val SETTINGS_RAIL_BREAKPOINT = 760.dp
private val SETTINGS_RAIL_WIDTH = 168.dp
private val MODEL_PANES_BREAKPOINT = 620.dp

private val SETTINGS_FORM_MAX_WIDTH = 920.dp
private const val UNSAVED_BAR_ALPHA = 0.12f
