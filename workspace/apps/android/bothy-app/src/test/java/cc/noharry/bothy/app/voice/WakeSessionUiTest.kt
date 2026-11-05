package cc.noharry.bothy.app.voice
import cc.noharry.bothy.app.session.IDLE_MINUTE_STEPS
import cc.noharry.bothy.app.session.SESSION_COUNT_STEPS
import cc.noharry.bothy.app.session.stepped
import org.junit.Assert.assertEquals
import org.junit.Test

/** The session settings' steppers for the quiet window and the session limit. */
class WakeSessionUiTest {
    @Test fun steppersMoveToTheNextStepAndStopAtTheEnds() {
        assertEquals(10, stepped(5, IDLE_MINUTE_STEPS, up = true))
        assertEquals(3, stepped(5, IDLE_MINUTE_STEPS, up = false))
        assertEquals(10, stepped(7, IDLE_MINUTE_STEPS, up = true))
        assertEquals(5, stepped(7, IDLE_MINUTE_STEPS, up = false))
        assertEquals(240, stepped(240, IDLE_MINUTE_STEPS, up = true))
        assertEquals(1, stepped(1, SESSION_COUNT_STEPS, up = false))
        assertEquals(30, stepped(20, SESSION_COUNT_STEPS, up = true))
    }
}
