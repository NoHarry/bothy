package cc.noharry.bothy.sample

import cc.noharry.agent.voice.VoiceAudioSource
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DebugInjectedAudioTest {
    @Test
    fun injectedWordsAreAddedOverTheMicrophoneAcrossFramesThenItPassesAlone() = runBlocking {
        val frames = List(3) { ShortArray(4) { 10 } }
        val heard = DebugInjectedAudio(VoiceAudioSource { flowOf(*frames.toTypedArray()) })
        // Six samples, little-endian: 100 five times, then one that clips at full scale.
        heard.inject(shortsLe(100, 100, 100, 100, 100, Short.MAX_VALUE.toInt()))

        val out = heard.frames().toList()

        assertArrayEquals(shortArrayOf(110, 110, 110, 110), out[0])
        assertArrayEquals(shortArrayOf(110, Short.MAX_VALUE, 10, 10), out[1])
        assertSame(frames[2], out[2])
        // The microphone's own frames are never written to: the wake word hears the same recording.
        assertArrayEquals(ShortArray(4) { 10 }, frames[0])
    }

    private fun shortsLe(vararg values: Int) = ByteArray(values.size * 2).also { bytes ->
        values.forEachIndexed { i, v -> bytes[2 * i] = v.toByte(); bytes[2 * i + 1] = (v shr 8).toByte() }
    }
}
