package cc.noharry.bothy.server.voice.realtime

import android.os.SystemClock
import cc.noharry.bothy.voice.OwnSpeech

/**
 * 小博 saying late results in the end-to-end conversation ([RealtimeVoice.tellLate]): from the first until its announcer
 * is done ([announcing]; it starts within [LATE_SPEECH_START_MILLIS]), and what it said ([OwnSpeech]). The service hears
 * 小博's voice as anyone's and writes it out late, so words like it, heard while it speaks or in the moment after
 * ([cc.noharry.bothy.voice.OWN_SPEECH_TAIL_MILLIS]), are its own coming back, not the speaker's. Its end is noticed
 * when [speaking] is asked, which the microphone's stream does with every frame. Any thread.
 */
internal class XiaoboSpeech(
    private val announcing: () -> Boolean,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val said = OwnSpeech()

    /** Since when 小博 says a late result (elapsed-realtime ms); 0 when it does not. */
    @Volatile
    var since = 0L
        private set

    /** 小博 starts saying [text] now, after whatever it says already. */
    fun says(text: String) {
        val now = clock()
        since = now
        said.saying(text, now)
    }

    /** Whether 小博 says a late result now; once its announcer is done it does not, and its tail starts. */
    fun speaking(): Boolean {
        val start = since
        if (start == 0L) return false
        val now = clock()
        if (announcing() || now - start < LATE_SPEECH_START_MILLIS) return true
        stopped(now)
        return false
    }

    /** It stopped (the speaker cut in, or it finished) at [now]. */
    fun stopped(now: Long = clock()) {
        since = 0L
        said.ended(now)
    }

    /** Whether [words], first heard at [heardAt], are 小博's own: like what it says, now or just before. */
    fun echoes(words: String, heardAt: Long = clock()): Boolean = said.echoes(words, heardAt)
}

/** 小博's speech of a late result starts within this (its TTS request); the model hears silence until it ends. */
internal const val LATE_SPEECH_START_MILLIS = 4_000L
