package cc.noharry.bothy.app.voice
import android.content.Context
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.voice.DashScopeAsrModel
import cc.noharry.bothy.voice.WakeSensitivity
import cc.noharry.bothy.voice.echoCancellerAvailable
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.SettingsNote
import cc.noharry.bothy.app.console.SettingsSaveBar
import cc.noharry.bothy.app.console.SettingsTabs
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.outlinedTextFieldColors
import cc.noharry.bothy.app.console.singleLine
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.console.switchColors
import cc.noharry.bothy.app.console.ui.SelectableOptionRow
import cc.noharry.bothy.app.model.ProviderConfig
import cc.noharry.bothy.app.plans.number
import cc.noharry.bothy.app.runtime.RuntimeSession
import cc.noharry.bothy.app.voice.tts.DOUBAO_TTS_SPEECH_RATE
import cc.noharry.bothy.app.voice.tts.DOUBAO_TTS_VOICE
import cc.noharry.bothy.app.voice.tts.DoubaoTtsSettings
import cc.noharry.bothy.app.voice.tts.DoubaoTtsVoice
import cc.noharry.bothy.app.voice.tts.MIMO_TTS_ENABLED
import cc.noharry.bothy.app.voice.tts.MIMO_TTS_STREAM
import cc.noharry.bothy.app.voice.tts.MIMO_TTS_VOICE
import cc.noharry.bothy.app.voice.tts.MimoTtsVoice
import cc.noharry.bothy.app.voice.tts.SystemTts
import cc.noharry.bothy.app.voice.tts.TTS_PROVIDER
import cc.noharry.bothy.app.voice.tts.TtsConfig
import cc.noharry.bothy.app.voice.tts.TtsProvider
import cc.noharry.bothy.app.voice.tts.clearDoubaoTtsKey
import cc.noharry.bothy.app.voice.tts.clearTtsKey
import cc.noharry.bothy.app.voice.tts.loadDoubaoTtsKey
import cc.noharry.bothy.app.voice.tts.loadTtsKey
import cc.noharry.bothy.app.voice.tts.saveDoubaoTtsKey
import cc.noharry.bothy.app.voice.tts.saveTtsChoice
import cc.noharry.bothy.app.voice.tts.saveTtsKey
import cc.noharry.bothy.app.voice.tts.saveTtsSettings
import cc.noharry.bothy.app.voice.tts.ttsConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class VoiceSettingsTab(@StringRes val label: Int) {
    KEYS(R.string.voice_tab_keys),
    RECOGNITION(R.string.voice_tab_recognition),
    TURNS(R.string.voice_tab_turns),
    SPEECH(R.string.voice_tab_speech),
}

/** Saves one choice of the voice config at once, leaving typed edits pending. */
private typealias VoiceChoice = ((VoiceConfig) -> VoiceConfig) -> Unit

/**
 * Settings → 语音. 密钥 holds every key a voice turn needs, with whether each is there: 百炼 (cloud recognition), Jev
 * (judges each stretch; tool search shares it) and MiMo (speaks the reply). The other tabs only change settings and
 * send the user back to 密钥 when a key is missing. Choices (options, chips, switches) apply when tapped; typed fields
 * wait for 保存, and while any wait the foot turns into a highlighted 有未保存的修改 bar and their tab carries a dot.
 */
@Composable
internal fun VoiceSettingsPane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { VoiceSettingsState(context, scope) }
    var tabName by rememberSaveable { mutableStateOf(VoiceSettingsTab.KEYS.name) }
    LaunchedEffect(Unit) { state.tts = TtsForm.load(context) }
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
            val onForms = { next: VoiceForms -> state.forms = next }
            val onTts = { next: TtsForm -> state.tts = next }
            val onMessage = { text: String -> state.note = SettingsNote(text) }
            when (tab) {
                VoiceSettingsTab.KEYS -> VoiceKeysTab(state.forms, state.tts, onForms, onTts, onMessage)
                VoiceSettingsTab.RECOGNITION -> VoiceRecognitionTab(state.forms, onForms, state::chooseVoice)
                VoiceSettingsTab.TURNS -> VoiceTurnsTab(state.forms, onForms, state::chooseVoice)
                VoiceSettingsTab.SPEECH -> VoiceSpeechTab(state.tts, onTts, state::chooseTts, onMessage)
            }
        }
        SettingsSaveBar(unsaved.isNotEmpty(), state.note, state::save, state::discard)
    }
}

