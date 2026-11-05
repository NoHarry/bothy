package cc.noharry.bothy.app.phone

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import java.util.Calendar

/**
 * A new chat on a phone, as the design opens it: a greeting for the time of day, what Bothy asks, and three things to
 * ask as cards, which go into the composer to be sent or changed. The tablet's console opens on 小博's picture instead.
 */
@Composable
internal fun PhoneWelcome(onSuggest: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = ConsoleColors
    val greeting = remember { greetingFor(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
    // In the middle while it fits; held sideways or in a large font, it scrolls.
    BoxWithConstraints(modifier) {
        Column(Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight), verticalArrangement = Arrangement.Center) {
            Text(stringResource(greeting), style = PhoneType.greeting, color = colors.text)
            Text(stringResource(R.string.phone_greeting_ask), style = PhoneType.reply, color = colors.muted, modifier = Modifier.padding(top = 4.dp, bottom = 28.dp))
            listOf(R.string.phone_suggest_navigate, R.string.phone_suggest_remind, R.string.phone_suggest_news).forEach { id ->
                val words = stringResource(id)
                SuggestionCard(words) { onSuggest(words) }
                Spacer(Modifier.size(12.dp))
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

/** Something to ask: its words, and on the right an arrow out in a round chip. */
@Composable
private fun SuggestionCard(words: String, onClick: () -> Unit) {
    val colors = ConsoleColors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(CARD_RADIUS),
        color = colors.elevated,
        border = BorderStroke(1.dp, colors.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.heightIn(min = 64.dp).padding(start = 16.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(words, style = PhoneType.body, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(32.dp).background(colors.panel, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_phone_arrow_out), contentDescription = null, tint = colors.inkSoft, modifier = Modifier.size(18.dp))
            }
        }
    }
}

internal val CARD_RADIUS = 16.dp
private const val MORNING_FROM = 5
private const val NOON = 12
private const val EVENING_FROM = 18
