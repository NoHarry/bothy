package cc.noharry.bothy.playground.headunit
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Open-Meteo's answer read as the head unit's weather card reads AMap's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh")
class KeylessWeatherTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun nowAndTheNextDaysAreReadInAMapsWords() {
        val json = Json.parseToJsonElement(
            """{"current":{"temperature_2m":26.4,"relative_humidity_2m":71,"weather_code":3,"wind_speed_10m":14.0},
               "daily":{"weather_code":[0,61,95],"temperature_2m_max":[31.2,29.0,28.6],"temperature_2m_min":[24.1,23.5,22.9]}}""",
        ).jsonObject

        val report = KeylessWeather.report(json, "当前位置")!!

        assertEquals("阴", report.weather)
        assertEquals("26", report.temperature)
        assertEquals("71", report.humidity)
        assertEquals("风力 3级", report.windText(context.resources))
        assertEquals(listOf("晴", "31", "24"), report.days.first().let { listOf(it.weather, it.high, it.low) })
        assertEquals(listOf("小雨", "雷阵雨"), report.days.drop(1).map { it.weather })
    }

    @Test
    @Config(qualifiers = "en")
    fun theCardWordsItInTheScreensLanguage() {
        val report = KeylessWeather.report(
            Json.parseToJsonElement("""{"current":{"temperature_2m":20,"weather_code":61,"wind_speed_10m":14.0}}""").jsonObject,
            "",
        )!!
        assertEquals("Wind force 3", report.windText(context.resources))
        assertEquals("Light rain", context.getString(weatherWord(report.weather)!!))
    }

    @Test
    fun weekdaysAreNumberedAsAMapNumbersThem() {
        val sunday = 1
        val monday = 2
        assertEquals("7", KeylessWeather.amapWeekday(sunday, 0))
        assertEquals("1", KeylessWeather.amapWeekday(sunday, 1))
        assertEquals("1", KeylessWeather.amapWeekday(monday, 0))
        assertEquals("7", KeylessWeather.amapWeekday(monday, 6))
    }

    @Test
    fun anAnswerWithoutTheWeatherNowIsNone() {
        assertNull(KeylessWeather.report(Json.parseToJsonElement("""{"daily":{}}""").jsonObject, "当前位置"))
    }
}