/** The page's forms and its last note: a choice is saved when tapped, typed edits on [save]. */
private class VoiceSettingsState(private val context: Context, private val scope: CoroutineScope) {
    var forms by mutableStateOf(VoiceForms.load(context))
    var tts by mutableStateOf<TtsForm?>(null)
    var note by mutableStateOf(SettingsNote())

    fun chooseVoice(change: (VoiceConfig) -> VoiceConfig) {
        val error = saveVoiceConfig(context, change(forms.saved))
        note = SettingsNote(error ?: savedNote(context), error = error != null)
        if (error == null) forms = forms.withSaved(loadVoiceConfig(context))
    }

    fun chooseTts(name: String, value: String) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { saveTtsChoice(context, name, value) }
            note = if (ok) {
                SettingsNote(savedNote(context))
            } else {
                SettingsNote(context.getString(R.string.tts_save_failed), error = true)
            }
            if (!ok) return@launch
            val fresh = TtsForm.load(context)
            tts = tts?.let { typed -> fresh.copy(draft = typed.draft.withChoicesFrom(fresh.effective), key = typed.key) } ?: fresh
            RuntimeSession.refreshTts(context)
        }
    }

    fun save() {
        scope.launch {
            val error = withContext(Dispatchers.IO) { saveVoiceForms(context, forms, tts) }
            note = SettingsNote(error ?: savedNote(context), error = error != null)
            if (error == null) {
                forms = VoiceForms.load(context)
                tts = TtsForm.load(context)
                RuntimeSession.refreshTts(context)
            }
        }
    }

    fun discard() {
        scope.launch {
            forms = VoiceForms.load(context)
            tts = TtsForm.load(context)
            note = SettingsNote(context.getString(R.string.voice_discarded))
        }
    }
}

private fun savedNote(context: Context): String =
    context.getString(if (VoiceController.listening) R.string.voice_saved_after_restart else R.string.voice_saved)

/** The tabs whose typed fields differ from what is saved; a typed key counts until it is saved. */
private fun unsavedTabs(forms: VoiceForms, tts: TtsForm?): Set<VoiceSettingsTab> {
    val saved = VoiceDraft.of(forms.saved)
    val draft = forms.voice
    return buildSet {
        if (forms.asrKey.isNotBlank() || forms.jevKey.isNotBlank() || tts?.key?.isNotBlank() == true || tts?.doubaoKey?.isNotBlank() == true) {
            add(VoiceSettingsTab.KEYS)
        }
        if (draft.recognitionTyped() != saved.recognitionTyped()) add(VoiceSettingsTab.RECOGNITION)
        if (draft.turnsTyped() != saved.turnsTyped() || forms.jev.differsFrom(forms.jevSaved)) add(VoiceSettingsTab.TURNS)
        if (tts != null && tts.draft.typed() != tts.effective.typed()) add(VoiceSettingsTab.SPEECH)
    }
}

private fun TtsConfig.typed() = listOf(baseUrl, model, style, doubao.appId, doubao.resourceId)

private fun TtsConfig.withChoicesFrom(saved: TtsConfig) = copy(
    enabled = saved.enabled, voice = saved.voice, stream = saved.stream, provider = saved.provider,
    doubao = doubao.copy(voice = saved.doubao.voice, speechRate = saved.doubao.speechRate),
)

/** Settings → 语音 as typed. Keys are never saveable state or echoed from storage; each is written only when typed. */
private data class VoiceForms(
    val saved: VoiceConfig,
    val voice: VoiceDraft,
    val asrKey: String,
    val jevSaved: JevConfig,
    val jev: JevDraft,
    val jevKey: String,
) {
    /** After a choice was saved: the saved config, with the draft's choices following it and its typed edits kept. */
    fun withSaved(next: VoiceConfig): VoiceForms = copy(saved = next, voice = voice.withChoicesFrom(next))

    companion object {
        fun load(context: Context): VoiceForms {
            val saved = loadVoiceConfig(context)
            val jev = loadJevConfig(context)
            return VoiceForms(saved, VoiceDraft.of(saved), "", jev, JevDraft.of(jev), "")
        }
    }
}

