package cc.noharry.bothy.server.voice

import android.content.Context
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.runtime.RunAnnouncer
import cc.noharry.agent.appserver.runtime.RuntimeRunScope
import cc.noharry.agent.appserver.runtime.VoicePort
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.bothy.server.voice.tts.TtsActivity
import cc.noharry.bothy.server.voice.tts.TtsAnnouncer
import cc.noharry.bothy.server.voice.tts.TtsConfig
import cc.noharry.bothy.server.voice.tts.deviceTtsAnnouncer
import cc.noharry.bothy.server.voice.tts.ttsConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 小博's voice for the runtime ([VoicePort]): runs spoken through the configured TTS (MiMo or 豆包), what a run says
 * as [RunSpeech] decides (silenced by the card's X, told to 豆包 in 端到端), and listening again after a report.
 */
internal object BothyVoice : VoicePort {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The TTS settings, loaded off the main thread at start and again when they are saved. */
    @Volatile private var config: TtsConfig? = null

    /** From the process's start: the TTS settings load, so the first line is said with them. */
    fun start(context: Context) {
        io.launch { load(context) }
    }

    override fun deviceAnnouncer(context: Context, scope: CoroutineScope, followsSwitch: Boolean, onStatus: (String) -> Unit): RunAnnouncer =
        TtsRunAnnouncer(deviceTtsAnnouncer(context, scope, onStatus), followsSwitch) { config ?: TtsConfig() }

    override fun textAnnouncer(scope: CoroutineScope, output: (text: String, final: Boolean) -> Unit): RunAnnouncer =
        TtsRunAnnouncer(TtsAnnouncer(scope, textOutput = output), followsSwitch = false, textOnly = true) { TtsConfig() }

    override fun speak(event: AgentEvent, requestId: String, owns: () -> Boolean, say: () -> Unit) = RunSpeech.speak(event, requestId, owns, say)

    override fun silence(requestId: String) = RunSpeech.silence(requestId)

    override val active: StateFlow<Boolean> get() = TtsActivity.active

    override suspend fun ready(context: Context): Boolean = (config ?: load(context)).ready

    override fun refresh(context: Context) {
        // 小博's wake answer follows the voice too, from the next wake on.
        WakeAck.prepare(context.applicationContext)
        io.launch { load(context) }
    }

    override fun planDue() = VoiceFeedback.planDue(null)

    override fun listenAfterReport(context: Context, since: Long) {
        if (!VoiceController.closedSince(since)) VoiceController.followUp(context.applicationContext)
    }

    private suspend fun load(context: Context): TtsConfig =
        withContext(Dispatchers.IO) { ProviderConfig.load(context.applicationContext).ttsConfig() }.also { config = it }
}

/**
 * A [TtsAnnouncer] as a [RunAnnouncer]: a run is said when TTS is on ([followsSwitch]: the 播报 switch, else whenever
 * TTS is set up); a text-only one hands every line on.
 */
private class TtsRunAnnouncer(
    private val tts: TtsAnnouncer,
    private val followsSwitch: Boolean,
    private val textOnly: Boolean = false,
    private val config: () -> TtsConfig,
) : RunAnnouncer {
    override val busy: Boolean get() = tts.busy

    override fun observe(runScope: RuntimeRunScope, event: AgentEvent, ownsUi: (RuntimeRunScope) -> Boolean) {
        val current = config()
        if (textOnly || current.enabled) tts.observe(runScope, event, current, ownsUi)
    }

    override fun announce(text: String): Boolean = speakable()?.let { tts.announce(it, text); true } ?: false

    override fun announceFirst(text: String): Boolean = speakable()?.let { tts.announceFirst(it, text); true } ?: false

    override fun speakWireSpeech(taskId: String, text: String, final: Boolean) {
        speakable()?.let { tts.speakWireSpeech(it, taskId, text, final) }
    }

    override fun stop() = tts.stop()

    /** The settings to speak with now, or null when nothing can be said. */
    private fun speakable(): TtsConfig? = config().takeIf { it.ready && (!followsSwitch || it.enabled) }
}
