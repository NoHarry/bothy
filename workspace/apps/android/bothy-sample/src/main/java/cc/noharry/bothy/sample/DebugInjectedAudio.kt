package cc.noharry.bothy.sample

import cc.noharry.bothy.voice.VoiceAudioSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * adb (`voice_pcm`): recorded words mixed into what a Jev session's microphone hears, from the next frame on, so a
 * spoken turn can be tried on the bench with nobody speaking, as `realtime_pcm` does for 端到端. The room stays under
 * the words: the reply's echo, noise and nearby talk reach the recognizer as they would.
 */
internal class DebugInjectedAudio(private val source: VoiceAudioSource) : VoiceAudioSource {
    private val queued = ConcurrentLinkedQueue<ShortArray>()
    private var words: ShortArray? = null
    private var offset = 0

    /** 16 kHz PCM16 little-endian, heard after anything injected before it. */
    fun inject(pcm16le: ByteArray) {
        val samples = ShortArray(pcm16le.size / 2)
        ByteBuffer.wrap(pcm16le).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        queued += samples
    }

    override fun frames(): Flow<ShortArray> = source.frames().map(::mix)

    /** The frame with the next injected samples added, clipped at full scale; the frame itself when none wait. */
    private fun mix(frame: ShortArray): ShortArray {
        var mixed: ShortArray? = null
        var i = 0
        while (i < frame.size) {
            val next = words?.takeIf { offset < it.size } ?: queued.poll()?.also { words = it; offset = 0 } ?: break
            val out = mixed ?: frame.copyOf().also { mixed = it }
            while (i < frame.size && offset < next.size) {
                out[i] = (out[i] + next[offset++]).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                i++
            }
        }
        return mixed ?: frame
    }
}
