package cc.noharry.bothy.playground

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Toll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.amap.api.navi.model.AMapLaneInfo
import com.amap.api.navi.model.AMapNaviCross
import com.amap.api.navi.view.DriveWayView

/** The lanes at the junction ahead, as AMap draws them in its stock screen, on a dark strip ([Guidance.lanes]). */
@Composable
internal fun LaneBar(lanes: AMapLaneInfo, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(UnitColors.card).padding(horizontal = 10.dp, vertical = 6.dp)) {
        AndroidView(factory = { DriveWayView(it) }, update = { view -> view.loadDriveWayBitmap(lanes) })
    }
}

/**
 * The junction ahead as AMap pictures it ([Guidance.cross]), as its stock screen shows it (owner 2026-10-22: 「有些大小也
 * 参考下」): big, over the map's left part, the turn shrunk to a line on top; the route's cards give it their place.
 */
@Composable
internal fun JunctionCard(nav: NavState, cross: AMapNaviCross, modifier: Modifier = Modifier) {
    val picture = cross.bitmap ?: return
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(UnitColors.card)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(turnIcon(nav.turnIcon), contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
            val (number, unit) = nav.stepMeters.stepDistanceParts()
            Text(number, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(unit, color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
            Text(nav.nextRoad.ifEmpty { "前方道路" }, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Image(
            picture.asImageBitmap(),
            contentDescription = "路口放大图",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(picture.width.toFloat() / picture.height.coerceAtLeast(1)),
        )
    }
}

/** The next service area or toll station ahead and how far ([Guide.stopName]). */
@Composable
internal fun RoadsideCard(guide: Guide, modifier: Modifier = Modifier) {
    UnitCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (guide.stopIsToll) Icons.Filled.Toll else Icons.Filled.LocalGasStation, contentDescription = null, tint = UnitColors.teal, modifier = Modifier.size(20.dp))
            Text("${if (guide.stopIsToll) "收费站" else "服务区"} · ${guide.stopName}", color = Color.White, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Text(guide.stopMeters.km(), color = UnitColors.muted, fontSize = 13.sp)
        }
    }
}

/** The limit ahead as a road sign: a red ring around the number. */
@Composable
internal fun LimitSign(kmh: Int) {
    Box(
        Modifier.size(36.dp).background(Color.White, CircleShape).border(3.5.dp, LIMIT_RED, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text("$kmh", color = Color.Black, fontSize = if (kmh >= 100) 13.sp else 15.sp, fontWeight = FontWeight.Bold) }
}

/** How a stretch of the route flows, coloured as AMap's traffic bar colours it. */
internal fun trafficColor(status: Int): Color = when (status) {
    1 -> Color(0xFF34C759)
    2 -> Color(0xFFFFC72C)
    3 -> Color(0xFFE5483C)
    4 -> Color(0xFF9C1F1A)
    else -> UnitColors.teal
}

internal val LIMIT_RED = Color(0xFFE5483C)

/** How much more of the map's left the junction's picture takes than the route's column: a column and its gap. */
internal fun junctionExtra(column: Dp, gap: Dp): Dp = column + gap
