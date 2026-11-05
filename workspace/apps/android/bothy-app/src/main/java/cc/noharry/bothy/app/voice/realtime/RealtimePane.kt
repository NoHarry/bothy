package cc.noharry.bothy.app.voice.realtime
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.Empty
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
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.app.words
import cc.noharry.bothy.protocol.CUSTOM_REALTIME_PERSONA
import cc.noharry.bothy.protocol.RealtimeRequests
import cc.noharry.bothy.protocol.RealtimeSettings
import cc.noharry.bothy.protocol.RealtimeView
import cc.noharry.bothy.protocol.voice

/**
 * 设置 → 端到端语音: the switch, barge-in, the voice and the persona, and a connection test. Switches, the voice and a
 * persona preset apply when tapped (from the next wake); typed persona text waits for 保存 and becomes 自定义.
 */
@Composable
internal fun RealtimePane() {
    val server = LocalServer.current
    var view by remember { mutableStateOf<RealtimeView?>(null) }
    LaunchedEffect(Unit) { server.send(RealtimeRequests.READ, Empty) { view = it } }
    view?.let { RealtimeForm(it) { stored -> view = stored } }
}

@Composable
private fun RealtimeForm(view: RealtimeView, onStored: (RealtimeView) -> Unit) {
    val server = LocalServer.current
    val saved = view.settings
    var persona by remember { mutableStateOf(view.effectivePersona) }
    var note by remember { mutableStateOf(SettingsNote()) }
    val savedAfterRestart = stringResource(R.string.realtime_saved_after_restart)
    val savedNextWake = stringResource(R.string.realtime_saved_next_wake)
    val discarded = stringResource(R.string.voice_discarded)
    val listening = server.state.voice.listening
    val apply = { candidate: RealtimeSettings, then: (RealtimeView) -> Unit ->
        server.send(RealtimeRequests.SAVE, candidate) { stored ->
            onStored(stored)
            note = SettingsNote(if (listening) savedAfterRestart else savedNextWake)
            then(stored)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).widthIn(max = 880.dp).verticalScroll(rememberScrollState()).padding(Dimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
        ) {
            RealtimeSwitches(view) { apply(it) {} }
            Text(stringResource(R.string.voice_voice), style = ConsoleType.label, color = ConsoleColors.muted)
            view.voices.forEach { voice ->
                SelectableOptionRow(voice.label, saved.voice == voice.id, { apply(saved.copy(voice = voice.id)) {} })
            }
            RealtimePersonas(view) { preset ->
                apply(saved.copy(personaPreset = preset)) { persona = it.effectivePersona }
            }
            OutlinedTextField(
                value = persona,
                onValueChange = { persona = it },
                label = { Text(stringResource(R.string.realtime_persona_text)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                colors = outlinedTextFieldColors(),
            )
            RealtimeTry()
        }
        SettingsSaveBar(
            unsaved = persona.trim() != view.effectivePersona,
            note = note,
            onSave = {
                // A blank persona is stored as the default one.
                apply(saved.copy(persona = persona.trim(), personaPreset = CUSTOM_REALTIME_PERSONA)) { persona = it.effectivePersona }
            },
            onDiscard = { persona = view.effectivePersona; note = SettingsNote(discarded) },
        )
    }
}

@Composable
private fun RealtimeSwitches(view: RealtimeView, onApply: (RealtimeSettings) -> Unit) {
    val saved = view.settings
    Text(stringResource(R.string.realtime_intro), color = ConsoleColors.muted)
    if (!view.hasKey) {
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
private fun RealtimePersonas(view: RealtimeView, onPick: (String) -> Unit) {
    val saved = view.settings
    Text(stringResource(R.string.realtime_persona), style = ConsoleType.label, color = ConsoleColors.muted)
    view.personas.forEach { preset ->
        SelectableOptionRow(preset.label, saved.personaPreset == preset.id, { onPick(preset.id) }, subtitle = preset.summary)
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
private fun RealtimeTry() {
    val server = LocalServer.current
    val resources = LocalResources.current
    // The last test's line, and whether it failed.
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val connecting = stringResource(R.string.realtime_connecting)
    TextButton(enabled = !busy, onClick = {
        busy = true
        result = connecting to false
        server.send(
            RealtimeRequests.TEST,
            Empty,
            onRefused = { result = it.message.orEmpty() to true; busy = false },
        ) { result = resources.words(it.finding) to it.failed; busy = false }
    }) { Text(stringResource(R.string.realtime_test)) }
    result?.let { (line, failed) ->
        Text(line, style = ConsoleType.bodySmall, color = if (failed) ConsoleColors.danger else ConsoleColors.text)
    }
}
