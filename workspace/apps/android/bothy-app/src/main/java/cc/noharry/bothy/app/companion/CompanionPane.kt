package cc.noharry.bothy.app.companion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import cc.noharry.bothy.app.console.ui.SelectableOptionRow
import kotlinx.coroutines.launch

/** The typed fields of the chosen service and the shared ones; numbers stay text until saved. */
private data class CompanionDraft(val baseUrl: String, val model: String, val extraBody: String, val threshold: String, val persona: String) {
    fun applyTo(saved: CompanionConfig): CompanionConfig = saved.copy(
        threshold = threshold.toDoubleOrNull() ?: -1.0,
        persona = persona,
        endpoints = saved.endpoints + (saved.service to CompanionEndpoint(baseUrl, model, extraBody)),
    )

    companion object {
        fun of(config: CompanionConfig) = config.endpoint().let {
            CompanionDraft(it.baseUrl, it.model, it.extraBody, config.threshold.toString(), config.persona)
        }
    }
}

/**
 * 设置 → 陪聊: the switch, observe or route, the service and its address, model, key and extra fields, the persona,
 * a 试一试, and the latest routing decisions (what observe mode is for).
 */
@Composable
internal fun CompanionPane() {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(loadCompanionConfig(context)) }
    var draft by remember { mutableStateOf(CompanionDraft.of(saved)) }
    // Credentials are never saveable UI state or echoed from storage.
    var key by remember { mutableStateOf("") }
    var note by remember { mutableStateOf(SettingsNote()) }
    val apply = { candidate: CompanionConfig, newKey: String, done: String ->
        val error = saveCompanionConfig(context, candidate, newKey)
        note = SettingsNote(error ?: done, error = error != null)
        if (error == null) {
            saved = loadCompanionConfig(context)
            draft = CompanionDraft.of(saved)
        }
        error == null
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).widthIn(max = 880.dp).verticalScroll(rememberScrollState()).padding(Dimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
        ) {
            CompanionSwitches(saved) { candidate -> apply(candidate, "", COMPANION_SAVED) }
            CompanionServiceFields(saved, draft, key, { draft = it }, { key = it })
            CompanionTry(saved)
            CompanionDecisions()
        }
        SettingsSaveBar(
            unsaved = draft != CompanionDraft.of(saved) || key.isNotBlank(),
            note = note,
            onSave = { if (apply(draft.applyTo(saved), key, COMPANION_SAVED)) key = "" },
            onDiscard = { draft = CompanionDraft.of(saved); key = ""; note = SettingsNote("已放弃修改") },
        )
    }
}

@Composable
private fun CompanionSwitches(saved: CompanionConfig, onApply: (CompanionConfig) -> Unit) {
    Text(
        "语音里的闲聊（聊天、倾诉、讲笑话）交给陪聊服务，用小博自己的声音回答；要办事的话照常交给小博。Jev 每轮多打一个「是不是闲聊」的分，" +
            "说到导航、空调、放歌这类词或小博刚问了问题时一律交给小博；陪聊服务失败或判断是要办事时也会转回小博。",
        color = ConsoleColors.muted,
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("启用陪聊", Modifier.weight(1f), color = ConsoleColors.text)
        Switch(saved.enabled, { onApply(saved.copy(enabled = it)) }, colors = switchColors())
    }
    CompanionMode.entries.forEach { mode ->
        SelectableOptionRow(mode.label, saved.mode == mode, { onApply(saved.copy(mode = mode)) })
    }
    Text("陪聊服务", style = ConsoleType.label, color = ConsoleColors.muted)
    CompanionService.entries.forEach { service ->
        SelectableOptionRow(service.label, saved.service == service, { onApply(saved.copy(service = service)) }, subtitle = service.hint)
    }
}

@Composable
private fun CompanionServiceFields(
    saved: CompanionConfig,
    draft: CompanionDraft,
    key: String,
    onDraft: (CompanionDraft) -> Unit,
    onKey: (String) -> Unit,
) {
    val context = LocalContext.current
    val ownKey = remember(saved.service, saved) { CompanionKeys.hasOwn(context, saved.service) }
    val keyLabel = when {
        ownKey -> "API key · 已保存（留空保持不变）"
        saved.service == CompanionService.QWEN -> "API key（留空用语音识别的百炼 key）"
        else -> "API key"
    }
    val missing = listOfNotNull(saved.readinessError(), "还没有 API key".takeIf { CompanionKeys.read(context, saved.service).isEmpty() })
    if (missing.isNotEmpty()) {
        Text(
            "${saved.service.label}：${missing.joinToString("，")}。填好保存前，闲聊会全部交给小博。",
            style = ConsoleType.bodySmall,
            color = ConsoleColors.warning,
        )
    }
    CompanionField("地址（到 /v1 这一级，不含 /chat/completions）", draft.baseUrl, { onDraft(draft.copy(baseUrl = it)) })
    CompanionField("模型", draft.model, { onDraft(draft.copy(model = it)) })
    CompanionField(keyLabel, key, onKey, secret = true)
    CompanionField("额外参数（JSON，可不填，比如 {\"enable_thinking\": false}）", draft.extraBody, { onDraft(draft.copy(extraBody = it)) })
    CompanionField("灵敏度（0.5–1，闲聊分数到这里才算闲聊；越高越保守）", draft.threshold, { onDraft(draft.copy(threshold = it)) })
    CompanionField("人设（系统提示）", draft.persona, { onDraft(draft.copy(persona = it)) }, singleLine = false)
}

@Composable
private fun CompanionTry(saved: CompanionConfig) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    TextButton(enabled = !busy, onClick = {
        busy = true
        result = "正在问${saved.service.label}…"
        scope.launch {
            val started = System.currentTimeMillis()
            result = runCatching { SmallTalkCompanion.ask(context, saved, COMPANION_TRY_WORDS) }.fold(
                onSuccess = { "「$COMPANION_TRY_WORDS」→ $it（${System.currentTimeMillis() - started} ms）" },
                onFailure = { "失败：${it.message ?: it.javaClass.simpleName}" },
            )
            busy = false
        }
    }) { Text("试一试（用已保存的设置）") }
    result?.let { Text(it, style = ConsoleType.bodySmall, color = if (it.startsWith("失败")) ConsoleColors.danger else ConsoleColors.text) }
}

@Composable
private fun CompanionDecisions() {
    var decisions by remember { mutableStateOf(SmallTalkCompanion.recentDecisions()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("最近的分流判断（本次启动以来）", Modifier.weight(1f), style = ConsoleType.label, color = ConsoleColors.muted)
        TextButton(onClick = { decisions = SmallTalkCompanion.recentDecisions() }) { Text("刷新") }
    }
    if (decisions.isEmpty()) Text("还没有语音轮次经过判断。", style = ConsoleType.bodySmall, color = ConsoleColors.muted)
    decisions.forEach { Text(it, style = ConsoleType.bodySmall, color = ConsoleColors.text) }
}

@Composable
private fun CompanionField(label: String, value: String, onChange: (String) -> Unit, secret: Boolean = false, singleLine: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        colors = outlinedTextFieldColors(),
    )
}

private const val COMPANION_SAVED = "已保存，下一轮语音生效"
private const val COMPANION_TRY_WORDS = "今天有点累"
