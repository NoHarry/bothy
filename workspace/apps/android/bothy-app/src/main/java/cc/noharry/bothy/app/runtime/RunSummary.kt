package cc.noharry.bothy.app.runtime
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.bothy.app.console.status

/**
 * Codex-style end-of-round summary labels for the debug console.
 */
internal enum class RunOutcome {
    COMPLETED,
    /** Answered, with parts the model said it could not get (finish outcome partial), such as unconfirmed flights. */
    PARTIAL,
    FAILED,
    STOPPED,
    REPLAY_FAILED,
}

internal fun sessionRunnerOutcome(
    runtimeResult: RuntimeResult?,
    replayFailed: Boolean,
    /** The run stopped to ask the user (the kernel's `ask`): the question is its reply, as the transcript keeps it. */
    askedUser: Boolean = false,
): RunOutcome = when {
    replayFailed -> RunOutcome.REPLAY_FAILED
    runtimeResult != null &&
        RuntimeCompletionSummary.firstPartyPass(runtimeResult.status, runtimeResult.completion) ->
        RunOutcome.COMPLETED
    // 2026-10-19: 「下单前确认一下…确认下单吗？」 was asked this way and the card said Failed (no result, so FAILED).
    askedUser || runtimeResult?.status == RuntimeRunStatus.WAITING_USER_INPUT -> RunOutcome.COMPLETED
    runtimeResult?.status == RuntimeRunStatus.COMPLETED && runtimeResult.completion.outcome == RuntimeCompletionOutcome.PARTIAL ->
        RunOutcome.PARTIAL
    runtimeResult?.status == RuntimeRunStatus.CANCELLED -> RunOutcome.STOPPED
    else -> RunOutcome.FAILED
}

internal fun formatRunSummary(
    durationMs: Long,
    outcome: RunOutcome,
): String {
    val elapsed = formatWorkedDuration(durationMs.coerceAtLeast(0L))
    return when (outcome) {
        RunOutcome.COMPLETED -> "Worked for $elapsed"
        RunOutcome.PARTIAL -> "Worked for $elapsed$PARTIAL_SUMMARY"
        RunOutcome.REPLAY_FAILED -> "Worked for $elapsed · replay failed"
        RunOutcome.FAILED -> "Failed after $elapsed"
        RunOutcome.STOPPED -> "Stopped after $elapsed"
    }
}

internal fun formatWorkedDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / MILLIS_PER_SECOND).coerceAtLeast(0L)
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return when {
        hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0 -> "${minutes}m ${"%02d".format(seconds)}s"
        else -> "${seconds}s"
    }
}

internal const val PARTIAL_SUMMARY = " · partial"
private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
