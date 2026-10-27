package cc.noharry.bothy.app.runtime
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.SettingsNote
import cc.noharry.bothy.app.console.SettingsSaveBar
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.outlinedTextFieldColors
import cc.noharry.bothy.app.console.singleLine
import cc.noharry.bothy.app.console.switchColors
import cc.noharry.bothy.app.skills.CustomSkillForm
import cc.noharry.bothy.app.skills.CustomSkills
import cc.noharry.bothy.app.skills.RuntimeSkill
import cc.noharry.bothy.app.skills.loadRuntimeSkills
import cc.noharry.bothy.app.skills.runtimeSkillText
import cc.noharry.bothy.app.skills.saveRuntimeSkill
import cc.noharry.bothy.app.voice.JevConfig
import cc.noharry.bothy.app.voice.JevMode
import cc.noharry.bothy.app.voice.loadJevConfig
import cc.noharry.bothy.app.voice.saveJevToolSearch

/**
 * Settings → 工具与技能 → 运行时技能, in two groups (owner 2026-10-20: 「自己后续添加的需要可以删除，预置的不行，可以区分下」):
 * the skills added on the device, which can be edited and deleted, and the preset ones shipped in the APK, which can only be
 * switched. Each switch applies to the next request. `editing` is the add/edit form's skill name, `""` for a new one.
 */
@Composable
internal fun RuntimeSkillsPane() {
    val context = LocalContext.current
    var skills by remember { mutableStateOf(loadRuntimeSkills(context)) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    editing?.let { name ->
        CustomSkillForm(name.ifEmpty { null }, onDone = { editing = null; skills = loadRuntimeSkills(context) }, Modifier.fillMaxSize())
        return
    }
    val (added, preset) = skills.partition { it.file != null }
    val row = @Composable { skill: RuntimeSkill, actions: (@Composable () -> Unit)? ->
        SkillRow(skill, expanded == skill.id, { expanded = if (expanded == skill.id) null else skill.id }, actions) {
            saveRuntimeSkill(context, skill.id, it)
            skills = loadRuntimeSkills(context)
        }
    }
    Column(
        Modifier.widthIn(max = 880.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${skills.count { it.enabled }} of ${skills.size} loaded · applies to the next request", Modifier.weight(1f), color = ConsoleColors.muted)
            TextButton(onClick = { editing = "" }) { Text("+ 添加技能") }
        }
        SkillGroupTitle("自己添加的", "可以编辑、删除")
        if (added.isEmpty()) {
            Text("还没有。点「+ 添加技能」粘贴 SKILL.md 内容，保存前会检查格式。", style = ConsoleType.bodySmall, color = ConsoleColors.muted)
        }
        added.forEach { skill ->
            row(skill) {
                TextButton(onClick = { editing = skill.id }) { Text("编辑") }
                // Two taps: the first asks, the second deletes.
                TextButton(onClick = {
                    if (deleting == skill.id) {
                        CustomSkills.delete(context, skill.id)
                        deleting = null
                        skills = loadRuntimeSkills(context)
                    } else {
                        deleting = skill.id
                    }
                }) { Text(if (deleting == skill.id) "确认删除" else "删除", color = ConsoleColors.danger) }
            }
        }
        SkillGroupTitle("预置", "随 App 打包，只能开关，不能删除")
        preset.forEach { skill -> row(skill, null) }
    }
}

@Composable
private fun SkillGroupTitle(title: String, note: String) {
    Row(Modifier.padding(top = Dimens.spaceSm), verticalAlignment = Alignment.Bottom) {
        Text(title, style = ConsoleType.panelTitle, color = ConsoleColors.text)
        Text("  $note", style = ConsoleType.meta, color = ConsoleColors.muted)
    }
}

/** One skill: its switch, its text on tap, and [actions] (编辑 and 删除) for one added on the device. */
@Composable
private fun SkillRow(
    skill: RuntimeSkill,
    expanded: Boolean,
    onTap: () -> Unit,
    actions: (@Composable () -> Unit)?,
    onSwitch: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    Column {
        Row(Modifier.fillMaxWidth().padding(Dimens.spaceMd), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onTap)) {
                Text(skill.label, style = ConsoleType.body, color = ConsoleColors.text)
                Text(skill.description, style = ConsoleType.meta, color = ConsoleColors.muted)
                Text(if (skill.enabled) "Loaded · tap to preview" else "Not loaded · tap to preview", color = ConsoleColors.muted)
            }
            Switch(skill.enabled, onCheckedChange = onSwitch, colors = switchColors())
        }
        actions?.let { Row(Modifier.padding(horizontal = Dimens.spaceSm)) { it() } }
        if (expanded) Text(runtimeSkillText(context, skill), Modifier.padding(Dimens.spaceMd), color = ConsoleColors.text)
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
    val context = LocalContext.current
    var saved by remember { mutableStateOf(loadJevConfig(context)) }
    var draft by remember { mutableStateOf(ToolSearchDraft.of(saved)) }
    var note by remember { mutableStateOf(SettingsNote()) }
    val apply = { next: JevConfig ->
        val error = saveJevToolSearch(context, next)
        note = SettingsNote(error ?: "已保存 · 下一次请求生效", error = error != null)
        if (error == null) saved = loadJevConfig(context)
        error == null
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).widthIn(max = 880.dp).verticalScroll(rememberScrollState()).padding(Dimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
        ) {
            ToolSearchIntro(saved)
            JevEnabledSwitch(saved.enabled) { apply(saved.copy(enabled = it)) }
            JevModeSelector(saved.mode) { apply(saved.copy(mode = it)) }
            if (saved.mode != JevMode.FLAT) {
                JevField("领域相关度（0–1）", draft.domainMinScore) { draft = draft.copy(domainMinScore = it) }
            }
            JevField("最低相关度（0–1）", draft.minScore) { draft = draft.copy(minScore = it) }
            JevField("最多候选工具（1–24）", draft.maxTools) { draft = draft.copy(maxTools = it) }
        }
        SettingsSaveBar(
            unsaved = draft != ToolSearchDraft.of(saved),
            note = note,
            onSave = { if (apply(draft.applyTo(saved))) draft = ToolSearchDraft.of(saved) },
            onDiscard = { draft = ToolSearchDraft.of(saved); note = SettingsNote("已放弃修改") },
        )
    }
}

