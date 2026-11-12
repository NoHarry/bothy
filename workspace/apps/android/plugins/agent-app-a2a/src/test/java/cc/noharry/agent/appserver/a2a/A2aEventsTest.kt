package cc.noharry.agent.appserver.a2a

import cc.noharry.agent.NoticeDetails
import cc.noharry.agent.NoticeKind
import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.ThreadItem
import cc.noharry.agent.a2a.server.A2aOutput
import cc.noharry.agent.appserver.runtime.ExecutionMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a caller of the local endpoint sees of a run: progress in the app's words, never the runtime's raw payloads. */
class A2aEventsTest {
    @Test
    fun remoteAdapterProjectsSpeechAndStopsWithoutRawPayloadDisclosure() {
        assertNull(a2aExecutionOutput(ExecutionMessage.Runtime(ThreadEvent.ItemDelta("thinking", "secret"))))
        assertTrue(a2aExecutionOutput(ExecutionMessage.Speech("你好", true)) is A2aOutput.Speech)
        assertEquals(A2aOutput.State.CANCELED, (a2aExecutionOutput(ExecutionMessage.Stopped) as A2aOutput.Result).state)
    }

    @Test
    fun a2aProgressUsesTheAppsWordsNotTheRuntimesEnglish() {
        val retry = ThreadItem.Notice("n1", "turn", NoticeKind.RETRY, "Retry 1 in 500 ms", details = NoticeDetails(attempt = 1))
        val status = a2aRuntimeOutput(ThreadEvent.ItemCompleted(retry)) as A2aOutput.Status
        assertEquals("retry", status.phase)
        assertFalse(status.text.contains("500 ms"))
        assertNull(a2aRuntimeOutput(ThreadEvent.ItemCompleted(ThreadItem.Notice("n2", "turn", NoticeKind.WORKSPACE_CHANGED, "default"))))
        val search = a2aRuntimeOutput(ThreadEvent.ItemStarted(ThreadItem.WebSearch("w1", "turn", "bing", "天气"))) as A2aOutput.Status
        assertEquals("search", search.phase)
    }
}
