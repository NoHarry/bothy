package cc.noharry.bothy.server.memory

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.memory.memoryProfileText
import cc.noharry.agent.appserver.protocol.MemoryCategory
import cc.noharry.agent.appserver.protocol.MemorySource
import cc.noharry.agent.appserver.protocol.UserMemory
import cc.noharry.agent.appserver.runtime.ProviderPresets
import cc.noharry.agent.appserver.runtime.RunFacts
import cc.noharry.agent.appserver.server.AppServerConfig
import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.agent.appserver.server.PluginHost
import cc.noharry.agent.appserver.server.PluginSwitches
import cc.noharry.bothy.protocol.CompanionConfig
import cc.noharry.bothy.protocol.CompanionMode
import cc.noharry.bothy.server.companion.companionMessages
import cc.noharry.bothy.server.companion.decideCompanionRoute
import cc.noharry.bothy.server.server.BothyImages
import cc.noharry.bothy.server.server.BothyPersona
import cc.noharry.bothy.server.server.bothyConfig
import cc.noharry.bothy.server.voice.realtime.realtimeInstructions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bothy's side of what 小博 remembers: its plugins name Bothy's places in their tools' words, and the profile reaches its
 * end-to-end voice and its companion. The memory book, its tool and its words are the memory plugin's (`UserMemoryTest`
 * and `MemoryWordsTest` in agent-app-memory); the picture tool's words are the pictures plugin's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh", application = Application::class)
class UserMemoryTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val plugins = PluginHost(app, bothyConfig().plugins.filter { it.id == "memory" } + BothyImages, AllOn())

    @After
    fun stop() {
        plugins.setEnabled("memory", false)
        plugins.setEnabled("images", false)
        AppServerTesting.reset()
    }

    @Test
    fun bothysPluginsNameBothysPlacesInTheirTools() = runBlocking {
        // Bothy's persona, with an image model its build carries, so the picture tool is mounted.
        val imageModel = mapOf("IMAGE_MODEL_BASE_URL" to "https://images.example.com/v1", "IMAGE_MODEL_NAME" to "draw", "IMAGE_MODEL_API_KEY" to "k")
        AppServerTesting.install(AppServerConfig(name = "test", persona = BothyPersona, presets = ProviderPresets(values = imageModel)))
        plugins.startAll()
        val facts = RunFacts(app, userInput = "", child = false, webSearch = false, subagentTypes = emptySet())
        val memory = plugins.additions.runTools.flatMap { it(facts) }.single().listTools().single().description
        assertTrue(memory, memory.contains("记住 / 修改 / 忘掉关于用户本人的长期信息（用户画像，设置 → 记忆）。"))
        val picture = plugins.additions.tools.flatMap { it.provide(app, emptyList()) }.single().listTools().single().description
        assertTrue(picture, picture.contains("按文字描述生成一张图片，并直接显示在屏幕的悬浮窗上。"))
    }

    @Test
    fun theProfileReachesTheEndToEndModelAndTheCompanion() {
        AppServerTesting.install(bothyConfig())
        val memories = listOf(
            UserMemory("m1", MemoryCategory.IDENTITY, "叫我老王", MemorySource.SAID, null, 1_000L, 1_000L),
            UserMemory("m2", MemoryCategory.HOME, "开空调一般 22 度", MemorySource.SAID, null, 1_000L, 1_000L),
        )
        val profile = memoryProfileText(memories)
        assertTrue(profile, profile.contains("- 称呼与身份：叫我老王\n- 设备与家居偏好：开空调一般 22 度"))
        val on = realtimeInstructions("你是小博。", profile, remembers = true)
        assertTrue(on.startsWith("你是小博。\n"))
        assertTrue(on.contains("由小博记下") && on.endsWith("开空调一般 22 度"))
        val off = realtimeInstructions("你是小博。", profile, remembers = false)
        assertFalse(off.contains("由小博记下") || off.contains("老王"))
        val system = companionMessages("你是小博。", emptyList(), "今天好累", profile).first().second
        assertTrue(system.endsWith(profile))
        val companion = CompanionConfig(enabled = true, mode = CompanionMode.ROUTE)
        assertFalse(decideCompanionRoute(companion, "记住我喜欢周杰伦", 0.95, null, false).toCompanion)
        assertFalse(decideCompanionRoute(companion, "以后叫我老王吧", 0.95, null, false).toCompanion)
        assertEquals("", memoryProfileText(emptyList()))
    }

    private class AllOn : PluginSwitches {
        private val off = mutableSetOf<String>()

        override fun isOn(id: String): Boolean = id !in off

        override fun set(id: String, on: Boolean) {
            if (on) off -= id else off += id
        }
    }
}
