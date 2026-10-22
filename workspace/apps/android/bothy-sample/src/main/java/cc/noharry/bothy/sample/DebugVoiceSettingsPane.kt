package cc.noharry.bothy.sample

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cc.noharry.agent.voice.DashScopeAsrModel
import cc.noharry.agent.voice.SherpaOnnxAsrEngine
import cc.noharry.agent.voice.echoCancellerAvailable
import cc.noharry.agent.voice.WakeSensitivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class VoiceSettingsTab(val label: String) { KEYS("密钥"), RECOGNITION("识别与唤醒"), TURNS("判断与打断"), SPEECH("播报") }

/** Saves one choice of the voice config at once, leaving typed edits pending. */
private typealias VoiceChoice = ((DebugVoiceConfig) -> DebugVoiceConfig) -> Unit

/**
 * Settings → 语音. 密钥 holds every key a voice turn needs, with whether each is there: 百炼 (cloud recognition), Jev
 * (judges each stretch; tool search shares it) and MiMo (speaks the reply). The other tabs only change settings and
 * send the user back to 密钥 when a key is missing. Choices (options, chips, switches) apply when tapped; typed fields
 * wait for 保存, and while any wait the foot turns into a highlighted 有未保存的修改 bar and their tab carries a dot.
 */
@Composable
internal fun DebugVoiceSettingsPane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { VoiceSettingsState(context, scope) }
    var tabName by rememberSaveable { mutableStateOf(VoiceSettingsTab.KEYS.name) }
    LaunchedEffect(Unit) { state.tts = TtsForm.load(context) }
    val tab = VoiceSettingsTab.valueOf(tabName)
    val unsaved = unsavedTabs(state.forms, state.tts)
    Column(Modifier.fillMaxSize()) {
        val labels = VoiceSettingsTab.entries.map { if (it in unsaved) "${it.label} ●" else it.label }
        SettingsTabs(labels, labels[tab.ordinal]) { label -> tabName = VoiceSettingsTab.entries[labels.indexOf(label)].name }
        Column(
            Modifier.weight(1f).widthIn(max = 880.dp).verticalScroll(rememberScrollState()).padding(DebugDimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceMd),
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

    fun chooseVoice(change: (DebugVoiceConfig) -> DebugVoiceConfig) {
        val error = saveDebugVoiceConfig(context, change(forms.saved))
        note = SettingsNote(error ?: savedNote(), error = error != null)
        if (error == null) forms = forms.withSaved(loadDebugVoiceConfig(context))
    }

    fun chooseTts(name: String, value: String) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { saveDebugTtsChoice(context, name, value) }
            note = if (ok) SettingsNote(savedNote()) else SettingsNote("无法保存播报设置。", error = true)
            if (!ok) return@launch
            val fresh = TtsForm.load(context)
            tts = tts?.let { typed -> fresh.copy(draft = typed.draft.withChoicesFrom(fresh.effective), key = typed.key) } ?: fresh
            DebugRuntimeSession.refreshTts(context)
        }
    }

    fun save() {
        scope.launch {
            val error = withContext(Dispatchers.IO) { saveVoiceForms(context, forms, tts) }
            note = SettingsNote(error ?: savedNote(), error = error != null)
            if (error == null) {
                forms = VoiceForms.load(context)
                tts = TtsForm.load(context)
                DebugRuntimeSession.refreshTts(context)
            }
        }
    }

    fun discard() {
        scope.launch {
            forms = VoiceForms.load(context)
            tts = TtsForm.load(context)
            note = SettingsNote("已放弃修改")
        }
    }
}

private fun savedNote(): String = if (DebugVoiceController.listening) "已保存 · 结束当前监听后重新唤醒生效" else "已保存"

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

private fun DebugTtsConfig.typed() = listOf(baseUrl, model, style, doubao.appId, doubao.resourceId)

private fun DebugTtsConfig.withChoicesFrom(saved: DebugTtsConfig) = copy(
    enabled = saved.enabled, voice = saved.voice, stream = saved.stream, provider = saved.provider,
    doubao = doubao.copy(voice = saved.doubao.voice, speechRate = saved.doubao.speechRate),
)

