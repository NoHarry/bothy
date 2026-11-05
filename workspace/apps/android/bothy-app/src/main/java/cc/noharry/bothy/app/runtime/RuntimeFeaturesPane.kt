package cc.noharry.bothy.app.runtime
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.CapabilityRequests
import cc.noharry.agent.appserver.protocol.EnabledParams
import cc.noharry.agent.appserver.protocol.IdParams
import cc.noharry.agent.appserver.protocol.RuntimeSkillView
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.SettingsNote
import cc.noharry.bothy.app.console.SettingsSaveBar
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.outlinedTextFieldColors
import cc.noharry.bothy.app.console.switchColors
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.app.skills.CustomSkillForm
import cc.noharry.bothy.app.words
import cc.noharry.bothy.protocol.JevSearchMode
import cc.noharry.bothy.protocol.JevSettings
import cc.noharry.bothy.protocol.ToolSearchRequests
import cc.noharry.bothy.protocol.toolSearch

/**
 * Settings → 工具与技能 → 运行时技能, in two groups (owner 2026-10-20: 「自己后续添加的需要可以删除，预置的不行，可以区分下」):
 * the skills added on the device, which can be edited and deleted, and the preset ones shipped in the APK, which can only be
 * switched. Each switch applies to the next request. `editing` is the add/edit form's skill name, `""` for a new one.
 */
@Composable
internal fun RuntimeSkillsPane() {
    val server = LocalServer.current
    val skills = server.state.preferences.runtimeSkills
    var expanded by remember { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    editing?.let { name ->
        CustomSkillForm(name.ifEmpty { null }, onDone = { editing = null }, Modifier.fillMaxSize())
        return
    }
    val (added, preset) = skills.partition { it.custom }
    val row = @Composable { skill: RuntimeSkillView, actions: (@Composable () -> Unit)? ->
        SkillRow(skill, expanded == skill.id, { expanded = if (expanded == skill.id) null else skill.id }, actions) {
            server.send(CapabilityRequests.SET_SKILL, EnabledParams(skill.id, it))
        }
    }
    Column(
        Modifier.widthIn(max = 880.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.runtime_skills_loaded, skills.count { it.enabled }, skills.size), Modifier.weight(1f), color = ConsoleColors.muted)
            TextButton(onClick = { editing = "" }) { Text(stringResource(R.string.runtime_add_skill)) }
        }
        SkillGroupTitle(R.string.runtime_skills_added, R.string.runtime_skills_added_note)
        if (added.isEmpty()) {
            Text(stringResource(R.string.runtime_skills_added_empty), style = ConsoleType.bodySmall, color = ConsoleColors.muted)
        }
        added.forEach { skill ->
            row(skill) {
                TextButton(onClick = { editing = skill.id }) { Text(stringResource(R.string.common_edit)) }
                // Two taps: the first asks, the second deletes.
                TextButton(onClick = {
                    if (deleting == skill.id) {
                        server.send(CapabilityRequests.DELETE_SKILL, IdParams(skill.id))
                        deleting = null
                    } else {
                        deleting = skill.id
                    }
                }) {
                    Text(stringResource(if (deleting == skill.id) R.string.runtime_confirm_delete else R.string.common_delete), color = ConsoleColors.danger)
                }
            }
        }
        SkillGroupTitle(R.string.runtime_skills_preset, R.string.runtime_skills_preset_note)
        preset.forEach { skill -> row(skill, null) }
    }
}

@Composable
private fun SkillGroupTitle(@StringRes title: Int, @StringRes note: Int) {
    Row(Modifier.padding(top = Dimens.spaceSm), verticalAlignment = Alignment.Bottom) {
        Text(stringResource(title), style = ConsoleType.panelTitle, color = ConsoleColors.text)
        Text("  ${stringResource(note)}", style = ConsoleType.meta, color = ConsoleColors.muted)
    }
}

/** One skill: its switch, its text on tap, and [actions] (编辑 and 删除) for one added on the device. */
@Composable
private fun SkillRow(
    skill: RuntimeSkillView,
    expanded: Boolean,
    onTap: () -> Unit,
    actions: (@Composable () -> Unit)?,
    onSwitch: (Boolean) -> Unit,
) {
    val server = LocalServer.current
    var text by remember(skill.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(expanded, skill.id) {
        if (expanded && text == null) server.send(CapabilityRequests.SKILL_TEXT, IdParams(skill.id)) { text = it.text }
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(Dimens.spaceMd), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onTap)) {
                Text(skill.label, style = ConsoleType.body, color = ConsoleColors.text)
                Text(skill.description, style = ConsoleType.meta, color = ConsoleColors.muted)
                Text(
                    if (skill.enabled) {
                        stringResource(R.string.runtime_skill_loaded)
                    } else {
                        stringResource(R.string.runtime_skill_not_loaded)
                    },
                    color = ConsoleColors.muted,
                )
            }
            Switch(skill.enabled, onCheckedChange = onSwitch, colors = switchColors())
        }
        actions?.let { Row(Modifier.padding(horizontal = Dimens.spaceSm)) { it() } }
        if (expanded) text?.let { Text(it, Modifier.padding(Dimens.spaceMd), color = ConsoleColors.text) }
        HorizontalDivider(color = ConsoleColors.borderSubtle)
    }
}

