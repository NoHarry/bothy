package cc.noharry.bothy.playground

import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/**
 * The head unit's weather without an AMap key (owner 2026-10-25: installed with a model key alone, it should still be
 * whole): Open-Meteo, which needs no key, now and the next days at a point. Slow from China (some 10 s), so asked in
 * the background and cached like AMap's.
 */
internal object KeylessWeather {
    fun fetch(at: GeoPoint, place: String): WeatherReport? = runCatching {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=${at.lat}&longitude=${at.lng}" +
                "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=$DAYS",
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        val body = try {
            connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
        report(Json.parseToJsonElement(body).jsonObject, place)
    }.getOrNull()

    internal fun report(json: JsonObject, place: String): WeatherReport? {
        val now = json["current"]?.jsonObject ?: return null
        val daily = json["daily"]?.jsonObject
        val codes = daily?.get("weather_code")?.jsonArray.orEmpty()
        val highs = daily?.get("temperature_2m_max")?.jsonArray.orEmpty()
        val lows = daily?.get("temperature_2m_min")?.jsonArray.orEmpty()
        val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return WeatherReport(
            city = place,
            weather = weatherName(now["weather_code"]?.jsonPrimitive?.intOrNull),
            temperature = now["temperature_2m"]?.jsonPrimitive?.doubleOrNull?.roundToInt()?.toString().orEmpty(),
            humidity = now["relative_humidity_2m"]?.jsonPrimitive?.doubleOrNull?.roundToInt()?.toString().orEmpty(),
            wind = now["wind_speed_10m"]?.jsonPrimitive?.doubleOrNull?.let { "风力 ${beaufort(it)}级" }.orEmpty(),
            days = codes.indices.map { i ->
                DayWeather(
                    week = amapWeekday(today, i),
                    weather = weatherName(codes[i].jsonPrimitive.intOrNull),
                    high = highs.getOrNull(i)?.jsonPrimitive?.doubleOrNull?.roundToInt()?.toString().orEmpty(),
                    low = lows.getOrNull(i)?.jsonPrimitive?.doubleOrNull?.roundToInt()?.toString().orEmpty(),
                )
            },
        )
    }

    /** The weekday [ahead] days after [today] (Calendar's, Sunday 1) as AMap numbers it, Monday 1 to Sunday 7: the card names it. */
    internal fun amapWeekday(today: Int, ahead: Int): String = ((today - 1 + ahead + WEEK - 1) % WEEK + 1).toString()

    /** WMO weather codes, as AMap names its weather (晴, 多云, 阴, 小雨…), so the card's icons follow. */
    internal fun weatherName(code: Int?): String = when (code) {
        0 -> "晴"
        1, 2 -> "多云"
        3 -> "阴"
        45, 48 -> "雾"
        51, 53, 55, 56, 57 -> "小雨"
        61, 80 -> "小雨"
        63, 81 -> "中雨"
        65, 66, 67, 82 -> "大雨"
        71, 73, 75, 77, 85, 86 -> "雪"
        95, 96, 99 -> "雷阵雨"
        else -> ""
    }

    /** km/h to the Beaufort force a weather report gives. */
    private fun beaufort(kmh: Double): Int = BEAUFORT_UPPER_KMH.indexOfFirst { kmh < it }.let { if (it < 0) BEAUFORT_UPPER_KMH.size else it }

    private const val DAYS = 4
    private const val TIMEOUT_MS = 20_000
    private const val WEEK = 7
    private val BEAUFORT_UPPER_KMH = listOf(1.0, 6.0, 12.0, 20.0, 29.0, 39.0, 50.0, 62.0, 75.0, 89.0, 103.0, 118.0)
}