/** Settings → 语音 as typed. Keys are never saveable state or echoed from storage; each is written only when typed. */
private data class VoiceForms(
    val saved: DebugVoiceConfig,
    val voice: VoiceDraft,
    val asrKey: String,
    val jevSaved: DebugJevConfig,
    val jev: JevDraft,
    val jevKey: String,
) {
    /** After a choice was saved: the saved config, with the draft's choices following it and its typed edits kept. */
    fun withSaved(next: DebugVoiceConfig): VoiceForms = copy(saved = next, voice = voice.withChoicesFrom(next))

    companion object {
        fun load(context: Context): VoiceForms {
            val saved = loadDebugVoiceConfig(context)
            val jev = loadDebugJevConfig(context)
            return VoiceForms(saved, VoiceDraft.of(saved), "", jev, JevDraft.of(jev), "")
        }
    }
}

/** The Jev connection as typed; tool search's own settings stay on 工具与技能 → 工具检索. */
private data class JevDraft(val endpoint: String, val model: String, val timeout: String) {
    fun differsFrom(saved: DebugJevConfig): Boolean =
        endpoint.trim() != saved.endpoint || model.trim() != saved.model || timeout.trim() != saved.timeoutMillis.toString()

    companion object {
        fun of(config: DebugJevConfig) = JevDraft(config.endpoint, config.model, config.timeoutMillis.toString())
    }
}

/** What replies are spoken with now ([effective]: app settings over local.properties) and the form as typed. */
private data class TtsForm(
    val effective: DebugTtsConfig,
    val draft: DebugTtsConfig,
    val key: String,
    val keyStored: Boolean,
    val doubaoKey: String = "",
    val doubaoKeyStored: Boolean = false,
) {
    companion object {
        suspend fun load(context: Context): TtsForm = withContext(Dispatchers.IO) {
            val effective = DebugProviderConfig.load(context).ttsConfig()
            TtsForm(effective, effective, "", loadDebugTtsKey(context).isNotEmpty(), "", loadDebugDoubaoTtsKey(context).isNotEmpty())
        }
    }
}

/** Null when every tab saved, else what did not, one line per service. Jev and TTS are written only when changed. */
private fun saveVoiceForms(context: Context, forms: VoiceForms, tts: TtsForm?): String? {
    val jevChanged = forms.jev.differsFrom(forms.jevSaved) || forms.jevKey.isNotBlank()
    return listOfNotNull(
        saveDebugVoiceConfig(context, forms.voice.applyTo(forms.saved), forms.asrKey)?.let { "识别与录音：$it" },
        if (jevChanged) {
            val timeout = forms.jev.timeout.trim().toLongOrNull() ?: 0
            saveDebugJevConnection(context, forms.jev.endpoint, forms.jev.model, timeout, forms.jevKey)?.let { "Jev：$it" }
        } else {
            null
        },
        tts?.takeIf { it.draft.typed() != it.effective.typed() }?.let { saveDebugTtsSettings(context, it.draft) }?.let { "语音播报：$it" },
        tts?.key?.takeIf(String::isNotBlank)?.let { saveDebugTtsKey(context, it) }?.let { "MiMo key：$it" },
        tts?.doubaoKey?.takeIf(String::isNotBlank)?.let { saveDebugDoubaoTtsKey(context, it) }?.let { "豆包语音 key：$it" },
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
    Text(
        "语音用到的 key 都在这里填，其他页签只改设置。Key 只能输入、不会显示，存在本机不备份的目录；留空保存不会改动已有的 key。",
        color = DebugColors.muted,
    )
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
        saved.hasDashscopeKey -> "已保存" to DebugColors.success
        saved.engine == DebugVoiceEngine.DASHSCOPE -> "缺少：识别选的是百炼，语音会话开不了" to DebugColors.danger
        else -> "未填：用本地识别时不需要" to DebugColors.muted
    }
    VoiceCard("百炼", status) {
        VoiceNote("识别与唤醒 → 识别引擎 选「云端 · 阿里云百炼」时用；热词表也建在这个账号下。")
        VoiceField(keyLabel(saved.hasDashscopeKey), forms.asrKey, { onForms(forms.copy(asrKey = it)) }, secret = true)
        DeleteKeyButton(saved.hasDashscopeKey) {
            val error = saveDebugVoiceConfig(context, saved.copy(engine = DebugVoiceEngine.LOCAL), clearKey = true)
            onMessage(error ?: "已删除百炼 key · 已切回本地识别")
            if (error == null) {
                onForms(forms.copy(saved = loadDebugVoiceConfig(context), voice = forms.voice.copy(engine = DebugVoiceEngine.LOCAL), asrKey = ""))
            }
        }
    }
}

