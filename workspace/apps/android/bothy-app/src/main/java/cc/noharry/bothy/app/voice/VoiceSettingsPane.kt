package cc.noharry.bothy.app.voice
import android.content.Intent
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.FlagParams
import cc.noharry.agent.appserver.protocol.TextParams
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.SettingsNote
import cc.noharry.bothy.app.console.SettingsSaveBar
import cc.noharry.bothy.app.console.SettingsTabs
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.outlinedTextFieldColors
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.console.switchColors
import cc.noharry.bothy.app.console.ui.SelectableOptionRow
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.app.frontend.ServerAccess
import cc.noharry.bothy.app.words
import cc.noharry.bothy.protocol.DoubaoTtsView
import cc.noharry.bothy.protocol.JevConnectionDraft
import cc.noharry.bothy.protocol.JevSettings
import cc.noharry.bothy.protocol.SettingsOption
import cc.noharry.bothy.protocol.TtsChoice
import cc.noharry.bothy.protocol.TtsChoiceParams
import cc.noharry.bothy.protocol.TtsProvider
import cc.noharry.bothy.protocol.TtsSettings
import cc.noharry.bothy.protocol.VoiceEngine
import cc.noharry.bothy.protocol.VoiceKey
import cc.noharry.bothy.protocol.VoiceKeyParams
import cc.noharry.bothy.protocol.VoiceRequests
import cc.noharry.bothy.protocol.VoiceSettings
import cc.noharry.bothy.protocol.VoiceSettingsRequests
import cc.noharry.bothy.protocol.VoiceSettingsSaveParams
import cc.noharry.bothy.protocol.VoiceSettingsView
import cc.noharry.bothy.protocol.parseHotwords
import cc.noharry.bothy.protocol.voice

private enum class VoiceSettingsTab(@StringRes val label: Int) {
    KEYS(R.string.voice_tab_keys),
    RECOGNITION(R.string.voice_tab_recognition),
    TURNS(R.string.voice_tab_turns),
    SPEECH(R.string.voice_tab_speech),
}

/** Saves one choice of the voice settings at once, leaving typed edits pending. */
private typealias VoiceChoice = ((VoiceSettings) -> VoiceSettings) -> Unit

/**
 * Settings → 语音. 密钥 holds every key a voice turn needs, with whether each is there: 百炼 (cloud recognition), Jev
 * (judges each stretch; tool search shares it) and MiMo (speaks the reply). The other tabs only change settings and
 * send the user back to 密钥 when a key is missing. Choices (options, chips, switches) apply when tapped; typed fields
 * wait for 保存, and while any wait the foot turns into a highlighted 有未保存的修改 bar and their tab carries a dot.
 */
@Composable
internal fun VoiceSettingsPane() {
    val server = LocalServer.current
    var loaded by remember { mutableStateOf<VoiceSettingsView?>(null) }
    LaunchedEffect(Unit) { server.send(VoiceSettingsRequests.READ, Empty) { loaded = it } }
    loaded?.let { VoiceSettingsPage(it) }
}

