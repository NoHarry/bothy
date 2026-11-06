package cc.noharry.bothy.server.a2a

import cc.noharry.agent.a2a.server.A2aOutput
import cc.noharry.agent.appserver.protocol.OverlayCommand
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BothyA2aTest {
    private val cards = mutableListOf<OverlayCommand>()
    private var refusal: String? = null
    private val host = BothyA2aHost(card = { command -> cards += command; refusal })

    @Test
    fun bothyRunsTheLocalEndpointAndNamesTheCarAsItsSource() {
        assertTrue(BothyA2a.localServer)
        assertEquals("car", BothyA2a.contextSource)
        assertEquals(listOf("云端助手", "个人助手"), BothyA2a.nicknames)
        assertEquals("their cloud / personal assistant", BothyA2a.nicknamesMeaning)
    }

    @Test
    fun cardCommandsAreReadFromMessageMetadata() {
        assertEquals(OverlayCommand.SHOW, overlayCommand(mapOf(CAR_OVERLAY_METADATA to " Show ")))
        assertEquals(OverlayCommand.HIDE, overlayCommand(mapOf(CAR_OVERLAY_METADATA to "hide")))
        assertNull(overlayCommand(mapOf(CAR_OVERLAY_METADATA to "toggle")))
        assertNull(overlayCommand(emptyMap()))
        assertTrue(isOverlayControlOnly(mapOf(CAR_OVERLAY_ONLY_METADATA to "true")))
        assertFalse(isOverlayControlOnly(mapOf(CAR_OVERLAY_ONLY_METADATA to "yes")))
        assertFalse(isOverlayControlOnly(emptyMap()))
    }

    @Test
    fun aCardCommandAloneEndsTheRequestAndOneBesideATaskLetsItRun() = runBlocking {
        // Without a command a request runs, and the card is left alone.
        assertNull(host.intercept(emptyMap()))
        assertEquals(emptyList<OverlayCommand>(), cards)
        // A command beside a task is applied, and the task runs.
        assertNull(host.intercept(mapOf(CAR_OVERLAY_METADATA to "hide")))
        assertEquals(listOf(OverlayCommand.HIDE), cards)
        // Control-only: applied and answered; without a command refused, never run.
        assertEquals(
            A2aOutput.Result("悬浮窗已弹出", A2aOutput.State.COMPLETED),
            host.intercept(mapOf(CAR_OVERLAY_ONLY_METADATA to "true", CAR_OVERLAY_METADATA to "show")),
        )
        assertEquals(A2aOutput.State.REJECTED, host.intercept(mapOf(CAR_OVERLAY_ONLY_METADATA to "true"))?.state)
        // A card that could not be shown says why.
        refusal = "no assistant on screen"
        assertEquals(
            A2aOutput.Result("no assistant on screen", A2aOutput.State.FAILED),
            host.intercept(mapOf(CAR_OVERLAY_ONLY_METADATA to "true", CAR_OVERLAY_METADATA to "show")),
        )
    }

    @Test
    fun anotherClientsTaskBringsTheCardUpUnlessItSaidOtherwise() = runBlocking {
        host.inboundStarted(emptyMap())
        assertEquals(listOf(OverlayCommand.SHOW), cards)
        // An explicit command was applied before the task: a hide wins, and a show is not repeated.
        host.inboundStarted(mapOf(CAR_OVERLAY_METADATA to "hide"))
        host.inboundStarted(mapOf(CAR_OVERLAY_METADATA to "show"))
        assertEquals(listOf(OverlayCommand.SHOW), cards)
    }
}
