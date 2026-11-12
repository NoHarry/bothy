package cc.noharry.bothy.server.a2a

import cc.noharry.agent.appserver.protocol.A2aClientItem
import cc.noharry.agent.appserver.protocol.A2aClientState
import cc.noharry.agent.appserver.protocol.A2aInputSource
import cc.noharry.agent.appserver.protocol.A2aTaskStatus
import cc.noharry.agent.appserver.protocol.GeneratedPicture
import cc.noharry.bothy.protocol.view.overlayHud
import cc.noharry.bothy.server.TextRoute
import cc.noharry.bothy.server.parseA2aClientSwitch
import cc.noharry.bothy.server.textRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Bothy's side of client mode: where a debug text goes, and the overlay's card for a task. Client mode itself is the A2A
 * plugin's (`A2aClientSessionTest` in agent-app-a2a); the board of pictures the image tool draws is the pictures
 * plugin's (`PictureBoardTest` in agent-app-images).
 */
class A2aClientSessionTest {
    @Test
    fun clientModesCardShowsThePictureDrawnForItsTask() {
        val reference = "generated-images/YTJh/img-mv0a1b2c-qrst.jpg"
        val state = A2aClientState(
            items = listOf(
                A2aClientItem.User(1, "画一只猫", A2aInputSource.OVERLAY, A2aClientItem.User.Kind.MESSAGE),
                A2aClientItem.Display(2, "task-7", "画好了"),
                A2aClientItem.Terminal(3, "task-7", A2aTaskStatus.COMPLETED, "画好了", 20_000),
            ),
        )
        // The server publishes what the image tool drew by task; the card reads it from the state.
        val pictures = mapOf("task-7" to GeneratedPicture(reference, "猫"), "task-8" to GeneratedPicture(null, "狗"))
        assertEquals(GeneratedPicture(reference, "猫"), state.overlayHud(pictures = pictures).picture)
        assertNull(state.overlayHud(pictures = pictures - "task-7").picture)
    }

    @Test
    fun adbRouteHonoursExplicitRouteThenClientMode() {
        assertEquals(TextRoute.LOCAL, textRoute(null, clientMode = false))
        assertEquals(TextRoute.A2A_CLIENT, textRoute(null, clientMode = true))
        assertEquals(TextRoute.A2A_CLIENT, textRoute("A2A", clientMode = false))
        assertEquals(TextRoute.LOCAL, textRoute("local", clientMode = true))
        assertEquals(TextRoute.LOCAL, textRoute("bogus", clientMode = false))
        assertEquals(true, parseA2aClientSwitch("on"))
        assertEquals(false, parseA2aClientSwitch(" OFF "))
        assertNull(parseA2aClientSwitch("maybe"))
    }
}