@Composable
private fun VoiceSettingsPage(first: VoiceSettingsView) {
    val server = LocalServer.current
    val resources = LocalResources.current
    val state = remember { VoiceSettingsState(server, resources, first) }
    state.server = server
    var tabName by rememberSaveable { mutableStateOf(VoiceSettingsTab.KEYS.name) }
    val tab = VoiceSettingsTab.valueOf(tabName)
    val unsaved = unsavedTabs(state.forms, state.tts)
    Column(Modifier.fillMaxSize()) {
        val labels = VoiceSettingsTab.entries.map {
            val label = stringResource(it.label)
            if (it in unsaved) "$label ●" else label
        }
        SettingsTabs(labels, labels[tab.ordinal]) { label -> tabName = VoiceSettingsTab.entries[labels.indexOf(label)].name }
        Column(
            Modifier.weight(1f).widthIn(max = 880.dp).verticalScroll(rememberScrollState()).padding(Dimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
        ) {
            when (tab) {
                VoiceSettingsTab.KEYS -> VoiceKeysTab(state)
                VoiceSettingsTab.RECOGNITION -> VoiceRecognitionTab(state)
                VoiceSettingsTab.TURNS -> VoiceTurnsTab(state.forms, { state.forms = it }, state::chooseVoice)
                VoiceSettingsTab.SPEECH -> VoiceSpeechTab(state)
            }
        }
        SettingsSaveBar(unsaved.isNotEmpty(), state.note, state::save, state::discard)
    }
}

/** The page as the server last answered, its forms and its last note: a choice is saved when tapped, typed edits on [save]. */
private class VoiceSettingsState(
    var server: ServerAccess,
    private val resources: Resources,
    first: VoiceSettingsView,
) {
    var view by mutableStateOf(first)
    var forms by mutableStateOf(VoiceForms.of(first))
    var tts by mutableStateOf(TtsForm.of(first))
    var note by mutableStateOf(SettingsNote())

    fun message(text: String) {
        note = SettingsNote(text)
    }

    fun chooseVoice(change: (VoiceSettings) -> VoiceSettings) {
        server.send(VoiceSettingsRequests.SAVE_VOICE, change(forms.saved)) { result ->
            noted(result.error)
            view = result.view
            if (result.error == null) forms = forms.withSaved(result.view.voice)
        }
    }

    fun chooseTts(choice: TtsChoice, value: String) {
        server.send(VoiceSettingsRequests.CHOOSE_TTS, TtsChoiceParams(choice, value)) { result ->
            noted(result.error)
            if (result.error != null) return@send
            view = result.view
            val fresh = TtsForm.of(result.view)
            tts = fresh.copy(draft = tts.draft.withChoicesFrom(fresh.effective), key = tts.key)
        }
    }

    fun save() {
        server.send(VoiceSettingsRequests.SAVE, saveParams(forms, tts)) { result ->
            noted(result.error)
            if (result.error == null) reset(result.view)
        }
    }

    fun discard() {
        server.send(VoiceSettingsRequests.READ, Empty) { fresh ->
            reset(fresh)
            message(resources.getString(R.string.voice_discarded))
        }
    }

    /** Deletes [key]; [done] says it in words and [then] updates the forms from what is now stored. */
    fun deleteKey(key: VoiceKey, done: String, then: (VoiceSettingsView) -> Unit) {
        server.send(VoiceSettingsRequests.DELETE_KEY, VoiceKeyParams(key)) { result ->
            message(result.error?.let(resources::words) ?: done)
            view = result.view
            if (result.error == null) then(result.view)
        }
    }

    fun setWakeLevel(id: String, onSaved: (failed: Boolean) -> Unit) {
        server.send(VoiceSettingsRequests.SET_WAKE_LEVEL, TextParams(id)) { result ->
            view = result.view
            if (result.error == null) forms = forms.withSaved(result.view.voice)
            onSaved(result.error != null)
        }
    }

    private fun reset(fresh: VoiceSettingsView) {
        view = fresh
        forms = VoiceForms.of(fresh)
        tts = TtsForm.of(fresh)
    }

    private fun noted(error: Finding?) {
        val saved = if (server.state.voice.listening) R.string.voice_saved_after_restart else R.string.voice_saved
        note = SettingsNote(error?.let(resources::words) ?: resources.getString(saved), error = error != null)
    }
}

/** The tabs whose typed fields differ from what is saved; a typed key counts until it is saved. */
private fun unsavedTabs(forms: VoiceForms, tts: TtsForm): Set<VoiceSettingsTab> {
    val saved = VoiceDraft.of(forms.saved)
    val draft = forms.voice
    return buildSet {
        if (forms.asrKey.isNotBlank() || forms.jevKey.isNotBlank() || tts.key.isNotBlank() || tts.doubaoKey.isNotBlank()) {
            add(VoiceSettingsTab.KEYS)
        }
        if (draft.recognitionTyped() != saved.recognitionTyped()) add(VoiceSettingsTab.RECOGNITION)
        if (draft.turnsTyped() != saved.turnsTyped() || forms.jev.differsFrom(forms.jevSaved)) add(VoiceSettingsTab.TURNS)
        if (tts.draft.typed() != tts.effective.typed()) add(VoiceSettingsTab.SPEECH)
    }
}

private fun TtsSettings.typed() = listOf(baseUrl, model, style, doubao.appId, doubao.resourceId)

private fun TtsSettings.withChoicesFrom(saved: TtsSettings) = copy(
    enabled = saved.enabled, voice = saved.voice, stream = saved.stream, provider = saved.provider,
    doubao = doubao.copy(voice = saved.doubao.voice, speechRate = saved.doubao.speechRate),
)

/** Settings → 语音 as typed. Keys are never saveable state or echoed from storage; each is written only when typed. */
private data class VoiceForms(
    val saved: VoiceSettings,
    val voice: VoiceDraft,
    val asrKey: String,
    val jevSaved: JevSettings,
    val jev: JevDraft,
    val jevKey: String,
) {
    /** After a choice was saved: the saved settings, with the draft's choices following them and its typed edits kept. */
    fun withSaved(next: VoiceSettings): VoiceForms = copy(saved = next, voice = voice.withChoicesFrom(next))

    companion object {
        fun of(view: VoiceSettingsView) = VoiceForms(view.voice, VoiceDraft.of(view.voice), "", view.jev, JevDraft.of(view.jev), "")
    }
}

/** The Jev connection as typed; tool search's own settings stay on 工具与技能 → 工具检索. */
private data class JevDraft(val endpoint: String, val model: String, val timeout: String) {
    fun differsFrom(saved: JevSettings): Boolean =
        endpoint.trim() != saved.endpoint || model.trim() != saved.model || timeout.trim() != saved.timeoutMillis.toString()

    companion object {
        fun of(config: JevSettings) = JevDraft(config.endpoint, config.model, config.timeoutMillis.toString())
    }
}

/** What replies are spoken with now ([effective]: app settings over local.properties) and the form as typed. */
private data class TtsForm(
    val effective: TtsSettings,
    val draft: TtsSettings,
    val key: String,
    val keyStored: Boolean,
    val doubaoKey: String = "",
    val doubaoKeyStored: Boolean = false,
) {
    companion object {
        fun of(view: VoiceSettingsView) = TtsForm(view.tts, view.tts, "", view.ttsKeyStored, "", view.doubaoKeyStored)
    }
}

/** What 保存 sends: everything typed; Jev and speech only when they changed, keys only when typed. */
private fun saveParams(forms: VoiceForms, tts: TtsForm) = VoiceSettingsSaveParams(
    voice = forms.voice.applyTo(forms.saved),
    asrKey = forms.asrKey,
    jev = if (forms.jev.differsFrom(forms.jevSaved) || forms.jevKey.isNotBlank()) {
        JevConnectionDraft(forms.jev.endpoint, forms.jev.model, forms.jev.timeout.trim().toLongOrNull() ?: 0, forms.jevKey)
    } else {
        null
    },
    tts = tts.draft.takeIf { it.typed() != tts.effective.typed() },
    ttsKey = tts.key,
    doubaoKey = tts.doubaoKey,
)

@Composable
private fun VoiceKeysTab(state: VoiceSettingsState) {
    Text(stringResource(R.string.voice_keys_intro), color = ConsoleColors.muted)
    DashScopeKeyCard(state)
    JevKeyCard(state)
    MimoKeyCard(state)
    DoubaoKeyCard(state)
}

@Composable
private fun DashScopeKeyCard(state: VoiceSettingsState) {
    val forms = state.forms
    val saved = forms.saved
    val status = when {
        saved.hasDashscopeKey -> stringResource(R.string.voice_saved) to ConsoleColors.success
        saved.engine == VoiceEngine.DASHSCOPE ->
            stringResource(R.string.voice_dashscope_key_missing_status) to ConsoleColors.danger
        else -> stringResource(R.string.voice_dashscope_key_optional) to ConsoleColors.muted
    }
    val deleted = stringResource(R.string.voice_dashscope_key_deleted)
    VoiceCard(stringResource(R.string.voice_dashscope_title), status) {
        VoiceNote(stringResource(R.string.voice_dashscope_key_note))
        VoiceField(keyLabel(saved.hasDashscopeKey), forms.asrKey, { state.forms = forms.copy(asrKey = it) }, secret = true)
        DeleteKeyButton(saved.hasDashscopeKey) {
            state.deleteKey(VoiceKey.DASHSCOPE, deleted) { stored ->
                val now = state.forms
                state.forms = now.copy(saved = stored.voice, voice = now.voice.copy(engine = VoiceEngine.LOCAL), asrKey = "")
            }
        }
    }
}

@Composable
private fun JevKeyCard(state: VoiceSettingsState) {
    val forms = state.forms
    val saved = forms.jevSaved
    val status = if (saved.hasApiKey) {
        stringResource(R.string.voice_saved) to ConsoleColors.success
    } else {
        stringResource(R.string.voice_jev_key_missing_status) to ConsoleColors.danger
    }
    val deleted = stringResource(
        if (saved.enabled) R.string.voice_jev_key_deleted_search_off else R.string.voice_jev_key_deleted,
    )
    VoiceCard("Jev", status) {
        VoiceNote(stringResource(R.string.voice_jev_key_note))
        VoiceField(keyLabel(saved.hasApiKey), forms.jevKey, { state.forms = forms.copy(jevKey = it) }, secret = true)
        DeleteKeyButton(saved.hasApiKey) {
            state.deleteKey(VoiceKey.JEV, deleted) { stored -> state.forms = state.forms.copy(jevSaved = stored.jev, jevKey = "") }
        }
    }
}

@Composable
private fun MimoKeyCard(state: VoiceSettingsState) {
    val tts = state.tts
    val packaged = !tts.keyStored && tts.effective.hasApiKey
    val status = when {
        tts.keyStored -> stringResource(R.string.voice_saved) to ConsoleColors.success
        packaged -> stringResource(R.string.voice_mimo_key_packaged) to ConsoleColors.success
        else -> stringResource(R.string.voice_key_missing_silent) to ConsoleColors.danger
    }
    val deleted = stringResource(R.string.voice_mimo_key_deleted)
    VoiceCard("MiMo", status) {
        VoiceNote(stringResource(R.string.voice_mimo_key_note))
        val label = if (packaged) stringResource(R.string.voice_mimo_key_label_packaged) else keyLabel(tts.keyStored)
        VoiceField(label, tts.key, { state.tts = tts.copy(key = it) }, secret = true)
        DeleteKeyButton(tts.keyStored) { state.deleteKey(VoiceKey.MIMO, deleted) { state.tts = TtsForm.of(it) } }
    }
}

@Composable
private fun VoiceRecognitionTab(state: VoiceSettingsState) {
    val forms = state.forms
    val localError = state.view.localAsrProblem?.let { words(it) }
    val saved = forms.saved
    val draft = forms.voice
    val choose: VoiceChoice = state::chooseVoice
    val onDraft = { next: VoiceDraft -> state.forms = forms.copy(voice = next) }
    val status = when {
        saved.engine == VoiceEngine.LOCAL -> (localError ?: stringResource(R.string.voice_local_ready)) to
            (if (localError == null) ConsoleColors.success else ConsoleColors.danger)
        saved.hasDashscopeKey ->
            stringResource(R.string.voice_dashscope_ready, saved.dashscopeModel) to ConsoleColors.success
        else -> stringResource(R.string.voice_dashscope_key_missing_go) to ConsoleColors.danger
    }
    VoiceCard(stringResource(R.string.voice_recognizer), status) {
        VoiceEngineOptions(draft.engine, localError) { engine -> choose { it.copy(engine = engine) } }
        if (draft.engine == VoiceEngine.LOCAL) {
            val label = stringResource(R.string.voice_cpu_threads)
            VoiceField(label, draft.threads, { onDraft(draft.copy(threads = it)) }, number = true)
        } else {
            DashScopeSettings(state.view.asrModels, draft, onDraft, choose)
        }
    }
    VoiceCaptureCard(state.view.echoCancellerAvailable, saved, draft, onDraft, choose)
    VoiceCard(stringResource(R.string.voice_wake_title)) { VoiceWakeSection(state) }
}

@Composable
private fun VoiceCaptureCard(aecAvailable: Boolean, saved: VoiceSettings, draft: VoiceDraft, onDraft: (VoiceDraft) -> Unit, choose: VoiceChoice) {
    VoiceCard(stringResource(R.string.voice_capture)) {
        VoiceSwitchRow(stringResource(R.string.voice_echo_cancel), draft.echoCancel) { on ->
            choose { it.copy(echoCancel = on) }
        }
        EchoCancelNote(aecAvailable)
        VoiceSwitchRow(stringResource(R.string.voice_near_gate), saved.nearGate) { on ->
            choose { it.copy(nearGate = on) }
        }
        if (saved.nearGate) {
            val label = stringResource(R.string.voice_near_gate_db)
            VoiceField(label, draft.nearBelow, { onDraft(draft.copy(nearBelow = it)) }, number = true)
        }
    }
}

@Composable
private fun VoiceEngineOptions(selected: VoiceEngine, localError: String?, onSelect: (VoiceEngine) -> Unit) {
    SelectableOptionRow(
        stringResource(R.string.voice_engine_local),
        selected == VoiceEngine.LOCAL,
        { onSelect(VoiceEngine.LOCAL) },
        subtitle = localError ?: stringResource(R.string.voice_engine_local_detail),
    )
    SelectableOptionRow(
        stringResource(R.string.voice_engine_dashscope),
        selected == VoiceEngine.DASHSCOPE,
        { onSelect(VoiceEngine.DASHSCOPE) },
        subtitle = stringResource(R.string.voice_engine_dashscope_detail),
    )
}

@Composable
private fun DashScopeSettings(models: List<SettingsOption>, draft: VoiceDraft, onDraft: (VoiceDraft) -> Unit, choose: VoiceChoice) {
    Text(stringResource(R.string.voice_recognition_model), style = ConsoleType.label, color = ConsoleColors.muted)
    models.forEach { model ->
        SelectableOptionRow(model.label, draft.model == model.id, { choose { it.copy(dashscopeModel = model.id) } }, subtitle = model.id)
    }
    VoiceField(stringResource(R.string.voice_websocket_address), draft.endpoint, { onDraft(draft.copy(endpoint = it)) })
    VoiceField(
        stringResource(R.string.voice_hotwords),
        draft.hotwords,
        { onDraft(draft.copy(hotwords = it)) },
    )
}

@Composable
private fun VoiceTurnsTab(forms: VoiceForms, onForms: (VoiceForms) -> Unit, choose: VoiceChoice) {
    val onDraft = { next: VoiceDraft -> onForms(forms.copy(voice = next)) }
    VoiceNote(stringResource(R.string.voice_turns_intro))
    VoiceCard(stringResource(R.string.voice_turn_judging)) {
        VoiceSwitchRow(stringResource(R.string.voice_jev_turns), forms.voice.jevTurns) { on ->
            choose { it.copy(jevTurns = on) }
        }
    }
    JevConnectionCard(forms, onForms)
    VoiceCard(stringResource(R.string.voice_thresholds)) { VoiceJudgeSection(forms.voice, onDraft, choose) }
    VoiceCard(stringResource(R.string.voice_barge_in_title)) { VoiceBargeInSection(forms.voice, onDraft, choose) }
}

@Composable
private fun JevConnectionCard(forms: VoiceForms, onForms: (VoiceForms) -> Unit) {
    val saved = forms.jevSaved
    val status = when {
        !forms.saved.jevTurns -> stringResource(R.string.voice_jev_off) to ConsoleColors.muted
        saved.hasApiKey -> stringResource(R.string.voice_ready_detail, saved.model) to ConsoleColors.success
        else -> stringResource(R.string.voice_jev_no_key) to ConsoleColors.muted
    }
    VoiceCard(stringResource(R.string.voice_jev_connection), status) {
        VoiceNote(stringResource(R.string.voice_jev_connection_note))
        val jev = forms.jev
        VoiceFieldRow(
            {
                val label = stringResource(R.string.voice_address)
                VoiceField(label, jev.endpoint, { onForms(forms.copy(jev = jev.copy(endpoint = it))) }, it)
            },
            {
                val label = stringResource(R.string.voice_model)
                VoiceField(label, jev.model, { onForms(forms.copy(jev = jev.copy(model = it))) }, it)
            },
        )
        val timeout = stringResource(R.string.voice_jev_timeout)
        VoiceField(timeout, jev.timeout, { onForms(forms.copy(jev = jev.copy(timeout = it))) }, number = true)
    }
}

@Composable
private fun VoiceSpeechTab(state: VoiceSettingsState) {
    val tts = state.tts
    val onTts = { next: TtsForm -> state.tts = next }
    val choose: (TtsChoice, String) -> Unit = state::chooseTts
    TtsProviderPicker(tts.effective.provider) { choose(TtsChoice.PROVIDER, it.wireName) }
    if (tts.effective.provider == TtsProvider.DOUBAO) return DoubaoSpeechCard(state)
    if (tts.effective.provider == TtsProvider.SYSTEM) return SystemSpeechCard(state)
    val status = tts.effective.status?.let { words(it) }.orEmpty() to (if (tts.effective.ready) ConsoleColors.success else ConsoleColors.danger)
    VoiceCard(stringResource(R.string.voice_speech_mimo_title), status) {
        val draft = tts.draft
        val edit = { next: TtsSettings -> onTts(tts.copy(draft = next)) }
        VoiceSwitchRow(stringResource(R.string.voice_speak_replies), draft.enabled) {
            choose(TtsChoice.ENABLED, it.toString())
        }
        VoiceFieldRow(
            {
                val label = stringResource(R.string.voice_address)
                VoiceField(label, draft.baseUrl, { edit(draft.copy(baseUrl = it)) }, it)
            },
            { VoiceField(stringResource(R.string.voice_model), draft.model, { edit(draft.copy(model = it)) }, it) },
        )
        MimoVoicePicker(state.view.mimoVoices, draft.voice) { choose(TtsChoice.VOICE, it) }
        val style = stringResource(R.string.voice_tts_style)
        VoiceField(style, draft.style, { edit(draft.copy(style = it)) }, singleLine = false)
        VoiceSwitchRow(stringResource(R.string.voice_tts_stream), draft.stream) {
            choose(TtsChoice.STREAM, it.toString())
        }
        VoiceNote(stringResource(R.string.voice_speech_note))
        PreviewButton(tts.effective.ready, R.string.voice_preview_saved, state::message)
    }
}

/** 试听: speaks a line with the saved settings; [onMessage] hears [starting], then whether speech started. */
@Composable
private fun PreviewButton(enabled: Boolean, @StringRes starting: Int, onMessage: (String) -> Unit) {
    val server = LocalServer.current
    val startingText = stringResource(starting)
    val started = stringResource(R.string.voice_preview_started)
    val failed = stringResource(R.string.voice_preview_failed)
    TextButton(enabled = enabled, onClick = {
        onMessage(startingText)
        server.send(VoiceSettingsRequests.PREVIEW, Empty, onRefused = { onMessage(failed) }) { onMessage(if (it.on) started else failed) }
    }) { Text(stringResource(R.string.voice_preview)) }
}

/** 音色: the mimo-v2.5-tts presets as chips; a voice set elsewhere (local.properties, the provider file) keeps a chip. */
@Composable
private fun MimoVoicePicker(voices: List<SettingsOption>, selected: String, onSelect: (String) -> Unit) {
    Text(stringResource(R.string.voice_voice), style = ConsoleType.label, color = ConsoleColors.muted)
    voices.partition { it.summary == CHINESE_VOICE }.toList().forEach { group ->
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
            group.forEach { voice -> VoiceChip(voice.label, selected == voice.id) { onSelect(voice.id) } }
        }
    }
    if (selected.isNotBlank() && voices.none { it.id == selected }) {
        VoiceChip(stringResource(R.string.voice_other_voice, selected), selected = true) {}
    }
    VoiceNote(stringResource(R.string.voice_mimo_presets_note))
}

