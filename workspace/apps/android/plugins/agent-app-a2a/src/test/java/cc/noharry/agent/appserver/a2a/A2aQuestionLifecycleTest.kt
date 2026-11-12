package cc.noharry.agent.appserver.a2a
import cc.noharry.agent.*
import cc.noharry.agent.a2a.server.A2aOutput
import org.junit.Assert.*
import org.junit.Test

class A2aQuestionLifecycleTest {
    @Test fun answeringAndExpiryCloseTheSameRemoteQuestion() {
        val question = Question("turn:question:first", "turn", "Destination?", listOf("A", "B"), async = true, status = ItemStatus.IN_PROGRESS)
        val events = listOf(ThreadEvent.ItemStarted(question), ThreadEvent.ItemUpdated(question.copy(status = ItemStatus.COMPLETED)),
            ThreadEvent.ItemCompleted(question.copy(status = ItemStatus.COMPLETED)))
        val outputs = events.map { a2aRuntimeOutput(it) as A2aOutput.Question }
        assertEquals(listOf("pending", "answered", "answered"), outputs.map { it.state })
        assertEquals(setOf(question.id), outputs.map { it.questionId }.toSet())
        val expired = a2aRuntimeOutput(ThreadEvent.ItemCompleted(question.copy(status = ItemStatus.DECLINED))) as A2aOutput.Question
        assertEquals("expired", expired.state)
        assertEquals(question.id, expired.questionId)
    }
}