/** The Jev connection as typed; tool search's own settings stay on 工具与技能 → 工具检索. */
private data class JevDraft(val endpoint: String, val model: String, val timeout: String) {
    fun differsFrom(saved: JevConfig): Boolean =
        endpoint.trim() != saved.endpoint || model.trim() != saved.model || timeout.trim() != saved.timeoutMillis.toString()

    companion object {
        fun of(config: JevConfig) = JevDraft(config.endpoint, config.model, config.timeoutMillis.toString())
    }
}

/** What replies are spoken with now ([effective]: app settings over local.properties) and the form as typed. */
private data class TtsForm(
    val effective: TtsConfig,
    val draft: TtsConfig,
    val key: String,
    val keyStored: Boolean,
    val doubaoKey: String = "",
    val doubaoKeyStored: Boolean = false,
) {
    companion object {
        suspend fun load(context: Context): TtsForm = withContext(Dispatchers.IO) {
            val effective = ProviderConfig.load(context).ttsConfig()
            TtsForm(effective, effective, "", loadTtsKey(context).isNotEmpty(), "", loadDoubaoTtsKey(context).isNotEmpty())
        }
    }
}

/** Null when every tab saved, else what did not, one line per service. Jev and TTS are written only when changed. */
private fun saveVoiceForms(context: Context, forms: VoiceForms, tts: TtsForm?): String? {
    val jevChanged = forms.jev.differsFrom(forms.jevSaved) || forms.jevKey.isNotBlank()
    return listOfNotNull(
        saveVoiceConfig(context, forms.voice.applyTo(forms.saved), forms.asrKey)
            ?.let { context.getString(R.string.voice_error_recognition, it) },
        if (jevChanged) {
            val timeout = forms.jev.timeout.trim().toLongOrNull() ?: 0
            saveJevConnection(context, forms.jev.endpoint, forms.jev.model, timeout, forms.jevKey)
                ?.let { context.getString(R.string.voice_error_jev, it) }
        } else {
            null
        },
        tts?.takeIf { it.draft.typed() != it.effective.typed() }?.let { saveTtsSettings(context, it.draft) }
            ?.let { context.getString(R.string.voice_error_tts, it) },
        tts?.key?.takeIf(String::isNotBlank)?.let { saveTtsKey(context, it) }
            ?.let { context.getString(R.string.voice_error_mimo_key, it) },
        tts?.doubaoKey?.takeIf(String::isNotBlank)?.let { saveDoubaoTtsKey(context, it) }
            ?.let { context.getString(R.string.voice_error_doubao_key, it) },
    ).joinToString("\n").ifEmpty { null }
}

@Composable
private fun VoiceKeysTab(
    forms: VoiceForms,
    tts: TtsForm?,
    onForms: (VoiceForms) -> Unit,
    onTts: (TtsForm) -> Unit,
    onMessage: (String) -> Unit,
) {
    Text(stringResource(R.string.voice_keys_intro), color = ConsoleColors.muted)
    DashScopeKeyCard(forms, onForms, onMessage)
    JevKeyCard(forms, onForms, onMessage)
    MimoKeyCard(tts, onTts, onMessage)
    DoubaoKeyCard(tts, onTts, onMessage)
}

@Composable
private fun DashScopeKeyCard(forms: VoiceForms, onForms: (VoiceForms) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
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
        VoiceField(keyLabel(saved.hasDashscopeKey), forms.asrKey, { onForms(forms.copy(asrKey = it)) }, secret = true)
        DeleteKeyButton(saved.hasDashscopeKey) {
            val error = saveVoiceConfig(context, saved.copy(engine = VoiceEngine.LOCAL), clearKey = true)
            onMessage(error ?: deleted)
            if (error == null) {
                onForms(forms.copy(saved = loadVoiceConfig(context), voice = forms.voice.copy(engine = VoiceEngine.LOCAL), asrKey = ""))
            }
        }
    }
}

