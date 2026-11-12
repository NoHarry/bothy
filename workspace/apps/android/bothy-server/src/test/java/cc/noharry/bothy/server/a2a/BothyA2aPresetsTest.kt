package cc.noharry.bothy.server.a2a

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.a2a.loadA2aProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The A2A presets Bothy ships (`a2a-client-profiles.json` in its assets): a local endpoint over each packaged client,
 * off until the person turns one on, listed before the person's own. How presets and the person's profiles load, save
 * and switch is the A2A plugin's (`A2aModuleConfigTest` in agent-app-a2a).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BothyA2aPresetsTest {
    @Test
    fun bothyShipsTwoLocalPresetsOffAtFirst() {
        val profiles = loadA2aProfiles(ApplicationProvider.getApplicationContext())
        assertEquals(listOf("direct-local", "official-local"), profiles.map { it.id })
        assertEquals(listOf("direct-jsonrpc", "official-java-sdk"), profiles.map { it.moduleId })
        assertTrue(profiles.none { it.enabled || it.userDefined })
        assertTrue(profiles.all { it.allowLoopbackHttpForDebug && it.endpoint.host == "127.0.0.1" })
    }
}
