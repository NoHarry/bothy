package cc.noharry.bothy.app.phoneassist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ui.IconMic
import cc.noharry.bothy.app.console.ui.StatusDot
import cc.noharry.bothy.app.phone.PhoneType
import cc.noharry.bothy.app.phone.onAccent

/** 小博's mark in the sheet's headers. */
@Composable
internal fun Avatar() {
    val colors = ConsoleColors
    Box(Modifier.size(32.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.phone_assist_avatar), style = PhoneType.label, color = onAccent())
    }
}

@Composable
internal fun ListeningChip() {
    val colors = ConsoleColors
    Row(Modifier.clip(CircleShape).background(colors.accentSoft).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color = colors.accent, pulsing = true, modifier = Modifier.size(7.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.phone_assist_listening), style = PhoneType.small, color = colors.accent)
    }
}

/** The voice's newest loudness as bars across the sheet, tallest in the middle of each. */
@Composable
internal fun LevelBars(levels: List<Float>) {
    val colors = ConsoleColors
    val recent = levels.takeLast(BARS)
    Canvas(Modifier.fillMaxWidth().height(36.dp)) {
        val step = size.width / BARS
        val bar = step * BAR_SHARE
        for (index in 0 until BARS) {
            val level = recent.getOrNull(index - (BARS - recent.size)) ?: 0f
            val h = size.height * (MIN_BAR + (1f - MIN_BAR) * level.coerceIn(0f, 1f))
            drawRoundRect(colors.accent.copy(alpha = if (index % 2 == 0) 1f else BAR_SOFT), Offset(index * step + (step - bar) / 2, (size.height - h) / 2), Size(bar, h), CornerRadius(bar / 2))
        }
    }
}

/**
 * The sheet's input box, focused as it opens: [onSend] sends; with [onLongSend], a long press on send does that
 * instead (adding to a task by default, stopping it and asking anew on a long press, as Eta queues and interrupts).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SheetInput(hint: String, onSend: (String) -> Unit, onLongSend: ((String) -> Unit)?, modifier: Modifier = Modifier) {
    val colors = ConsoleColors
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val send = { use: (String) -> Unit -> text.trim().takeIf { it.isNotEmpty() }?.let { use(it); text = "" }; Unit }
    Row(
        modifier.clip(CircleShape).background(colors.elevated).border(1.dp, colors.border, CircleShape).padding(start = 18.dp, end = 6.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
            if (text.isEmpty()) Text(hint, style = PhoneType.reply, color = colors.muted)
            BasicTextField(text, { text = it }, textStyle = PhoneType.reply.copy(color = colors.text), cursorBrush = SolidColor(colors.accent), modifier = Modifier.fillMaxWidth().focusRequester(focus))
        }
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(colors.accent).combinedClickable(
                onClick = { send(onSend) },
                onLongClick = onLongSend?.let { long -> { send(long) } },
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_phone_send), stringResource(R.string.phone_assist_send), tint = onAccent(), modifier = Modifier.size(18.dp))
        }
    }
}

/** Where the input box opens: its hint on a pill. */
@Composable
internal fun InputPlaceholder(hint: String, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val colors = ConsoleColors
    Surface(onClick = onOpen, shape = CircleShape, color = colors.elevated, border = BorderStroke(1.dp, colors.border), modifier = modifier.heightIn(min = 48.dp)) {
        Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
            Text(hint, style = PhoneType.reply, color = colors.muted)
        }
    }
}

/** A quiet round button: an icon ([painter]; a close cross when null). */
@Composable
internal fun RoundButton(painter: Painter?, label: String, onClick: () -> Unit) {
    val colors = ConsoleColors
    Surface(onClick = onClick, shape = CircleShape, color = colors.elevated, border = BorderStroke(1.dp, colors.border), modifier = Modifier.size(48.dp)) {
        Box(contentAlignment = Alignment.Center) {
            if (painter != null) Icon(painter, label, tint = colors.inkSoft, modifier = Modifier.size(22.dp))
            else Icon(Icons.Filled.Close, label, tint = colors.inkSoft, modifier = Modifier.size(22.dp))
        }
    }
}

/** The big microphone: listening, a tap stops it; otherwise a tap starts listening. Its caption says which. */
@Composable
internal fun BigMic(listening: Boolean, onTap: () -> Unit) {
    val colors = ConsoleColors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
            Surface(onClick = onTap, shape = CircleShape, color = colors.accent, modifier = Modifier.size(68.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(IconMic, contentDescription = null, tint = onAccent(), modifier = Modifier.size(28.dp)) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(if (listening) R.string.phone_assist_tap_stop else R.string.phone_assist_tap_talk), style = PhoneType.small, color = colors.muted)
    }
}

/** Stops the task going on: a square in a red ring. */
@Composable
internal fun StopButton(onStop: () -> Unit) {
    val colors = ConsoleColors
    Surface(onClick = onStop, shape = CircleShape, color = colors.elevated, border = BorderStroke(1.5.dp, colors.danger), modifier = Modifier.size(48.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(14.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).background(colors.danger))
        }
    }
}

private const val BARS = 28
private const val BAR_SHARE = 0.5f
private const val MIN_BAR = 0.12f
private const val BAR_SOFT = 0.55f
