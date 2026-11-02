package cc.noharry.bothy.playground.headunit
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RoundaboutRight
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.UTurnRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.playground.R
import cc.noharry.bothy.playground.nav.Guide
import cc.noharry.bothy.playground.nav.NavState
import cc.noharry.bothy.playground.nav.NavStatus
import cc.noharry.bothy.playground.nav.TrafficStretch
import cc.noharry.bothy.playground.nav.ViaProgress
import cc.noharry.bothy.playground.nav.clockParts
import kotlinx.coroutines.delay

/**
 * The next manoeuvre: the turn arrow, how far, onto which road, the exit to take; then what is left of the whole route.
 * Sized after AMap's stock panel (owner 2026-10-22: 「有些大小也参考下」): its arrow is about 55 dp and its distance about
 * 36 sp on this tablet. Tapped, the guidance is said again ([onReplay]), as tapping the stock panel does.
 */
@Composable
internal fun TurnCard(nav: NavState, now: Long, modifier: Modifier = Modifier, onReplay: (() -> Unit)? = null) {
    UnitCard(if (onReplay == null) modifier else modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onReplay)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(turnIcon(nav.turnIcon), contentDescription = null, tint = Color.White, modifier = Modifier.size(TURN_ARROW))
            Column {
                val (number, unitName) = nav.stepMeters.stepDistanceParts()
                val unit = stringResource(unitName)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(number, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp)
                    Text(unit, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 3.dp, bottom = 5.dp))
                }
                // 后进入 leads the road, as on AMap's stock panel: beside a long distance it wrapped.
                val onto = stringResource(R.string.headunit_turn_onto)
                val road = nav.nextRoad.ifEmpty { stringResource(R.string.headunit_road_ahead) }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(onto, color = UnitColors.muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 3.dp))
                    Text(road, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        ExitLine(nav.guide)
        val gpsWeak = stringResource(R.string.headunit_gps_weak)
        if (nav.guide.gpsWeak) Text(gpsWeak, color = Color(0xFFFFC72C), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(10.dp))
        SpeedLine(nav)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            val left = "${nav.remainingSeconds.durationText()} · ${nav.remainingMeters.distanceText()}"
            Text(left, color = UnitColors.muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(etaText(now + nav.remainingSeconds * 1000L, now), color = UnitColors.muted, fontSize = 14.sp)
        }
    }
}