@Composable
private fun VoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Dimens.radiusFull)
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (selected) ConsoleColors.accentSoft else ConsoleColors.elevated)
            .border(1.dp, if (selected) ConsoleColors.accent else ConsoleColors.borderSubtle, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.spaceMd),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = ConsoleType.bodySmall, color = if (selected) ConsoleColors.accent else ConsoleColors.text)
    }
}

/** A titled panel; [status] is one line in the colour that says whether it is good news. */
@Composable
private fun VoiceCard(title: String, status: Pair<String, Color>? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.radiusMd),
        color = ConsoleColors.panel,
        border = BorderStroke(1.dp, ConsoleColors.borderSubtle),
    ) {
        Column(Modifier.padding(Dimens.spaceMd), verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
            Text(title, style = ConsoleType.panelTitle, color = ConsoleColors.text)
            status?.let { (text, color) -> Text("● $text", style = ConsoleType.bodySmall, color = color) }
            content()
        }
    }
}

@Composable
private fun VoiceNote(text: String) {
    Text(text, style = ConsoleType.bodySmall, color = ConsoleColors.muted)
}

@Composable
private fun DeleteKeyButton(stored: Boolean, onClick: () -> Unit) {
    TextButton(enabled = stored, onClick = onClick) { Text(stringResource(R.string.voice_delete_key)) }
}

