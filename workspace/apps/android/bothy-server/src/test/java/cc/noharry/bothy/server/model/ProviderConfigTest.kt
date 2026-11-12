package cc.noharry.bothy.server.model
import cc.noharry.agent.appserver.image.IMAGE_MODEL_EMBEDDED_KEYS
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.runtime.ProviderPresets
import cc.noharry.agent.appserver.server.AppServerConfig
import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.bothy.server.BuildConfig
import cc.noharry.bothy.server.mcp.AGENT_RELAY_URL_KEY
import cc.noharry.bothy.server.server.bothyPresets
import cc.noharry.bothy.server.voice.JEV_EMBEDDED_KEYS
import cc.noharry.bothy.server.voice.VOICE_EMBEDDED_KEYS
import cc.noharry.bothy.server.voice.tts.TTS_CONFIG_KEYS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The model services Bothy's build carries (model-provider-profiles.json and local.properties, its BuildConfig) as the
 * server reads them. How profiles are parsed and picked is the server's (`ProviderConfigTest` in agent-app-server).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProviderConfigTest {
    @After
    fun bareHost() = AppServerTesting.reset()

    @Test
    fun buildConfigProviderConfigDeclaresMultipleProfiles() {
        // The profiles file alone, as the server reads it: what local.properties adds is a developer's own.
        val file = Json.parseToJsonElement(BuildConfig.ENV_MODEL_PROVIDER_CONFIG).jsonObject
        val listed = file.getValue("profiles").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        val presets = bothyPresets()
        AppServerTesting.install(AppServerConfig(name = "test", presets = ProviderPresets(configText = presets.configText, sections = presets.sections)))
        val config = ProviderConfig.load(ApplicationProvider.getApplicationContext())
        val byId = config.profiles.associateBy { it.id }

        assertEquals(listed.toSet(), byId.keys)
        // The file's default answers until the person picks another.
        assertEquals(file.getValue("defaultProfile").jsonPrimitive.content, config.activeProfileId)
        assertTrue(config.profiles.all { it.model.isNotBlank() && it.baseUrl.isNotBlank() })
        assertTrue(config.profiles.all { it.apiKey.isBlank() })
        assertEquals("openai-chat-options", byId.getValue("deepseek-api").searchCapability)
        assertEquals("anthropic-server-tool", byId.getValue("deepseek-anthropic").searchCapability)
        assertEquals("none", byId.getValue("no-hosted-search").searchCapability)
    }

    @Test
    fun buildConfigLocalOverridesOnlyDeclareProviderApiKeys() {
        // KEY=value lines, as the build writes them (see this module's build.gradle.kts).
        val values = BuildConfig.ENV_MODEL_PROVIDER_LOCAL_OVERRIDES.lines().filter { it.isNotBlank() }.associate { it.substringBefore("=").trim() to it.substringAfter("=") }
        val allowedLegacyKeys = setOf("OPENAI_API_KEY", "ANTHROPIC_API_KEY")

        assertTrue(values.keys.all { key ->
            key in allowedLegacyKeys ||
                key in TTS_CONFIG_KEYS ||
                key in IMAGE_MODEL_EMBEDDED_KEYS ||
                key in VOICE_EMBEDDED_KEYS ||
                key in JEV_EMBEDDED_KEYS ||
                key == AGENT_RELAY_URL_KEY ||
                key == "AGENT_RUNTIME_DEFAULT_PROFILE" ||
                (key.startsWith("AGENT_RUNTIME_PROVIDER_PROFILE_") &&
                    listOf("LABEL", "PROVIDER", "ENDPOINT", "BASE_URL", "MODEL", "API_KEY", "STREAM", "SEARCH_CAPABILITY").any { key.endsWith("_$it") })
        })
    }
}
