package cc.noharry.bothy.sample

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The voice's log lines (wake, recording, gates, turns, end-to-end), written to logcat as before and kept in memory
 * too, so 问题反馈 and log uploads carry them (owner 2026-10-14: 「日志里面能多有点信息吗」). an early report had none:
 * its system log rolled over every ~70 s under window-manager lines, and some ROMs drop third-party info logs. The
 * last [MAX_LINES] stay, for this process only; recognized speech is in them, as in logcat. Thread-safe.
 */
internal object DebugVoiceLog {
    private val lines = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun i(tag: String, message: String) {
        runCatching { Log.i(tag, message) }
        keep("I", tag, message)
    }

    fun w(tag: String, message: String) {
        runCatching { Log.w(tag, message) }
        keep("W", tag, message)
    }

    fun e(tag: String, message: String) {
        runCatching { Log.e(tag, message) }
        keep("E", tag, message)
    }

    /** The kept lines, oldest first. */
    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    private fun keep(level: String, tag: String, message: String) {
        synchronized(lines) {
            lines.addLast("${stamp.format(Date())} $level $tag: $message")
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    /** About an hour of a busy voice session's lines; ~150 KB. */
    private const val MAX_LINES = 1_500
}