@Composable
private fun keyLabel(stored: Boolean): String =
    stringResource(if (stored) R.string.voice_api_key_saved else R.string.voice_api_key)

/**
 * 语音唤醒, off by default: applied at once, outside 保存, since it opens the microphone. While on, the wake word is
 * listened for on the device whether or not the overlay runs (the system mic indicator stays lit).
 */
@Composable
private fun VoiceWakeSection(state: VoiceSettingsState) {
    val server = LocalServer.current
    val resources = LocalResources.current
    val voice = server.state.voice
    var message by remember { mutableStateOf("") }
    val sensitivityFailed = stringResource(R.string.voice_wake_sensitivity_save_failed)
    if (voice.wakeMigrated) {
        Text(stringResource(R.string.voice_wake_migrated_off), style = ConsoleType.bodySmall, color = ConsoleColors.warning)
    }
    VoiceSwitchRow(stringResource(R.string.voice_wake_switch), voice.wakeWordEnabled) { on ->
        server.send(VoiceRequests.SET_WAKE_WORD, FlagParams(on)) { message = it.error?.let(resources::words).orEmpty() }
    }
    Text(
        stringResource(R.string.voice_wake_explainer, state.view.wakeIdleEndSeconds),
        style = ConsoleType.bodySmall,
        color = ConsoleColors.muted,
    )
    Text(
        listOf(if (voice.wakeWordEnabled) stringResource(R.string.voice_wake_status, voice.wakeStatus?.let { words(it) }.orEmpty()) else "", message)
            .filter(String::isNotEmpty).joinToString(" · "),
        style = ConsoleType.bodySmall,
        color = if (voice.wakeListening) ConsoleColors.success else ConsoleColors.muted,
    )
    WakeSensitivityOptions(state) { failed -> message = if (failed) sensitivityFailed else "" }
}

