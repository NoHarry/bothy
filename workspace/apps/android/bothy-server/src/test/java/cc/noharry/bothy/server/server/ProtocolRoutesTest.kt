package cc.noharry.bothy.server.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.MethodKind
import cc.noharry.agent.appserver.protocol.ServerInfo
import cc.noharry.agent.appserver.server.CONNECTION_METHODS
import cc.noharry.agent.appserver.server.ServerHub
import cc.noharry.agent.appserver.server.kind
import cc.noharry.bothy.protocol.ProtocolMethods
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        val hub = ServerHub(unused(), unused(), Dispatchers.Unconfined, CoroutineScope(Job()), ServerInfo("test"), serverModules(app))

        val answered = hub.routes.methodNames + CONNECTION_METHODS
        val declared = ProtocolMethods.all.filter { it.kind == MethodKind.CLIENT_REQUEST }.map { it.name }

        assertEquals(declared.sorted(), answered.sorted())
    }

    /** Routes are only listed, never called, so the backends do nothing. */
    private inline fun <reified T> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> error("${method.name} called") } as T
}
