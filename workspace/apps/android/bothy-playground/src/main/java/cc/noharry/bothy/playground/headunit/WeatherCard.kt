package cc.noharry.bothy.playground.headunit
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        Text(report?.weather?.ifEmpty { null } ?: "车外", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(0.4f))
        Text("${report?.temperature ?: outsideTempC.deg().removeSuffix("C").removeSuffix("°")}°", color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Light, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text(
            listOfNotNull(report?.city, report?.days?.firstOrNull()?.let { "最高 ${it.high}° 最低 ${it.low}°" }).joinToString(" · ").ifEmpty { "等定位后显示天气" },
            color = UnitColors.muted,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.weight(0.5f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            report?.days.orEmpty().take(FORECAST_DAYS).forEachIndexed { i, day -> DayTile(if (i == 0) "今天" else "周${day.week.toWeekday()}", day, Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(10.dp))
        report?.let {
            Text("湿度 ${it.humidity}% · ${it.wind}", color = UnitColors.muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
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
        Icon(weatherIcon(day.weather), contentDescription = day.weather, tint = Color.White, modifier = Modifier.size(20.dp))
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

/** AMap's week number (1 Monday … 7 Sunday) as 一 … 日. */
private fun String.toWeekday(): String = when (trim()) {
    "1" -> "一"
    "2" -> "二"
    "3" -> "三"
    "4" -> "四"
    "5" -> "五"
    "6" -> "六"
    "7" -> "日"
    else -> this
}

private const val FORECAST_DAYS = 4