@Composable
private fun JevKeyCard(forms: VoiceForms, onForms: (VoiceForms) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val saved = forms.jevSaved
    val status = if (saved.hasApiKey) "已保存" to DebugColors.success else "缺少：语音会话开不了，工具检索也用不了" to DebugColors.danger
    VoiceCard("Jev", status) {
        VoiceNote("判断每句话是不是对小博说、说完没有；工具与技能 → 工具检索 也用它。")
        VoiceField(keyLabel(saved.hasApiKey), forms.jevKey, { onForms(forms.copy(jevKey = it)) }, secret = true)
        DeleteKeyButton(saved.hasApiKey) {
            val error = saveDebugJevConnection(context, saved.endpoint, saved.model, saved.timeoutMillis, clearKey = true)
            onMessage(error ?: "已删除 Jev key" + if (saved.enabled) " · 工具检索已关闭" else "")
            if (error == null) onForms(forms.copy(jevSaved = loadDebugJevConfig(context), jevKey = ""))
        }
    }
}

@Composable
private fun MimoKeyCard(tts: TtsForm?, onTts: (TtsForm) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val packaged = tts != null && !tts.keyStored && tts.effective.apiKey.isNotBlank()
    val status = when {
        tts == null -> "读取中…" to DebugColors.muted
        tts.keyStored -> "已保存" to DebugColors.success
        packaged -> "用打包时的 MiMo key" to DebugColors.success
        else -> "缺少：回复只显示、不播报" to DebugColors.danger
    }
    VoiceCard("MiMo", status) {
        VoiceNote("播报回复用（MiMo TTS）；不填就用 MiMo 模型服务的 key。")
        if (tts == null) return@VoiceCard
        val label = if (packaged) "API key · 留空则用打包时的 MiMo key" else keyLabel(tts.keyStored)
        VoiceField(label, tts.key, { onTts(tts.copy(key = it)) }, secret = true)
        DeleteKeyButton(tts.keyStored) {
            scope.launch {
                val cleared = withContext(Dispatchers.IO) { clearDebugTtsKey(context) }
                onMessage(if (cleared) "已删除这里保存的 MiMo key" else "无法删除 MiMo key")
                if (cleared) {
                    onTts(TtsForm.load(context))
                    DebugRuntimeSession.refreshTts(context)
                }
            }
        }
    }
}

@Composable
private fun VoiceRecognitionTab(forms: VoiceForms, onForms: (VoiceForms) -> Unit, choose: VoiceChoice) {
    val context = LocalContext.current
    val localError = remember { SherpaOnnxAsrEngine.availabilityError(context.assets) }
    val saved = forms.saved
    val draft = forms.voice
    val onDraft = { next: VoiceDraft -> onForms(forms.copy(voice = next)) }
    val status = when {
        saved.engine == DebugVoiceEngine.LOCAL -> (localError ?: "可用 · 本机识别，音频不出设备") to (if (localError == null) DebugColors.success else DebugColors.danger)
        saved.hasDashscopeKey -> "可用 · 百炼 ${saved.dashscopeModel}" to DebugColors.success
        else -> "缺少百炼 key：到「密钥」页填写" to DebugColors.danger
    }
    VoiceCard("识别引擎", status) {
        VoiceEngineOptions(draft.engine, localError) { engine -> choose { it.copy(engine = engine) } }
        if (draft.engine == DebugVoiceEngine.LOCAL) {
            VoiceField("CPU 线程数（1–4）", draft.threads, { onDraft(draft.copy(threads = it)) }, number = true)
        } else {
            DashScopeSettings(draft, onDraft, choose)
        }
    }
    VoiceCard("录音方式") {
        VoiceSwitchRow("系统回声消除（AEC + 降噪）", draft.echoCancel) { on -> choose { it.copy(echoCancel = on) } }
        EchoCancelNote()
        VoiceSwitchRow("过滤远处人声（以唤醒词的音量为准，明显更轻的说话声不送去识别）", saved.nearGate) { on -> choose { it.copy(nearGate = on) } }
        if (saved.nearGate) {
            VoiceField("近场门（比唤醒词轻多少 dB 以内都算唤醒人，4–30）", draft.nearBelow, { onDraft(draft.copy(nearBelow = it)) }, number = true)
        }
    }
    VoiceCard("语音唤醒") { VoiceWakeSection() }
}

