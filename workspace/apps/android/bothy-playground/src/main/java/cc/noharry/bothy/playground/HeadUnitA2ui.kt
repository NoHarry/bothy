package cc.noharry.bothy.playground

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The reply's cards, picks and panel in 小博's card on the head unit ([A2ui]). A pick tapped or the panel confirmed
 * is said to 小博 as the user would say it; after that the offer is answered and stays as it was chosen.
 */
@Composable
internal fun A2uiContent(a2ui: A2ui) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        a2ui.cards.forEach { A2uiCardView(it) }
        if (a2ui.choices.isNotEmpty()) A2uiChoices(a2ui.choices) { Assistant.say(context, it) }
        if (a2ui.controls.isNotEmpty()) A2uiPanel(a2ui.controls) { Assistant.say(context, it) }
    }
}

@Composable
private fun A2uiCardView(card: A2uiCard) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(UnitColors.raised).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            card.icon?.let { A2uiIcon(it, UnitColors.teal, 22.dp) }
            Column(Modifier.weight(1f)) {
                Text(card.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                card.subtitle?.let { Text(it, color = UnitColors.muted, fontSize = 12.sp, lineHeight = 17.sp) }
            }
            card.value?.let { Text(it, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        }
        card.fields.forEach { (name, value) -> FieldRow(name, value) }
        card.progress.forEach { ProgressRow(it) }
        if (card.items.isNotEmpty()) {
            when (card.layout) {
                "strip" -> ItemStrip(card.items)
                "grid" -> ItemGrid(card.items)
                "compare" -> ItemCompare(card.items)
                else -> ItemList(card.items)
            }
        }
    }
}

@Composable
private fun FieldRow(name: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, color = UnitColors.muted, fontSize = 12.sp, modifier = Modifier.width(72.dp))
        Text(value, color = Color.White, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ProgressRow(bar: A2uiProgress) {
    val share = if (bar.max > 0) (bar.value / bar.max).toFloat().coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(bar.label, color = UnitColors.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            bar.text?.let { Text(it, color = Color.White, fontSize = 12.sp) }
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(UnitColors.card)) {
            Box(Modifier.fillMaxWidth(share).height(6.dp).clip(RoundedCornerShape(3.dp)).background(UnitColors.teal))
        }
    }
}