/** The exit to take and where it leads, as AMap's stock panel shows it: 去, the exit on a road sign's green, the way. */
@Composable
private fun ExitLine(guide: Guide) {
    if (guide.exit.isBlank() && guide.toward.isBlank()) return
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.headunit_exit_to), color = UnitColors.muted, fontSize = 15.sp)
        if (guide.exit.isNotBlank()) {
            Text(
                if ("出口" in guide.exit) guide.exit else stringResource(R.string.headunit_exit_number, guide.exit),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.background(EXIT_GREEN, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        if (guide.toward.isNotBlank()) Text(guide.toward, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** How fast the car goes against the limit ahead, the lights still ahead, and an average-speed section under way. */
@Composable
private fun SpeedLine(nav: NavState) {
    // How fast the car goes and the lights still ahead (owner 2026-10-22: 「确认下红绿灯，车速等信息能正常显示吗」).
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // Over the limit of the camera ahead, the speed turns red beside the limit's sign.
        if (nav.guide.limitKmh > 0) {
            LimitSign(nav.guide.limitKmh)
            Spacer(Modifier.width(6.dp))
        }
        Text("${nav.speedKmh}", color = if (nav.guide.over(nav.speedKmh)) LIMIT_RED else Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("km/h", color = UnitColors.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        if (nav.lightsLeft > 0) {
            // On the right, kept to one line beside the limit's sign: the light's icon says what the number counts.
            Spacer(Modifier.weight(1f))
            val lights = stringResource(R.string.headunit_traffic_lights)
            Icon(Icons.Filled.Traffic, contentDescription = lights, tint = UnitColors.muted, modifier = Modifier.size(18.dp).padding(bottom = 2.dp))
            val count = pluralStringResource(R.plurals.headunit_lights_left, nav.lightsLeft, nav.lightsLeft)
            Text(count, color = UnitColors.muted, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
    if (nav.guide.sectionLimitKmh > 0) {
        val average = nav.guide.sectionAverageKmh
        Text(
            stringResource(
                R.string.headunit_section_speed,
                average,
                nav.guide.sectionLimitKmh,
                nav.guide.sectionLeftMeters.distanceText(),
            ),
            color = if (average > nav.guide.sectionLimitKmh) LIMIT_RED else UnitColors.muted,
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** The next stop (or the destination when none is left), with how far and when; how many more come after it. */
@Composable
internal fun NextStopCard(nav: NavState, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val next = nav.waypoints.firstOrNull()
    val left = nav.toWaypoints.firstOrNull() ?: ViaProgress(nav.remainingMeters, nav.remainingSeconds)
    val name = next?.name ?: nav.destination?.name.orEmpty()
    UnitCard(modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(if (next != null) UnitColors.teal else PlaygroundColors.ember), contentAlignment = Alignment.Center) {
                Icon(if (next != null) Icons.Filled.Place else Icons.Filled.Flag, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Text(name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = UnitColors.muted)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            val distance = left.meters.distanceText()
            val more = nav.waypoints.size - 1
            val line = when {
                more > 0 -> pluralStringResource(R.plurals.headunit_next_more, more, distance, more)
                next != null -> stringResource(R.string.headunit_next_then_end, distance)
                else -> stringResource(R.string.headunit_next_is_end, distance)
            }
            Text(line, color = UnitColors.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(etaText(now + left.seconds * 1000L, now), color = UnitColors.muted, fontSize = 13.sp)
        }
    }
}

/**
 * The route just planned, shown whole on the map before driving it: how far, how long, when, tolls; it starts by itself
 * when the count runs out, or at once, or not at all.
 */
@Composable
internal fun RoutePreviewCard(nav: NavState, onStart: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(nav.startsAt) {
        while (System.currentTimeMillis() < nav.startsAt) {
            now = System.currentTimeMillis()
            delay(COUNT_TICK_MS)
        }
    }
    val left = ((nav.startsAt - now + 999) / 1000).coerceAtLeast(0)
    UnitCard(modifier) {
        val planned = stringResource(R.string.headunit_route_planned)
        Text(planned, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        val summary = stringResource(
            R.string.headunit_route_summary,
            nav.totalMeters.distanceText(),
            nav.totalSeconds.durationText(),
            clockLabel(now + nav.totalSeconds * 1000L, now),
        )
        Text(summary, color = UnitColors.muted, fontSize = 13.sp)
        if (nav.waitsForGo) Text(stringResource(R.string.headunit_say_go), color = UnitColors.muted, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (nav.waitsForGo) {
                    stringResource(R.string.headunit_start_navigation)
                } else {
                    stringResource(R.string.headunit_start_navigation_in, left)
                },
                color = UnitColors.screen,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(UnitColors.teal).clickable(onClick = onStart).padding(horizontal = 18.dp, vertical = 9.dp),
            )
            val cancel = stringResource(R.string.common_cancel)
            Text(cancel, color = Color.White, fontSize = 15.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(UnitColors.raised).clickable(onClick = onCancel).padding(horizontal = 18.dp, vertical = 9.dp))
        }
    }
}

/** Not navigating: what to say, or where the car arrived. */
@Composable
internal fun IdleNavCard(nav: NavState, ready: String?, modifier: Modifier = Modifier) {
    UnitCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (nav.status == NavStatus.ARRIVED) Icons.Filled.Flag else Icons.Filled.Navigation, contentDescription = null, tint = UnitColors.teal, modifier = Modifier.size(26.dp))
            Column {
                val title = when {
                    ready != null -> stringResource(R.string.headunit_nav_unavailable)
                    nav.status == NavStatus.ARRIVED ->
                        stringResource(R.string.headunit_arrived_at, nav.destination?.name.orEmpty())
                    else -> stringResource(R.string.headunit_where_to)
                }
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val hint = ready ?: stringResource(R.string.headunit_nav_hint)
                Text(hint, color = UnitColors.muted, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

/**
 * The navigation's quick actions, worded as AMap's stock buttons are: exit, overview, mute, pause while simulating
 * ([paused] null when not), and more (the settings) unless [onMore] is null.
 */
@Composable
internal fun NavActionBar(
    overview: Boolean,
    muted: Boolean,
    paused: Boolean?,
    onExit: () -> Unit,
    onOverview: () -> Unit,
    onMute: () -> Unit,
    onPause: () -> Unit,
    onMore: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(modifier.clip(RoundedCornerShape(16.dp)).background(UnitColors.card).padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        ActionItem(Icons.AutoMirrored.Filled.ExitToApp, stringResource(R.string.headunit_action_exit), onExit)
        val overviewLabel =
            stringResource(if (overview) R.string.headunit_action_exit_overview else R.string.headunit_action_overview)
        ActionItem(if (overview) Icons.Filled.MyLocation else Icons.Filled.Route, overviewLabel, onOverview)
        val muteLabel = stringResource(if (muted) R.string.headunit_action_muted else R.string.headunit_action_voice)
        val muteIcon = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp
        ActionItem(muteIcon, muteLabel, onMute)
        if (paused != null) {
            val pauseLabel =
                stringResource(if (paused) R.string.headunit_action_resume else R.string.headunit_action_pause)
            ActionItem(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, pauseLabel, onPause)
        }
        if (onMore != null) ActionItem(Icons.Filled.MoreHoriz, stringResource(R.string.headunit_action_more), onMore)
    }
}

@Composable
private fun ActionItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(28.dp))
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
    }
}

/** Off the car, as on AMap's stock screen: back to it at once, or a little closer or farther. */
@Composable
internal fun BrowseControls(onBack: () -> Unit, onZoom: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(24.dp)).background(CAR_BLUE).clickable(onClick = onBack).padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Navigation, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            val resume = stringResource(R.string.headunit_resume_navigation)
            Text(resume, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        ZoomButton(Icons.Filled.Remove, stringResource(R.string.headunit_zoom_out)) { onZoom(false) }
        ZoomButton(Icons.Filled.Add, stringResource(R.string.headunit_zoom_in)) { onZoom(true) }
    }
}

@Composable
private fun ZoomButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(UnitColors.card).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

/**
 * The route as a vertical bar on the map's edge, start at the bottom, as AMap's stock traffic bar (owner 2026-10-22:
 * 「有些大小也参考下」, its bar about 18 dp wide on this tablet): white-rimmed, the driven part grey, the road ahead
 * coloured by its traffic (teal while unknown), a dot for each stop ahead, the end on top, the car where it is now.
 */
@Composable
internal fun RouteProgressBar(nav: NavState, traffic: List<TrafficStretch> = emptyList(), modifier: Modifier = Modifier) {
    val total = nav.totalMeters.coerceAtLeast(1)
    val done = (1f - nav.remainingMeters.toFloat() / total).coerceIn(0f, 1f)
    val stops = nav.toWaypoints.map { done + (1f - done) * (it.meters.toFloat() / nav.remainingMeters.coerceAtLeast(1)).coerceIn(0f, 1f) }
    Canvas(modifier.width(PROGRESS_WIDTH)) {
        val x = size.width / 2
        val bar = 12.dp.toPx()
        // Inside the rounded ends, so the car and the end sit on the bar.
        val inset = bar / 2 + 2.dp.toPx()
        fun y(fraction: Float) = inset + (size.height - inset * 2) * (1f - fraction)
        drawLine(Color.White, Offset(x, y(0f)), Offset(x, y(1f)), bar + 5.dp.toPx(), StrokeCap.Round)
        drawLine(DRIVEN_GREY, Offset(x, y(0f)), Offset(x, y(done)), bar, StrokeCap.Round)
        drawLine(UnitColors.teal, Offset(x, y(done)), Offset(x, y(1f)), bar, StrokeCap.Round)
        // The road ahead coloured by its traffic, as AMap's bar is (its stretches run from the route's start).
        val routeMeters = traffic.sumOf { it.meters }.coerceAtLeast(1)
        var from = 0
        traffic.forEach { stretch ->
            val a = (from.toFloat() / routeMeters).coerceAtLeast(done)
            val b = ((from + stretch.meters).toFloat() / routeMeters).coerceAtMost(0.995f)
            if (b > a) drawLine(trafficColor(stretch.status), Offset(x, y(a)), Offset(x, y(b)), bar, StrokeCap.Butt)
            from += stretch.meters
        }
        stops.forEach {
            drawCircle(Color.White, 6.dp.toPx(), Offset(x, y(it)))
            drawCircle(UnitColors.teal, 3.5.dp.toPx(), Offset(x, y(it)))
        }
        drawCircle(Color.White, 9.dp.toPx(), Offset(x, y(1f)))
        drawCircle(PlaygroundColors.ember, 6.5.dp.toPx(), Offset(x, y(1f)))
        drawCarMark(Offset(x, y(done)))
    }
}

/** The car on the bar: AMap's blue arrow in a white disc. */
private fun DrawScope.drawCarMark(at: Offset) {
    drawCircle(Color.White, 11.dp.toPx(), at)
    val car = Path().apply {
        moveTo(at.x, at.y - 7.dp.toPx())
        lineTo(at.x + 5.5.dp.toPx(), at.y + 5.dp.toPx())
        lineTo(at.x, at.y + 2.dp.toPx())
        lineTo(at.x - 5.5.dp.toPx(), at.y + 5.dp.toPx())
        close()
    }
    drawPath(car, CAR_BLUE)
}

/** The road the car is on, at the bottom of the map. */
@Composable
internal fun RoadChip(road: String, modifier: Modifier = Modifier) {
    if (road.isBlank()) return
    Text(
        road,
        color = Color.White,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(UnitColors.card).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
internal fun UnitCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(UnitColors.card).padding(horizontal = 16.dp, vertical = 14.dp)) {
        content()
    }
}

/** The arrow for AMap's turn type (com.amap.api.navi.enums.IconType). */
internal fun turnIcon(type: Int): ImageVector = when (type) {
    2 -> Icons.Filled.TurnLeft
    3 -> Icons.Filled.TurnRight
    4 -> Icons.Filled.TurnSlightLeft
    5 -> Icons.Filled.TurnSlightRight
    6 -> Icons.Filled.TurnSharpLeft
    7 -> Icons.Filled.TurnSharpRight
    8 -> Icons.Filled.UTurnLeft
    19 -> Icons.Filled.UTurnRight
    10 -> Icons.Filled.Place
    15 -> Icons.Filled.Flag
    13 -> Icons.Filled.LocalGasStation
    11, 12, in 17..28 -> Icons.Filled.RoundaboutRight
    65 -> Icons.Filled.TurnSlightLeft
    66 -> Icons.Filled.TurnSlightRight
    else -> Icons.Filled.Straight
}

/** How far to the turn, the number and its unit apart, as AMap's panel sets them (「421」「米」). */
internal fun Int.stepDistanceParts(): Pair<String, Int> =
    if (this < 1000) {
        "$this" to R.string.headunit_unit_meters
    } else {
        "${(this / 100) / 10.0}".removeSuffix(".0") to R.string.headunit_unit_kilometers
    }

/** A distance as the screen shows it, 「800 米」「1.2 公里」; what the model reads is [km]'s. */
@Composable
internal fun Int.distanceText(): String =
    if (this < 1000) stringResource(R.string.headunit_meters, this)
    else stringResource(R.string.headunit_kilometers, "${(this / 100) / 10.0}".removeSuffix(".0"))

/** A duration as the screen shows it, 「25 分钟」「1 小时 5 分」; what the model reads is [duration]'s. */
@Composable
internal fun Int.durationText(): String {
    val minutes = (this + 59) / 60
    return when {
        minutes < 60 -> stringResource(R.string.headunit_minutes, minutes)
        minutes % 60 == 0 -> stringResource(R.string.headunit_hours, minutes / 60)
        else -> stringResource(R.string.headunit_hours_minutes, minutes / 60, minutes % 60)
    }
}

/** A time as the screen shows it: 「21:34」 today, 「明天 07:05」 after midnight ([clockParts]). */
@Composable
internal fun clockLabel(at: Long, now: Long): String {
    val (time, tomorrow) = clockParts(at, now)
    return if (tomorrow) stringResource(R.string.headunit_tomorrow_at, time) else time
}

/** When it gets there, as the screen says it: 「21:34到」. */
@Composable
internal fun etaText(at: Long, now: Long): String = stringResource(R.string.headunit_eta, clockLabel(at, now))

private val TURN_ARROW = 64.dp
internal val PROGRESS_WIDTH = 26.dp
/** From the screen's right edge to the bar's left: the bar about 10 dp in from the edge, as AMap's. */
internal val PROGRESS_INSET = PROGRESS_WIDTH + 6.dp
private val DRIVEN_GREY = Color(0xFFB9BEC6)
private val CAR_BLUE = Color(0xFF2F7BFF)
private val EXIT_GREEN = Color(0xFF0E8A43)

private const val COUNT_TICK_MS = 250L

/** The head unit's look: near-black glass over the map, teal for the route. */
internal object UnitColors {
    val screen = Color(0xFF07090C)
    val panel = Color(0xFF11161D)
    val card = Color(0xE6151B23)
    val raised = Color(0xFF1C232D)
    val muted = Color(0xFF8D97A6)
    val teal = Color(0xFF2FC8C2)
}
