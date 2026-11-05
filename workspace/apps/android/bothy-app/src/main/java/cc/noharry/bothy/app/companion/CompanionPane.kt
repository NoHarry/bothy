package cc.noharry.bothy.app.companion
import androidx.annotation.StringRes
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.TextParams
import cc.noharry.agent.appserver.protocol.text
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.clockSeconds
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.SettingsNote
import cc.noharry.bothy.app.console.SettingsSaveBar
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.outlinedTextFieldColors
import cc.noharry.bothy.app.console.switchColors
import cc.noharry.bothy.app.console.ui.SelectableOptionRow
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.app.words
import cc.noharry.bothy.protocol.CompanionConfig
import cc.noharry.bothy.protocol.CompanionDecisionView
import cc.noharry.bothy.protocol.CompanionEndpoint
import cc.noharry.bothy.protocol.CompanionMode
import cc.noharry.bothy.protocol.CompanionRequests
import cc.noharry.bothy.protocol.CompanionSaveParams
import cc.noharry.bothy.protocol.CompanionService
import cc.noharry.bothy.protocol.CompanionTryResult
import cc.noharry.bothy.protocol.CompanionView

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
    val server = LocalServer.current
    var view by remember { mutableStateOf<CompanionView?>(null) }
    LaunchedEffect(Unit) { server.send(CompanionRequests.READ, Empty) { view = it } }
    view?.let { CompanionForm(it) { stored -> view = stored } }
}

@Composable
private fun CompanionForm(view: CompanionView, onStored: (CompanionView) -> Unit) {
    val server = LocalServer.current
    val resources = LocalResources.current
    val saved = view.config
    var draft by remember { mutableStateOf(CompanionDraft.of(saved)) }
    // Credentials are never saveable UI state or echoed from storage.
    var key by remember { mutableStateOf("") }
    var note by remember { mutableStateOf(SettingsNote()) }
    val savedNote = stringResource(R.string.companion_saved)
    val discardedNote = stringResource(R.string.companion_discarded)
    val apply = { candidate: CompanionConfig, newKey: String, done: String, then: () -> Unit ->
        server.send(CompanionRequests.SAVE, CompanionSaveParams(candidate, newKey)) { result ->
            note = SettingsNote(result.error?.let(resources::words) ?: done, error = result.error != null)
            onStored(result.view)
            if (result.error == null) {
                draft = CompanionDraft.of(result.view.config)
                then()
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).widthIn(max = 880.dp).verticalScroll(rememberScrollState()).padding(Dimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
        ) {
            CompanionSwitches(saved) { candidate -> apply(candidate, "", savedNote) {} }
            CompanionServiceFields(view, draft, key, { draft = it }, { key = it })
            CompanionTry(saved)
            CompanionDecisions()
        }
        SettingsSaveBar(
            unsaved = draft != CompanionDraft.of(saved) || key.isNotBlank(),
            note = note,
            onSave = { apply(draft.applyTo(saved), key, savedNote) { key = "" } },
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
        SelectableOptionRow(stringResource(mode.screenName), saved.mode == mode, { onApply(saved.copy(mode = mode)) })
    }
    Text(stringResource(R.string.companion_service), style = ConsoleType.label, color = ConsoleColors.muted)
    CompanionService.entries.forEach { service ->
        SelectableOptionRow(
            stringResource(service.screenName),
            saved.service == service,
            { onApply(saved.copy(service = service)) },
            subtitle = stringResource(service.screenHint),
        )
    }
}

@Composable
private fun CompanionServiceFields(
    view: CompanionView,
    draft: CompanionDraft,
    key: String,
    onDraft: (CompanionDraft) -> Unit,
    onKey: (String) -> Unit,
) {
    val saved = view.config
    val ownKey = saved.service in view.ownKeys
    val keyLabel = when {
        ownKey -> stringResource(R.string.companion_key_saved)
        saved.service == CompanionService.QWEN -> stringResource(R.string.companion_key_qwen)
        else -> stringResource(R.string.companion_key)
    }
    val noKey = stringResource(R.string.companion_no_key_yet)
    val hasKey = saved.service in view.usableKeys
    val missing = listOfNotNull(view.notReady?.let { words(it) }, noKey.takeIf { !hasKey })
    if (missing.isNotEmpty()) {
        val name = stringResource(saved.service.screenName)
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
    val server = LocalServer.current
    val resources = LocalResources.current
    var result by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val asking = stringResource(R.string.companion_try_asking, stringResource(saved.service.screenName))
    val words = stringResource(R.string.companion_try_words)
    TextButton(enabled = !busy, onClick = {
        busy = true
        failed = false
        result = asking
        val answered = { tried: CompanionTryResult ->
            val reply = tried.reply
            failed = reply == null
            result = if (reply != null) {
                resources.getString(R.string.companion_try_reply, words, reply, tried.elapsedMillis)
            } else {
                resources.getString(R.string.companion_try_failed, tried.failure?.let(resources::words).orEmpty())
            }
            busy = false
        }
        server.send(
            CompanionRequests.TRY,
            TextParams(words),
            onRefused = { answered(CompanionTryResult(failure = text(it.message.orEmpty()))) },
            onAnswer = answered,
        )
    }) { Text(stringResource(R.string.companion_try)) }
    result?.let { text ->
        Text(text, style = ConsoleType.bodySmall, color = if (failed) ConsoleColors.danger else ConsoleColors.text)
    }
}

@Composable
private fun CompanionDecisions() {
    val server = LocalServer.current
    var decisions by remember { mutableStateOf(emptyList<CompanionDecisionView>()) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) { server.send(CompanionRequests.DECISIONS, Empty) { decisions = it.decisions } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.companion_decisions_title),
            Modifier.weight(1f),
            style = ConsoleType.label,
            color = ConsoleColors.muted,
        )
        TextButton(onClick = { refresh += 1 }) {
            Text(stringResource(R.string.common_refresh))
        }
    }
    if (decisions.isEmpty()) {
        val empty = stringResource(R.string.companion_decisions_empty)
        Text(empty, style = ConsoleType.bodySmall, color = ConsoleColors.muted)
    }
    decisions.forEach { recent ->
        val route = stringResource(
            if (recent.toCompanion) R.string.companion_route_companion else R.string.companion_route_agent,
        )
        Text(
            stringResource(R.string.companion_decision_line, clockSeconds(recent.atEpochMillis), route, words(recent.reason), recent.words),
            style = ConsoleType.bodySmall,
            color = ConsoleColors.text,
        )
    }
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

/** What Settings calls a service, and what it says about it. */
@get:StringRes
private val CompanionService.screenName: Int
    get() = when (this) {
        CompanionService.QWEN -> R.string.companion_service_qwen
        CompanionService.DOUBAO -> R.string.companion_service_doubao
        CompanionService.HUNYUAN -> R.string.companion_service_hunyuan
        CompanionService.CUSTOM -> R.string.companion_service_custom
    }

@get:StringRes
private val CompanionService.screenHint: Int
    get() = when (this) {
        CompanionService.QWEN -> R.string.companion_service_qwen_hint
        CompanionService.DOUBAO -> R.string.companion_service_doubao_hint
        CompanionService.HUNYUAN -> R.string.companion_service_hunyuan_hint
        CompanionService.CUSTOM -> R.string.companion_service_custom_hint
    }

@get:StringRes
private val CompanionMode.screenName: Int
    get() = when (this) {
        CompanionMode.OBSERVE -> R.string.companion_mode_observe
        CompanionMode.ROUTE -> R.string.companion_mode_route
    }
