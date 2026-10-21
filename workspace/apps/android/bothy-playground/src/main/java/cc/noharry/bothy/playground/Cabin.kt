package cc.noharry.bothy.playground

import kotlin.math.roundToInt

/** The four seats; the windows sit at the same four places. */
enum class Seat(val id: String, val label: String) {
    DRIVER("driver", "主驾"),
    PASSENGER("passenger", "副驾"),
    REAR_LEFT("rear_left", "左后"),
    REAR_RIGHT("rear_right", "右后"),
}

enum class ClimateMode(val id: String, val label: String) {
    AUTO("auto", "自动"),
    COOL("cool", "制冷"),
    HEAT("heat", "制热"),
    VENT("vent", "通风"),
}

/** Heating and ventilation never run together on one seat: turning one on turns the other off. */
data class SeatComfort(val heat: Int = 0, val ventilation: Int = 0)

data class CabinClimate(
    val on: Boolean = false,
    val driverTemp: Double = 24.0,
    val passengerTemp: Double = 24.0,
    val fan: Int = 3,
    val mode: ClimateMode = ClimateMode.AUTO,
)

data class AmbientLight(val on: Boolean = true, val colorName: String = "暖白", val color: Long = 0xFFFFE2B8, val brightness: Int = 60)

data class Media(val playing: Boolean = false, val title: String = "", val artist: String = "", val volume: Int = 12)

/** The drive the playground runs when 模拟行驶 is on: speed, battery and the weather outside. */
data class Drive(val moving: Boolean = false, val speedKmh: Double = 0.0, val batteryPct: Double = 78.0, val outsideTempC: Double = 31.0)

/**
 * A fictional compact EV, parked in the sun at first: warm inside, everything off but the ambient light, the driver
 * alone in it and the one speaking. [speaker] is set in the playground; a real car would tell it from its microphones.
 */
data class Cabin(
    val climate: CabinClimate = CabinClimate(),
    val seats: Map<Seat, SeatComfort> = Seat.entries.associateWith { SeatComfort() },
    val windows: Map<Seat, Int> = Seat.entries.associateWith { 0 },
    val ambient: AmbientLight = AmbientLight(),
    val media: Media = Media(),
    val nav: NavState = NavState(),
    val doorsLocked: Boolean = true,
    val insideTempC: Double = 30.0,
    val drive: Drive = Drive(),
    val occupants: Set<Seat> = setOf(Seat.DRIVER),
    val speaker: Seat? = Seat.DRIVER,
    /** The doors standing open (they sit beside the seats of the same name). */
    val doorsOpen: Set<Seat> = emptySet(),
    val trunkOpen: Boolean = false,
    /** The centre screen, 0 (off) to 100. */
    val screenBrightness: Int = 70,
) {
    /** Moving: doors and the trunk stay shut. */
    val moving: Boolean get() = drive.moving && drive.speedKmh > 0.0

    /** Everyone aboard but the driver. */
    val passengers: List<Seat> get() = Seat.entries.filter { it in occupants && it != Seat.DRIVER }

    /** [seat] taken or left; the speaker who leaves hands the word to whoever is still aboard. */
    fun withOccupant(seat: Seat, aboard: Boolean): Cabin {
        val now = if (aboard) occupants + seat else occupants - seat
        val talking = if (!aboard && speaker == seat) Seat.entries.firstOrNull { it in now } else speaker
        return copy(occupants = now, speaker = talking)
    }

    /** [seat] speaking now, and so aboard. */
    fun withSpeaker(seat: Seat): Cabin = copy(occupants = occupants + seat, speaker = seat)

    val rangeKm: Int get() = (drive.batteryPct * KM_PER_PERCENT).roundToInt()

    fun seat(seat: Seat): SeatComfort = seats.getValue(seat)

    fun window(seat: Seat): Int = windows.getValue(seat)
}

/** Named ambient colours the tools accept besides #RRGGBB, with English aliases a model may send. */
internal val AMBIENT_COLORS: List<Triple<String, Long, List<String>>> = listOf(
    Triple("暖白", 0xFFFFE2B8, listOf("white", "warm white", "白", "白色")),
    Triple("暖橙", 0xFFFFA552, listOf("orange", "橙", "橙色", "暖黄", "黄色", "yellow")),
    Triple("森林绿", 0xFF7FD6A0, listOf("green", "绿", "绿色")),
    Triple("冰蓝", 0xFF8FD3FF, listOf("blue", "蓝", "蓝色", "天蓝")),
    Triple("薰衣草紫", 0xFFC3A6FF, listOf("purple", "紫", "紫色")),
    Triple("玫瑰红", 0xFFFF7A90, listOf("red", "pink", "红", "红色", "粉", "粉色")),
    Triple("香槟金", 0xFFFFD166, listOf("gold", "金", "金色")),
)

internal const val KM_PER_PERCENT = 5.2
