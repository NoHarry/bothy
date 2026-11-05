package cc.noharry.bothy.protocol

import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.serialization.Serializable

/** 设置 → 端到端语音 as stored; [personaPreset] is a preset's id or [CUSTOM_REALTIME_PERSONA] for the typed [persona]. */
@Serializable
data class RealtimeSettings(
    val enabled: Boolean = false,
    val voice: String = "",
    val persona: String = "",
    val bargeIn: Boolean = false,
    val personaPreset: String = "",
    val lateSpeech: Boolean = false,
)

/** A choice on a settings page, named in the screen's language; [summary] says more, or groups it. */
@Serializable
data class SettingsOption(val id: String, val label: String, val summary: String = "")

/** The settings with what the page offers: the voices, the persona presets, and whether the 豆包语音 key is there. */
@Serializable
data class RealtimeView(
    val settings: RealtimeSettings = RealtimeSettings(),
    /** Who the model is told it is: the preset's text, or the typed persona. */
    val effectivePersona: String = "",
    val voices: List<SettingsOption> = emptyList(),
    val personas: List<SettingsOption> = emptyList(),
    val hasKey: Boolean = false,
)

/** A check's one line, and whether it failed. */
@Serializable
data class CheckLine(val finding: Finding, val failed: Boolean = false)

/** 设置 → 端到端语音. */
object RealtimeRequests {
    val READ = RequestMethod("realtime/read", Empty.serializer(), RealtimeView.serializer())
    val SAVE = RequestMethod("realtime/save", RealtimeSettings.serializer(), RealtimeView.serializer())

    /** Opens a session with the saved settings and closes it: how long it took, or why it failed. */
    val TEST = RequestMethod("realtime/test", Empty.serializer(), CheckLine.serializer())
}

/** [RealtimeSettings.personaPreset] when the typed persona is used instead of a preset. */
const val CUSTOM_REALTIME_PERSONA = "custom"
