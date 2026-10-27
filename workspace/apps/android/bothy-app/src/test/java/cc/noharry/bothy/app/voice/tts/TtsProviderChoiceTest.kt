package cc.noharry.bothy.app.voice.tts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who speaks when nothing was chosen by hand: the first service with a key, else the device's own voice. */
class TtsProviderChoiceTest {
    @Test
    fun withNoKeyAtAllTheDeviceVoiceSpeaksAndIsNotStreamed() {
        val config = TtsConfig.from(emptyMap())

        assertEquals(TtsProvider.SYSTEM, config.provider)
        assertTrue(config.ready)
        assertFalse(config.stream)
    }

    @Test
    fun aDoubaoKeyAloneMakesDoubaoSpeak() {
        val config = TtsConfig.from(mapOf(DOUBAO_TTS_API_KEY to "doubao-key"))

        assertEquals(TtsProvider.DOUBAO, config.provider)
        assertTrue(config.ready)
    }

    @Test
    fun aMimoKeyComesFirst() {
        val config = TtsConfig.from(mapOf(MIMO_TTS_API_KEY to "mimo-key", DOUBAO_TTS_API_KEY to "doubao-key"))

        assertEquals(TtsProvider.MIMO, config.provider)
    }

    @Test
    fun aChoiceByHandStaysEvenBeforeItsKey() {
        val config = TtsConfig.from(mapOf(TTS_PROVIDER to "mimo"))

        assertEquals(TtsProvider.MIMO, config.provider)
        assertFalse(config.ready)
    }

    @Test
    fun theDeviceVoiceCanBeSwitchedOff() {
        val config = TtsConfig.from(mapOf(MIMO_TTS_ENABLED to "false"))

        assertEquals(TtsProvider.SYSTEM, config.provider)
        assertFalse(config.ready)
    }
}
