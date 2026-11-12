package cc.noharry.bothy.server.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.MethodKind
import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.bothy.protocol.ProtocolMethods
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The schema's client requests (ProtocolMethods) are exactly what the server answers, so neither drifts. */
@RunWith(RobolectricTestRunner::class)
class ProtocolRoutesTest {
    @Test
    fun theServerAnswersEveryClientRequestOfTheProtocolAndNoOther() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        // Bothy's server has every built-in feature and all its plugins (its local A2A endpoint among them), whose
        // requests are part of its protocol.
        AppServerTesting.install(bothyConfig())
        val answered = try {
            AppServerTesting.requestsAnswered(serverModules(app))
        } finally {
            AppServerTesting.reset()
        }

        val declared = ProtocolMethods.all.filter { it.kind == MethodKind.CLIENT_REQUEST }.map { it.name }

        assertEquals(declared.sorted(), answered.sorted())
    }
}
