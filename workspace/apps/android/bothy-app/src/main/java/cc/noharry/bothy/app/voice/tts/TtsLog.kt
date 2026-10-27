package cc.noharry.bothy.app.voice.tts
import android.util.Log
import cc.noharry.bothy.app.voice.VoiceLog

internal object TtsLog {
    fun info(message: String) {
        VoiceLog.i(TAG, message)
    }

    fun warn(message: String) {
        VoiceLog.w(TAG, message)
    }

    private const val TAG = "DebugTts"
}
