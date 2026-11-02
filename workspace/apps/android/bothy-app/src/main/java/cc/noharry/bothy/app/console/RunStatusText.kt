@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.console

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cc.noharry.agent.runtime.SubagentState
import cc.noharry.agent.runtime.SubagentStatus
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.runtime.LIVE_MODEL_LABEL
import cc.noharry.bothy.app.runtime.PARTIAL_SUMMARY
import cc.noharry.bothy.app.runtime.SIMULATED_MODEL_LABEL
import cc.noharry.bothy.app.session.ToolCallStatus

/**
 * Screen text for words the runtime keeps in fixed English. `lastRunSummary` ("Worked for 1m 05s · partial") and the
 * durations are matched by the code that reads them, so they stay as they are and are put into the screen's language
 * only where they are shown.
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

/** A subagent's state as the screens show it. */
@StringRes
internal fun SubagentState.labelRes(): Int = when (this) {
    SubagentState.RUNNING -> R.string.console_subagent_running
    SubagentState.STALLED -> R.string.console_subagent_stalled
    SubagentState.COMPLETED -> R.string.console_subagent_completed
    SubagentState.FAILED -> R.string.console_subagent_failed
    SubagentState.CANCELLED -> R.string.console_subagent_cancelled
    SubagentState.TIMED_OUT -> R.string.console_subagent_timed_out
}

/** The screen's form of `SubagentStatus.logLine()`: seconds, tool and model calls, tokens and cache hit. */
internal fun Resources.subagentLogText(row: SubagentStatus, now: Long = System.currentTimeMillis()): String {
    val end = if (row.finishedAtEpochMillis > 0) row.finishedAtEpochMillis else now
    val seconds = (end - row.startedAtEpochMillis) / MILLIS_PER_SECOND
    val line = if (row.inputTokens > 0) {
        val cache = row.cachedTokens * PERCENT / row.inputTokens
        getString(
            R.string.console_subagent_log_cached,
            seconds, row.toolCalls, row.modelCalls, row.inputTokens, cache, row.outputTokens,
        )
    } else {
        getString(
            R.string.console_subagent_log,
            seconds, row.toolCalls, row.modelCalls, row.inputTokens, row.outputTokens,
        )
    }
    return if (row.error.isNotEmpty()) "$line · ${row.error}" else line
}

/** The runtime's stream label ("stream" / "non-stream", kept for the evidence files) as the screen shows it. */
@Composable
internal fun streamLabelText(label: String): String = when (label) {
    "stream" -> stringResource(R.string.console_stream_on)
    "non-stream" -> stringResource(R.string.console_stream_off)
    else -> label
}

/** The runtime's backend label as the screen shows it. */
@Composable
internal fun backendLabelText(label: String): String = when (label) {
    LIVE_MODEL_LABEL -> stringResource(R.string.console_backend_live)
    SIMULATED_MODEL_LABEL -> stringResource(R.string.console_backend_simulated)
    else -> label
}

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

private val RUN_SUMMARY = Regex("""(Worked for|Failed after|Stopped after) (.+?)( · partial| · replay failed)?""")
private val DURATION_LABEL = Regex("""(?:(\d+)h )?(?:(\d+)m )?(\d+)s""")
private const val REPLAY_FAILED_SUFFIX = " · replay failed"
private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
private const val PERCENT = 100
