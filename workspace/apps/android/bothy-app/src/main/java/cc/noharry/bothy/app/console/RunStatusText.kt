package cc.noharry.bothy.app.console

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cc.noharry.agent.appserver.protocol.RunOutcome
import cc.noharry.agent.appserver.protocol.RunResult
import cc.noharry.agent.appserver.protocol.SubagentRunState
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import cc.noharry.agent.appserver.protocol.view.BLOCKED_SUMMARY
import cc.noharry.agent.appserver.protocol.view.PARTIAL_SUMMARY
import cc.noharry.bothy.app.R

/**
 * Screen text for the English labels the overlay's projection carries (a finished run's "Worked for 1m 05s · partial",
 * [cc.noharry.agent.appserver.protocol.view.label]), put into the screen's language where they are shown.
 */
internal fun Resources.runSummaryText(summary: String): String {
    val match = RUN_SUMMARY.matchEntire(summary.trim()) ?: return summary
    val (verb, duration, suffix) = match.destructured
    val elapsed = durationLabelText(duration)
    val main = when (verb) {
        "Worked for" -> getString(R.string.console_run_worked_for, elapsed)
        "Failed after" -> getString(R.string.console_run_failed_after, elapsed)
        else -> getString(R.string.console_run_stopped_after, elapsed)
    }
    return when (suffix) {
        PARTIAL_SUMMARY -> getString(R.string.console_run_partial, main)
        BLOCKED_SUMMARY -> getString(R.string.console_run_blocked, main)
        REPLAY_FAILED_SUFFIX -> getString(R.string.console_run_replay_failed, main)
        else -> main
    }
}

@Composable
internal fun runSummaryText(summary: String): String = LocalContext.current.resources.runSummaryText(summary)

/** A duration as [cc.noharry.bothy.app.runtime.formatWorkedDuration] counts it, in the screen's language. */
internal fun Resources.durationText(durationMs: Long): String {
    val totalSeconds = (durationMs / MILLIS_PER_SECOND).coerceAtLeast(0L)
    val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    return durationText(totalSeconds / SECONDS_PER_HOUR, minutes, totalSeconds % SECONDS_PER_MINUTE)
}

@Composable
internal fun durationText(durationMs: Long): String = LocalContext.current.resources.durationText(durationMs)

/** A label `formatWorkedDuration` made ("1h 2m 3s", "1m 05s", "12s"); any other text as it is. */
internal fun Resources.durationLabelText(label: String): String {
    val match = DURATION_LABEL.matchEntire(label.trim()) ?: return label
    val (hours, minutes, seconds) = match.destructured
    return durationText(hours.toLongOrNull() ?: 0L, minutes.toLongOrNull() ?: 0L, seconds.toLong())
}

private fun Resources.durationText(hours: Long, minutes: Long, seconds: Long): String = when {
    hours > 0 -> getString(R.string.console_duration_hms, hours, minutes, seconds)
    minutes > 0 -> getString(R.string.console_duration_ms, minutes, seconds)
    else -> getString(R.string.console_duration_s, seconds)
}

/** A subagent lane's state as the screens show it. */
@StringRes
internal fun SubagentRunState.labelRes(): Int = when (this) {
    SubagentRunState.RUNNING -> R.string.console_subagent_running
    SubagentRunState.STALLED -> R.string.console_subagent_stalled
    SubagentRunState.COMPLETED -> R.string.console_subagent_completed
    SubagentRunState.FAILED -> R.string.console_subagent_failed
    SubagentRunState.CANCELLED -> R.string.console_subagent_cancelled
    SubagentRunState.TIMED_OUT -> R.string.console_subagent_timed_out
}

/** How the last run ended, in the screen's language. */
internal fun Resources.runResultText(result: RunResult): String {
    val elapsed = durationText(result.durationMs)
    val main = when (result.outcome) {
        RunOutcome.FAILED -> getString(R.string.console_run_failed_after, elapsed)
        RunOutcome.STOPPED -> getString(R.string.console_run_stopped_after, elapsed)
        RunOutcome.COMPLETED, RunOutcome.PARTIAL, RunOutcome.BLOCKED, RunOutcome.REPLAY_FAILED -> getString(R.string.console_run_worked_for, elapsed)
    }
    return when (result.outcome) {
        RunOutcome.PARTIAL -> getString(R.string.console_run_partial, main)
        RunOutcome.BLOCKED -> getString(R.string.console_run_blocked, main)
        RunOutcome.REPLAY_FAILED -> getString(R.string.console_run_replay_failed, main)
        else -> main
    }
}

@Composable
internal fun runResultText(result: RunResult): String = LocalContext.current.resources.runResultText(result)

/** Whether replies stream, as the screen says it. */
@Composable
internal fun streamText(stream: Boolean): String = stringResource(if (stream) R.string.console_stream_on else R.string.console_stream_off)

/** Which model runs answer with, as the screen says it. */
@Composable
internal fun backendText(simulated: Boolean): String =
    stringResource(if (simulated) R.string.console_backend_simulated else R.string.console_backend_live)

/** A tool call's state, as the timeline rows show it. */
@StringRes
internal fun ToolCallStatus.labelRes(): Int = when (this) {
    ToolCallStatus.RUNNING -> R.string.console_tool_running
    ToolCallStatus.DONE -> R.string.console_tool_done
    ToolCallStatus.FAILED -> R.string.console_tool_failed
    ToolCallStatus.UNKNOWN -> R.string.console_tool_unknown
}

/** A tool's run time: milliseconds under a second, else seconds with one decimal. */
internal fun Resources.shortElapsedText(millis: Long): String =
    if (millis < MILLIS_PER_SECOND) {
        getString(R.string.console_elapsed_ms, millis)
    } else {
        getString(R.string.console_elapsed_seconds, millis / MILLIS_PER_SECOND.toDouble())
    }

private val RUN_SUMMARY = Regex("""(Worked for|Failed after|Stopped after) (.+?)( · partial| · blocked| · replay failed)?""")
private val DURATION_LABEL = Regex("""(?:(\d+)h )?(?:(\d+)m )?(\d+)s""")
private const val REPLAY_FAILED_SUFFIX = " · replay failed"
private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
