package cc.noharry.bothy.server.server

import android.content.Context
import cc.noharry.agent.appserver.server.RouteModule
import cc.noharry.agent.appserver.server.RouteTable
import cc.noharry.bothy.protocol.CheckLine
import cc.noharry.bothy.protocol.RealtimeRequests
import cc.noharry.bothy.protocol.RealtimeSettings
import cc.noharry.bothy.protocol.RealtimeView
import cc.noharry.bothy.protocol.SettingsOption
import cc.noharry.bothy.server.voice.realtime.DEFAULT_REALTIME_PERSONA
import cc.noharry.bothy.server.voice.realtime.DoubaoRealtimePersona
import cc.noharry.bothy.server.voice.realtime.DoubaoRealtimeVoice
import cc.noharry.bothy.server.voice.realtime.RealtimeConfig
import cc.noharry.bothy.server.voice.realtime.loadRealtimeConfig
import cc.noharry.bothy.server.voice.realtime.realtimeConnectionLine
import cc.noharry.bothy.server.voice.realtime.realtimeKey
import cc.noharry.bothy.server.voice.realtime.saveRealtimeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 设置 → 端到端语音: its settings with the voices and personas to pick from, and a connection test. */
internal class RealtimeRoutes(private val app: Context) : RouteModule {
    override fun RouteTable.install() {
        on(RealtimeRequests.READ) { withContext(Dispatchers.IO) { view() } }
        on(RealtimeRequests.SAVE) { settings ->
            withContext(Dispatchers.IO) {
                saveRealtimeConfig(app, settings.config())
                view()
            }
        }
        on(RealtimeRequests.TEST) {
            val saved = withContext(Dispatchers.IO) { loadRealtimeConfig(app) }
            realtimeConnectionLine(app, saved).let { (found, failed) -> CheckLine(found, failed) }
        }
    }

    private fun view(): RealtimeView {
        val config = loadRealtimeConfig(app)
        return RealtimeView(
            settings = config.settings(),
            effectivePersona = config.effectivePersona,
            voices = DoubaoRealtimeVoice.entries.map { SettingsOption(it.id, it.label(app)) },
            personas = DoubaoRealtimePersona.entries.map {
                SettingsOption(it.id, app.getString(it.label), app.getString(it.summary))
            },
            hasKey = realtimeKey(app).isNotEmpty(),
        )
    }
}

private fun RealtimeConfig.settings() = RealtimeSettings(enabled, voice, persona, bargeIn, personaPreset, lateSpeech)

private fun RealtimeSettings.config() =
    RealtimeConfig(enabled, voice, persona.trim().ifEmpty { DEFAULT_REALTIME_PERSONA }, bargeIn, personaPreset, lateSpeech)