/** 唤醒阈值: how readily the wake word wakes, applied at once; the spotter starts again with it. */
@Composable
private fun WakeSensitivityOptions(state: VoiceSettingsState, onSaved: (failed: Boolean) -> Unit) {
    val selected = state.view.voice.wakeSensitivity
    Text(stringResource(R.string.voice_wake_sensitivity), style = ConsoleType.label, color = ConsoleColors.muted)
    val separator = stringResource(R.string.voice_phrase_separator)
    state.view.wakeLevels.forEach { level ->
        val (label, detail) = when (level.id) {
            "high" -> R.string.voice_wake_high to R.string.voice_wake_high_detail
            "low" -> R.string.voice_wake_low to R.string.voice_wake_low_detail
            else -> R.string.voice_wake_standard to R.string.voice_wake_standard_detail
        }
        val phrases = level.phrases.map { stringResource(R.string.voice_phrase_quoted, it) }.joinToString(separator)
        SelectableOptionRow(stringResource(label), selected == level.id, { state.setWakeLevel(level.id, onSaved) }, subtitle = stringResource(detail, phrases))
    }
}

/** The form as typed; numbers stay text until saved, where validation reports what is wrong. */
private data class VoiceDraft(
    val engine: VoiceEngine,
    val threads: String,
    val endpoint: String,
    val model: String,
    val autoSubmit: Boolean,
    val history: String,
    val echoCancel: Boolean,
    val nearBelow: String,
    val hotwords: String,
    val bargeIn: Boolean,
    val bargeInScore: String,
    val handOverChime: Boolean,
    val jevTurns: Boolean,
    val eos: String,
    val eosFloor: String,
    val agentBound: String,
    val sideBound: String,
    val silenceCapSeconds: String,
) {
    fun applyTo(saved: VoiceSettings): VoiceSettings = saved.copy(
        engine = engine,
        localThreads = threads.toIntOrNull() ?: 0,
        dashscopeEndpoint = endpoint,
        dashscopeModel = model,
        autoSubmit = autoSubmit,
        historyMessages = history.toIntOrNull() ?: -1,
        echoCancel = echoCancel,
        nearGateBelowDb = nearBelow.toIntOrNull() ?: -1,
        hotwords = parseHotwords(hotwords),
        bargeIn = bargeIn,
        handOverChime = handOverChime,
        jevTurns = jevTurns,
        thresholds = saved.thresholds.copy(
            bargeIn = bargeInScore.toDoubleOrNull() ?: Double.NaN,
            eos = eos.toDoubleOrNull() ?: Double.NaN,
            eosFloor = eosFloor.toDoubleOrNull() ?: Double.NaN,
            addresseeAgent = agentBound.toDoubleOrNull() ?: Double.NaN,
            addresseeSide = sideBound.toDoubleOrNull() ?: Double.NaN,
            silenceCapMillis = ((silenceCapSeconds.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
        ),
    )

    /** The choices as saved in [config]; typed fields stay as typed. */
    fun withChoicesFrom(config: VoiceSettings): VoiceDraft = copy(
        engine = config.engine,
        model = config.dashscopeModel,
        autoSubmit = config.autoSubmit,
        echoCancel = config.echoCancel,
        bargeIn = config.bargeIn,
        handOverChime = config.handOverChime,
        jevTurns = config.jevTurns,
    )

    fun recognitionTyped() = listOf(threads, endpoint, hotwords, nearBelow)

    fun turnsTyped() = listOf(eos, eosFloor, agentBound, sideBound, silenceCapSeconds, history, bargeInScore)

    companion object {
        fun of(config: VoiceSettings) = VoiceDraft(
            engine = config.engine,
            threads = config.localThreads.toString(),
            endpoint = config.dashscopeEndpoint,
            model = config.dashscopeModel,
            autoSubmit = config.autoSubmit,
            history = config.historyMessages.toString(),
            echoCancel = config.echoCancel,
            nearBelow = config.nearGateBelowDb.toString(),
            hotwords = config.hotwords.joinToString("，"),
            bargeIn = config.bargeIn,
            bargeInScore = config.thresholds.bargeIn.toString(),
            handOverChime = config.handOverChime,
            jevTurns = config.jevTurns,
            eos = config.thresholds.eos.toString(),
            eosFloor = config.thresholds.eosFloor.toString(),
            agentBound = config.thresholds.addresseeAgent.toString(),
            sideBound = config.thresholds.addresseeSide.toString(),
            silenceCapSeconds = (config.thresholds.silenceCapMillis / 1000.0).toString(),
        )
    }
}

@Composable
private fun EchoCancelNote(aecAvailable: Boolean) {
    Text(
        if (aecAvailable) stringResource(R.string.voice_aec_note)
        else stringResource(R.string.voice_aec_missing),
        style = ConsoleType.bodySmall,
        color = if (aecAvailable) ConsoleColors.muted else ConsoleColors.warning,
    )
}

@Composable
private fun VoiceBargeInSection(draft: VoiceDraft, onDraft: (VoiceDraft) -> Unit, choose: VoiceChoice) {
    VoiceSwitchRow(stringResource(R.string.voice_barge_in), draft.bargeIn) { on -> choose { it.copy(bargeIn = on) } }
    Text(
        stringResource(R.string.voice_barge_in_note),
        style = ConsoleType.bodySmall,
        color = ConsoleColors.muted,
    )
    val score = stringResource(R.string.voice_barge_in_score)
    VoiceField(score, draft.bargeInScore, { onDraft(draft.copy(bargeInScore = it)) }, number = true)
    val chime = stringResource(R.string.voice_hand_over_chime)
    VoiceSwitchRow(chime, draft.handOverChime) { on -> choose { it.copy(handOverChime = on) } }
}

@Composable
private fun VoiceJudgeSection(draft: VoiceDraft, onDraft: (VoiceDraft) -> Unit, choose: VoiceChoice) {
    VoiceFieldRow(
        {
            val label = stringResource(R.string.voice_eos)
            VoiceField(label, draft.eos, { onDraft(draft.copy(eos = it)) }, it, number = true)
        },
        {
            val label = stringResource(R.string.voice_eos_floor)
            VoiceField(label, draft.eosFloor, { onDraft(draft.copy(eosFloor = it)) }, it, number = true)
        },
    )
    VoiceFieldRow(
        {
            val label = stringResource(R.string.voice_addressee_agent)
            VoiceField(label, draft.agentBound, { onDraft(draft.copy(agentBound = it)) }, it, number = true)
        },
        {
            val label = stringResource(R.string.voice_addressee_side)
            VoiceField(label, draft.sideBound, { onDraft(draft.copy(sideBound = it)) }, it, number = true)
        },
    )
    VoiceFieldRow(
        {
            val label = stringResource(R.string.voice_silence_cap)
            val onChange = { text: String -> onDraft(draft.copy(silenceCapSeconds = text)) }
            VoiceField(label, draft.silenceCapSeconds, onChange, it, number = true)
        },
        {
            val label = stringResource(R.string.voice_history)
            VoiceField(label, draft.history, { onDraft(draft.copy(history = it)) }, it, number = true)
        },
    )
    VoiceSwitchRow(stringResource(R.string.voice_auto_submit), draft.autoSubmit) { on ->
        choose { it.copy(autoSubmit = on) }
    }
}

@Composable
private fun VoiceSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = ConsoleColors.text)
        Switch(checked, onChange, colors = switchColors())
    }
}

