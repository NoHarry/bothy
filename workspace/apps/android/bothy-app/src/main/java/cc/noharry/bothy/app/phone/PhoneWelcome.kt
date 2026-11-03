package cc.noharry.bothy.app.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import java.util.Calendar

/**
 * A new chat on a phone: a greeting for the time of day and a few things to ask, which go into the composer to be
 * sent or changed. The tablet's console opens on 小博's picture instead.
 */
@Composable
internal fun PhoneWelcome(onSuggest: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = ConsoleColors
    val greeting = remember { greetingFor(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
    Column(modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(greeting), style = ConsoleType.hero.copy(fontSize = GREETING_SIZE, fontWeight = FontWeight.Light), color = colors.text, textAlign = TextAlign.Center)
        Text(stringResource(R.string.phone_greeting_ask), style = ConsoleType.body, color = colors.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 24.dp))
        val suggestions = listOf(
            Icons.Filled.Notifications to R.string.phone_suggest_remind,
            Icons.Filled.Place to R.string.phone_suggest_nearby,
            Icons.Filled.LocationOn to R.string.phone_suggest_navigate,
            Icons.Filled.Search to R.string.phone_suggest_news,
        ).map { (icon, words) -> icon to stringResource(words) }
        suggestions.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { (icon, words) -> SuggestionChip(icon, words, Modifier.weight(1f)) { onSuggest(words) } }
            }
        }
    }
}

/** The greeting for an hour of the day: morning until noon, afternoon until six, evening through the night. */
internal fun greetingFor(hour: Int): Int = when (hour) {
    in MORNING_FROM until NOON -> R.string.phone_greeting_morning
    in NOON until EVENING_FROM -> R.string.phone_greeting_afternoon
    else -> R.string.phone_greeting_evening
}

@Composable
private fun SuggestionChip(icon: ImageVector, words: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = ConsoleColors
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(Dimens.radiusFull))
            .background(colors.elevated.copy(alpha = CHIP_ALPHA))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(words, style = ConsoleType.meta, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val GREETING_SIZE = 30.sp
private const val CHIP_ALPHA = 0.7f
private const val MORNING_FROM = 5
private const val NOON = 12
private const val EVENING_FROM = 18
