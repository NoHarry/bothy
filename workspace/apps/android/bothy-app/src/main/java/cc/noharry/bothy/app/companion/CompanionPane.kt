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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.R
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
    val savedNote = stringResource(R.string.companion_saved)
    val discardedNote = stringResource(R.string.companion_discarded)
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
            CompanionSwitches(saved) { candidate -> apply(candidate, "", savedNote) }
            CompanionServiceFields(saved, draft, key, { draft = it }, { key = it })
            CompanionTry(saved)
            CompanionDecisions()
        }
        SettingsSaveBar(
            unsaved = draft != CompanionDraft.of(saved) || key.isNotBlank(),
            note = note,
            onSave = { if (apply(draft.applyTo(saved), key, savedNote)) key = "" },
            onDiscard = { draft = CompanionDraft.of(saved); key = ""; note = SettingsNote(discardedNote) },
        )
    }
}

@Composable
private fun CompanionSwitches(saved: CompanionConfig, onApply: (CompanionConfig) -> Unit) {
    Text(
        stringResource(R.string.companion_intro),
        color = ConsoleColors.muted,
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.companion_enable), Modifier.weight(1f), color = ConsoleColors.text)
        Switch(saved.enabled, { onApply(saved.copy(enabled = it)) }, colors = switchColors())
    }
    CompanionMode.entries.forEach { mode ->
        SelectableOptionRow(stringResource(mode.label), saved.mode == mode, { onApply(saved.copy(mode = mode)) })
    }
    Text(stringResource(R.string.companion_service), style = ConsoleType.label, color = ConsoleColors.muted)
    CompanionService.entries.forEach { service ->
        SelectableOptionRow(
            stringResource(service.label),
            saved.service == service,
            { onApply(saved.copy(service = service)) },
            subtitle = stringResource(service.hint),
        )
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
        ownKey -> stringResource(R.string.companion_key_saved)
        saved.service == CompanionService.QWEN -> stringResource(R.string.companion_key_qwen)
        else -> stringResource(R.string.companion_key)
    }
    val noKey = stringResource(R.string.companion_no_key_yet)
    val hasKey = CompanionKeys.read(context, saved.service).isNotEmpty()
    val missing = listOfNotNull(saved.readinessError(context), noKey.takeIf { !hasKey })
    if (missing.isNotEmpty()) {
        val name = stringResource(saved.service.label)
        Text(
            if (missing.size == 1) {
                stringResource(R.string.companion_missing_one, name, missing[0])
            } else {
                stringResource(R.string.companion_missing_two, name, missing[0], missing[1])
            },
            style = ConsoleType.bodySmall,
            color = ConsoleColors.warning,
        )
    }
    val address = stringResource(R.string.companion_field_address)
    val extra = stringResource(R.string.companion_field_extra)
    val threshold = stringResource(R.string.companion_field_threshold)
    val persona = stringResource(R.string.companion_field_persona)
    CompanionField(address, draft.baseUrl, { onDraft(draft.copy(baseUrl = it)) })
    CompanionField(stringResource(R.string.companion_field_model), draft.model, { onDraft(draft.copy(model = it)) })
    CompanionField(keyLabel, key, onKey, secret = true)
    CompanionField(extra, draft.extraBody, { onDraft(draft.copy(extraBody = it)) })
    CompanionField(threshold, draft.threshold, { onDraft(draft.copy(threshold = it)) })
    CompanionField(persona, draft.persona, { onDraft(draft.copy(persona = it)) }, singleLine = false)
}

@Composable
private fun CompanionTry(saved: CompanionConfig) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val asking = stringResource(R.string.companion_try_asking, stringResource(saved.service.label))
    val words = stringResource(R.string.companion_try_words)
    TextButton(enabled = !busy, onClick = {
        busy = true
        failed = false
        result = asking
        scope.launch {
            val started = System.currentTimeMillis()
            result = runCatching { SmallTalkCompanion.ask(context, saved, words) }.fold(
                onSuccess = { reply ->
                    resources.getString(R.string.companion_try_reply, words, reply, System.currentTimeMillis() - started)
                },
                onFailure = {
                    failed = true
                    resources.getString(R.string.companion_try_failed, companionFailureText(context, it))
                },
            )
            busy = false
        }
    }) { Text(stringResource(R.string.companion_try)) }
    result?.let { text ->
        Text(text, style = ConsoleType.bodySmall, color = if (failed) ConsoleColors.danger else ConsoleColors.text)
    }
}

@Composable
private fun CompanionDecisions() {
    var decisions by remember { mutableStateOf(SmallTalkCompanion.recentDecisions()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.companion_decisions_title),
            Modifier.weight(1f),
            style = ConsoleType.label,
            color = ConsoleColors.muted,
        )
        TextButton(onClick = { decisions = SmallTalkCompanion.recentDecisions() }) {
            Text(stringResource(R.string.common_refresh))
        }
    }
    if (decisions.isEmpty()) {
        val empty = stringResource(R.string.companion_decisions_empty)
        Text(empty, style = ConsoleType.bodySmall, color = ConsoleColors.muted)
    }
    decisions.forEach { recent ->
        val route = stringResource(
            if (recent.decision.toCompanion) R.string.companion_route_companion else R.string.companion_route_agent,
        )
        val reason = reasonText(recent.decision.why)
        Text(
            stringResource(R.string.companion_decision_line, recent.time, route, reason, recent.words),
            style = ConsoleType.bodySmall,
            color = ConsoleColors.text,
        )
    }
}

@Composable
private fun reasonText(why: CompanionRouteReason): String = when (why) {
    CompanionRouteReason.Off -> stringResource(R.string.companion_reason_off)
    CompanionRouteReason.NotScored -> stringResource(R.string.companion_reason_not_scored)
    is CompanionRouteReason.BelowThreshold -> stringResource(R.string.companion_reason_below, why.score, why.threshold)
    is CompanionRouteReason.TaskWord -> stringResource(R.string.companion_reason_task_word, why.word)
    CompanionRouteReason.AnswersAgent -> stringResource(R.string.companion_reason_answers_agent)
    is CompanionRouteReason.Observing -> stringResource(R.string.companion_reason_observing, why.score)
    is CompanionRouteReason.SmallTalk -> stringResource(R.string.companion_reason_small_talk, why.score)
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
