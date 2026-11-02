package cc.noharry.bothy.app.voice.realtime
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.SettingsNote
import cc.noharry.bothy.app.console.SettingsSaveBar
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.outlinedTextFieldColors
import cc.noharry.bothy.app.console.switchColors
import cc.noharry.bothy.app.console.ui.SelectableOptionRow
import cc.noharry.bothy.app.evidence.CheckFix
import cc.noharry.bothy.app.evidence.CheckResult
import cc.noharry.bothy.app.evidence.CheckState
import cc.noharry.bothy.app.model.summary
import cc.noharry.bothy.app.voice.VoiceController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 → 端到端语音: the switch, barge-in, the voice and the persona, and a connection test. Switches, the voice and a
 * persona preset apply when tapped (from the next wake); typed persona text waits for 保存 and becomes 自定义.
 */
@Composable
internal fun RealtimePane() {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(loadRealtimeConfig(context)) }
    var persona by remember { mutableStateOf(saved.effectivePersona) }
    var note by remember { mutableStateOf(SettingsNote()) }
    val savedAfterRestart = stringResource(R.string.realtime_saved_after_restart)
    val savedNextWake = stringResource(R.string.realtime_saved_next_wake)
    val discarded = stringResource(R.string.voice_discarded)
    val apply = { candidate: RealtimeConfig ->
        saveRealtimeConfig(context, candidate)
        saved = loadRealtimeConfig(context)
        note = SettingsNote(if (VoiceController.listening) savedAfterRestart else savedNextWake)
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).widthIn(max = 880.dp).verticalScroll(rememberScrollState()).padding(Dimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
        ) {
            RealtimeSwitches(saved, apply)
            Text(stringResource(R.string.voice_voice), style = ConsoleType.label, color = ConsoleColors.muted)
            DoubaoRealtimeVoice.entries.forEach { voice ->
                val label = voice.label(context)
                SelectableOptionRow(label, saved.voice == voice.id, { apply(saved.copy(voice = voice.id)) })
            }
            RealtimePersonas(saved) { preset ->
                apply(saved.copy(personaPreset = preset))
                persona = saved.effectivePersona
            }
            OutlinedTextField(
                value = persona,
                onValueChange = { persona = it },
                label = { Text(stringResource(R.string.realtime_persona_text)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                colors = outlinedTextFieldColors(),
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
            onDiscard = { persona = saved.effectivePersona; note = SettingsNote(discarded) },
        )
    }
}

@Composable
private fun RealtimeSwitches(saved: RealtimeConfig, onApply: (RealtimeConfig) -> Unit) {
    val context = LocalContext.current
    Text(stringResource(R.string.realtime_intro), color = ConsoleColors.muted)
    if (realtimeKey(context).isEmpty()) {
        Text(stringResource(R.string.realtime_no_key), style = ConsoleType.bodySmall, color = ConsoleColors.warning)
    }
    RealtimeSwitch(stringResource(R.string.realtime_enable), saved.enabled) { onApply(saved.copy(enabled = it)) }
    RealtimeSwitch(stringResource(R.string.realtime_barge_in), saved.bargeIn) { onApply(saved.copy(bargeIn = it)) }
    Text(
        stringResource(R.string.realtime_barge_in_note),
        style = ConsoleType.bodySmall,
        color = ConsoleColors.muted,
    )
    RealtimeSwitch(stringResource(R.string.realtime_late_speech), saved.lateSpeech) {
        onApply(saved.copy(lateSpeech = it))
    }
    Text(
        stringResource(R.string.realtime_late_speech_note),
        style = ConsoleType.bodySmall,
        color = ConsoleColors.muted,
    )
}

/** The presets, then 自定义 (the persona last typed and saved). */
@Composable
private fun RealtimePersonas(saved: RealtimeConfig, onPick: (String) -> Unit) {
    Text(stringResource(R.string.realtime_persona), style = ConsoleType.label, color = ConsoleColors.muted)
    DoubaoRealtimePersona.entries.forEach { preset ->
        SelectableOptionRow(
            stringResource(preset.label),
            saved.personaPreset == preset.id,
            { onPick(preset.id) },
            subtitle = stringResource(preset.summary),
        )
    }
    SelectableOptionRow(
        stringResource(R.string.realtime_custom),
        saved.personaPreset == CUSTOM_REALTIME_PERSONA,
        { onPick(CUSTOM_REALTIME_PERSONA) },
        subtitle = stringResource(R.string.realtime_custom_summary),
    )
}

@Composable
private fun RealtimeSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = ConsoleColors.text)
        Switch(checked, onChange, colors = switchColors())
    }
}

@Composable
private fun RealtimeTry(saved: RealtimeConfig) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The last test's line, and whether it failed.
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val connecting = stringResource(R.string.realtime_connecting)
    TextButton(enabled = !busy, onClick = {
        busy = true
        result = connecting to false
        scope.launch {
            result = realtimeConnectionLine(context, saved)
            busy = false
        }
    }) { Text(stringResource(R.string.realtime_test)) }
    result?.let { (line, failed) ->
        Text(line, style = ConsoleType.bodySmall, color = if (failed) ConsoleColors.danger else ConsoleColors.text)
    }
}

/** What the connection test says, and whether it failed. */
private suspend fun realtimeConnectionLine(context: Context, config: RealtimeConfig): Pair<String, Boolean> {
    val key = withContext(Dispatchers.IO) { realtimeKey(context) }
    if (key.isEmpty()) return context.getString(R.string.realtime_failed_no_key) to true
    return probeRealtime(context, key, config).fold(
        onSuccess = {
            val voice = DoubaoRealtimeVoice.labelOf(context, config.voice)
            context.getString(R.string.realtime_connected, it, voice, config.personaLabel(context)) to false
        },
        onFailure = { context.getString(R.string.realtime_failed, it.message ?: it.javaClass.simpleName) to true },
    )
}

private suspend fun probeRealtime(context: Context, key: String, config: RealtimeConfig): Result<Long> = try {
    withContext(Dispatchers.IO) { probeDoubaoRealtime(context, doubaoRealtimeClient(context), key, config) }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}

/** 一键检查 → 端到端演示: off, no key, or how long a session takes to open. */
/** 端到端 in a report: on, 允许打断, the voice and the persona. */
internal fun RealtimeConfig.reportLine(context: Context): String = context.getString(
    R.string.realtime_report_line,
    context.getString(if (bargeIn) R.string.common_on else R.string.common_off),
    context.getString(if (lateSpeech) R.string.common_on else R.string.common_off),
    DoubaoRealtimeVoice.labelOf(context, voice),
    personaLabel(context),
)

internal suspend fun checkRealtime(context: Context): CheckResult {
    val config = loadRealtimeConfig(context)
    if (!config.enabled) return CheckResult(CheckState.OFF, context.getString(R.string.realtime_check_off))
    val key = withContext(Dispatchers.IO) { realtimeKey(context) }
    if (key.isEmpty()) {
        return CheckResult(CheckState.FAIL, context.getString(R.string.realtime_check_no_key), CheckFix.VOICE)
    }
    val voice = DoubaoRealtimeVoice.labelOf(context, config.voice)
    val bargeIn = context.getString(if (config.bargeIn) R.string.common_on else R.string.common_off)
    return probeRealtime(context, key, config).fold(
        onSuccess = {
            val persona = config.personaLabel(context)
            CheckResult(CheckState.OK, context.getString(R.string.realtime_check_ok, voice, persona, bargeIn, it))
        },
        onFailure = { CheckResult(CheckState.FAIL, it.message ?: it.javaClass.simpleName) },
    )
}