@Composable
private fun JevKeyCard(forms: VoiceForms, onForms: (VoiceForms) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
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
        VoiceField(keyLabel(saved.hasApiKey), forms.jevKey, { onForms(forms.copy(jevKey = it)) }, secret = true)
        DeleteKeyButton(saved.hasApiKey) {
            val error = saveJevConnection(context, saved.endpoint, saved.model, saved.timeoutMillis, clearKey = true)
            onMessage(error ?: deleted)
            if (error == null) onForms(forms.copy(jevSaved = loadJevConfig(context), jevKey = ""))
        }
    }
}

@Composable
private fun MimoKeyCard(tts: TtsForm?, onTts: (TtsForm) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val packaged = tts != null && !tts.keyStored && tts.effective.apiKey.isNotBlank()
    val status = when {
        tts == null -> stringResource(R.string.voice_loading) to ConsoleColors.muted
        tts.keyStored -> stringResource(R.string.voice_saved) to ConsoleColors.success
        packaged -> stringResource(R.string.voice_mimo_key_packaged) to ConsoleColors.success
        else -> stringResource(R.string.voice_key_missing_silent) to ConsoleColors.danger
    }
    val deleted = stringResource(R.string.voice_mimo_key_deleted)
    val deleteFailed = stringResource(R.string.voice_mimo_key_delete_failed)
    VoiceCard("MiMo", status) {
        VoiceNote(stringResource(R.string.voice_mimo_key_note))
        if (tts == null) return@VoiceCard
        val label = if (packaged) stringResource(R.string.voice_mimo_key_label_packaged) else keyLabel(tts.keyStored)
        VoiceField(label, tts.key, { onTts(tts.copy(key = it)) }, secret = true)
        DeleteKeyButton(tts.keyStored) {
            scope.launch {
                val cleared = withContext(Dispatchers.IO) { clearTtsKey(context) }
                onMessage(if (cleared) deleted else deleteFailed)
                if (cleared) {
                    onTts(TtsForm.load(context))
                    RuntimeSession.refreshTts(context)
                }
            }
        }
    }
}

@Composable
private fun VoiceRecognitionTab(forms: VoiceForms, onForms: (VoiceForms) -> Unit, choose: VoiceChoice) {
    val context = LocalContext.current
    val localError = remember { localAsrUnavailable(context) }
    val saved = forms.saved
    val draft = forms.voice
    val onDraft = { next: VoiceDraft -> onForms(forms.copy(voice = next)) }
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
            DashScopeSettings(draft, onDraft, choose)
        }
    }
    VoiceCard(stringResource(R.string.voice_capture)) {
        VoiceSwitchRow(stringResource(R.string.voice_echo_cancel), draft.echoCancel) { on ->
            choose { it.copy(echoCancel = on) }
        }
        EchoCancelNote()
        VoiceSwitchRow(stringResource(R.string.voice_near_gate), saved.nearGate) { on ->
            choose { it.copy(nearGate = on) }
        }
        if (saved.nearGate) {
            val label = stringResource(R.string.voice_near_gate_db)
            VoiceField(label, draft.nearBelow, { onDraft(draft.copy(nearBelow = it)) }, number = true)
        }
    }
    VoiceCard(stringResource(R.string.voice_wake_title)) { VoiceWakeSection() }
}

@Composable
private fun VoiceEngineOptions(selected: VoiceEngine, localError: String?, onSelect: (VoiceEngine) -> Unit) {
    SelectableOptionRow(
        stringResource(VoiceEngine.LOCAL.label),
        selected == VoiceEngine.LOCAL,
        { onSelect(VoiceEngine.LOCAL) },
        subtitle = localError ?: stringResource(R.string.voice_engine_local_detail),
    )
    SelectableOptionRow(
        stringResource(VoiceEngine.DASHSCOPE.label),
        selected == VoiceEngine.DASHSCOPE,
        { onSelect(VoiceEngine.DASHSCOPE) },
        subtitle = stringResource(R.string.voice_engine_dashscope_detail),
    )
}

