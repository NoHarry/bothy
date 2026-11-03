package cc.noharry.bothy.app.console

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
}