/** Two fields side by side, each given its half of the row. */
@Composable
private fun VoiceFieldRow(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        first(Modifier.weight(1f))
        second(Modifier.weight(1f))
    }
}

@Composable
private fun VoiceField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    secret: Boolean = false,
    number: Boolean = false,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text),
        colors = outlinedTextFieldColors(),
        modifier = modifier,
    )
}

/** 播报服务: MiMo or 豆包, saved when tapped; each keeps its own settings. */
@Composable
private fun TtsProviderPicker(selected: TtsProvider, onSelect: (TtsProvider) -> Unit) {
    Text(stringResource(R.string.voice_tts_provider), style = ConsoleType.label, color = ConsoleColors.muted)
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        TtsProvider.entries.forEach { provider ->
            VoiceChip(stringResource(provider.screenName), selected == provider) { onSelect(provider) }
        }
    }
}

/** The device's own voice: nothing to set but whether replies are spoken, and Android's page for its engine. */
@Composable
private fun SystemSpeechCard(state: VoiceSettingsState) {
    val context = LocalContext.current
    val tts = state.tts
    val status = tts.effective.status?.let { words(it) }.orEmpty() to (if (tts.effective.ready) ConsoleColors.success else ConsoleColors.danger)
    VoiceCard(stringResource(R.string.voice_speech_system_title), status) {
        VoiceSwitchRow(stringResource(R.string.voice_speak_replies), tts.draft.enabled) {
            state.chooseTts(TtsChoice.ENABLED, it.toString())
        }
        VoiceNote(stringResource(R.string.voice_system_speech_note))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
            PreviewButton(enabled = true, starting = R.string.voice_preview_system, onMessage = state::message)
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(TTS_SETTINGS_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) {
                Text(stringResource(R.string.voice_system_tts_settings))
            }
        }
    }
}

