package cc.noharry.bothy.playground

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
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.RoundaboutRight
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Straight
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** The next manoeuvre: the turn arrow, how far, onto which road; then what is left of the whole route. */
@Composable
internal fun TurnCard(nav: NavState, now: Long, modifier: Modifier = Modifier) {
    UnitCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(turnIcon(nav.turnIcon), contentDescription = null, tint = Color.White, modifier = Modifier.size(52.dp))
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(nav.stepMeters.stepDistance(), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(" 进入", color = UnitColors.muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 5.dp))
                }
                Text(nav.nextRoad.ifEmpty { "前方道路" }, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("${nav.remainingSeconds.duration()} · ${nav.remainingMeters.km()}", color = UnitColors.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("${clockText(now + nav.remainingSeconds * 1000L, now)}到", color = UnitColors.muted, fontSize = 13.sp)
        }
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
            val more = if (nav.waypoints.size > 1) " · 后面还有 ${nav.waypoints.size - 1} 站" else if (next != null) " · 然后到终点" else " · 终点"
            Text(left.meters.km() + more, color = UnitColors.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("${clockText(now + left.seconds * 1000L, now)}到", color = UnitColors.muted, fontSize = 13.sp)
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
        Text("路线已规划", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("全程 ${nav.totalMeters.km()} · 约 ${nav.totalSeconds.duration()} · ${clockText(now + nav.totalSeconds * 1000L, now)}到", color = UnitColors.muted, fontSize = 13.sp)
        if (nav.waitsForGo) Text("说「出发」就走", color = UnitColors.muted, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (nav.waitsForGo) "开始导航" else "开始导航 ${left}s",
                color = UnitColors.screen,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(UnitColors.teal).clickable(onClick = onStart).padding(horizontal = 18.dp, vertical = 9.dp),
            )
            Text("取消", color = Color.White, fontSize = 15.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(UnitColors.raised).clickable(onClick = onCancel).padding(horizontal = 18.dp, vertical = 9.dp))
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
                    ready != null -> "导航还不能用"
                    nav.status == NavStatus.ARRIVED -> "已到达「${nav.destination?.name}」"
                    else -> "想去哪？"
                }
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ready ?: "对小博说「导航去深圳湾公园，路上经过南山书城」", color = UnitColors.muted, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

/** The navigation's quick actions, as on the head unit: exit, overview, mute, and more (the settings) unless [onMore] is null. */
@Composable
internal fun NavActionBar(overview: Boolean, muted: Boolean, onExit: () -> Unit, onOverview: () -> Unit, onMute: () -> Unit, onMore: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(16.dp)).background(UnitColors.card).padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        ActionItem(Icons.AutoMirrored.Filled.ExitToApp, "退出导航", onExit)
        ActionItem(if (overview) Icons.Filled.MyLocation else Icons.Filled.Route, if (overview) "回到车辆" else "查看全览", onOverview)
        ActionItem(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, if (muted) "已静音" else "播报中", onMute)
        if (onMore != null) ActionItem(Icons.Filled.MoreHoriz, "更多", onMore)
    }
}

@Composable
private fun ActionItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp))
        Text(label, color = UnitColors.muted, fontSize = 11.sp)
    }
}

/**
 * The route as a vertical bar on the map's edge, start at the bottom: driven part dim, the rest teal, a dot for each stop
 * ahead, a flag at the end, the car where it is now.
 */
@Composable
internal fun RouteProgressBar(nav: NavState, modifier: Modifier = Modifier) {
    val total = nav.totalMeters.coerceAtLeast(1)
    val done = (1f - nav.remainingMeters.toFloat() / total).coerceIn(0f, 1f)
    val stops = nav.toWaypoints.map { done + (1f - done) * (it.meters.toFloat() / nav.remainingMeters.coerceAtLeast(1)).coerceIn(0f, 1f) }
    Canvas(modifier.width(18.dp)) {
        val x = size.width / 2
        fun y(fraction: Float) = size.height * (1f - fraction)
        val bar = 6.dp.toPx()
        drawLine(Color.White.copy(alpha = 0.18f), Offset(x, y(0f)), Offset(x, y(done)), bar, StrokeCap.Round)
        drawLine(UnitColors.teal, Offset(x, y(done)), Offset(x, y(1f)), bar, StrokeCap.Round)
        stops.forEach {
            drawCircle(Color.White, 6.dp.toPx(), Offset(x, y(it)))
            drawCircle(UnitColors.teal, 3.5.dp.toPx(), Offset(x, y(it)))
        }
        drawCircle(PlaygroundColors.ember, 6.dp.toPx(), Offset(x, y(1f)))
        val car = Path().apply {
            moveTo(x, y(done) - 9.dp.toPx())
            lineTo(x + 7.dp.toPx(), y(done) + 6.dp.toPx())
            lineTo(x, y(done) + 2.dp.toPx())
            lineTo(x - 7.dp.toPx(), y(done) + 6.dp.toPx())
            close()
        }
        drawPath(car, Color.White)
        drawPath(car, UnitColors.teal, style = Stroke(1.5.dp.toPx()))
    }
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
private fun turnIcon(type: Int): ImageVector = when (type) {
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

private fun Int.stepDistance(): String = if (this < 1000) "$this 米" else "${(this / 100) / 10.0} 公里".replace(".0 ", " ")

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
