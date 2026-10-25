package cc.noharry.bothy.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who speaks when nothing was chosen by hand: the first service with a key, else the device's own voice. */
class DebugTtsProviderChoiceTest {
    @Test
    fun withNoKeyAtAllTheDeviceVoiceSpeaksAndIsNotStreamed() {
        val config = DebugTtsConfig.from(emptyMap())

        assertEquals(DebugTtsProvider.SYSTEM, config.provider)
        assertTrue(config.ready)
        assertFalse(config.stream)
    }

    @Test
    fun aDoubaoKeyAloneMakesDoubaoSpeak() {
        val config = DebugTtsConfig.from(mapOf(DOUBAO_TTS_API_KEY to "doubao-key"))

        assertEquals(DebugTtsProvider.DOUBAO, config.provider)
        assertTrue(config.ready)
    }

    @Test
    fun aMimoKeyComesFirst() {
        val config = DebugTtsConfig.from(mapOf(MIMO_TTS_API_KEY to "mimo-key", DOUBAO_TTS_API_KEY to "doubao-key"))

        assertEquals(DebugTtsProvider.MIMO, config.provider)
    }

    @Test
    fun aChoiceByHandStaysEvenBeforeItsKey() {
        val config = DebugTtsConfig.from(mapOf(TTS_PROVIDER to "mimo"))

        assertEquals(DebugTtsProvider.MIMO, config.provider)
        assertFalse(config.ready)
    }

    @Test
    fun theDeviceVoiceCanBeSwitchedOff() {
        val config = DebugTtsConfig.from(mapOf(MIMO_TTS_ENABLED to "false"))

        assertEquals(DebugTtsProvider.SYSTEM, config.provider)
        assertFalse(config.ready)
    }
}