@Composable
private fun DashScopeSettings(draft: VoiceDraft, onDraft: (VoiceDraft) -> Unit, choose: VoiceChoice) {
    Text(stringResource(R.string.voice_recognition_model), style = ConsoleType.label, color = ConsoleColors.muted)
    DashScopeAsrModel.entries.forEach { model ->
        val label = stringResource(model.displayLabel())
        SelectableOptionRow(label, draft.model == model.id, { choose { it.copy(dashscopeModel = model.id) } }, subtitle = model.id)
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
private fun VoiceSpeechTab(tts: TtsForm?, onTts: (TtsForm) -> Unit, choose: (String, String) -> Unit, onMessage: (String) -> Unit) {
    if (tts == null) {
        VoiceNote(stringResource(R.string.voice_loading))
        return
    }
    val context = LocalContext.current
    TtsProviderPicker(tts.effective.provider) { choose(TTS_PROVIDER, it.wireName) }
    if (tts.effective.provider == TtsProvider.DOUBAO) return DoubaoSpeechCard(tts, onTts, choose, onMessage)
    if (tts.effective.provider == TtsProvider.SYSTEM) return SystemSpeechCard(tts, choose, onMessage)
    val status = ttsStatusLine(context, tts.effective) to
        (if (tts.effective.ready) ConsoleColors.success else ConsoleColors.danger)
    VoiceCard(stringResource(R.string.voice_speech_mimo_title), status) {
        val draft = tts.draft
        val edit = { next: TtsConfig -> onTts(tts.copy(draft = next)) }
        VoiceSwitchRow(stringResource(R.string.voice_speak_replies), draft.enabled) {
            choose(MIMO_TTS_ENABLED, it.toString())
        }
        VoiceFieldRow(
            {
                val label = stringResource(R.string.voice_address)
                VoiceField(label, draft.baseUrl, { edit(draft.copy(baseUrl = it)) }, it)
            },
            { VoiceField(stringResource(R.string.voice_model), draft.model, { edit(draft.copy(model = it)) }, it) },
        )
        MimoVoicePicker(draft.voice) { choose(MIMO_TTS_VOICE, it) }
        val style = stringResource(R.string.voice_tts_style)
        VoiceField(style, draft.style, { edit(draft.copy(style = it)) }, singleLine = false)
        VoiceSwitchRow(stringResource(R.string.voice_tts_stream), draft.stream) {
            choose(MIMO_TTS_STREAM, it.toString())
        }
        VoiceNote(stringResource(R.string.voice_speech_note))
        PreviewButton(tts.effective.ready, R.string.voice_preview_saved, onMessage)
    }
}

/** 试听: speaks a line with the saved settings; [onMessage] hears [starting], then whether speech started. */
@Composable
private fun PreviewButton(enabled: Boolean, @StringRes starting: Int, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val startingText = stringResource(starting)
    val started = stringResource(R.string.voice_preview_started)
    val failed = stringResource(R.string.voice_preview_failed)
    TextButton(enabled = enabled, onClick = {
        onMessage(startingText)
        RuntimeSession.previewTts(context, "你好，我是小博，播报正常。") { ok ->
            onMessage(if (ok) started else failed)
        }
    }) { Text(stringResource(R.string.voice_preview)) }
}

/** 音色: the mimo-v2.5-tts presets as chips; a voice set elsewhere (local.properties, the provider file) keeps a chip. */
@Composable
private fun MimoVoicePicker(selected: String, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    Text(stringResource(R.string.voice_voice), style = ConsoleType.label, color = ConsoleColors.muted)
    MimoTtsVoice.entries.partition { it.chinese }.toList().forEach { voices ->
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
            voices.forEach { voice -> VoiceChip(voice.label(context), selected == voice.id) { onSelect(voice.id) } }
        }
    }
    if (selected.isNotBlank() && MimoTtsVoice.entries.none { it.id == selected }) {
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

internal fun ttsStatusLine(context: Context, config: TtsConfig): String = when {
    config.ready -> context.getString(R.string.voice_ready_detail, config.label(context))
    config.provider == TtsProvider.DOUBAO && config.doubao.key.isBlank() ->
        context.getString(R.string.voice_doubao_key_missing_status)
    config.provider == TtsProvider.MIMO && config.apiKey.isBlank() ->
        context.getString(R.string.voice_mimo_key_missing_status)
    !config.enabled -> context.getString(R.string.voice_off)
    else -> context.getString(R.string.voice_config_incomplete)
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
private fun VoiceWakeSection() {
    val context = LocalContext.current
    val enabled by WakeWord.enabled.collectAsState()
    val status by WakeWord.status.collectAsState()
    var message by remember { mutableStateOf("") }
    // The switch as saved, even before the overlay has started.
    LaunchedEffect(Unit) { WakeWord.load(context) }
    val switchFailed = stringResource(R.string.voice_switch_save_failed)
    val sensitivityFailed = stringResource(R.string.voice_wake_sensitivity_save_failed)
    VoiceSwitchRow(stringResource(R.string.voice_wake_switch), enabled) { on ->
        message = if (WakeWord.setEnabled(context, on)) "" else switchFailed
    }
    Text(
        stringResource(R.string.voice_wake_explainer, WAKE_IDLE_END_MILLIS / 1000),
        style = ConsoleType.bodySmall,
        color = ConsoleColors.muted,
    )
    Text(
        listOf(if (enabled) stringResource(R.string.voice_wake_status, status.message) else "", message)
            .filter(String::isNotEmpty).joinToString(" · "),
        style = ConsoleType.bodySmall,
        color = if (status.listening) ConsoleColors.success else ConsoleColors.muted,
    )
    WakeSensitivityOptions { failed -> message = if (failed) sensitivityFailed else "" }
}

/** 唤醒阈值: how readily the wake word wakes, applied at once; the spotter starts again with it. */
@Composable
private fun WakeSensitivityOptions(onSaved: (failed: Boolean) -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(loadVoiceConfig(context).wakeSensitivity) }
    Text(stringResource(R.string.voice_wake_sensitivity), style = ConsoleType.label, color = ConsoleColors.muted)
    WakeSensitivity.entries.forEach { sensitivity ->
        val (label, subtitle) = sensitivity.labels()
        SelectableOptionRow(label, selected == sensitivity, {
            val saved = WakeWord.setSensitivity(context, sensitivity)
            if (saved) selected = sensitivity
            onSaved(!saved)
        }, subtitle = subtitle)
    }
}

@Composable
private fun WakeSensitivity.labels(): Pair<String, String> {
    val phrases = XIAOBO_WAKE_PROFILE.phrasesFor(this).map { it.phrase }.distinct()
        .map { stringResource(R.string.voice_phrase_quoted, it) }
        .joinToString(stringResource(R.string.voice_phrase_separator))
    return when (this) {
        WakeSensitivity.HIGH ->
            stringResource(R.string.voice_wake_high) to stringResource(R.string.voice_wake_high_detail, phrases)
        WakeSensitivity.STANDARD ->
            stringResource(R.string.voice_wake_standard) to stringResource(R.string.voice_wake_standard_detail, phrases)
        WakeSensitivity.LOW ->
            stringResource(R.string.voice_wake_low) to stringResource(R.string.voice_wake_low_detail, phrases)
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
    fun applyTo(saved: VoiceConfig): VoiceConfig = saved.copy(
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
    fun withChoicesFrom(config: VoiceConfig): VoiceDraft = copy(
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
        fun of(config: VoiceConfig) = VoiceDraft(
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
private fun EchoCancelNote() {
    val aecAvailable = remember { echoCancellerAvailable() }
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
            VoiceChip(stringResource(provider.label), selected == provider) { onSelect(provider) }
        }
    }
}

/** The device's own voice: nothing to set but whether replies are spoken, and Android's page for its engine. */
@Composable
private fun SystemSpeechCard(tts: TtsForm, choose: (String, String) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val status = ttsStatusLine(context, tts.effective) to
        (if (tts.effective.ready) ConsoleColors.success else ConsoleColors.danger)
    VoiceCard(stringResource(R.string.voice_speech_system_title), status) {
        VoiceSwitchRow(stringResource(R.string.voice_speak_replies), tts.draft.enabled) {
            choose(MIMO_TTS_ENABLED, it.toString())
        }
        VoiceNote(stringResource(R.string.voice_system_speech_note))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
            PreviewButton(enabled = true, starting = R.string.voice_preview_system, onMessage = onMessage)
            TextButton(onClick = { runCatching { context.startActivity(SystemTts.settingsIntent()) } }) {
                Text(stringResource(R.string.voice_system_tts_settings))
            }
        }
    }
}

/** 豆包语音合成: switch, App ID (only for an App ID + Access Token pair), resource, voice, speed, 试听. */
@Composable
private fun DoubaoSpeechCard(tts: TtsForm, onTts: (TtsForm) -> Unit, choose: (String, String) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val status = ttsStatusLine(context, tts.effective) to
        (if (tts.effective.ready) ConsoleColors.success else ConsoleColors.danger)
    VoiceCard(stringResource(R.string.voice_speech_doubao_title), status) {
        val draft = tts.draft
        val edit = { next: DoubaoTtsSettings -> onTts(tts.copy(draft = draft.copy(doubao = next))) }
        VoiceSwitchRow(stringResource(R.string.voice_speak_replies), draft.enabled) {
            choose(MIMO_TTS_ENABLED, it.toString())
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
        DoubaoVoicePicker(draft.doubao.voice) { choose(DOUBAO_TTS_VOICE, it) }
        Text(stringResource(R.string.voice_speech_rate), style = ConsoleType.label, color = ConsoleColors.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
            DOUBAO_SPEEDS.forEach { (label, rate) ->
                VoiceChip(stringResource(label), draft.doubao.speechRate == rate) {
                    choose(DOUBAO_TTS_SPEECH_RATE, rate.toString())
                }
            }
        }
        VoiceNote(stringResource(R.string.voice_doubao_note))
        PreviewButton(tts.effective.ready, R.string.voice_preview_saved, onMessage)
    }
}

/** 音色: some 豆包 2.0 voices as chips; an id typed elsewhere (local.properties) keeps a chip. */
@Composable
private fun DoubaoVoicePicker(selected: String, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    Text(stringResource(R.string.voice_voice), style = ConsoleType.label, color = ConsoleColors.muted)
    DoubaoTtsVoice.entries.chunked(DOUBAO_VOICES_PER_ROW).forEach { voices ->
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
            voices.forEach { voice -> VoiceChip(voice.label(context), selected == voice.id) { onSelect(voice.id) } }
        }
    }
    if (selected.isNotBlank() && DoubaoTtsVoice.entries.none { it.id == selected }) {
        VoiceChip(stringResource(R.string.voice_other_voice, selected), selected = true) {}
    }
}

@Composable
private fun DoubaoKeyCard(tts: TtsForm?, onTts: (TtsForm) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val packaged = tts != null && !tts.doubaoKeyStored && tts.effective.doubao.key.isNotBlank()
    val status = when {
        tts == null -> stringResource(R.string.voice_loading) to ConsoleColors.muted
        tts.doubaoKeyStored -> stringResource(R.string.voice_saved) to ConsoleColors.success
        packaged -> stringResource(R.string.voice_doubao_key_packaged) to ConsoleColors.success
        else -> stringResource(R.string.voice_doubao_key_optional) to ConsoleColors.muted
    }
    val deleted = stringResource(R.string.voice_doubao_key_deleted)
    val deleteFailed = stringResource(R.string.voice_doubao_key_delete_failed)
    VoiceCard(stringResource(R.string.voice_doubao_speech), status) {
        VoiceNote(stringResource(R.string.voice_doubao_key_note))
        if (tts == null) return@VoiceCard
        VoiceField(keyLabel(tts.doubaoKeyStored), tts.doubaoKey, { onTts(tts.copy(doubaoKey = it)) }, secret = true)
        DeleteKeyButton(tts.doubaoKeyStored) {
            scope.launch {
                val cleared = withContext(Dispatchers.IO) { clearDoubaoTtsKey(context) }
                onMessage(if (cleared) deleted else deleteFailed)
                if (cleared) {
                    onTts(TtsForm.load(context))
                    RuntimeSession.refreshTts(context)
                }
            }
        }
    }
}

private val DOUBAO_SPEEDS = listOf(
    R.string.voice_speed_slow to -25,
    R.string.voice_speed_normal to 0,
    R.string.voice_speed_fast to 25,
    R.string.voice_speed_very_fast to 50,
)
private const val DOUBAO_VOICES_PER_ROW = 4