@Composable
private fun ToolSearchIntro(saved: JevConfig) {
    Text("第一次调用模型前，先让 Jev 挑出相关的工具；Jev 出错时照常可以搜索工具。", color = ConsoleColors.muted)
    Text(
        if (saved.hasApiKey) "使用 设置 → 语音 里的 Jev 连接：${saved.model} · ${saved.endpoint}"
        else "Jev 还没有 API key：请先在 设置 → 语音 → 密钥 保存，再开启工具检索。",
        style = ConsoleType.bodySmall,
        color = if (saved.hasApiKey) ConsoleColors.muted else ConsoleColors.danger,
    )
}

/** The numbers as typed; they stay text until saved, where validation says what is wrong. */
private data class ToolSearchDraft(val minScore: String, val maxTools: String, val domainMinScore: String) {
    fun applyTo(saved: JevConfig): JevConfig = saved.copy(
        minScore = minScore.toDoubleOrNull() ?: Double.NaN,
        maxTools = maxTools.toIntOrNull() ?: 0,
        domainMinScore = domainMinScore.toDoubleOrNull() ?: Double.NaN,
    )

    companion object {
        fun of(config: JevConfig) = ToolSearchDraft(config.minScore.toString(), config.maxTools.toString(), config.domainMinScore.toString())
    }
}

@Composable
private fun JevEnabledSwitch(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("用 Jev 挑选工具", Modifier.weight(1f), color = ConsoleColors.text)
        Switch(enabled, onChange, colors = switchColors())
    }
}

@Composable
private fun JevModeSelector(mode: JevMode, onChange: (JevMode) -> Unit) {
    Column {
        JevMode.entries.forEach { option ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = mode == option, onClick = { onChange(option) })
                Text(option.label, Modifier.clickable { onChange(option) }, color = ConsoleColors.text)
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
