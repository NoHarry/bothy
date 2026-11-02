package cc.noharry.bothy.playground.headunit
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.playground.R
import cc.noharry.bothy.playground.cabin.deg
import cc.noharry.bothy.playground.nav.DayWeather
import cc.noharry.bothy.playground.nav.WeatherReport

/**
 * The weather where the car is, as the head unit's weather card: what it is like now and how warm, the city, then the
 * next days as blue tiles. Without AMap's report yet, the car's own outside temperature.
 */
@Composable
internal fun WeatherCard(report: WeatherReport?, outsideTempC: Double, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(UnitColors.panel).padding(16.dp)) {
        val now = report?.weather?.ifEmpty { null }?.let { weatherText(it) }
            ?: stringResource(R.string.headunit_weather_outside)
        Text(now, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(0.4f))
        Text("${report?.temperature ?: outsideTempC.deg().removeSuffix("C").removeSuffix("°")}°", color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Light, modifier = Modifier.align(Alignment.CenterHorizontally))
        val city = report?.city?.ifEmpty { stringResource(R.string.headunit_weather_here) }
        val range = report?.days?.firstOrNull()?.let { stringResource(R.string.headunit_weather_range, it.high, it.low) }
        val waiting = stringResource(R.string.headunit_weather_waiting)
        Text(
            listOfNotNull(city, range).joinToString(" · ").ifEmpty { waiting },
            color = UnitColors.muted,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.weight(0.5f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            report?.days.orEmpty().take(FORECAST_DAYS).forEachIndexed { i, day ->
                val label = when {
                    i == 0 -> stringResource(R.string.headunit_weather_today)
                    else -> WEEKDAYS[day.week.trim()]?.let { stringResource(it) } ?: day.week
                }
                DayTile(label, day, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(10.dp))
        val resources = LocalContext.current.resources
        report?.let {
            val humidity = stringResource(R.string.headunit_weather_humidity, it.humidity, it.windText(resources))
            Text(humidity, color = UnitColors.muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun DayTile(label: String, day: DayWeather, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Brush.verticalGradient(listOf(Color(0xFF3D6BE0), Color(0xFF213C8F)))).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = Color.White, fontSize = 11.sp)
        val weather = weatherText(day.weather)
        Icon(weatherIcon(day.weather), contentDescription = weather, tint = Color.White, modifier = Modifier.size(20.dp))
        Text("${day.high}°", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

private fun weatherIcon(weather: String): ImageVector = when {
    weather.contains("雷") -> Icons.Filled.Thunderstorm
    weather.contains("雪") -> Icons.Filled.AcUnit
    weather.contains("雨") -> Icons.Filled.Grain
    weather.contains("雾") || weather.contains("霾") || weather.contains("沙") -> Icons.Filled.Dehaze
    weather.contains("阴") -> Icons.Filled.Cloud
    weather.contains("云") -> Icons.Filled.WbCloudy
    else -> Icons.Filled.WbSunny
}

/** AMap's week number (1 Monday … 7 Sunday) as the card names the day: 周一 … 周日. */
private val WEEKDAYS = mapOf(
    "1" to R.string.headunit_weekday_mon, "2" to R.string.headunit_weekday_tue, "3" to R.string.headunit_weekday_wed,
    "4" to R.string.headunit_weekday_thu, "5" to R.string.headunit_weekday_fri, "6" to R.string.headunit_weekday_sat,
    "7" to R.string.headunit_weekday_sun,
)

/**
 * AMap's weather words that Open-Meteo's codes are given ([KeylessWeather.weatherName]), as the screen names them; AMap's
 * other words are its own and show as they come.
 */
private val WEATHER_WORDS = mapOf(
    "晴" to R.string.headunit_weather_clear, "多云" to R.string.headunit_weather_cloudy, "阴" to R.string.headunit_weather_overcast,
    "雾" to R.string.headunit_weather_fog, "小雨" to R.string.headunit_weather_light_rain,
    "中雨" to R.string.headunit_weather_moderate_rain, "大雨" to R.string.headunit_weather_heavy_rain,
    "雪" to R.string.headunit_weather_snow, "雷阵雨" to R.string.headunit_weather_thunderstorm,
)

/** The screen's word for [weather] (AMap's word), or null where it shows AMap's own. */
@StringRes
internal fun weatherWord(weather: String): Int? = WEATHER_WORDS[weather]

/** [weather] (AMap's word) as the screen shows it. */
@Composable
internal fun weatherText(weather: String): String = weatherWord(weather)?.let { stringResource(it) } ?: weather

/** The wind as the card words it: 「东南风 3级」 from AMap, 「风力 3级」 from Open-Meteo, which gives no direction. */
internal fun WeatherReport.windText(resources: Resources): String = when {
    windDirection.isNotEmpty() -> {
        val direction = WIND_DIRECTIONS[windDirection]?.let { resources.getString(it) } ?: windDirection
        resources.getString(R.string.headunit_weather_wind_from, direction, windForce)
    }
    windForce.isNotEmpty() -> resources.getString(R.string.headunit_weather_wind, windForce)
    else -> ""
}

/** AMap's wind directions, as the screen words them (the Chinese is AMap's own). */
private val WIND_DIRECTIONS = mapOf(
    "东" to R.string.headunit_wind_east, "南" to R.string.headunit_wind_south, "西" to R.string.headunit_wind_west,
    "北" to R.string.headunit_wind_north, "东北" to R.string.headunit_wind_northeast, "东南" to R.string.headunit_wind_southeast,
    "西北" to R.string.headunit_wind_northwest, "西南" to R.string.headunit_wind_southwest,
)

private const val FORECAST_DAYS = 4