/** A list, under headings where items carry a group (a trip's days). */
@Composable
private fun ItemList(items: List<A2uiItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { i, item ->
            if (item.group != null && item.group != items.getOrNull(i - 1)?.group) {
                Text(item.group, color = UnitColors.teal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.icon?.let { A2uiIcon(it, UnitColors.muted, 18.dp) }
                Column(Modifier.weight(1f)) {
                    Text(item.title, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    item.detail?.let { Text(it, color = UnitColors.muted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    item.fields.forEach { (name, value) -> Text("$name $value", color = UnitColors.muted, fontSize = 11.sp) }
                }
                item.value?.let { Text(it, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

/** One row of short columns: the days of a forecast. */
@Composable
private fun ItemStrip(items: List<A2uiItem>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item -> Tile(item, Modifier.weight(1f), narrow = items.size > NARROW_STRIP) }
    }
}

/** Two columns of tiles: four tyres, battery and range. */
@Composable
private fun ItemGrid(items: List<A2uiItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { Tile(it, Modifier.weight(1f)) }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Tile(item: A2uiItem, modifier: Modifier, narrow: Boolean = false) {
    // Five days side by side in the card's column leave little room: one line each, smaller where narrow.
    val size = if (narrow) 10.sp else 12.sp
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(UnitColors.card).padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(item.title, color = UnitColors.muted, fontSize = if (narrow) 10.sp else 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
        item.icon?.let { A2uiIcon(it, Color.White, if (narrow) 18.dp else 20.dp) }
        item.value?.let { Text(it, color = Color.White, fontSize = size, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip) }
        item.detail?.let { Text(it, color = UnitColors.muted, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** Two or three side by side, a row per field: charging stations, parking. */
@Composable
private fun ItemCompare(items: List<A2uiItem>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(UnitColors.card).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(item.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.value?.let { Text(it, color = UnitColors.teal, fontSize = 12.sp) }
                item.detail?.let { Text(it, color = UnitColors.muted, fontSize = 10.sp) }
                item.fields.forEach { (name, value) ->
                    Text(name, color = UnitColors.muted, fontSize = 10.sp)
                    Text(value, color = Color.White, fontSize = 11.sp)
                }
            }
        }
    }
}

/** The picks, numbered as 小博 said them; the recommended one marked. A tap says 「第N个」, once. */
@Composable
private fun A2uiChoices(choices: List<A2uiChoice>, onPick: (String) -> Unit) {
    var picked by remember(choices) { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { choice ->
            val chosen = picked == choice.ordinal
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (chosen) UnitColors.teal.copy(alpha = 0.25f) else UnitColors.raised)
                    .border(1.dp, if (chosen) UnitColors.teal else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable(enabled = picked == null) {
                        picked = choice.ordinal
                        onPick(choice.reply)
                    }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(UnitColors.card), contentAlignment = Alignment.Center) {
                    Text("${choice.ordinal}", color = UnitColors.teal, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                choice.icon?.let { A2uiIcon(it, UnitColors.muted, 18.dp) }
                Text(choice.label, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
                if (choice.recommended) {
                    Text(
                        "推荐", color = UnitColors.screen, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(UnitColors.teal).padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

/** Sliders, switches and options, set together and confirmed once. */
@Composable
private fun A2uiPanel(controls: List<A2uiControl>, onConfirm: (String) -> Unit) {
    val values = remember(controls) { mutableStateListOf(*controls.map { it.initialValue() }.toTypedArray()) }
    var sent by remember(controls) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(UnitColors.raised).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        controls.forEachIndexed { i, control ->
            when (control) {
                is A2uiControl.Slider -> SliderRow(control, values[i] as Double, !sent) { values[i] = it }
                is A2uiControl.Switch -> SwitchRow(control.label, values[i] as Boolean, !sent) { values[i] = it }
                is A2uiControl.Choice -> {
                    @Suppress("UNCHECKED_CAST")
                    OptionRow(control, values[i] as List<Int>, !sent) { values[i] = it }
                }
            }
        }
        Text(
            if (sent) "已发给小博" else "确定",
            color = if (sent) UnitColors.muted else UnitColors.screen,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (sent) UnitColors.card else UnitColors.teal)
                .clickable(enabled = !sent) {
                    a2uiPanelReply(controls, values.toList()).takeIf { it.isNotEmpty() }?.let {
                        sent = true
                        onConfirm(it)
                    }
                }
                .padding(vertical = 9.dp),
        )
    }
}

@Composable
private fun SliderRow(control: A2uiControl.Slider, value: Double, enabled: Boolean, onChange: (Double) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(control.label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("${control.format(control.snap(value))}${control.unit}", color = UnitColors.teal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(control.snap(it.toDouble())) },
            valueRange = control.min.toFloat()..control.max.toFloat(),
            enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = UnitColors.teal, activeTrackColor = UnitColors.teal, inactiveTrackColor = UnitColors.card),
        )
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(value, onChange, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = UnitColors.teal, checkedThumbColor = UnitColors.screen))
    }
}

@Composable
private fun OptionRow(control: A2uiControl.Choice, chosen: List<Int>, enabled: Boolean, onChange: (List<Int>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(control.label, color = Color.White, fontSize = 13.sp)
        control.options.chunked(OPTIONS_PER_ROW).forEachIndexed { row, names ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                names.forEachIndexed { column, name ->
                    val index = row * OPTIONS_PER_ROW + column
                    val on = index in chosen
                    Text(
                        name,
                        color = if (on) UnitColors.screen else Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) UnitColors.teal else UnitColors.card)
                            .clickable(enabled = enabled) {
                                onChange(if (control.multiple) (if (on) chosen - index else chosen + index) else listOf(index))
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** An A2UI icon: `svg:` and a path on a 24×24 grid, as Bothy's own glyphs come, or one of the catalog's names. */
@Composable
internal fun A2uiIcon(name: String, tint: Color, size: Dp) {
    val vector = remember(name) { a2uiVector(name) }
    if (vector != null) {
        Icon(vector, contentDescription = null, tint = tint, modifier = Modifier.size(size))
    } else {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) { Box(Modifier.size(size / 3).clip(CircleShape).background(tint)) }
    }
}

private fun a2uiVector(name: String): ImageVector? {
    if (name.startsWith("svg:")) {
        val nodes = runCatching { addPathNodes(name.removePrefix("svg:")) }.getOrNull() ?: return null
        return ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(nodes, fill = SolidColor(Color.White))
            .build()
    }
    return CATALOG_ICONS[name]
}

/** The A2UI catalog's names the head unit draws; any other shows as a dot. */
private val CATALOG_ICONS = mapOf(
    "accountCircle" to Icons.Filled.AccountCircle, "add" to Icons.Filled.Add, "calendarToday" to Icons.Filled.CalendarToday,
    "call" to Icons.Filled.Call, "check" to Icons.Filled.Check, "close" to Icons.Filled.Close, "error" to Icons.Filled.Error,
    "event" to Icons.Filled.Event, "favorite" to Icons.Filled.Favorite, "home" to Icons.Filled.Home, "info" to Icons.Filled.Info,
    "locationOn" to Icons.Filled.LocationOn, "lock" to Icons.Filled.Lock, "mail" to Icons.Filled.Mail,
    "notifications" to Icons.Filled.Notifications, "person" to Icons.Filled.Person, "phone" to Icons.Filled.Phone,
    "play" to Icons.Filled.PlayArrow, "search" to Icons.Filled.Search, "settings" to Icons.Filled.Settings,
    "star" to Icons.Filled.Star, "warning" to Icons.Filled.Warning,
)

private const val OPTIONS_PER_ROW = 3
/** More days than this in a strip take the narrow tiles. */
private const val NARROW_STRIP = 3
