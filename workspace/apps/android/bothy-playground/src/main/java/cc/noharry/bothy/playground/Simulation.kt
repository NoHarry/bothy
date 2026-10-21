package cc.noharry.bothy.playground

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The world moving on its own, [seconds] at a time, fast enough that a plan such as 「车内低于 24 度就关窗」 or
 * 「客厅超过 28 度开空调」 fires within minutes of a demo: rooms and the cabin drift toward their air conditioner or the
 * weather outside, a drive drains the battery and counts down the route, and the vacuum works through its rooms.
 * [notes] collects what happened worth telling (an arrival, a room cleaned).
 */
internal fun World.tick(seconds: Double, random: Random = Random.Default, notes: MutableList<String> = mutableListOf()): World =
    copy(cabin = cabin.tick(seconds, random, notes), home = home.tick(seconds, notes))

internal fun Cabin.tick(seconds: Double, random: Random, notes: MutableList<String>): Cabin {
    val moved = when {
        // Navigating, the car goes as fast as the navigation (or its simulation) says, and uses what its range says a
        // kilometre takes: a whole demo route must not empty it, as the quick drain below would.
        nav.active -> copy(drive = drive.copy(speedKmh = nav.speedKmh.toDouble(), batteryPct = drive.travelled(nav.speedKmh * seconds / SECONDS_PER_HOUR)))
        drive.moving -> driven(seconds, random)
        else -> copy(drive = drive.copy(speedKmh = 0.0))
    }
    return moved.copy(insideTempC = moved.nextInsideTemp(seconds))
}

private fun Cabin.driven(seconds: Double, random: Random): Cabin {
    val speed = (drive.speedKmh + random.nextDouble(-SPEED_JITTER, SPEED_JITTER) * seconds + (CRUISE_KMH - drive.speedKmh) * 0.1 * seconds)
        .coerceIn(MIN_DRIVE_KMH, MAX_DRIVE_KMH)
    return copy(drive = drive.copy(speedKmh = speed, batteryPct = drive.drained(speed, climate.on, seconds)))
}

private fun Drive.travelled(km: Double): Double = max(0.0, batteryPct - km / KM_PER_PERCENT)

/** The quick drain of a drive without navigation, so plans on the battery fire within minutes. */
private fun Drive.drained(speedKmh: Double, climateOn: Boolean, seconds: Double): Double =
    max(0.0, batteryPct - (DRAIN_IDLE + speedKmh / 100.0 * DRAIN_PER_100KMH + if (climateOn) DRAIN_CLIMATE else 0.0) * seconds)

private fun Cabin.nextInsideTemp(seconds: Double): Double {
    val outside = drive.outsideTempC
    val openShare = windows.values.average() / 100.0
    val conditioned = climate.on && climate.mode != ClimateMode.VENT
    val target = if (conditioned) climate.driverTemp else outside
    val rate = if (conditioned) CLIMATE_RATE * climate.fan / 3.0 else DRIFT_RATE + OPEN_WINDOW_RATE * openShare
    return approach(insideTempC, target, rate * seconds)
}

internal fun Home.tick(seconds: Double, notes: MutableList<String>): Home {
    val warmed = Room.entries.fold(this) { home, room ->
        home.withRoom(room) { state ->
            val cooling = state.climate.on && state.climate.mode != ClimateMode.VENT
            val target = if (cooling) state.climate.targetTemp else outdoorTempC - INDOOR_OFFSET
            state.copy(tempC = approach(state.tempC, target, (if (cooling) ROOM_CLIMATE_RATE else ROOM_DRIFT_RATE) * seconds))
        }
    }
    return warmed.copy(vacuum = vacuum.tick(seconds, notes))
}

internal fun Vacuum.tick(seconds: Double, notes: MutableList<String>): Vacuum = when (status) {
    VacuumStatus.DOCKED -> copy(battery = min(100.0, battery + CHARGE_PER_SECOND * seconds))
    VacuumStatus.PAUSED -> this
    VacuumStatus.RETURNING -> (progress + seconds / RETURN_SECONDS).let { done ->
        if (done >= 1.0) copy(status = VacuumStatus.DOCKED, room = null, progress = 0.0) else copy(progress = done)
    }
    VacuumStatus.CLEANING -> cleaned(seconds, notes)
}

private fun Vacuum.cleaned(seconds: Double, notes: MutableList<String>): Vacuum {
    val done = progress + seconds / ROOM_SECONDS
    val drained = max(0.0, battery - DISCHARGE_PER_SECOND * seconds)
    if (done < 1.0) return copy(progress = done, battery = drained)
    room?.let { notes += "扫地机扫完了${it.label}" }
    val next = queue.firstOrNull()
    return if (next != null) {
        copy(room = next, queue = queue.drop(1), progress = 0.0, battery = drained)
    } else {
        copy(status = VacuumStatus.RETURNING, queue = emptyList(), progress = 0.0, battery = drained)
    }
}

private fun approach(value: Double, target: Double, step: Double): Double =
    if (abs(target - value) <= step) target else value + if (target > value) step else -step

private const val CRUISE_KMH = 62.0
private const val MIN_DRIVE_KMH = 20.0
private const val MAX_DRIVE_KMH = 110.0
private const val SPEED_JITTER = 6.0
/** Battery %, per second: fast on purpose, so 「电量低于 70% 提醒我」 comes true in a few minutes. */
private const val SECONDS_PER_HOUR = 3_600.0
private const val DRAIN_IDLE = 0.01
private const val DRAIN_PER_100KMH = 0.06
private const val DRAIN_CLIMATE = 0.01
/** Degrees per second toward the set temperature at fan 3, toward the weather with the air conditioning off. */
private const val CLIMATE_RATE = 0.08
private const val DRIFT_RATE = 0.01
private const val OPEN_WINDOW_RATE = 0.04
private const val ROOM_CLIMATE_RATE = 0.05
private const val ROOM_DRIFT_RATE = 0.006
private const val INDOOR_OFFSET = 1.0
private const val ROOM_SECONDS = 45.0
private const val RETURN_SECONDS = 6.0
private const val CHARGE_PER_SECOND = 0.5
private const val DISCHARGE_PER_SECOND = 0.08
