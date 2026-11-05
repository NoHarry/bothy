package cc.noharry.bothy.server.voice.tts
import cc.noharry.bothy.server.voice.VoiceLog

internal object TtsLog {
    fun info(message: String) {
        VoiceLog.i(TAG, message)
    }

    fun warn(message: String) {
        VoiceLog.w(TAG, message)
    }

    private const val TAG = "DebugTts"
}