@Composable
private fun VoiceEngineOptions(selected: DebugVoiceEngine, localError: String?, onSelect: (DebugVoiceEngine) -> Unit) {
    DebugSelectableOptionRow(
        DebugVoiceEngine.LOCAL.label,
        selected == DebugVoiceEngine.LOCAL,
        { onSelect(DebugVoiceEngine.LOCAL) },
        subtitle = localError ?: "Zipformer Small 中英双语流式 · INT8 编码器/Joiner · FP32 解码器 · 音频不出设备",
    )
    DebugSelectableOptionRow(
        DebugVoiceEngine.DASHSCOPE.label,
        selected == DebugVoiceEngine.DASHSCOPE,
        { onSelect(DebugVoiceEngine.DASHSCOPE) },
        subtitle = "实时识别 WebSocket · 音频会上传到百炼，按识别时长计费 · key 在「密钥」页",
    )
}

@Composable
private fun DashScopeSettings(draft: VoiceDraft, onDraft: (VoiceDraft) -> Unit, choose: VoiceChoice) {
    Text("识别模型", style = DebugType.label, color = DebugColors.muted)
    DashScopeAsrModel.entries.forEach { model ->
        DebugSelectableOptionRow(model.label, draft.model == model.id, { choose { it.copy(dashscopeModel = model.id) } }, subtitle = model.id)
    }
    VoiceField("WebSocket 地址", draft.endpoint, { onDraft(draft.copy(endpoint = it)) })
    VoiceField(
        "热词（逗号分隔，每个最多 15 字；保存后在你的百炼账号里建一张热词表）",
        draft.hotwords,
        { onDraft(draft.copy(hotwords = it)) },
    )
}

@Composable
private fun VoiceTurnsTab(forms: VoiceForms, onForms: (VoiceForms) -> Unit, choose: VoiceChoice) {
    val onDraft = { next: VoiceDraft -> onForms(forms.copy(voice = next)) }
    VoiceNote(
        "点击控制台发送按钮旁的麦克风唤醒，持续监听，再点一次结束。开着 Jev 判断时，由 Jev 判断是否对小博说、是否说完，" +
            "旁人的对话和拿不准的内容只标记、不执行；关掉或没有 Jev key 时，识别出的每句话直接交给 Agent。",
    )
    VoiceCard("说完与对象判断") {
        VoiceSwitchRow("用 Jev 判断话说完没、是不是对小博说（需要 Jev key）", forms.voice.jevTurns) { on -> choose { it.copy(jevTurns = on) } }
    }
    JevConnectionCard(forms, onForms)
    VoiceCard("判断阈值") { VoiceJudgeSection(forms.voice, onDraft, choose) }
    VoiceCard("打断与提示音") { VoiceBargeInSection(forms.voice, onDraft, choose) }
}

@Composable
private fun JevConnectionCard(forms: VoiceForms, onForms: (VoiceForms) -> Unit) {
    val saved = forms.jevSaved
    val status = when {
        !forms.saved.jevTurns -> "已关闭：每句话直接交给 Agent" to DebugColors.muted
        saved.hasApiKey -> "可用 · ${saved.model}" to DebugColors.success
        else -> "没有 Jev key：每句话直接交给 Agent（到「密钥」页填写可开启判断）" to DebugColors.muted
    }
    VoiceCard("Jev 连接", status) {
        VoiceNote(
            "工具与技能 → 工具检索 也用这个连接。识别出的文字（含可能的旁聊）和最近几轮对话会发给 Jev，" +
                "每次判断约 2k 输入 token，按调用量计费。",
        )
        val jev = forms.jev
        VoiceFieldRow(
            { VoiceField("地址", jev.endpoint, { onForms(forms.copy(jev = jev.copy(endpoint = it))) }, it) },
            { VoiceField("模型", jev.model, { onForms(forms.copy(jev = jev.copy(model = it))) }, it) },
        )
        VoiceField("超时（毫秒，500–15000）", jev.timeout, { onForms(forms.copy(jev = jev.copy(timeout = it))) }, number = true)
    }
}

