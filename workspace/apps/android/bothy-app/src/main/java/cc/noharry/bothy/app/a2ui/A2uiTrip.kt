package cc.noharry.bothy.app.a2ui
import androidx.a2ui.compose.runtime.A2uiComponentProperties
import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.runtime.A2uiProperty
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.ui.icon
import cc.noharry.bothy.app.image.rememberPicture
import cc.noharry.bothy.app.overlay.OverlayPalette

/**
 * A trip card's pieces (owner 2026-10-17: 信息量大的时候卡片扩大，以更人类友好的形式展示；现在这个布局还是不好看):
 * a `Ticket` per direction with the times large and the line between them, a `Stop` per place of a day with its
 * time in front, and a `Stay` per hotel with its price set off. Text is capped so a day reads at a glance.
 */
internal class A2uiTicket(private val carLook: Boolean) : A2uiComponent {
    override val name: String = TICKET_COMPONENT
    override val description: String = "One way of a trip: which leg, the flight, train or drive, departure and arrival times, the route and a note."
    override val properties: List<A2uiProperty<*>> = listOf(LEG, SERVICE, TIMES, ROUTE, NOTE, MODE)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val times = properties[TIMES].orEmpty()
        val (depart, arrive) = TICKET_TIMES.find(times)?.destructured?.let { (from, to) -> from to to } ?: (times to null)
        val style = tripStyle(carLook)
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                properties[LEG]?.takeIf(String::isNotBlank)?.let { TripChip(it, carLook) }
                Text(properties[SERVICE].orEmpty(), style = style.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(depart, style = style.large, maxLines = 1)
                if (arrive != null) {
                    Row(Modifier.weight(1f).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).height(2.dp).background(style.line))
                        TicketMode(properties[MODE], style.secondary.color)
                        Box(Modifier.weight(1f).height(2.dp).background(style.line))
                    }
                    Text(arrive, style = style.large, maxLines = 1)
                }
            }
            properties[ROUTE]?.takeIf(String::isNotBlank)?.let { Text(it, style = style.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            properties[NOTE]?.takeIf(String::isNotBlank)?.let { Text(it, style = style.faint, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }

    private companion object {
        val LEG = A2uiProperty.string("leg", false, "去程, 回程 …")
        val SERVICE = A2uiProperty.string("service", false, "Flight or train and carrier, such as 9C8819 春秋航空.")
        val TIMES = A2uiProperty.string("times", false, "Departure → arrival, such as 07:50 → 10:55.")
        val ROUTE = A2uiProperty.string("route", false, "From and to, such as 虹桥T1 → 天府T2.")
        val NOTE = A2uiProperty.string("note", false, "Price, other options.")
        val MODE = A2uiProperty.string("mode", false, "flight, train or drive: the mark on the line between the times.")
    }
}

/** One place of a day: its time in front, then the name and a short note. */
internal class A2uiStop(private val carLook: Boolean) : A2uiComponent {
    override val name: String = STOP_COMPONENT
    override val description: String = "One stop of a day: time, name and a short note."
    override val properties: List<A2uiProperty<*>> = listOf(TIME, TITLE, NOTE)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val style = tripStyle(carLook)
        Row(modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(properties[TIME].orEmpty(), style = style.time, maxLines = 2, modifier = Modifier.width(STOP_TIME_WIDTH))
            Column(Modifier.weight(1f)) {
                Text(properties[TITLE].orEmpty(), style = style.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                properties[NOTE]?.takeIf(String::isNotBlank)?.let { Text(it, style = style.faint, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }

    private companion object {
        val TIME = A2uiProperty.string("time", false, "When, such as 14:00–16:30 or 晚上.")
        val TITLE = A2uiProperty.string("title", true, "The place.")
        val NOTE = A2uiProperty.string("note", false, "Ticket, booking, what to see.")
    }
}

/** One hotel: its picture as a thumbnail beside the name, the price set off, a short note. */
internal class A2uiStay(private val carLook: Boolean) : A2uiComponent {
    override val name: String = STAY_COMPONENT
    override val description: String = "One hotel: thumbnail, name, price and a short note."
    override val properties: List<A2uiProperty<*>> = listOf(TITLE, PRICE, NOTE, IMAGE)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val style = tripStyle(carLook)
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            properties[IMAGE]?.takeIf(String::isNotBlank)?.let { StayThumbnail(it) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(properties[TITLE].orEmpty(), style = style.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                properties[PRICE]?.takeIf(String::isNotBlank)?.let { Text(it, style = style.price, maxLines = 1) }
                properties[NOTE]?.takeIf(String::isNotBlank)?.let { Text(it, style = style.faint, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }

    private companion object {
        val TITLE = A2uiProperty.string("title", true, "The hotel.")
        val PRICE = A2uiProperty.string("price", false, "Such as 约 500–800 元/晚.")
        val NOTE = A2uiProperty.string("note", false, "Area, metro, why.")
        val IMAGE = A2uiProperty.string("image", false, "A picture saved on the device or a public https photo.")
    }
}

@Composable
private fun StayThumbnail(reference: String) {
    val pixels = with(LocalDensity.current) { STAY_THUMBNAIL_WIDTH.roundToPx() }
    val picture by rememberPicture(reference, pixels)
    val frame = Modifier.size(STAY_THUMBNAIL_WIDTH, STAY_THUMBNAIL_HEIGHT).clip(RoundedCornerShape(10.dp))
    picture?.let { Image(it, null, frame, contentScale = ContentScale.Crop) } ?: Box(frame.background(LocalContentColor.current.copy(alpha = 0.06f)))
}

/** The way between the times: a plane heading on, a train or a device, as line icons (an emoji drew a cut-off nose). */
@Composable
private fun TicketMode(mode: String?, tint: Color) {
    val (icon, turn) = when (mode) {
        TripMode.TRAIN.wire -> TRAIN_ICON to 0f
        TripMode.DRIVE.wire -> CAR_ICON to 0f
        else -> PLANE_ICON to 90f
    }
    Icon(icon, null, Modifier.padding(horizontal = 6.dp).size(MODE_ICON_SIZE).rotate(turn), tint = tint)
}

private fun materialIcon(path: String): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
        .build()

@Composable
private fun TripChip(text: String, carLook: Boolean) {
    val shape = RoundedCornerShape(50)
    val chip = if (carLook) Modifier.clip(shape).background(OverlayPalette.signature) else Modifier.clip(shape).background(MaterialTheme.colorScheme.primary)
    val color = if (carLook) OverlayPalette.onSignature else MaterialTheme.colorScheme.onPrimary
    Text(text, style = TextStyle(color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold), modifier = chip.padding(horizontal = 10.dp, vertical = 3.dp))
}

private class TripStyle(
    val large: TextStyle,
    val title: TextStyle,
    val time: TextStyle,
    val price: TextStyle,
    val secondary: TextStyle,
    val faint: TextStyle,
    val line: Color,
)

@Composable
private fun tripStyle(carLook: Boolean): TripStyle {
    if (carLook) {
        return TripStyle(
            large = TextStyle(color = OverlayPalette.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold),
            title = TextStyle(color = OverlayPalette.text, fontSize = 17.sp, fontWeight = FontWeight.Medium),
            time = TextStyle(color = OverlayPalette.glow, fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp),
            price = TextStyle(brush = OverlayPalette.signature, fontSize = 20.sp, fontWeight = FontWeight.Medium),
            secondary = TextStyle(color = OverlayPalette.secondary, fontSize = 14.sp),
            faint = TextStyle(color = OverlayPalette.faint, fontSize = 13.sp, lineHeight = 18.sp),
            line = OverlayPalette.tileEdge,
        )
    }
    val content = LocalContentColor.current
    return TripStyle(
        large = MaterialTheme.typography.headlineSmall,
        title = MaterialTheme.typography.titleSmall,
        time = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.primary),
        price = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.primary),
        secondary = MaterialTheme.typography.bodySmall,
        faint = MaterialTheme.typography.bodySmall.copy(color = content.copy(alpha = 0.6f)),
        line = content.copy(alpha = 0.2f),
    )
}

internal const val TICKET_COMPONENT = "Ticket"
internal const val STOP_COMPONENT = "Stop"
internal const val STAY_COMPONENT = "Stay"
/** Material Flight, Train and DirectionsCar. */
private val PLANE_ICON by lazy {
    materialIcon("M21 16v-2l-8-5V3.5c0-.83-.67-1.5-1.5-1.5S10 2.67 10 3.5V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5l8 2.5z")
}
private val TRAIN_ICON by lazy {
    materialIcon(
        "M12 2c-4 0-8 .5-8 4v9.5C4 17.43 5.57 19 7.5 19L6 20.5v.5h2.23l2-2H14l2 2h2v-.5L16.5 19c1.93 0 3.5-1.57 3.5-3.5V6c0-3.5-3.58-4-8-4z" +
            "M7.5 17c-.83 0-1.5-.67-1.5-1.5S6.67 14 7.5 14s1.5.67 1.5 1.5S8.33 17 7.5 17zm3.5-7H6V6h5v4zm2 0V6h5v4h-5z" +
            "m3.5 7c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5z",
    )
}
private val CAR_ICON by lazy {
    materialIcon(
        "M18.92 6.01C18.72 5.42 18.16 5 17.5 5h-11c-.66 0-1.21.42-1.42 1.01L3 12v8c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1h12v1" +
            "c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-8l-2.08-5.99zM6.5 16c-.83 0-1.5-.67-1.5-1.5S5.67 13 6.5 13s1.5.67 1.5 1.5S7.33 16 6.5 16z" +
            "m11 0c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zM5 11l1.5-4.5h11L19 11H5z",
    )
}
private val MODE_ICON_SIZE = 20.dp
private val TICKET_TIMES = Regex("""(\d{1,2}:\d{2})\s*(?:→|->|—|–|-|~|至)\s*(\d{1,2}:\d{2})""")
private val STOP_TIME_WIDTH = 78.dp
private val STAY_THUMBNAIL_WIDTH = 104.dp
private val STAY_THUMBNAIL_HEIGHT = 72.dp
