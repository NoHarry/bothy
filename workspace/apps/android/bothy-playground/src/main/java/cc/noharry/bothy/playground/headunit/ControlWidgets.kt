package cc.noharry.bothy.playground.headunit
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One line of the controls: a short label, then whatever changes it. */
@Composable
internal fun ControlRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = PlaygroundColors.muted, fontSize = 13.sp, maxLines = 1, modifier = Modifier.width(LABEL_WIDTH))
        content()
    }
}

@Composable
internal fun MiniPill(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val back = when {
        selected -> PlaygroundColors.sage
        else -> PlaygroundColors.elevated
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(back)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        val color = when {
            selected -> PlaygroundColors.bg
            enabled -> PlaygroundColors.text
            else -> PlaygroundColors.faint
        }
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** A row of pills, one of which is [selected]; it scrolls sideways when it does not fit. */
@Composable
internal fun <T> RowScope.Choices(options: List<Pair<String, T>>, selected: T?, onSelect: (T) -> Unit) {
    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (label, value) -> MiniPill(label, value == selected) { onSelect(value) } }
    }
}

@Composable
internal fun SmallSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        modifier = Modifier.scale(SWITCH_SCALE),
        colors = SwitchDefaults.colors(checkedTrackColor = PlaygroundColors.sage, checkedThumbColor = PlaygroundColors.bg),
    )
}

/** − value + */
@Composable
internal fun Stepper(text: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        MiniPill("−", selected = false, onClick = onMinus)
        Text(text, color = PlaygroundColors.text, fontSize = 13.sp, maxLines = 1)
        MiniPill("+", selected = false, onClick = onPlus)
    }
}

/**
 * A slider that follows the finger and changes the world once, when it is let go: a drag is one manual change in the
 * log (and one reading a plan may see), not fifty.
 */
@Composable
internal fun RowScope.CommitSlider(value: Float, range: ClosedFloatingPointRange<Float>, label: (Float) -> String, onCommit: (Float) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: value
    Slider(
        value = shown,
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let(onCommit)
            dragging = null
        },
        valueRange = range,
        modifier = Modifier.weight(1f),
        colors = SliderDefaults.colors(thumbColor = PlaygroundColors.sage, activeTrackColor = PlaygroundColors.sage, inactiveTrackColor = PlaygroundColors.border),
    )
    Text(label(shown), color = PlaygroundColors.text, fontSize = 12.sp, maxLines = 1, modifier = Modifier.width(VALUE_WIDTH))
}

/** A colour to pick, ringed when it is the one in use. */
@Composable
internal fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(color)
            .border(if (selected) 2.dp else 0.dp, PlaygroundColors.text, CircleShape)
            .clickable(onClick = onClick),
    )
}

@Composable
internal fun SubTitle(text: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, color = PlaygroundColors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        trailing()
    }
}

private val LABEL_WIDTH = 48.dp
private val VALUE_WIDTH = 50.dp
private const val SWITCH_SCALE = 0.75f
