package cc.noharry.bothy.playground

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A point on the map, in AMap's (GCJ-02) coordinates. */
data class GeoPoint(val lat: Double, val lng: Double) {
    /** Great-circle distance to [other], metres. */
    fun metersTo(other: GeoPoint): Double {
        val dLat = Math.toRadians(other.lat - lat)
        val dLng = Math.toRadians(other.lng - lng)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat)) * cos(Math.toRadians(other.lat)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    private companion object {
        const val EARTH_RADIUS_M = 6_371_000.0
    }
}

/** A place found by search, or given by name and coordinates; what a route is made of. */
data class Place(
    val name: String,
    val point: GeoPoint,
    val poiId: String = "",
    val address: String = "",
    val rating: String = "",
    val openToday: String = "",
    val tel: String = "",
    /** From where the search was made (current location or the route), metres; -1 when unknown. */
    val distanceMeters: Int = -1,
)

enum class RoutePreference(val id: String, val label: String) {
    RECOMMENDED("recommended", "推荐"),
    AVOID_CONGESTION("avoid_congestion", "躲避拥堵"),
    NO_HIGHWAY("no_highway", "不走高速"),
    SAVE_MONEY("save_money", "少收费"),
    HIGHWAY_FIRST("highway_first", "高速优先"),
}

enum class NavStatus(val id: String, val label: String) {
    IDLE("idle", "没有导航"),
    /** The route is planned and shown whole on the map; driving it starts at [NavState.startsAt]. */
    PLANNED("planned", "路线已规划"),
    NAVIGATING("navigating", "导航中"),
    ARRIVED("arrived", "已到达"),
}

/** What is left to one waypoint still ahead, as the navigation reports it. */
data class ViaProgress(val meters: Int, val seconds: Int)

/**
 * The navigation as the car knows it: where it goes, through which stops still ahead (a stop leaves the list when the car
 * reaches it), how it routes, and the live readings while it drives. [simulated] drives the route without moving.
 */
data class NavState(
    val status: NavStatus = NavStatus.IDLE,
    val destination: Place? = null,
    val waypoints: List<Place> = emptyList(),
    val preference: RoutePreference = RoutePreference.RECOMMENDED,
    val simulated: Boolean = true,
    val totalMeters: Int = 0,
    val totalSeconds: Int = 0,
    val remainingMeters: Int = 0,
    val remainingSeconds: Int = 0,
    val toWaypoints: List<ViaProgress> = emptyList(),
    val currentRoad: String = "",
    val nextRoad: String = "",
    val stepMeters: Int = 0,
    val turnIcon: Int = 0,
    val speedKmh: Int = 0,
    /** Traffic lights still ahead on the route, as AMap counts them. */
    val lightsLeft: Int = 0,
    val reachedWaypoints: List<String> = emptyList(),
    /** When a planned route starts driving, epoch millis; 0 when not waiting. */
    val startsAt: Long = 0L,
    /** A route only planned (「规划下路线」): it stays shown, with no count, until the user's word to go. */
    val waitsForGo: Boolean = false,
    /** What else AMap tells while driving, as its stock screen shows it ([Guide]). */
    val guide: Guide = Guide(),
) {
    /** A route is in hand: planned and shown, or being driven. */
    val active: Boolean get() = status == NavStatus.NAVIGATING || status == NavStatus.PLANNED

    val previewing: Boolean get() = status == NavStatus.PLANNED

    val driving: Boolean get() = status == NavStatus.NAVIGATING
}

/**
 * What AMap tells besides the turn while driving, as its stock screen shows it (owner 2026-10-22: 「各种指示信息有没有用到
 * 位，相比公版」): the limit at the camera ahead, an average-speed section, the exit to take and where it leads, the next
 * service area or toll station, a weak GPS. Lanes and the junction's picture are AMap's own drawings ([Guidance]).
 */
data class Guide(
    /** The speed limit of the camera ahead, km/h; 0 when none is known. */
    val limitKmh: Int = 0,
    val cameraMeters: Int = 0,
    /** In an average-speed section: the average so far, the limit and what is left of it; all 0 outside one. */
    val sectionAverageKmh: Int = 0,
    val sectionLimitKmh: Int = 0,
    val sectionLeftMeters: Int = 0,
    /** The exit's number or name and where it leads (「出口 · 南山」), when the next turn is one. */
    val exit: String = "",
    val toward: String = "",
    /** The next service area or toll station ahead. */
    val stopName: String = "",
    val stopIsToll: Boolean = false,
    val stopMeters: Int = 0,
    val gpsWeak: Boolean = false,
) {
    /** Over the limit ahead, by more than AMap's own leeway. */
    fun over(speedKmh: Int): Boolean = limitKmh > 0 && speedKmh > limitKmh + OVER_LEEWAY_KMH
}