@Composable
private fun VoiceSpeechTab(tts: TtsForm?, onTts: (TtsForm) -> Unit, choose: (String, String) -> Unit, onMessage: (String) -> Unit) {
    if (tts == null) {
        VoiceNote("读取中…")
        return
    }
    val context = LocalContext.current
    TtsProviderPicker(tts.effective.provider) { choose(TTS_PROVIDER, it.wireName) }
    if (tts.effective.provider == DebugTtsProvider.DOUBAO) return DoubaoSpeechCard(tts, onTts, choose, onMessage)
    val status = ttsStatusLine(tts.effective) to (if (tts.effective.ready) DebugColors.success else DebugColors.danger)
    VoiceCard("语音播报（MiMo TTS）", status) {
        val draft = tts.draft
        val edit = { next: DebugTtsConfig -> onTts(tts.copy(draft = next)) }
        VoiceSwitchRow("播报回复", draft.enabled) { choose(MIMO_TTS_ENABLED, it.toString()) }
        VoiceFieldRow(
            { VoiceField("地址", draft.baseUrl, { edit(draft.copy(baseUrl = it)) }, it) },
            { VoiceField("模型", draft.model, { edit(draft.copy(model = it)) }, it) },
        )
        MimoVoicePicker(draft.voice) { choose(MIMO_TTS_VOICE, it) }
        VoiceField("风格（告诉 TTS 怎么读）", draft.style, { edit(draft.copy(style = it)) }, singleLine = false)
        VoiceSwitchRow("流式播放（边合成边播）", draft.stream) { choose(MIMO_TTS_STREAM, it.toString()) }
        VoiceNote("语音发起的请求，回复总会播报，部分完成和失败也播；打字发起的只在完成或需要你回答时播报。")
        TextButton(enabled = tts.effective.ready, onClick = {
            onMessage("正在试听已保存的设置…")
            DebugRuntimeSession.previewTts(context, "你好，我是小博，播报正常。") { ok -> onMessage(if (ok) "已开始播报" else "播报没有启动") }
        }) { Text("试听") }
    }
}

/** 音色: the mimo-v2.5-tts presets as chips; a voice set elsewhere (local.properties, the provider file) keeps a chip. */
@Composable
private fun MimoVoicePicker(selected: String, onSelect: (String) -> Unit) {
    Text("音色", style = DebugType.label, color = DebugColors.muted)
    MimoTtsVoice.entries.partition { it.chinese }.toList().forEach { voices ->
        Row(horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm)) {
            voices.forEach { voice -> VoiceChip(voice.label, selected == voice.id) { onSelect(voice.id) } }
        }
    }
    if (selected.isNotBlank() && MimoTtsVoice.entries.none { it.id == selected }) {
        VoiceChip("其他 · $selected", selected = true) {}
    }
    VoiceNote("预置音色只用于 mimo-v2.5-tts 模型。")
}

@Composable
private fun VoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(DebugDimens.radiusFull)
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (selected) DebugColors.accentSoft else DebugColors.elevated)
            .border(1.dp, if (selected) DebugColors.accent else DebugColors.borderSubtle, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = DebugDimens.spaceMd),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = DebugType.bodySmall, color = if (selected) DebugColors.accent else DebugColors.text)
    }
}

internal fun ttsStatusLine(config: DebugTtsConfig): String = when {
    config.ready -> "可用 · ${config.label}"
    config.provider == DebugTtsProvider.DOUBAO && config.doubao.key.isBlank() -> "缺少豆包语音 key：到「密钥」页填写；现在回复只显示、不播报"
    config.provider == DebugTtsProvider.MIMO && config.apiKey.isBlank() -> "缺少 MiMo key：到「密钥」页填写；现在回复只显示、不播报"
    !config.enabled -> "已关闭"
    else -> "配置不完整"
}

