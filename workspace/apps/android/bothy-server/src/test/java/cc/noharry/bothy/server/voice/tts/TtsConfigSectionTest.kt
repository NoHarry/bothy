package cc.noharry.bothy.server.voice.tts

import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.runtime.ProviderPresets
import cc.noharry.agent.appserver.server.AppServerConfig
import cc.noharry.agent.appserver.server.AppServerTesting
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The profiles file's `tts` object, Bothy's own section ([BOTHY_CONFIG_SECTIONS]), as the server reads a host's
 * sections into its values. How a host's section is read is the server's (`ProviderConfigTest` in agent-app-server).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TtsConfigSectionTest {
    @After
    fun bareHost() = AppServerTesting.reset()

    @Test
    fun parsesTtsJsonConfig() {
        val file = """
                {
                  "tts": {
                    "enabled": true,
                    "apiKey": "key",
                    "base_url": "https://tts.example.com/v1",
                    "model": "mimo-v2.5-tts",
                    "voice": "Chloe",
                    "format": "wav",
                    "style": "calm",
                    "optimize_text_preview": true,
                    "stream": false
                  }
                }
            """.trimIndent()
        AppServerTesting.install(AppServerConfig(name = "test", presets = ProviderPresets(configText = file, sections = BOTHY_CONFIG_SECTIONS)))

        val config = TtsConfig.from(ProviderConfig.load(ApplicationProvider.getApplicationContext()).values)

        assertTrue(config.ready)
        assertEquals("key", config.apiKey)
        assertEquals("https://tts.example.com/v1", config.baseUrl)
        assertEquals("calm", config.style)
        assertTrue(config.optimizeTextPreview)
        assertFalse(config.stream)
    }
}
