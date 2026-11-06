package cc.noharry.agent.appserver.model

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.deepseek.DeepSeekAdapter
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.server.RouteTable
import cc.noharry.agent.appserver.server.coreModules
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A model service's extras come only with its adapter: a DeepSeek profile has chat alone in a server without one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ProviderAdapterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val deepSeek = ProviderProfile(
        "ds-adapter", "DeepSeek", ModelProvider.ANTHROPIC, "k", "deepseek-flash", "https://api.deepseek.com/anthropic", ProviderEndpoint.MESSAGES,
    )
    private val config = ProviderConfig.load(context).let { it.copy(profiles = it.profiles + deepSeek) }

    @After
    fun noAdapters() = AppHost.install(providerAdapters = emptyList())

    @Test
    fun withoutItsAdapterADeepSeekProfileHasChatAlone() {
        assertFalse(config.hasWebLookup(context))
        assertNull(appWebSearch(context, config, deepSeek, forceApi = true))
        assertNull(appWebFetchFallback(context, config, deepSeek))
        assertFalse(config.nativeUpdates(context, deepSeek))
        assertFalse(methods().any { it.startsWith("deepseek/") })

        AppHost.install(providerAdapters = listOf(DeepSeekAdapter))
        assertTrue(config.hasWebLookup(context))
        assertNotNull(appWebSearch(context, config, deepSeek, forceApi = true))
        assertTrue(config.nativeUpdates(context, deepSeek))
        assertTrue(methods().containsAll(listOf("deepseek/balance", "deepseek/probe", "deepseek/check")))
    }

    /** The requests the server's modules answer, as a route table records them. */
    private fun methods(): Set<String> {
        val names = mutableSetOf<String>()
        val table = object : RouteTable {
            override fun <P, R> on(method: RequestMethod<P, R>, block: suspend (P) -> R) {
                names += method.name
            }
        }
        coreModules(context).forEach { module -> with(module) { table.install() } }
        return names
    }
}