/**
 * Settings → 工具与技能 → 工具检索: whether Jev picks the relevant tools before the first model call, and how. It uses
 * the Jev connection kept on Settings → 语音 (key on 密钥, address and model on 判断与打断), shared with the voice
 * judge. The switch and the mode apply when tapped; the numbers wait for 保存.
 */
@Composable
internal fun JevToolSearchPane() {
    val server = LocalServer.current
    val resources = LocalResources.current
    val saved = server.state.toolSearch
    var draft by remember { mutableStateOf(ToolSearchDraft.of(saved)) }
    var note by remember { mutableStateOf(SettingsNote()) }
    val apply = { next: JevSettings, saved: () -> Unit ->
        server.send(ToolSearchRequests.SET, next) { result ->
            val error = result.error?.let(resources::words)
            note = SettingsNote(error ?: resources.getString(R.string.runtime_saved_next_request), error = error != null)
            if (error == null) saved()
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).widthIn(max = 880.dp).verticalScroll(rememberScrollState()).padding(Dimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
        ) {
            ToolSearchIntro(saved)
            JevEnabledSwitch(saved.enabled) { apply(saved.copy(enabled = it)) {} }
            JevModeSelector(saved.mode) { apply(saved.copy(mode = it)) {} }
            if (saved.mode != JevSearchMode.FLAT) {
                JevField(stringResource(R.string.runtime_jev_domain_min_score), draft.domainMinScore) { draft = draft.copy(domainMinScore = it) }
            }
            JevField(stringResource(R.string.runtime_jev_min_score), draft.minScore) { draft = draft.copy(minScore = it) }
            JevField(stringResource(R.string.runtime_jev_max_tools), draft.maxTools) { draft = draft.copy(maxTools = it) }
        }
        SettingsSaveBar(
            unsaved = draft != ToolSearchDraft.of(saved),
            note = note,
            onSave = {
                val next = draft.applyTo(saved)
                apply(next) { draft = ToolSearchDraft.of(next) }
            },
            onDiscard = {
                draft = ToolSearchDraft.of(saved)
                note = SettingsNote(resources.getString(R.string.runtime_changes_discarded))
            },
        )
    }
}

@Composable
private fun ToolSearchIntro(saved: JevSettings) {
    Text(stringResource(R.string.runtime_tool_search_intro), color = ConsoleColors.muted)
    Text(
        if (saved.hasApiKey) stringResource(R.string.runtime_tool_search_connection, saved.model, saved.endpoint)
        else stringResource(R.string.runtime_tool_search_no_key),
        style = ConsoleType.bodySmall,
        color = if (saved.hasApiKey) ConsoleColors.muted else ConsoleColors.danger,
    )
}

/** The numbers as typed; they stay text until saved, where validation says what is wrong. */
private data class ToolSearchDraft(val minScore: String, val maxTools: String, val domainMinScore: String) {
    fun applyTo(saved: JevSettings): JevSettings = saved.copy(
        minScore = minScore.toDoubleOrNull() ?: Double.NaN,
        maxTools = maxTools.toIntOrNull() ?: 0,
        domainMinScore = domainMinScore.toDoubleOrNull() ?: Double.NaN,
    )

    companion object {
        fun of(config: JevSettings) = ToolSearchDraft(config.minScore.toString(), config.maxTools.toString(), config.domainMinScore.toString())
    }
}

@Composable
private fun JevEnabledSwitch(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.runtime_tool_search_switch), Modifier.weight(1f), color = ConsoleColors.text)
        Switch(enabled, onChange, colors = switchColors())
    }
}

@Composable
private fun JevModeSelector(mode: JevSearchMode, onChange: (JevSearchMode) -> Unit) {
    Column {
        JevSearchMode.entries.forEach { option ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = mode == option, onClick = { onChange(option) })
                Text(stringResource(option.labelRes()), Modifier.clickable { onChange(option) }, color = ConsoleColors.text)
            }
        }
    }
}

@Composable
private fun JevField(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = change, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        singleLine = true, colors = outlinedTextFieldColors(),
    )
}

@StringRes
private fun JevSearchMode.labelRes(): Int = when (this) {
    JevSearchMode.FLAT -> R.string.voice_jev_mode_flat
    JevSearchMode.TWO_STAGE -> R.string.voice_jev_mode_two_stage
    JevSearchMode.TWO_STAGE_SUBDOMAINS -> R.string.voice_jev_mode_two_stage_subdomains
}