/** 豆包语音合成: switch, App ID (only for an App ID + Access Token pair), resource, voice, speed, 试听. */
@Composable
private fun DoubaoSpeechCard(state: VoiceSettingsState) {
    val tts = state.tts
    val status = tts.effective.status?.let { words(it) }.orEmpty() to (if (tts.effective.ready) ConsoleColors.success else ConsoleColors.danger)
    VoiceCard(stringResource(R.string.voice_speech_doubao_title), status) {
        val draft = tts.draft
        val edit = { next: DoubaoTtsView -> state.tts = tts.copy(draft = draft.copy(doubao = next)) }
        VoiceSwitchRow(stringResource(R.string.voice_speak_replies), draft.enabled) {
            state.chooseTts(TtsChoice.ENABLED, it.toString())
        }
        VoiceFieldRow(
            {
                val label = stringResource(R.string.voice_doubao_resource_id)
                VoiceField(label, draft.doubao.resourceId, { edit(draft.doubao.copy(resourceId = it)) }, it)
            },
            {
                val label = stringResource(R.string.voice_doubao_app_id)
                VoiceField(label, draft.doubao.appId, { edit(draft.doubao.copy(appId = it)) }, it)
            },
        )
        DoubaoVoicePicker(state.view.doubaoVoices, draft.doubao.voice) { state.chooseTts(TtsChoice.DOUBAO_VOICE, it) }
        Text(stringResource(R.string.voice_speech_rate), style = ConsoleType.label, color = ConsoleColors.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
            DOUBAO_SPEEDS.forEach { (label, rate) ->
                VoiceChip(stringResource(label), draft.doubao.speechRate == rate) {
                    state.chooseTts(TtsChoice.DOUBAO_SPEECH_RATE, rate.toString())
                }
            }
        }
        VoiceNote(stringResource(R.string.voice_doubao_note))
        PreviewButton(tts.effective.ready, R.string.voice_preview_saved, state::message)
    }
}

