package cc.noharry.bothy.sample

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import cc.noharry.agent.voice.VoiceHandOverMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** The last voice hand-over that deserves a notice; [fellBack] when an append found the task already ended. */
internal data class DebugVoiceHandOverNotice(
    val mode: VoiceHandOverMode,
    val text: String,
    val atMillis: Long,
    val fellBack: Boolean = false,
    /** What 小博 said back to an append, once spoken. */
    val ack: String? = null,
)

/**
 * Lets the speaker feel how a voice turn reached a working agent without looking: two light taps for an append, a
 * falling pair when the task is replaced, and a short notice above the composer. A new turn gets no tone; the agent's
 * reply is the feedback. The chimes are made here, soft and bell-like (system tones sound like a phone); they are not
 * speech and the echo canceller removes them. Main thread.
 */
internal object DebugVoiceFeedback {
    private val main = Handler(Looper.getMainLooper())
    private val mutableNotice = MutableStateFlow<DebugVoiceHandOverNotice?>(null)
    val notice: StateFlow<DebugVoiceHandOverNotice?> = mutableNotice

    fun handedOver(mode: VoiceHandOverMode, text: String, chime: Boolean) {
        if (mode == VoiceHandOverMode.NEW_TURN) return
        mutableNotice.value = DebugVoiceHandOverNotice(mode, text, SystemClock.elapsedRealtime())
        if (chime) play(if (mode == VoiceHandOverMode.APPEND) appendChime else replaceChime)
    }

    /** The overlay woke and is listening: a soft rising pair, so the speaker knows without looking. */
    fun woke() = play(wakeChime)

    /** Dismissed by voice: the wake chime turned round, falling. */
    fun dismissed() = play(dismissChime)

    /**
     * A plan came due ([DebugPlanNotice]): the muffled rising fourth of [PLAN_CHIME_NOTES], unlike the bell-like voice
     * chimes; [tried] instead when trying another ([DebugPlanNoticeReceiver]).
     */
    fun planDue(tried: DebugChimeTry? = null) =
        play(tried?.let { softChimeSamples(it.notes ?: PLAN_CHIME_NOTES, it.timbre ?: PLAN_CHIME_TIMBRE, peak = it.peak) } ?: planChime)

    private fun play(samples: ShortArray) {
        val create = { attributes: AudioAttributes ->
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(CHIME_SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
        }
        // System sounds, not speech.
        val track = runCatching {
            create(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
        }.getOrNull() ?: return
        runCatching {
            track.write(samples, 0, samples.size)
            track.play()
        }
        main.postDelayed({ runCatching { track.release() } }, samples.size * 1000L / CHIME_SAMPLE_RATE + RELEASE_GRACE_MILLIS)
    }

    private val wakeChime: ShortArray by lazy { chimeSamples(WAKE_CHIME_NOTES) }
    private val appendChime: ShortArray by lazy { chimeSamples(APPEND_CHIME_NOTES) }
    private val replaceChime: ShortArray by lazy { chimeSamples(REPLACE_CHIME_NOTES) }
    private val dismissChime: ShortArray by lazy { chimeSamples(DISMISS_CHIME_NOTES) }
    private val planChime: ShortArray by lazy { softChimeSamples(PLAN_CHIME_NOTES) }

    /** The acknowledgement spoken for the current append. */
    fun acknowledged(ack: String) {
        mutableNotice.value = mutableNotice.value?.copy(ack = ack)
    }

    /** The append arrived after the task ended and became a new turn. */
    fun appendBecameNewTurn(text: String) {
        mutableNotice.value = DebugVoiceHandOverNotice(VoiceHandOverMode.NEW_TURN, text, SystemClock.elapsedRealtime(), fellBack = true)
    }
}

/**
 * A bell-like chime: each note a sine with a soft octave overtone, a 6 ms attack and an exponential fade, the notes
 * overlapping slightly. [notes] are (frequency Hz, start s, length s); the peak stays at [CHIME_PEAK] of full scale.
 */
internal fun chimeSamples(notes: List<Triple<Double, Double, Double>>, sampleRate: Int = CHIME_SAMPLE_RATE): ShortArray {
    val total = notes.maxOf { (_, start, length) -> start + length }
    val out = DoubleArray((total * sampleRate).toInt())
    notes.forEach { (frequency, start, length) ->
        val first = (start * sampleRate).toInt()
        val count = (length * sampleRate).toInt()
        for (i in 0 until count) {
            val t = i.toDouble() / sampleRate
            val envelope = min(1.0, t / CHIME_ATTACK_SECONDS) * exp(-t * CHIME_DECAY_PER_SECOND) * min(1.0, (length - t) / CHIME_TAIL_SECONDS)
            val wave = sin(2 * PI * frequency * t) + CHIME_OVERTONE * sin(4 * PI * frequency * t)
            if (first + i < out.size) out[first + i] += wave * envelope
        }
    }
    val peak = out.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
    return ShortArray(out.size) { (out[it] / peak * CHIME_PEAK * Short.MAX_VALUE).toInt().toShort() }
}

/** E6 then A6, a fourth up: reads as "here". */
private val WAKE_CHIME_NOTES = listOf(Triple(1318.51, 0.0, 0.16), Triple(1760.0, 0.09, 0.32))
/** C6 twice, quick and light: "got it, added". */
private val APPEND_CHIME_NOTES = listOf(Triple(1046.5, 0.0, 0.12), Triple(1046.5, 0.11, 0.2))
/** A6 down to E6: "bye", the wake chime reversed. */
private val DISMISS_CHIME_NOTES = listOf(Triple(1760.0, 0.0, 0.14), Triple(1318.51, 0.09, 0.32))
/** A5 down to E5: "changed course". */
private val REPLACE_CHIME_NOTES = listOf(Triple(880.0, 0.0, 0.16), Triple(659.25, 0.1, 0.3))
internal const val CHIME_SAMPLE_RATE = 44_100
private const val CHIME_PEAK = 0.32
private const val CHIME_OVERTONE = 0.18
private const val CHIME_ATTACK_SECONDS = 0.006
private const val CHIME_DECAY_PER_SECOND = 9.0
private const val CHIME_TAIL_SECONDS = 0.03

private const val RELEASE_GRACE_MILLIS = 150L
