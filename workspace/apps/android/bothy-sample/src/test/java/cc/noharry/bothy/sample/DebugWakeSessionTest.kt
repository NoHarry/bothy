package cc.noharry.bothy.sample

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugWakeSessionTest {
    @Test fun onlyAWakeAfterTheQuietWindowOnASessionWithTurnsStartsANewOne() {
        val now = 1_800_000_000_000L
        fun session(turns: Int, quietMillis: Long) = DebugSessionSummary("s", "t", now - quietMillis, turns, "")

        assertFalse(debugWakeStartsNewSession(null, now))
        assertFalse(debugWakeStartsNewSession(session(turns = 0, quietMillis = 3_600_000), now))
        assertFalse(debugWakeStartsNewSession(session(turns = 3, quietMillis = DEBUG_WAKE_NEW_SESSION_IDLE_MILLIS - 1), now))
        assertTrue(debugWakeStartsNewSession(session(turns = 3, quietMillis = DEBUG_WAKE_NEW_SESSION_IDLE_MILLIS), now))
    }
}
