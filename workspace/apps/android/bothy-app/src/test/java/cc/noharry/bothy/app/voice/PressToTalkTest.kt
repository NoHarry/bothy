package cc.noharry.bothy.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class PressToTalkTest {
    @Test
    fun releaseSendsAndASlideOrATapCancels() {
        assertEquals(PressOutcome.SEND, pressOutcome(slidUp = false, heldMillis = 400))
        assertEquals(PressOutcome.CANCEL, pressOutcome(slidUp = true, heldMillis = 800))
        assertEquals(PressOutcome.CANCEL, pressOutcome(slidUp = false, heldMillis = 80))
    }

    @Test
    fun aPressStopsAWakeOrFollowUpThatIsAlreadyListening() {
        assertEquals(HoldDown.STOP_OTHER, holdDown(listening = true, holding = false))
        assertEquals(HoldDown.ALREADY, holdDown(listening = true, holding = true))
        assertEquals(HoldDown.START, holdDown(listening = false, holding = false))
    }
}
