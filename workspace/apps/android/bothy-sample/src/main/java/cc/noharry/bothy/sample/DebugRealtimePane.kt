package cc.noharry.bothy.sample

import android.content.Context
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 → 端到端语音: the switch, barge-in, the voice and the persona, and a connection test. Switches, the voice and a
 * persona preset apply when tapped (from the next wake); typed persona text waits for 保存 and becomes 自定义.
 */
@Composable
internal fun DebugRealtimePane() {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(loadDebugRealtimeConfig(context)) }
    var persona by remember { mutableStateOf(saved.effectivePersona) }
    var note by remember { mutableStateOf(SettingsNote()) }
    val apply = { candidate: DebugRealtimeConfig ->
        saveDebugRealtimeConfig(context, candidate)
        saved = loadDebugRealtimeConfig(context)
        note = SettingsNote(if (DebugVoiceController.listening) "已保存 · 结束当前对话后重新唤醒生效" else "已保存，下次唤醒生效")
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).widthIn(max = 880.dp).verticalScroll(rememberScrollState()).padding(DebugDimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm),
        ) {
            RealtimeSwitches(saved, apply)
            Text("音色", style = DebugType.label, color = DebugColors.muted)
            DoubaoRealtimeVoice.entries.forEach { voice ->
                DebugSelectableOptionRow(voice.label, saved.voice == voice.id, { apply(saved.copy(voice = voice.id)) })
            }
            RealtimePersonas(saved) { preset ->
                apply(saved.copy(personaPreset = preset))
                persona = saved.effectivePersona
            }
            OutlinedTextField(
                value = persona,
                onValueChange = { persona = it },
                label = { Text("人设内容（改了按保存就是自定义；办事交给小博、结束对话的规则会自动加在后面）") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                colors = debugOutlinedTextFieldColors(),
            )
            RealtimeTry(saved)
        }
        SettingsSaveBar(
            unsaved = persona.trim() != saved.effectivePersona,
            note = note,
            onSave = {
                apply(saved.copy(persona = persona.trim().ifEmpty { DEFAULT_REALTIME_PERSONA }, personaPreset = CUSTOM_REALTIME_PERSONA))
                persona = saved.effectivePersona
            },
            onDiscard = { persona = saved.effectivePersona; note = SettingsNote("已放弃修改") },
        )
    }
}

@Composable
private fun RealtimeSwitches(saved: DebugRealtimeConfig, onApply: (DebugRealtimeConfig) -> Unit) {
    val context = LocalContext.current
    Text(
        "演示用：唤醒后由豆包端到端实时语音（Seeduplex）直接听和说，闲聊它自己回答；要办事时它把事情交给小博执行（小博不出声），" +
            "再把结果说给你。打开后，唤醒和麦克风按钮都走这条路，不经过语音识别、Jev 和陪聊。用的是 设置 → 语音 → 密钥 里的豆包语音 key，" +
            "控制台要开通端到端实时语音。",
        color = DebugColors.muted,
    )
    if (realtimeKey(context).isEmpty()) {
        Text("还没有豆包语音 key：先到 设置 → 语音 → 密钥 填写。", style = DebugType.bodySmall, color = DebugColors.warning)
    }
    RealtimeSwitch("启用端到端演示模式", saved.enabled) { onApply(saved.copy(enabled = it)) }
    RealtimeSwitch("允许打断（全双工）", saved.bargeIn) { onApply(saved.copy(bargeIn = it)) }
    Text(
        "关着时豆包说话期间听不到你（设备回声消除不好时不会自己打断自己），想打断就喊唤醒词；打开后可以直接插话。",
        style = DebugType.bodySmall,
        color = DebugColors.muted,
    )
    RealtimeSwitch("小博补播报晚到的结果", saved.lateSpeech) { onApply(saved.copy(lateSpeech = it)) }
    Text(
        "关着时（默认），豆包等不到的结果（超过等待时间、子任务和计划的回报）只显示在屏幕上，并悄悄告诉豆包，问起时它知道；" +
            "打开后由小博等豆包说完再自己念出来。",
        style = DebugType.bodySmall,
        color = DebugColors.muted,
    )
}

/** The presets, then 自定义 (the persona last typed and saved). */
@Composable
private fun RealtimePersonas(saved: DebugRealtimeConfig, onPick: (String) -> Unit) {
    Text("人设", style = DebugType.label, color = DebugColors.muted)
    DoubaoRealtimePersona.entries.forEach { preset ->
        DebugSelectableOptionRow(preset.label, saved.personaPreset == preset.id, { onPick(preset.id) }, subtitle = preset.summary)
    }
    DebugSelectableOptionRow(
        "自定义",
        saved.personaPreset == CUSTOM_REALTIME_PERSONA,
        { onPick(CUSTOM_REALTIME_PERSONA) },
        subtitle = "用下面框里自己写的人设",
    )
}

@Composable
private fun RealtimeSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = DebugColors.text)
        Switch(checked, onChange, colors = debugSwitchColors())
    }
}

@Composable
private fun RealtimeTry(saved: DebugRealtimeConfig) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    TextButton(enabled = !busy, onClick = {
        busy = true
        result = "正在连接豆包端到端…"
        scope.launch {
            result = realtimeConnectionLine(context, saved)
            busy = false
        }
    }) { Text("测试连接（用已保存的设置）") }
    result?.let { Text(it, style = DebugType.bodySmall, color = if (it.startsWith("失败")) DebugColors.danger else DebugColors.text) }
}

private suspend fun realtimeConnectionLine(context: Context, config: DebugRealtimeConfig): String {
    val key = withContext(Dispatchers.IO) { realtimeKey(context) }
    if (key.isEmpty()) return "失败：缺少豆包语音 key"
    return probeRealtime(context, key, config).fold(
        onSuccess = { "会话建立用了 $it ms · ${DoubaoRealtimeVoice.labelOf(config.voice)} · ${config.personaLabel()}" },
        onFailure = { "失败：${it.message ?: it.javaClass.simpleName}" },
    )
}

private suspend fun probeRealtime(context: Context, key: String, config: DebugRealtimeConfig): Result<Long> = try {
    withContext(Dispatchers.IO) { probeDoubaoRealtime(doubaoRealtimeClient(context), key, config) }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}

/** 一键检查 → 端到端演示: off, no key, or how long a session takes to open. */
/** 端到端 in a report: on, 允许打断, the voice and the persona. */
internal fun DebugRealtimeConfig.reportLine(): String =
    "开 · 允许打断${if (bargeIn) "开" else "关"} · 补播报${if (lateSpeech) "开" else "关"} · ${DoubaoRealtimeVoice.labelOf(voice)} · ${personaLabel()}"

internal suspend fun checkRealtime(context: Context): DebugCheckResult {
    val config = loadDebugRealtimeConfig(context)
    if (!config.enabled) return DebugCheckResult(DebugCheckState.OFF, "没打开")
    val key = withContext(Dispatchers.IO) { realtimeKey(context) }
    if (key.isEmpty()) return DebugCheckResult(DebugCheckState.FAIL, "缺少豆包语音 key", DebugCheckFix.VOICE)
    val voice = DoubaoRealtimeVoice.labelOf(config.voice)
    return probeRealtime(context, key, config).fold(
        onSuccess = { DebugCheckResult(DebugCheckState.OK, "$voice · ${config.personaLabel()} · 允许打断${if (config.bargeIn) "开" else "关"} · 会话建立用了 $it ms") },
        onFailure = { DebugCheckResult(DebugCheckState.FAIL, it.message ?: it.javaClass.simpleName) },
    )
}
