@file:OptIn(cc.noharry.agent.appserver.InternalAppServerApi::class)

package cc.noharry.agent.appserver.image

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.GeneratedImageResult
import cc.noharry.agent.appserver.protocol.GeneratedPicture
import cc.noharry.agent.appserver.protocol.ImageModelConfig
import cc.noharry.agent.appserver.protocol.ImageModelRequests
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcNotification
import cc.noharry.agent.appserver.protocol.view.IMAGE_GENERATE_TOOL
import cc.noharry.agent.appserver.server.PluginHost
import cc.noharry.agent.appserver.server.PluginSwitches
import cc.noharry.agent.appserver.server.PublishedState
import cc.noharry.agent.spi.ToolCallContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The image model and its pictures come only with their plugin: its requests, its tool, the board, the receiver. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ImagesPluginTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val host = PluginHost(app, listOf(ImagesPlugin(shownOn = "in the conversation")), AllOn())

    @After
    fun stop() = host.setEnabled("images", false).let { }

    @Test
    fun runningItBringsTheRequestsAndTheToolOnceTheModelIsSetUp() {
        host.startAll()
        val additions = host.additions
        val requests = listOf(ImageModelRequests.READ, ImageModelRequests.SAVE, ImageModelRequests.MODELS, ImageModelRequests.TRY)
        assertEquals(requests.map { it.name }.toSet(), additions.routes.keys)
        assertTrue(IMAGE_TOOL_SERVER_ID in additions.ownToolServers)
        fun tools() = additions.tools.flatMap { it.provide(app, emptyList()) }.map { it.providerId }
        assertTrue(tools().isEmpty())
        assertNull(saveImageModelConfig(app, READY, replacementKey = "sk-test"))
        assertEquals(listOf(IMAGE_TOOL_SERVER_ID), tools())
    }

    @Test
    fun turnedOffItEmptiesTheBoardAndARunStillDrawingLeavesNothingOnIt() = runBlocking {
        host.startAll()
        val state = RecordingState()
        host.publishInto(state)
        assertNull(saveImageModelConfig(app, READY, replacementKey = "sk-test"))
        val drawing = host.additions.tools.single().provide(app, emptyList()).single()
        val reference = "generated-images/YTJh/img-a-bcde.jpg"
        // The provider the plugin built, with the generation stood in for.
        val tool = ImageToolProvider(null, { ImagesPlugin.running }) { _, _ -> ImageResult.Success(GeneratedImageResult(reference, 1, 1, 1, 1, null)) }
        tool.callTool(IMAGE_GENERATE_TOOL, """{"prompt":"cat"}""", ToolCallContext("s", "r", "task-1"))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(GeneratedPicture(reference, "cat"), state.current.offerPictures["task-1"])
        assertEquals(IMAGE_TOOL_SERVER_ID, drawing.providerId)

        host.setEnabled("images", false)
        assertTrue(state.current.offerPictures.isEmpty())
        assertTrue(PictureBoard.all.value.isEmpty())
        with(host.additions) { assertTrue(routes.isEmpty() && tools.isEmpty() && ownToolServers.isEmpty()) }
        tool.callTool(IMAGE_GENERATE_TOOL, """{"prompt":"dog"}""", ToolCallContext("s", "r", "task-2"))
        assertNull(PictureBoard.forTask("task-2"))
    }

    @Test
    fun theDebugReceiverDoesNothingWhileItIsOff() {
        val intent = Intent(app.packageName + IMAGE_MODEL_ACTION_SUFFIX).putExtra("model", "changed")
        ImageModelBroadcastReceiver().onReceive(app, intent)
        assertFalse(loadImageModelConfig(app).model == "changed")
    }

    private class RecordingState : PublishedState {
        override var current = ServerState()
        override fun update(change: ServerState.() -> ServerState) { current = current.change() }
        override fun <T> follow(flow: Flow<T>, into: ServerState.(T) -> ServerState) = error("a plugin follows through its own scope")
        override fun broadcast(notification: JsonRpcNotification) = Unit
    }

    private class AllOn : PluginSwitches {
        private val off = mutableSetOf<String>()

        override fun isOn(id: String): Boolean = id !in off

        override fun set(id: String, on: Boolean) {
            if (on) off -= id else off += id
        }
    }

    private companion object {
        val READY = ImageModelConfig(enabled = true, baseUrl = "https://api.example.com/v1", model = "img-1")
    }
}