/** 音色: some 豆包 2.0 voices as chips; an id typed elsewhere (local.properties) keeps a chip. */
@Composable
private fun DoubaoVoicePicker(voices: List<SettingsOption>, selected: String, onSelect: (String) -> Unit) {
    Text(stringResource(R.string.voice_voice), style = ConsoleType.label, color = ConsoleColors.muted)
    voices.chunked(DOUBAO_VOICES_PER_ROW).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
            row.forEach { voice -> VoiceChip(voice.label, selected == voice.id) { onSelect(voice.id) } }
        }
    }
    if (selected.isNotBlank() && voices.none { it.id == selected }) {
        VoiceChip(stringResource(R.string.voice_other_voice, selected), selected = true) {}
    }
}

@Composable
private fun DoubaoKeyCard(state: VoiceSettingsState) {
    val tts = state.tts
    val packaged = !tts.doubaoKeyStored && tts.effective.doubao.hasKey
    val status = when {
        tts.doubaoKeyStored -> stringResource(R.string.voice_saved) to ConsoleColors.success
        packaged -> stringResource(R.string.voice_doubao_key_packaged) to ConsoleColors.success
        else -> stringResource(R.string.voice_doubao_key_optional) to ConsoleColors.muted
    }
    val deleted = stringResource(R.string.voice_doubao_key_deleted)
    VoiceCard(stringResource(R.string.voice_doubao_speech), status) {
        VoiceNote(stringResource(R.string.voice_doubao_key_note))
        VoiceField(keyLabel(tts.doubaoKeyStored), tts.doubaoKey, { state.tts = tts.copy(doubaoKey = it) }, secret = true)
        DeleteKeyButton(tts.doubaoKeyStored) { state.deleteKey(VoiceKey.DOUBAO, deleted) { state.tts = TtsForm.of(it) } }
    }
}

private val DOUBAO_SPEEDS = listOf(
    R.string.voice_speed_slow to -25,
    R.string.voice_speed_normal to 0,
    R.string.voice_speed_fast to 25,
    R.string.voice_speed_very_fast to 50,
)
private const val DOUBAO_VOICES_PER_ROW = 4

/** [SettingsOption.summary] the server gives MiMo's Chinese voices. */
private const val CHINESE_VOICE = "zh"

/** Android's page for the system speech engine. */
private const val TTS_SETTINGS_ACTION = "com.android.settings.TTS_SETTINGS"

@get:StringRes
private val TtsProvider.screenName: Int
    get() = when (this) {
        TtsProvider.MIMO -> R.string.tts_provider_mimo
        TtsProvider.DOUBAO -> R.string.tts_provider_doubao
        TtsProvider.SYSTEM -> R.string.tts_provider_system
    }