/** A titled panel; [status] is one line in the colour that says whether it is good news. */
@Composable
private fun VoiceCard(title: String, status: Pair<String, Color>? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DebugDimens.radiusMd),
        color = DebugColors.panel,
        border = BorderStroke(1.dp, DebugColors.borderSubtle),
    ) {
        Column(Modifier.padding(DebugDimens.spaceMd), verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm)) {
            Text(title, style = DebugType.panelTitle, color = DebugColors.text)
            status?.let { (text, color) -> Text("● $text", style = DebugType.bodySmall, color = color) }
            content()
        }
    }
}

@Composable
private fun VoiceNote(text: String) {
    Text(text, style = DebugType.bodySmall, color = DebugColors.muted)
}

@Composable
private fun DeleteKeyButton(stored: Boolean, onClick: () -> Unit) {
    TextButton(enabled = stored, onClick = onClick) { Text("删除 key") }
}

private fun keyLabel(stored: Boolean): String = if (stored) "API key · 已保存（留空保持不变）" else "API key"

/**
 * 语音唤醒, off by default: applied at once, outside 保存, since it opens the microphone. While on, the wake word is
 * listened for on the device whether or not the overlay runs (the system mic indicator stays lit).
 */
@Composable
private fun VoiceWakeSection() {
    val context = LocalContext.current
    val enabled by DebugWakeWord.enabled.collectAsState()
    val status by DebugWakeWord.status.collectAsState()
    var message by remember { mutableStateOf("") }
    // The switch as saved, even before the overlay has started.
    LaunchedEffect(Unit) { DebugWakeWord.load(context) }
    VoiceSwitchRow("说「你好，小博」「hi，小博」或长按小博唤醒（关闭时长按小博是让它休息）", enabled) { on ->
        message = if (DebugWakeWord.setEnabled(context, on)) "" else "无法保存开关。"
    }
    Text(
        "立即生效，不用点保存。唤醒词由本机的小模型一直听（约 4.6 MB，不上传音频）；唤醒后小博飞到中间打开卡片，卡片的输入框换成语音，" +
            "空闲 ${WAKE_IDLE_END_MILLIS / 1000} 秒、点「收起」或说「退下吧」「再见」后结束，再回到听唤醒词（说退出只对唤醒的会话有效）。" +
            "操作屏幕时，听到的话显示在屏幕顶部，截图时隐藏。",
        style = DebugType.bodySmall,
        color = DebugColors.muted,
    )
    Text(
        listOf(if (enabled) "状态：${status.message}" else "", message).filter(String::isNotEmpty).joinToString(" · "),
        style = DebugType.bodySmall,
        color = if (status.listening) DebugColors.success else DebugColors.muted,
    )
    WakeSensitivityOptions { failed -> message = if (failed) "无法保存唤醒阈值。" else "" }
}

/** 唤醒阈值: how readily the wake word wakes, applied at once; the spotter starts again with it. */
@Composable
private fun WakeSensitivityOptions(onSaved: (failed: Boolean) -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(loadDebugVoiceConfig(context).wakeSensitivity) }
    Text("唤醒阈值", style = DebugType.label, color = DebugColors.muted)
    WakeSensitivity.entries.forEach { sensitivity ->
        val (label, subtitle) = sensitivity.labels()
        DebugSelectableOptionRow(label, selected == sensitivity, {
            val saved = DebugWakeWord.setSensitivity(context, sensitivity)
            if (saved) selected = sensitivity
            onSaved(!saved)
        }, subtitle = subtitle)
    }
}

private fun WakeSensitivity.labels(): Pair<String, String> {
    val phrases = phrases.map { it.phrase }.distinct().joinToString("、") { "「$it」" }
    return when (this) {
        WakeSensitivity.HIGH -> "高 · 不易误唤醒" to "只认 $phrases，要说得清楚"
        WakeSensitivity.STANDARD -> "中 · 默认" to "认 $phrases"
        WakeSensitivity.LOW -> "低 · 容易唤醒" to "认 $phrases，发音不太准也唤醒"
    }
}

