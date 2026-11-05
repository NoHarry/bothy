package cc.noharry.bothy.server.voice

import android.content.Context
import android.os.SystemClock
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.WakeOrigin
import cc.noharry.agent.appserver.protocol.logText
import cc.noharry.agent.appserver.server.Presenter
import cc.noharry.bothy.server.voice.tts.TtsActivity

/**
 * A wake (你好，小博 or OK Bothy, a long press, adb): stops what 小博 is doing, answers (in English to OK Bothy), and
 * starts (or restarts) a woken session. The overlay shows it on its card; without the overlay the console's voice strip
 * does. Why it could not listen, or null.
 */
internal fun startWokenVoice(context: Context, source: WakeSource): Finding? {
    val interrupted = VoiceController.interruptForWake(context)
    // Every wake chimes at once (owner 2026-10-25: 「唤醒和退出要一个提示音」); the spoken answer follows the chime, before
    // listening is set up, which takes a few hundred milliseconds, and is taken back if it cannot listen.
    VoiceFeedback.woke()
    val answered = WakeAck.play(context, interrupted, english = source == WakeSource.ENGLISH_WORD)
    val settingUp = SystemClock.elapsedRealtime()
    val failure = if (VoiceController.listening) {
        VoiceController.restart(context)
    } else {
        VoiceController.start(context, VoiceOrigin.WAKE)
    }
    if (failure != null && answered) TtsActivity.interrupt()
    val took = SystemClock.elapsedRealtime() - settingUp
    VoiceLog.i("DebugVoice", "overlay woken by $source${if (interrupted) " (interrupting)" else ""}, listening set up in $took ms${failure?.let { ": ${it.logText()}" }.orEmpty()}")
    return failure
}

/** A wake the assistant on screen takes when it is up (it shows it on its card), else one heard without it. */
internal fun wakeAnywhere(context: Context, source: WakeSource) {
    Presenter.wake(WakeOrigin.valueOf(source.name)) { taken -> if (!taken) startWokenVoice(context.applicationContext, source) }
}
