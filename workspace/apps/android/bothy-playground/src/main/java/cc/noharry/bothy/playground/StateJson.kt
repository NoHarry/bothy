package cc.noharry.bothy.playground

import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The state as the servers report it, in the status tools and the resources a plan watches. Field names are what a
 * watch path names, e.g. `inside_temp_c`, `seats.driver.heat`, `rooms.living.temp_c`; listed in [CABIN_FIELDS] and
 * [HOME_FIELDS] for the resources' descriptions.
 */
internal fun Cabin.toJson(userHere: Boolean): JsonObject = buildJsonObject {
    put("user_here", userHere)
    put("inside_temp_c", insideTempC.oneDecimal())
    put("outside_temp_c", drive.outsideTempC.oneDecimal())
    put("battery_pct", drive.batteryPct.oneDecimal())
    put("range_km", rangeKm)
    put("speed_kmh", drive.speedKmh.roundToInt())
    put("moving", drive.moving)
    put("doors_locked", doorsLocked)
    putJsonObject("doors_open") { Seat.entries.forEach { put(it.id, it in doorsOpen) } }
    put("trunk_open", trunkOpen)
    put("screen_brightness", screenBrightness)
    put("volume", media.volume)
    put("speaker", speaker?.id.orEmpty())
    put("occupant_count", occupants.size)
    putJsonObject("occupants") { Seat.entries.forEach { put(it.id, it in occupants) } }
    putJsonObject("climate") {
        put("on", climate.on)
        put("driver_temp_c", climate.driverTemp)
        put("passenger_temp_c", climate.passengerTemp)
        put("fan", climate.fan)
        put("mode", climate.mode.id)
    }
    putJsonObject("seats") {
        Seat.entries.forEach { seat ->
            putJsonObject(seat.id) {
                put("heat", seat(seat).heat)
                put("ventilation", seat(seat).ventilation)
            }
        }
    }
    putJsonObject("windows") { Seat.entries.forEach { put(it.id, window(it)) } }
    putJsonObject("ambient") {
        put("on", ambient.on)
        put("color", ambient.colorName)
        put("brightness", ambient.brightness)
    }
    putJsonObject("media") {
        put("playing", media.playing)
        put("title", media.title)
        put("artist", media.artist)
    }
    put("navigation", nav.toJson())
}

internal fun Home.toJson(userHere: Boolean): JsonObject = buildJsonObject {
    put("user_here", userHere)
    put("outdoor_temp_c", outdoorTempC.oneDecimal())
    put("door_locked", doorLocked)
    put("scene", scene.orEmpty())
    putJsonObject("rooms") { Room.entries.forEach { put(it.id, room(it).toJson(it)) } }
    putJsonObject("vacuum") {
        put("status", vacuum.status.id)
        put("room", vacuum.room?.id.orEmpty())
        put("progress_pct", (vacuum.progress * 100).roundToInt())
        put("battery_pct", vacuum.battery.roundToInt())
    }
}

private fun RoomState.toJson(room: Room): JsonObject = buildJsonObject {
    put("name", room.label)
    put("temp_c", tempC.oneDecimal())
    put("humidity_pct", humidity)
    put("presence", presence)
    putJsonObject("light") {
        put("on", light.on)
        put("brightness", light.brightness)
        put("tone", light.tone.id)
    }
    put("curtain_open_pct", curtainOpen)
    if (room.hasClimate) {
        putJsonObject("climate") {
            put("on", climate.on)
            put("target_temp_c", climate.targetTemp)
            put("mode", climate.mode.id)
        }
    }
}

internal const val CABIN_FIELDS = "user_here（用户在不在车上）、inside_temp_c（车内温度）、outside_temp_c、battery_pct（电量 %）、range_km（续航）、speed_kmh、" +
    "moving、doors_locked、doors_open.<座位>（车门开没开）、trunk_open、screen_brightness（中控屏亮度 0–100）、volume（音量 0–30）、speaker（正在说话的人的座位）、occupant_count、occupants.<座位>（有没有人）、climate.on / driver_temp_c / passenger_temp_c / fan / mode、seats.<driver|passenger|rear_left|rear_right>.heat / " +
    "ventilation（0–3）、windows.<同上>（开度 0–100）、ambient.on / color / brightness、media.playing / title、" +
    "navigation.status（idle / planned / navigating / arrived）/ waits_for_go（路线显示着等出发）/ arrived / destination / remaining_km / remaining_min / " +
    "next_waypoint / next_waypoint_km / waypoints / reached_waypoints / current_road / speed_kmh"

internal const val HOME_FIELDS = "user_here（用户在不在家）、outdoor_temp_c、door_locked、scene、rooms.<living|bedroom|kitchen|study>.temp_c / humidity_pct / " +
    "presence（有没有人）/ light.on / light.brightness / light.tone / curtain_open_pct / climate.on / climate.target_temp_c（厨房没有空调）、" +
    "vacuum.status（docked / cleaning / paused / returning）/ room / progress_pct / battery_pct"

/** The navigation alone, as the real-navigation head unit serves it ([HeadUnitModes]): the same paths plans watch. */
internal const val NAV_FIELDS = "navigation.status（idle / planned / navigating / arrived）/ waits_for_go（路线显示着等出发）/ arrived / destination / " +
    "remaining_km / remaining_min / next_waypoint / next_waypoint_km / waypoints / reached_waypoints / current_road / speed_kmh / " +
    "lights_left（前方还有几个红绿灯）"

internal fun navigationJson(world: World): JsonObject = buildJsonObject { put("navigation", world.cabin.nav.toJson()) }

private fun NavState.toJson(): JsonObject = buildJsonObject {
    put("status", status.id)
    put("arrived", status == NavStatus.ARRIVED)
    put("waits_for_go", waitsForGo)
    put("destination", destination?.name.orEmpty())
    put("remaining_km", (remainingMeters / 100) / 10.0)
    put("remaining_min", (remainingSeconds + 59) / 60)
    put("next_waypoint", waypoints.firstOrNull()?.name.orEmpty())
    put("next_waypoint_km", toWaypoints.firstOrNull()?.let { (it.meters / 100) / 10.0 } ?: 0.0)
    put("waypoints", JsonArray(waypoints.map { JsonPrimitive(it.name) }))
    put("reached_waypoints", JsonArray(reachedWaypoints.map { JsonPrimitive(it) }))
    put("preference", preference.id)
    put("simulated", simulated)
    put("current_road", currentRoad)
    put("speed_kmh", speedKmh)
    put("lights_left", lightsLeft)
}

private fun Double.oneDecimal(): Double = (this * 10).roundToInt() / 10.0
