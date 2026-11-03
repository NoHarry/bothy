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
}
