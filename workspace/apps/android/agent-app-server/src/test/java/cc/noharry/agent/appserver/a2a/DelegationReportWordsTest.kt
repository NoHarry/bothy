package cc.noharry.agent.appserver.a2a

import cc.noharry.agent.a2a.A2aArtifact
import cc.noharry.agent.a2a.A2aPart
import cc.noharry.agent.a2a.A2aTask
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A delegation's report in the generic persona's English; either language's marks open a report turn. */
@OptIn(ExperimentalA2aClientApi::class)
class DelegationReportWordsTest {
    private val delegation = A2aDelegation("Grok Bot", "task-1", "Send me a test email", startedAtEpochMillis = 0L)

    @Test
    fun aReportIsWordedInEnglish() {
        val done = A2aTask(
            id = "task-1", contextId = "ctx-1", status = A2aTask.Status.COMPLETED,
            artifacts = listOf(A2aArtifact("task-1-round-1", parts = listOf(A2aPart.Text("Email sent.")))),
        )
        val report = delegationReport(delegation, A2aFollowed(done), listOf(A2aReportPicture("generated-images/t/1.png", "storefront")))

        assertEquals("[Delegation report] Grok Bot: Email sent.", report.display)
        assertEquals(
            listOf("[Delegation report] Grok Bot finished \"Send me a test email\": Email sent.", "Pictures: generated-images/t/1.png (storefront)"),
            report.model.lines(),
        )
        assertEquals(
            "[Delegation failed] Grok Bot: Handling \"Send me a test email\": no answer.",
            delegationReport(delegation, A2aFollowed(null, "no answer")).display,
        )
        assertTrue(report.display.isDelegationReport() && "【委托追问】Grok Bot：发到哪个邮箱？".isDelegationReport())
        assertEquals("[Delegation report]/[Delegation question]/[Delegation failed]", delegationMarks())
    }
}
