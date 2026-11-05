package cc.noharry.bothy.app.trajectory

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import cc.noharry.agent.appserver.protocol.EventLogHealthStatus
import cc.noharry.agent.appserver.protocol.TrajectoryHealth
import cc.noharry.agent.appserver.protocol.TrajectoryKind
import cc.noharry.agent.appserver.protocol.TrajectoryLane
import cc.noharry.agent.appserver.protocol.TrajectoryRecord
import cc.noharry.agent.appserver.protocol.TrajectoryRichAvailability
import cc.noharry.agent.appserver.protocol.view.TrajectoryInspectorTab
import cc.noharry.agent.appserver.protocol.view.TrajectoryTimelineMode
import cc.noharry.agent.appserver.protocol.view.TrajectoryTimelineProjection
import cc.noharry.agent.appserver.protocol.view.formatTrajectoryDurationLabel
import cc.noharry.bothy.app.R

/**
 * Screen text of the trajectory screen. The projector keeps its record titles and health labels in fixed English (the
 * search and the tests read them); these put them into the screen's language where they are shown.
 */
internal fun Resources.trajectoryTitleText(kind: TrajectoryKind, title: String): String {
    val res = when (kind) {
        TrajectoryKind.LOAD_ERROR -> R.string.trajectory_title_load_error
        TrajectoryKind.GAP -> R.string.trajectory_title_gap
        TrajectoryKind.STATUS -> if (title == STATUS_TITLE) R.string.trajectory_title_status else null
        else -> PERSISTED_TITLES[title]
    }
    return when {
        res != null -> getString(res)
        kind == TrajectoryKind.STATUS && title.startsWith("$STATUS_TITLE ") ->
            getString(R.string.trajectory_title_status_value, title.removePrefix(STATUS_TITLE).trim())
        else -> title
    }
}

@Composable
internal fun TrajectoryRecord.displayTitle(): String = LocalContext.current.resources.trajectoryTitleText(kind, title)

@StringRes
internal fun TrajectoryLane.labelRes(): Int = when (this) {
    TrajectoryLane.INPUT -> R.string.trajectory_lane_input
    TrajectoryLane.MODEL -> R.string.trajectory_lane_model
    TrajectoryLane.TOOLS -> R.string.trajectory_lane_tools
    TrajectoryLane.SYSTEM -> R.string.trajectory_lane_system
}

@StringRes
internal fun TrajectoryInspectorTab.labelRes(): Int = when (this) {
    TrajectoryInspectorTab.SUMMARY -> R.string.trajectory_tab_summary
    TrajectoryInspectorTab.PREVIEW -> R.string.trajectory_tab_preview
    TrajectoryInspectorTab.RAW -> R.string.trajectory_tab_raw
    TrajectoryInspectorTab.SOURCE -> R.string.trajectory_tab_source
    TrajectoryInspectorTab.REQUEST -> R.string.trajectory_tab_request
    TrajectoryInspectorTab.OPTIONS -> R.string.trajectory_tab_options
    TrajectoryInspectorTab.TOOLS -> R.string.trajectory_tab_tools
    TrajectoryInspectorTab.THINKING -> R.string.trajectory_tab_thinking
    TrajectoryInspectorTab.OUTPUT -> R.string.trajectory_tab_output
    TrajectoryInspectorTab.USAGE -> R.string.trajectory_tab_usage
    TrajectoryInspectorTab.PAYLOAD -> R.string.trajectory_tab_payload
    TrajectoryInspectorTab.RESULT -> R.string.trajectory_tab_result
    TrajectoryInspectorTab.SCHEMA -> R.string.trajectory_tab_schema
    TrajectoryInspectorTab.TIMING -> R.string.trajectory_tab_timing
    TrajectoryInspectorTab.HEALTH -> R.string.trajectory_tab_health
}

/** The screen's form of [TrajectoryHealth.badgeLabel]. */
@StringRes
internal fun EventLogHealthStatus.labelRes(): Int = when (this) {
    EventLogHealthStatus.COMPLETE -> R.string.trajectory_health_complete
    EventLogHealthStatus.DEGRADED -> R.string.trajectory_health_degraded
    EventLogHealthStatus.UNAVAILABLE -> R.string.trajectory_health_unavailable
    EventLogHealthStatus.VOLATILE -> R.string.trajectory_health_volatile
    EventLogHealthStatus.UNKNOWN -> R.string.trajectory_health_unknown
}

@StringRes
internal fun TrajectoryRichAvailability.badgeRes(): Int = when (this) {
    TrajectoryRichAvailability.AVAILABLE -> R.string.trajectory_details_available
    TrajectoryRichAvailability.PARTIAL -> R.string.trajectory_details_partial
    TrajectoryRichAvailability.REDACTED -> R.string.trajectory_details_redacted
    TrajectoryRichAvailability.HASH_ONLY -> R.string.trajectory_details_hash_only
    TrajectoryRichAvailability.UNAVAILABLE -> R.string.trajectory_details_unavailable
}

@StringRes
internal fun TrajectoryTimelineMode.labelRes(): Int = when (this) {
    TrajectoryTimelineMode.ACTUAL_TIME -> R.string.trajectory_mode_actual_time
    TrajectoryTimelineMode.EQUAL_WIDTH -> R.string.trajectory_mode_equal_width
}

/** The screen's form of [headerMetricLabels]: time to the first model call, each lane's time, the run's span. */
internal fun TrajectoryTimelineProjection.headerMetricTexts(resources: Resources): List<String> {
    val lanes = TrajectoryLane.entries.filter { it != TrajectoryLane.SYSTEM }.map { lane ->
        resources.getString(
            R.string.trajectory_metric_lane,
            resources.getString(lane.labelRes()),
            formatTrajectoryDurationLabel(laneDurationMs[lane]),
        )
    }
    val ttft = resources.getString(R.string.trajectory_metric_ttft, formatTrajectoryDurationLabel(timeToFirstModelMs))
    return listOf(ttft) +
        lanes +
        listOf(resources.getString(R.string.trajectory_metric_span, formatTrajectoryDurationLabel(spanMs)))
}

/** The projector's fixed titles (`persistedTitle`); a tool's own name is shown as it is. */
private val PERSISTED_TITLES = mapOf(
    "Run started" to R.string.trajectory_title_run_started,
    "User message" to R.string.trajectory_title_user_message,
    "Thinking" to R.string.trajectory_title_thinking,
    "Assistant chunk" to R.string.trajectory_title_assistant_chunk,
    "Assistant message" to R.string.trajectory_title_assistant_message,
    "Model tool call" to R.string.trajectory_title_model_tool_call,
    "Tool started" to R.string.trajectory_title_tool_started,
    "Tool finished" to R.string.trajectory_title_tool_finished,
    "Search activity" to R.string.trajectory_title_search_activity,
    "Model request" to R.string.trajectory_title_model_request,
    "Model metadata" to R.string.trajectory_title_model_metadata,
    "Model call" to R.string.trajectory_title_model_call,
    "Token usage" to R.string.trajectory_title_token_usage,
    "Run finished" to R.string.trajectory_title_run_finished,
)

private const val STATUS_TITLE = "Status"