/** The form as typed; numbers stay text until saved, where validation reports what is wrong. */
private data class VoiceDraft(
    val engine: DebugVoiceEngine,
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
    fun applyTo(saved: DebugVoiceConfig): DebugVoiceConfig = saved.copy(
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
    fun withChoicesFrom(config: DebugVoiceConfig): VoiceDraft = copy(
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
        fun of(config: DebugVoiceConfig) = VoiceDraft(
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
        if (aecAvailable) "开启后录音改用通话音源，由系统做回声消除和降噪。"
        else "此设备没有系统回声消除：保持半双工，Agent 处理和播报期间暂停收音。",
        style = DebugType.bodySmall,
        color = if (aecAvailable) DebugColors.muted else DebugColors.warning,
    )
}

@Composable
private fun VoiceBargeInSection(draft: VoiceDraft, onDraft: (VoiceDraft) -> Unit, choose: VoiceChoice) {
    VoiceSwitchRow("播报和执行任务时继续收音、可以打断（需开启回声消除）", draft.bargeIn) { on -> choose { it.copy(bargeIn = on) } }
    Text(
        "和播报内容重合的残余回声在本地丢弃，Jev 判断为插话时立即停止播报。",
        style = DebugType.bodySmall,
        color = DebugColors.muted,
    )
    VoiceField("打断阈值（Jev taking_floor ≥）", draft.bargeInScore, { onDraft(draft.copy(bargeInScore = it)) }, number = true)
    VoiceSwitchRow("追加 / 换任务时的提示音（嘀嘀 = 追加，嘀 = 换任务）", draft.handOverChime) { on -> choose { it.copy(handOverChime = on) } }
}

@Composable
private fun VoiceJudgeSection(draft: VoiceDraft, onDraft: (VoiceDraft) -> Unit, choose: VoiceChoice) {
    VoiceFieldRow(
        { VoiceField("说完阈值（无静音时）", draft.eos, { onDraft(draft.copy(eos = it)) }, it, number = true) },
        { VoiceField("说完阈值下限（静音 3 秒后）", draft.eosFloor, { onDraft(draft.copy(eosFloor = it)) }, it, number = true) },
    )
    VoiceFieldRow(
        { VoiceField("面向 Agent ≥", draft.agentBound, { onDraft(draft.copy(agentBound = it)) }, it, number = true) },
        { VoiceField("人人对话 <", draft.sideBound, { onDraft(draft.copy(sideBound = it)) }, it, number = true) },
    )
    VoiceFieldRow(
        { VoiceField("静音上限（秒）", draft.silenceCapSeconds, { onDraft(draft.copy(silenceCapSeconds = it)) }, it, number = true) },
        { VoiceField("上下文条数（0–12）", draft.history, { onDraft(draft.copy(history = it)) }, it, number = true) },
    )
    VoiceSwitchRow("面向 Agent 的话自动执行并播报", draft.autoSubmit) { on -> choose { it.copy(autoSubmit = on) } }
}

@Composable
private fun VoiceSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = DebugColors.text)
        Switch(checked, onChange, colors = debugSwitchColors())
    }
}

/** Two fields side by side, each given its half of the row. */
@Composable
private fun VoiceFieldRow(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm)) {
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
        colors = debugOutlinedTextFieldColors(),
        modifier = modifier,
    )
}

/** 播报服务: MiMo or 豆包, saved when tapped; each keeps its own settings. */
@Composable
private fun TtsProviderPicker(selected: DebugTtsProvider, onSelect: (DebugTtsProvider) -> Unit) {
    Text("播报服务", style = DebugType.label, color = DebugColors.muted)
    Row(horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm)) {
        DebugTtsProvider.entries.forEach { provider -> VoiceChip(provider.label, selected == provider) { onSelect(provider) } }
    }
}