private const val OVER_LEEWAY_KMH = 5

/** What a stop is asked for as: a name to search, coordinates, or both (coordinates win). */
data class PlaceSpec(val name: String, val point: GeoPoint? = null, val poiId: String = "")

/**
 * The stops in an order that saves driving: from [start] through every stop to [end] (kept last), the shortest tour by
 * straight-line distance; exact for up to [EXACT_ORDER_LIMIT] stops to place (a demo's handful), nearest-next beyond.
 * [keepFirst] keeps the first stop where it is, as for 「先去莲花山」. Straight lines, so a guide, not a guarantee.
 */
internal fun orderedByProximity(start: GeoPoint, stops: List<Place>, end: GeoPoint, keepFirst: Boolean): List<Place> {
    if (stops.size < 2) return stops
    val fixed = if (keepFirst) listOf(stops.first()) else emptyList()
    val free = stops.drop(fixed.size)
    val from = fixed.lastOrNull()?.point ?: start
    val ordered = if (free.size <= EXACT_ORDER_LIMIT) shortestTour(from, free, end) else nearestNext(from, free)
    return fixed + ordered
}

private fun tourLength(from: GeoPoint, stops: List<Place>, end: GeoPoint): Double {
    var at = from
    var total = 0.0
    stops.forEach { total += at.metersTo(it.point); at = it.point }
    return total + at.metersTo(end)
}

private fun shortestTour(from: GeoPoint, stops: List<Place>, end: GeoPoint): List<Place> {
    var best = stops
    var bestLength = tourLength(from, stops, end)
    fun visit(done: List<Place>, left: List<Place>) {
        if (left.isEmpty()) {
            val length = tourLength(from, done, end)
            if (length < bestLength) {
                best = done
                bestLength = length
            }
            return
        }
        left.forEach { next -> visit(done + next, left - next) }
    }
    visit(emptyList(), stops)
    return best
}

private fun nearestNext(from: GeoPoint, stops: List<Place>): List<Place> {
    val left = stops.toMutableList()
    val ordered = mutableListOf<Place>()
    var at = from
    while (left.isNotEmpty()) {
        val next = left.minBy { at.metersTo(it.point) }
        ordered += next
        left -= next
        at = next.point
    }
    return ordered
}

/** Up to this many stops to place are ordered exactly (8! tours); more fall back to nearest-next. */
internal const val EXACT_ORDER_LIMIT = 8

/** [stops] with [place] put at [position] (1-based among the stops; past the end or null puts it last). */
internal fun List<Place>.withStopAt(place: Place, position: Int?): List<Place> {
    val index = ((position ?: (size + 1)) - 1).coerceIn(0, size)
    return toMutableList().apply { add(index, place) }
}

/** The 0-based index of the stop [index] (1-based) or [name] names, or null. */
internal fun List<Place>.stopIndex(index: Int?, name: String?): Int? {
    if (index != null) return (index - 1).takeIf { it in indices }
    val said = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return indexOfFirst { it.name == said }.takeIf { it >= 0 }
        ?: indexOfFirst { it.name.contains(said) || said.contains(it.name) }.takeIf { it >= 0 }
}

/** The weather where the car is, as the head unit's weather card shows it. */
data class WeatherReport(
    val city: String,
    val weather: String,
    val temperature: String,
    val humidity: String,
    val wind: String,
    val days: List<DayWeather>,
    val at: Long = System.currentTimeMillis(),
)

data class DayWeather(val week: String, val weather: String, val high: String, val low: String)

/**
 * What the map is to show, in the order the navigation finds it: places found (pinned, the map moving to them, [adding]
 * to the pins already there or in their place; [ending] for the trip's destination), then the route planned, shown whole.
 */
sealed interface MapCue {
    data class Found(val places: List<Place>, val adding: Boolean, val ending: Boolean = false) : MapCue

    data object Route : MapCue
}
