package cc.noharry.bothy.app.trajectory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.runtime.append

/**
 * Input / Model / Tools lanes for the focused run.
 *
 * Tap focuses a record. Range drag is offered only where the ledger is not the sole scroll
 * surface, because keeping the ledger scrollable matters more than porting the reference
 * implementation's pointer behaviour. Range only dims rows; it never removes them.
 */
@Composable
internal fun TrajectoryTimeline(
    projection: TrajectoryTimelineProjection,
    selectedRecordId: TrajectoryRecordId?,
    selectedRange: TrajectoryTimeRange?,
    onSelect: (TrajectoryRecordId) -> Unit,
    onSelectRange: ((TrajectoryTimeRange?) -> Unit)?,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = ConsoleColors
    if (projection.isEmpty) return
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.elevated, RoundedCornerShape(Dimens.radiusMd))
            .padding(Dimens.spaceSm)
            .semantics { contentDescription = projection.timelineDescription() },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            projection.headerMetricLabels().forEach { label ->
                Text(text = label, style = ConsoleType.label, color = colors.muted, maxLines = 1)
            }
        }
        Spacer(Modifier.height(Dimens.spaceXs))
        val laneContent: @Composable () -> Unit = {
            Column {
                TrajectoryLane.entries.filter { it != TrajectoryLane.SYSTEM }.forEach { lane ->
                    TimelineLaneRow(
                        lane = lane,
                        items = projection.items.filter { it.lane == lane },
                        markers = projection.markers,
                        selectedRecordId = selectedRecordId,
                        selectedRange = selectedRange,
                        onSelect = onSelect,
                        onSelectRange = onSelectRange,
                    )
                    Spacer(Modifier.height(Dimens.spaceXxs))
                }
            }
        }
        if (compact) {
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                Box(Modifier.width(COMPACT_TIMELINE_WIDTH)) { laneContent() }
            }
        } else {
            laneContent()
        }
    }
}

@Composable
private fun TimelineLaneRow(
    lane: TrajectoryLane,
    items: List<TrajectoryTimelineItem>,
    markers: List<TrajectoryTimelineItem>,
    selectedRecordId: TrajectoryRecordId?,
    selectedRange: TrajectoryTimeRange?,
    onSelect: (TrajectoryRecordId) -> Unit,
    onSelectRange: ((TrajectoryTimeRange?) -> Unit)?,
) {
    val colors = ConsoleColors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = lane.name.lowercase(),
            style = ConsoleType.label,
            color = colors.tertiary,
            modifier = Modifier.width(LANE_LABEL_WIDTH),
        )
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .height(LANE_HEIGHT)
                .background(colors.panel, RoundedCornerShape(Dimens.radiusSm))
                .rangeDrag(onSelectRange),
        ) {
            val track = maxWidth
            markers.forEach { marker ->
                Box(
                    Modifier
                        .offset(x = track * marker.startFraction)
                        .width(MARKER_WIDTH)
                        .height(LANE_HEIGHT)
                        .background(colors.border),
                )
            }
            items.forEach { item ->
                val selected = item.id == selectedRecordId
                val inRange = selectedRange == null ||
                    selectedRange.intersects(item.startFraction, item.endFraction)
                val start = track * item.startFraction
                val width = item.endFraction
                    ?.let { (track * (it - item.startFraction)).coerceAtLeast(POINT_WIDTH) }
                    ?: POINT_WIDTH
                Box(
                    Modifier
                        .offset(x = start)
                        .widthIn(min = POINT_WIDTH)
                        .width(width)
                        .height(ITEM_HEIGHT)
                        .align(Alignment.CenterStart)
                        .background(
                            color = when {
                                selected -> colors.accent
                                item.isLive -> colors.activity
                                else -> colors.muted
                            }.copy(alpha = if (inRange) 1f else OUT_OF_RANGE_ALPHA),
                            shape = RoundedCornerShape(Dimens.radiusSm),
                        )
                        .clickable { onSelect(item.id) }
                        .semantics { contentDescription = item.itemDescription() },
                )
            }
        }
    }
}

/** Horizontal-only so a drag never competes with the ledger's vertical scroll. */
private fun Modifier.rangeDrag(onSelectRange: ((TrajectoryTimeRange?) -> Unit)?): Modifier {
    if (onSelectRange == null) return this
    return this.pointerInput(onSelectRange) {
        var startX = 0f
        var endX = 0f
        detectHorizontalDragGestures(
            onDragStart = { offset ->
                startX = offset.x
                endX = offset.x
            },
            onDragEnd = {
                val width = size.width.toFloat()
                if (width <= 0f) return@detectHorizontalDragGestures
                val from = (minOf(startX, endX) / width).coerceIn(0f, 1f)
                val to = (maxOf(startX, endX) / width).coerceIn(0f, 1f)
                onSelectRange(if (to - from < MIN_RANGE_FRACTION) null else TrajectoryTimeRange(from, to))
            },
        ) { _, dragAmount ->
            endX += dragAmount
        }
    }
}

private fun TrajectoryTimelineProjection.timelineDescription(): String =
    "Trajectory timeline, ${items.size} lane items, ${markers.size} markers, " +
        "${if (mode == TrajectoryTimelineMode.ACTUAL_TIME) "actual time" else "equal width"} mode, " +
        headerMetricLabels().joinToString()

private fun TrajectoryTimelineItem.itemDescription(): String = buildString {
    append(lane.name.lowercase())
    append(' ')
    append(label)
    status?.let { append(", status $it") }
    if (isSpan) append(", duration span") else append(", point in time")
    if (isLive) append(", live")
}

private val LANE_LABEL_WIDTH: Dp = 52.dp
private val LANE_HEIGHT: Dp = 18.dp
private val ITEM_HEIGHT: Dp = 10.dp
private val POINT_WIDTH: Dp = 6.dp
private val MARKER_WIDTH: Dp = 1.dp
private val COMPACT_TIMELINE_WIDTH: Dp = 640.dp
private const val OUT_OF_RANGE_ALPHA = 0.35f
private const val MIN_RANGE_FRACTION = 0.02f
