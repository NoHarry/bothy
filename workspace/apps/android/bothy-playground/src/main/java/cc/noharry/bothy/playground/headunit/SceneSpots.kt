package cc.noharry.bothy.playground.headunit
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import cc.noharry.bothy.playground.cabin.Seat
import cc.noharry.bothy.playground.cabin.seats
import cc.noharry.bothy.playground.home.Room
import cc.noharry.bothy.playground.home.line

/**
 * Where things sit in the two scene pictures, as fractions of the picture (measured on scene_cabin.webp and
 * scene_home.webp): what the overlays draw on. A new picture needs these measured again.
 */
internal object CabinSpots {
    const val ASPECT = 744f / 1200f
    val seats = mapOf(
        Seat.DRIVER to Offset(0.312f, 0.415f),
        Seat.PASSENGER to Offset(0.693f, 0.415f),
        Seat.REAR_LEFT to Offset(0.316f, 0.656f),
        Seat.REAR_RIGHT to Offset(0.690f, 0.656f),
    )
    /** A seat's radius, as a fraction of the picture's width. */
    const val SEAT_RADIUS = 0.103f
    val windows = mapOf(
        Seat.DRIVER to (Offset(0.108f, 0.207f) to Offset(0.108f, 0.398f)),
        Seat.PASSENGER to (Offset(0.892f, 0.207f) to Offset(0.892f, 0.398f)),
        Seat.REAR_LEFT to (Offset(0.097f, 0.421f) to Offset(0.097f, 0.663f)),
        Seat.REAR_RIGHT to (Offset(0.903f, 0.421f) to Offset(0.903f, 0.663f)),
    )
    /** The ambient strips across the dashboard; the doors' own are in [CabinParts.doorStrips]. */
    val dashStrips = listOf(
        Offset(0.200f, 0.187f) to Offset(0.392f, 0.187f),
        Offset(0.611f, 0.187f) to Offset(0.812f, 0.187f),
    )
    val cargo = Rect(0.2f, 0.79f, 0.8f, 0.875f)
    val tailgate = Rect(0.17f, 0.885f, 0.83f, 0.975f)
    val screen = Rect(0.396f, 0.161f, 0.610f, 0.209f)
    val betweenRows = Offset(0.5f, 0.545f)
    /** The cabin floor the air conditioning breathes over. */
    val interior = Rect(0.17f, 0.2f, 0.83f, 0.78f)
    val hood = Offset(0.5f, 0.075f)
    val lockChip = Offset(0.5f, 0.118f)

    /** The seat under [at] (a point as fractions of the picture), if any. */
    fun seatAt(at: Offset): Seat? = seats.entries.firstOrNull { (_, c) -> widthDistance(at, c) < SEAT_RADIUS }?.key

    /** The window [at] is on, within a finger's reach of its glass. */
    fun windowAt(at: Offset): Seat? = windows.entries.firstOrNull { (_, line) ->
        val (top, bottom) = line
        kotlin.math.abs(at.x - top.x) < WINDOW_REACH && at.y in top.y..bottom.y
    }?.key

    fun trunkAt(at: Offset): Boolean = cargo.contains(at) || tailgate.contains(at)

    private fun widthDistance(a: Offset, b: Offset): Float {
        val dx = a.x - b.x
        val dy = (a.y - b.y) / ASPECT
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private const val WINDOW_REACH = 0.05f
}

internal object HomeSpots {
    const val ASPECT = 1200f / 970f
    val rooms = mapOf(
        Room.LIVING to Rect(0.034f, 0.027f, 0.492f, 0.435f),
        Room.BEDROOM to Rect(0.508f, 0.027f, 0.974f, 0.435f),
        Room.KITCHEN to Rect(0.034f, 0.447f, 0.492f, 0.847f),
        Room.STUDY to Rect(0.508f, 0.447f, 0.974f, 0.847f),
    )
    val curtains = mapOf(
        Room.LIVING to (Offset(0.173f, 0.023f) to Offset(0.376f, 0.023f)),
        Room.BEDROOM to (Offset(0.622f, 0.023f) to Offset(0.825f, 0.023f)),
        Room.KITCHEN to (Offset(0.152f, 0.847f) to Offset(0.344f, 0.847f)),
        Room.STUDY to (Offset(0.656f, 0.847f) to Offset(0.840f, 0.847f)),
    )
    val door = Rect(0.446f, 0.857f, 0.538f, 0.952f)
    /** Where someone in each room is drawn: on the sofa, the bed, at the table, at the desk. */
    val people = mapOf(
        Room.LIVING to Offset(0.265f, 0.36f),
        Room.BEDROOM to Offset(0.713f, 0.25f),
        Room.KITCHEN to Offset(0.208f, 0.72f),
        Room.STUDY to Offset(0.674f, 0.64f),
    )
    val dock = Offset(0.127f, 0.405f)
    /** The vacuum's side, as a fraction of the picture's width. */
    const val VACUUM_SIZE = 0.05f
    /** A person's side in a room, as a fraction of the picture's width. */
    const val PERSON_SIZE = 0.075f

    fun roomAt(at: Offset): Room? = rooms.entries.firstOrNull { it.value.contains(at) }?.key

    /** The curtain rail [at] is on, within a finger's reach. */
    fun curtainAt(at: Offset): Room? = curtains.entries.firstOrNull { (_, rail) ->
        kotlin.math.abs(at.y - rail.first.y) < RAIL_REACH && at.x in rail.first.x..rail.second.x
    }?.key

    fun doorAt(at: Offset): Boolean = door.contains(at)

    private const val RAIL_REACH = 0.035f
}

internal fun Offset.on(size: Size): Offset = Offset(x * size.width, y * size.height)

internal fun Rect.on(size: Size): Rect = Rect(left * size.width, top * size.height, right * size.width, bottom * size.height)

/** The playground's look: Bothy's dusk palette, dark so lights and glows read from across a room. */
internal object PlaygroundColors {
    val bg = Color(0xFF0E1210)
    val panel = Color(0xFF141917)
    val elevated = Color(0xFF1B221E)
    val border = Color(0xFF29322D)
    val text = Color(0xFFE8E5DD)
    val muted = Color(0xFFA29D92)
    val faint = Color(0xFF6E6A62)
    val sage = Color(0xFF8CC7A5)
    val ember = Color(0xFFF2925A)
    val glow = Color(0xFFF5C26B)
    val cool = Color(0xFF7FC8FF)
    val heat = Color(0xFFFF8A4C)
    val curtain = Color(0xFFC65A3A)
    val night = Color(0xFF0B1220)
    val chip = Color(0xE6141917)
}
