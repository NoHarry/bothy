package cc.noharry.bothy.app.phone

import cc.noharry.bothy.app.session.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionDayTest {
    @Test
    fun groupsSessionsByLocalDay() {
        val now = 1_700_000_000_000L
        assertEquals(SessionDay.TODAY, sessionDay(now, now))
        assertEquals(SessionDay.YESTERDAY, sessionDay(now - 24L * 60L * 60L * 1000L, now))
        assertEquals(SessionDay.EARLIER, sessionDay(now - 3L * 24L * 60L * 60L * 1000L, now))
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

    private fun summary(id: String, title: String, updated: Long) = SessionSummary(
        sessionId = id,
        title = title,
        updatedAtEpochMillis = updated,
        turnCount = 0,
        compactedSummary = "",
    )
}
