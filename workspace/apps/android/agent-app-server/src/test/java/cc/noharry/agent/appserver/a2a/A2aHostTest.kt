package cc.noharry.agent.appserver.a2a

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.a2a.server.A2aOutput
import cc.noharry.agent.a2a.server.A2aRequest
import cc.noharry.agent.appserver.runtime.AppHost
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class A2aHostTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun restore() {
        AppHost.install(a2a = A2aHost.NONE)
    }

    @Test
    fun theDefaultHostRunsNoEndpointInterceptsNothingAndNamesTheDevice() = runBlocking {
        assertFalse(A2aHost.NONE.localServer)
        assertEquals(null, A2aHost.NONE.intercept(mapOf("anything" to "true")))
        assertEquals("device", A2aHost.NONE.contextSource)
    }

    @Test
    fun clientModeStaysOffWithoutTheLocalEndpointItTalksTo() {
        A2aClientConsole.setEnabled(context, true)

        assertFalse(A2aClientConsole.isEnabled(context))
    }

    @Test
    fun aHostsOwnCommandEndsTheRequestBeforeAnyTaskRuns() = runBlocking {
        val seen = mutableListOf<Map<String, String>>()
        AppHost.install(a2a = object : A2aHost {
            override suspend fun intercept(metadata: Map<String, String>): A2aOutput.Result? {
                seen += metadata
                return A2aOutput.Result("done by the host")
            }
        })
        val metadata = mapOf("hostCommand" to "wave")

        val outputs = AppA2aRequestHandler(context).execute(A2aRequest("task-1", "ctx-1", "ignored", metadata)).toList()

        assertEquals(listOf(A2aOutput.Result("done by the host")), outputs)
        assertEquals(listOf(metadata), seen)
    }
}
