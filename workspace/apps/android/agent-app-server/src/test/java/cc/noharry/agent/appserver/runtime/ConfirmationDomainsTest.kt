package cc.noharry.agent.appserver.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfirmationDomainsTest {
    @Test
    fun aRunComingOnScreenLeavesAnotherSessionsWaitingApprovalItsDomain() {
        val domains = ConfirmationDomains()
        domains.remember("a", "p1", "x.com")
        // A turn starts in session b while a's run waits in the background.
        domains.runShown("b", "r2")
        domains.approved("a", "p1")
        assertEquals(setOf("x.com"), domains.confirmed("a"))
        // A later run of a's own: what waited there before was the earlier run's.
        domains.runShown("a", "r3")
        assertEquals("", domains.of("p1"))
    }
}
