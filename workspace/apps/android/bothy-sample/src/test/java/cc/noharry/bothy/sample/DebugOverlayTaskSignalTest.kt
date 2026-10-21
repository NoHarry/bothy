package cc.noharry.bothy.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the folded 小博 shows for delegations: the most pressing one, as ring and capsule. */
class DebugOverlayTaskSignalTest {
    private val now = 100_000L

    @Test
    fun nothingUnderWayShowsNothing() {
        assertNull(overlayTaskSignal(emptyList(), now))
        assertNull(overlayTaskSignal(listOf(view("a", DebugDelegationStage.DONE)), now))
    }

    @Test
    fun stepsShowAsProgressWithTheStepUnderWay() {
        val steps = listOf(DebugDelegationStep("查天气", "done"), DebugDelegationStep("比较哪边更适合出游", "doing"), DebugDelegationStep("给建议", "todo"))
        val signal = overlayTaskSignal(listOf(view("a", DebugDelegationStage.WORKING, steps = steps)), now)!!

        assertEquals(OverlayTaskTone.WORKING, signal.tone)
        assertEquals(1 to 3, signal.stepsDone to signal.stepsTotal)
        assertEquals("Grok Bot · 1/3 步", signal.title)
        assertEquals("比较哪边更适合出游", signal.detail)
    }

    @Test
    fun withoutStepsTheStageAndTimeShow() {
        val signal = overlayTaskSignal(listOf(view("a", DebugDelegationStage.STARTED, startedAt = now - 72_000)), now)!!
        assertEquals("Grok Bot · 已开始 1:12", signal.title)
        assertEquals("「查明天的天气」", signal.detail)
        assertEquals(0, signal.stepsTotal)
    }

    @Test
    fun aWaitingResultComesFirstThenAStallThenTheLatest() {
        val working = view("a", DebugDelegationStage.WORKING)
        val stalled = view("b", DebugDelegationStage.STALLED, startedAt = now - 90_000)
        val result = view("c", DebugDelegationStage.REPORTING)

        val first = overlayTaskSignal(listOf(result, stalled, working), now)!!
        assertEquals(OverlayTaskTone.RESULT, first.tone)
        assertEquals("Grok Bot 有新结果", first.title)
        assertEquals(2, first.more)

        val second = overlayTaskSignal(listOf(working, stalled), now)!!
        assertEquals(OverlayTaskTone.STALLED, second.tone)
        assertEquals("已送达 1:30，可能在等审批", second.detail)
    }

    @Test
    fun aStepDoneOrANewStageChangesTheKeySoTheCapsuleOpensAgain() {
        val before = overlayTaskSignal(listOf(view("a", DebugDelegationStage.WORKING, steps = listOf(DebugDelegationStep("一", "doing")))), now)!!
        val later = overlayTaskSignal(listOf(view("a", DebugDelegationStage.WORKING, steps = listOf(DebugDelegationStep("一", "doing")))), now + 5_000)!!
        val done = overlayTaskSignal(listOf(view("a", DebugDelegationStage.WORKING, steps = listOf(DebugDelegationStep("一", "done")))), now)!!

        assertEquals("time alone does not reopen it", before.key, later.key)
        assertNotEquals(before.key, done.key)
    }

    private fun view(
        id: String,
        stage: DebugDelegationStage,
        steps: List<DebugDelegationStep> = emptyList(),
        startedAt: Long = now - 10_000,
    ) = DebugDelegationView(
        profileId = "grok", agentLabel = "Grok Bot", taskId = id, request = "查明天的天气", startedAtEpochMillis = startedAt,
        stage = stage, steps = steps,
    )
}
