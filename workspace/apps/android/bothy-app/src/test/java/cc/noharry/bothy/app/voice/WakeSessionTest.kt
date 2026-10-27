package cc.noharry.bothy.app.voice
import cc.noharry.bothy.app.runtime.wakeStartsNewSession
import cc.noharry.bothy.app.session.IDLE_MINUTE_STEPS
import cc.noharry.bothy.app.session.SESSION_COUNT_STEPS
import cc.noharry.bothy.app.session.SessionPolicy
import cc.noharry.bothy.app.session.SessionSummary
import cc.noharry.bothy.app.session.sessionsOverLimit
import cc.noharry.bothy.app.session.stepped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeSessionTest {
    private val now = 1_800_000_000_000L
    private fun session(turns: Int, quietMillis: Long, id: String = "s") = SessionSummary(id, "t", now - quietMillis, turns, "")

    @Test fun onlyAWakeAfterTheQuietWindowOnASessionWithTurnsStartsANewOne() {
        val policy = SessionPolicy(idleMinutes = 5)

        assertFalse(wakeStartsNewSession(null, now, policy = policy))
        assertFalse(wakeStartsNewSession(session(turns = 0, quietMillis = 3_600_000), now, policy = policy))
        assertFalse(wakeStartsNewSession(session(turns = 3, quietMillis = policy.idleMillis - 1), now, policy = policy))
        assertTrue(wakeStartsNewSession(session(turns = 3, quietMillis = policy.idleMillis), now, policy = policy))
    }

    @Test fun theQuietWindowIsTheSetOne() {
        val tenMinutes = SessionPolicy(idleMinutes = 10)

        assertFalse(wakeStartsNewSession(session(turns = 3, quietMillis = 6 * 60_000L), now, policy = tenMinutes))
        assertTrue(wakeStartsNewSession(session(turns = 3, quietMillis = 10 * 60_000L), now, policy = tenMinutes))
    }

    @Test fun everyWakeStartsANewOneButKeepsAnEmptyOrBusySession() {
        val everyWake = SessionPolicy(everyWake = true)

        assertTrue(wakeStartsNewSession(session(turns = 1, quietMillis = 1_000), now, policy = everyWake))
        assertFalse(wakeStartsNewSession(session(turns = 0, quietMillis = 1_000), now, policy = everyWake))
        assertFalse(wakeStartsNewSession(session(turns = 1, quietMillis = 1_000), now, backgroundWork = true, policy = everyWake))
    }

    @Test fun sessionsPastTheLimitGoOldestFirstExceptProtectedOnes() {
        val newestFirst = (1..5).map { session(turns = 1, quietMillis = it * 1_000L, id = "s$it") }

        assertEquals(listOf("s4", "s5"), sessionsOverLimit(newestFirst, keep = 3, protected = emptySet()))
        assertEquals(listOf("s5"), sessionsOverLimit(newestFirst, keep = 3, protected = setOf("s4")))
        assertEquals(emptyList<String>(), sessionsOverLimit(newestFirst, keep = 20, protected = emptySet()))
        assertEquals(listOf("s2", "s3", "s4", "s5"), sessionsOverLimit(newestFirst, keep = 0, protected = emptySet()))
    }

    @Test fun thePolicyStaysInBoundsAndDefaultsToTwentySessions() {
        assertEquals(20, SessionPolicy().maxSessions)
        assertEquals(5, SessionPolicy().idleMinutes)
        val bounded = SessionPolicy(idleMinutes = 0, maxSessions = 10_000).bounded()
        assertEquals(SessionPolicy.MIN_IDLE_MINUTES, bounded.idleMinutes)
        assertEquals(SessionPolicy.MAX_SESSIONS, bounded.maxSessions)
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
