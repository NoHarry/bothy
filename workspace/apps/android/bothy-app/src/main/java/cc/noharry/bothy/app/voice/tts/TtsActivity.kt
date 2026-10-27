package cc.noharry.bothy.app.voice.tts
import android.media.AudioTrack
import cc.noharry.bothy.voice.MicActivity
import cc.noharry.bothy.app.runtime.append
import cc.noharry.bothy.app.voice.SpeechFocus
import java.util.IdentityHashMap
import kotlin.math.log10
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether any speech is queued, being synthesized or playing, across every announcer and player in the process, and
 * what is being said. Half duplex stays deaf while this is true; full duplex compares what it hears against
 * [speech] to drop the reply's echo, and [interrupt] stops every announcer when someone cuts in.
 */
internal object TtsActivity {
    /** Each holder with how to stop it; players register without a stopper (their announcer stops them). */
    private val holders = IdentityHashMap<Any, (() -> Unit)?>()
    private val mutableActive = MutableStateFlow(false)
    val active: StateFlow<Boolean> = mutableActive
    private val mutableSpeech = MutableStateFlow("")
    val speech: StateFlow<String> = mutableSpeech

    fun begin(holder: Any, stop: (() -> Unit)? = null) = synchronized(holders) {
        holders[holder] = stop
        mutableActive.value = true
        SpeechFocus.began(holder)
    }

    fun end(holder: Any) = synchronized(holders) {
        holders.remove(holder)
        mutableActive.value = holders.isNotEmpty()
        if (holders.isEmpty()) mutableSpeech.value = ""
        SpeechFocus.ended(holder)
    }

    /** The text an announcer starts synthesizing; kept until all speech ends. */
    fun speaking(text: String) {
        mutableSpeech.value = text
    }

    /** Stops all speech now (a barge-in); queued announcements are dropped too. Main thread. */
    fun interrupt() {
        val stoppers = synchronized(holders) { holders.values.filterNotNull() }
        stoppers.forEach { stop -> runCatching(stop) }
    }
}

/**
 * How loud the streamed reply is right where the speaker is, for the overlay's aura: each chunk is measured in 20 ms
 * blocks as it is written, and read back at the track's playback head, so the light follows what is heard rather
 * than what was buffered ahead. Null when no stream plays (the WAV path is not measured). Any thread.
 */
internal object TtsLevels {
    private var track: AudioTrack? = null
    private var blockFrames = 0
    private val blocks = ArrayList<Float>()
    private var carry = ShortArray(0)

    @Synchronized
    fun start(track: AudioTrack, sampleRate: Int) {
        this.track = track
        blockFrames = sampleRate * BLOCK_MILLIS / 1000
        blocks.clear()
        carry = ShortArray(0)
    }

    /** One chunk of little-endian PCM16 mono, about to be written to [track]. */
    @Synchronized
    fun append(track: AudioTrack, pcm: ByteArray) {
        if (track !== this.track || blockFrames <= 0) return
        val samples = ShortArray(carry.size + pcm.size / 2)
        carry.copyInto(samples)
        for (i in 0 until pcm.size / 2) {
            samples[carry.size + i] = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort()
        }
        val whole = samples.size / blockFrames
        for (block in 0 until whole) {
            blocks += levelOf(samples.copyOfRange(block * blockFrames, (block + 1) * blockFrames))
        }
        carry = samples.copyOfRange(whole * blockFrames, samples.size)
    }

    @Synchronized
    fun stop(track: AudioTrack) {
        if (track !== this.track) return
        this.track = null
        blocks.clear()
        carry = ShortArray(0)
    }

    /** 0..1 at the playback head, or null when nothing streams. */
    @Synchronized
    fun current(): Float? {
        val playing = track ?: return null
        val head = runCatching { playing.playbackHeadPosition }.getOrNull() ?: return null
        return blocks.getOrNull(head / blockFrames.coerceAtLeast(1)) ?: 0f
    }

    private const val BLOCK_MILLIS = 20
}

/** Loudness on the voice level meter's scale: -60 dBFS and below is 0, full scale is 1. */
internal fun levelOf(samples: ShortArray): Float {
    val db = 20 * log10(MicActivity.rms(samples).coerceAtLeast(1e-6))
    return ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
}
