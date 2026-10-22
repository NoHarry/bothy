package cc.noharry.bothy.sample

import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DebugRunSummaryTest {
    @Test
    fun formatsSecondsOnly() {
        assertEquals("12s", formatWorkedDuration(12_000L))
        assertEquals("0s", formatWorkedDuration(0L))
        assertEquals("0s", formatWorkedDuration(400L))
    }

    @Test
    fun formatsMinutesAndHours() {
        assertEquals("1m 05s", formatWorkedDuration(65_000L))
        assertEquals("1h 2m 3s", formatWorkedDuration((3600 + 120 + 3) * 1000L))
    }

    @Test
    fun formatsOutcomes() {
        assertEquals("Worked for 12s", formatRunSummary(12_000L, DebugRunOutcome.COMPLETED))
        assertEquals(
            "Worked for 12s · replay failed",
            formatRunSummary(12_000L, DebugRunOutcome.REPLAY_FAILED),
        )
        assertEquals("Worked for 12s · partial", formatRunSummary(12_000L, DebugRunOutcome.PARTIAL))
        assertEquals("Failed after 3s", formatRunSummary(3_000L, DebugRunOutcome.FAILED))
        assertEquals("Stopped after 1s", formatRunSummary(1_000L, DebugRunOutcome.STOPPED))
    }

    @Test
    fun onlyCompletedSucceededPassesAndPartialIsPartlyDone() {
        assertEquals(
            DebugRunOutcome.COMPLETED,
            debugSessionRunnerOutcome(result(RuntimeRunStatus.COMPLETED, RuntimeCompletionOutcome.SUCCEEDED), false),
        )
        // An answer with unconfirmed parts (a trip whose flights are 待确认) is partly done, not failed.
        assertEquals(
            DebugRunOutcome.PARTIAL,
            debugSessionRunnerOutcome(result(RuntimeRunStatus.COMPLETED, RuntimeCompletionOutcome.PARTIAL), false),
        )
        listOf(
            RuntimeCompletionOutcome.BLOCKED,
            RuntimeCompletionOutcome.FAILED,
            RuntimeCompletionOutcome.UNKNOWN,
        ).forEach { outcome ->
            assertEquals(
                outcome.name,
                DebugRunOutcome.FAILED,
                debugSessionRunnerOutcome(result(RuntimeRunStatus.COMPLETED, outcome), false),
            )
        }
        assertEquals(
            DebugRunOutcome.STOPPED,
            debugSessionRunnerOutcome(result(RuntimeRunStatus.CANCELLED, RuntimeCompletionOutcome.UNKNOWN), false),
        )
        assertEquals(
            DebugRunOutcome.REPLAY_FAILED,
            debugSessionRunnerOutcome(result(RuntimeRunStatus.COMPLETED, RuntimeCompletionOutcome.SUCCEEDED), true),
        )
        assertEquals(DebugRunOutcome.FAILED, debugSessionRunnerOutcome(null, false))
        // A run that asked the user, whether its result is the waiting one, missing, or the cancel when it closed.
        assertEquals(
            DebugRunOutcome.COMPLETED,
            debugSessionRunnerOutcome(result(RuntimeRunStatus.WAITING_USER_INPUT, RuntimeCompletionOutcome.UNKNOWN), false),
        )
        assertEquals(DebugRunOutcome.COMPLETED, debugSessionRunnerOutcome(null, false, askedUser = true))
        assertEquals(
            DebugRunOutcome.COMPLETED,
            debugSessionRunnerOutcome(result(RuntimeRunStatus.CANCELLED, RuntimeCompletionOutcome.UNKNOWN), false, askedUser = true),
        )
    }

    private fun result(
        status: RuntimeRunStatus,
        outcome: RuntimeCompletionOutcome,
    ): RuntimeResult = RuntimeResult(
        "run-1",
        status,
        "done",
        completion = RuntimeCompletionSummary(outcome = outcome),
    )
}
