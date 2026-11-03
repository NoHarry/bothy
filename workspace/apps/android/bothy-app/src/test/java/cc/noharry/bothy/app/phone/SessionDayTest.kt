package cc.noharry.bothy.app.phone

import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.session.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionDayTest {
    @Test
    fun groupsSessionsByLocalDay() {
        val now = 1_700_000_000_000L
        assertEquals(SessionDay.TODAY, sessionDay(now, now))
        assertEquals(SessionDay.YESTERDAY, sessionDay(now - 24L * 60L * 60L * 1000L, now))
        assertEquals(SessionDay.THIS_WEEK, sessionDay(now - 3L * DAY, now))
        assertEquals(SessionDay.EARLIER, sessionDay(now - 8L * DAY, now))
    }

    @Test
    fun drawerGroupingFollowsTheQueryAndTheDay() {
        val now = 1_700_000_000_000L
        val sessions = listOf(
            summary("a", "买菜", now),
            summary("b", "修灯", now - 24L * 60L * 60L * 1000L),
        )
        assertEquals(listOf("a"), groupDrawerSessions(sessions, "菜", now).single().second.map { it.sessionId })
        assertEquals(SessionDay.YESTERDAY, groupDrawerSessions(sessions, "", now)[1].first)
    }

    @Test
    fun aChatNothingWasAskedInIsNotListed() {
        val now = 1_700_000_000_000L
        val sessions = listOf(summary("a", "买菜", now), summary("b", "", now, turns = 0))
        assertEquals(listOf("a"), groupDrawerSessions(sessions, "", now).flatMap { it.second }.map { it.sessionId })
        // Its first run going on, it is listed before any turn has ended.
        assertEquals(listOf("a", "b"), groupDrawerSessions(sessions, "", now, started = "b").flatMap { it.second }.map { it.sessionId })
    }

    @Test
    fun theGreetingFollowsTheHour() {
        assertEquals(R.string.phone_greeting_morning, greetingFor(8))
        assertEquals(R.string.phone_greeting_afternoon, greetingFor(15))
        assertEquals(R.string.phone_greeting_evening, greetingFor(21))
        assertEquals(R.string.phone_greeting_evening, greetingFor(2))
    }

    private companion object {
        const val DAY = 24L * 60L * 60L * 1000L
    }

    private fun summary(id: String, title: String, updated: Long, turns: Int = 1) = SessionSummary(
        sessionId = id,
        title = title,
        updatedAtEpochMillis = updated,
        turnCount = turns,
        compactedSummary = "",
    )
}
