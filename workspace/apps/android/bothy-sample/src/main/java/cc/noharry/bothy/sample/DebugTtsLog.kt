package cc.noharry.bothy.sample

import android.util.Log

internal object DebugTtsLog {
    fun info(message: String) {
        DebugVoiceLog.i(TAG, message)
    }

    fun warn(message: String) {
        DebugVoiceLog.w(TAG, message)
    }

    private const val TAG = "DebugTts"
}