/** 豆包语音合成: switch, App ID (only for an App ID + Access Token pair), resource, voice, speed, 试听. */
@Composable
private fun DoubaoSpeechCard(tts: TtsForm, onTts: (TtsForm) -> Unit, choose: (String, String) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val status = ttsStatusLine(tts.effective) to (if (tts.effective.ready) DebugColors.success else DebugColors.danger)
    VoiceCard("语音播报（豆包语音合成）", status) {
        val draft = tts.draft
        val edit = { next: DoubaoTtsSettings -> onTts(tts.copy(draft = draft.copy(doubao = next))) }
        VoiceSwitchRow("播报回复", draft.enabled) { choose(MIMO_TTS_ENABLED, it.toString()) }
        VoiceFieldRow(
            { VoiceField("资源 ID（2.0 音色用 seed-tts-2.0）", draft.doubao.resourceId, { edit(draft.doubao.copy(resourceId = it)) }, it) },
            { VoiceField("App ID（用 API key 时留空）", draft.doubao.appId, { edit(draft.doubao.copy(appId = it)) }, it) },
        )
        DoubaoVoicePicker(draft.doubao.voice) { choose(DOUBAO_TTS_VOICE, it) }
        Text("语速", style = DebugType.label, color = DebugColors.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm)) {
            DOUBAO_SPEEDS.forEach { (label, rate) -> VoiceChip(label, draft.doubao.speechRate == rate) { choose(DOUBAO_TTS_SPEECH_RATE, rate.toString()) } }
        }
        VoiceNote("用火山引擎「豆包语音」的单向流式接口，边合成边播。新版控制台的 API key 直接填在「密钥」页；旧版用 App ID + Access Token 时，App ID 填这里、Access Token 填「密钥」页。")
        TextButton(enabled = tts.effective.ready, onClick = {
            onMessage("正在试听已保存的设置…")
            DebugRuntimeSession.previewTts(context, "你好，我是小博，播报正常。") { ok -> onMessage(if (ok) "已开始播报" else "播报没有启动") }
        }) { Text("试听") }
    }
}

/** 音色: some 豆包 2.0 voices as chips; an id typed elsewhere (local.properties) keeps a chip. */
@Composable
private fun DoubaoVoicePicker(selected: String, onSelect: (String) -> Unit) {
    Text("音色", style = DebugType.label, color = DebugColors.muted)
    DoubaoTtsVoice.entries.chunked(DOUBAO_VOICES_PER_ROW).forEach { voices ->
        Row(horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm)) {
            voices.forEach { voice -> VoiceChip(voice.label, selected == voice.id) { onSelect(voice.id) } }
        }
    }
    if (selected.isNotBlank() && DoubaoTtsVoice.entries.none { it.id == selected }) VoiceChip("其他 · $selected", selected = true) {}
}

@Composable
private fun DoubaoKeyCard(tts: TtsForm?, onTts: (TtsForm) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val packaged = tts != null && !tts.doubaoKeyStored && tts.effective.doubao.key.isNotBlank()
    val status = when {
        tts == null -> "读取中…" to DebugColors.muted
        tts.doubaoKeyStored -> "已保存" to DebugColors.success
        packaged -> "用打包时的豆包语音 key" to DebugColors.success
        else -> "未填：选了豆包播报时需要" to DebugColors.muted
    }
    VoiceCard("豆包语音", status) {
        VoiceNote("播报选豆包时用：新版控制台的 API key，或旧版的 Access Token（App ID 填在「语音播报」页）。")
        if (tts == null) return@VoiceCard
        VoiceField(keyLabel(tts.doubaoKeyStored), tts.doubaoKey, { onTts(tts.copy(doubaoKey = it)) }, secret = true)
        DeleteKeyButton(tts.doubaoKeyStored) {
            scope.launch {
                val cleared = withContext(Dispatchers.IO) { clearDebugDoubaoTtsKey(context) }
                onMessage(if (cleared) "已删除这里保存的豆包语音 key" else "无法删除豆包语音 key")
                if (cleared) {
                    onTts(TtsForm.load(context))
                    DebugRuntimeSession.refreshTts(context)
                }
            }
        }
    }
}

private val DOUBAO_SPEEDS = listOf("慢" to -25, "正常" to 0, "快" to 25, "很快" to 50)
private const val DOUBAO_VOICES_PER_ROW = 4
