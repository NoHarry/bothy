package cc.noharry.bothy.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugWakeSessionTest {
    private val now = 1_800_000_000_000L
    private fun session(turns: Int, quietMillis: Long, id: String = "s") = DebugSessionSummary(id, "t", now - quietMillis, turns, "")

    @Test fun onlyAWakeAfterTheQuietWindowOnASessionWithTurnsStartsANewOne() {
        val policy = DebugSessionPolicy(idleMinutes = 5)

        assertFalse(debugWakeStartsNewSession(null, now, policy = policy))
        assertFalse(debugWakeStartsNewSession(session(turns = 0, quietMillis = 3_600_000), now, policy = policy))
        assertFalse(debugWakeStartsNewSession(session(turns = 3, quietMillis = policy.idleMillis - 1), now, policy = policy))
        assertTrue(debugWakeStartsNewSession(session(turns = 3, quietMillis = policy.idleMillis), now, policy = policy))
    }

    @Test fun theQuietWindowIsTheSetOne() {
        val tenMinutes = DebugSessionPolicy(idleMinutes = 10)

        assertFalse(debugWakeStartsNewSession(session(turns = 3, quietMillis = 6 * 60_000L), now, policy = tenMinutes))
        assertTrue(debugWakeStartsNewSession(session(turns = 3, quietMillis = 10 * 60_000L), now, policy = tenMinutes))
    }

    @Test fun everyWakeStartsANewOneButKeepsAnEmptyOrBusySession() {
        val everyWake = DebugSessionPolicy(everyWake = true)

        assertTrue(debugWakeStartsNewSession(session(turns = 1, quietMillis = 1_000), now, policy = everyWake))
        assertFalse(debugWakeStartsNewSession(session(turns = 0, quietMillis = 1_000), now, policy = everyWake))
        assertFalse(debugWakeStartsNewSession(session(turns = 1, quietMillis = 1_000), now, backgroundWork = true, policy = everyWake))
    }

    @Test fun sessionsPastTheLimitGoOldestFirstExceptProtectedOnes() {
        val newestFirst = (1..5).map { session(turns = 1, quietMillis = it * 1_000L, id = "s$it") }

        assertEquals(listOf("s4", "s5"), debugSessionsOverLimit(newestFirst, keep = 3, protected = emptySet()))
        assertEquals(listOf("s5"), debugSessionsOverLimit(newestFirst, keep = 3, protected = setOf("s4")))
        assertEquals(emptyList<String>(), debugSessionsOverLimit(newestFirst, keep = 20, protected = emptySet()))
        assertEquals(listOf("s2", "s3", "s4", "s5"), debugSessionsOverLimit(newestFirst, keep = 0, protected = emptySet()))
    }

    @Test fun thePolicyStaysInBoundsAndDefaultsToTwentySessions() {
        assertEquals(20, DebugSessionPolicy().maxSessions)
        assertEquals(5, DebugSessionPolicy().idleMinutes)
        val bounded = DebugSessionPolicy(idleMinutes = 0, maxSessions = 10_000).bounded()
        assertEquals(DebugSessionPolicy.MIN_IDLE_MINUTES, bounded.idleMinutes)
        assertEquals(DebugSessionPolicy.MAX_SESSIONS, bounded.maxSessions)
    }

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
