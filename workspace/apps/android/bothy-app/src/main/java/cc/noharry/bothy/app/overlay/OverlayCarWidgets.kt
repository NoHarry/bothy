package cc.noharry.bothy.app.overlay
import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.a2ui.catalog.MaterialA2uiBasicCatalogV1Defaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.ui.icon
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

/** Built-in icons and SVG glyphs drawn by the stock renderer, in the glass's text colour and at a reachable size. */
internal object CarA2uiIcon : A2uiBasicCatalogV1.Icon {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        source: A2uiBasicCatalogV1.Icon.Source,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        modifier: Modifier,
    ) {
        CompositionLocalProvider(LocalContentColor provides OverlayPalette.text) {
            with(MaterialA2uiBasicCatalogV1Defaults.icon) { TypedContent(source, accessibility, modifier.padding(end = 12.dp).size(CAR_ICON_SIZE)) }
        }
    }
}

/** The label with the live value beside it, over a wide track in the overlay's colours. */
internal object CarA2uiSlider : A2uiBasicCatalogV1.Slider {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        label: String?,
        min: Float,
        max: Float,
        value: Float,
        onValueChange: (Float) -> Unit,
        enabled: Boolean,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        checks: List<A2uiBasicCatalogV1.CheckRule>,
        modifier: Modifier,
    ) {
        Column(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(label.orEmpty(), style = TextStyle(color = OverlayPalette.text, fontSize = 18.sp, fontWeight = FontWeight.Medium))
                Spacer(Modifier.weight(1f))
                Text(
                    sliderValueLabel(value, max - min),
                    style = TextStyle(brush = OverlayPalette.signature, fontSize = 30.sp, fontWeight = FontWeight.Light),
                )
            }
            Slider(
                value = value.coerceIn(min, max),
                onValueChange = onValueChange,
                enabled = enabled && checks.all { it.condition },
                valueRange = min..max,
                colors = SliderDefaults.colors(
                    thumbColor = OverlayPalette.text,
                    activeTrackColor = OverlayPalette.sage,
                    inactiveTrackColor = OverlayPalette.tileEdge,
                ),
                modifier = Modifier.fillMaxWidth().height(SLIDER_HEIGHT),
            )
        }
    }
}

/** A CheckBox drawn as a device switch: a glass row the whole width of the card, so the hand need not find a small box. */
internal object CarA2uiSwitch : A2uiBasicCatalogV1.CheckBox {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        label: String,
        value: Boolean,
        onValueChange: (Boolean) -> Unit,
        enabled: Boolean,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        checks: List<A2uiBasicCatalogV1.CheckRule>,
        modifier: Modifier,
    ) {
        val active = enabled && checks.all { it.condition }
        Surface(
            onClick = { onValueChange(!value) },
            enabled = active,
            shape = CONTROL_SHAPE,
            color = if (value) OverlayPalette.tileRecommended else OverlayPalette.tile,
            border = BorderStroke(1.dp, if (value) OverlayPalette.sage.copy(alpha = 0.5f) else OverlayPalette.tileEdge),
            modifier = modifier.fillMaxWidth().padding(vertical = 5.dp).heightIn(min = CONTROL_HEIGHT),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                Text(label, style = TextStyle(color = OverlayPalette.text, fontSize = 18.sp, fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
                Text(
                    if (value) "开" else "关",
                    style = TextStyle(color = if (value) OverlayPalette.glow else OverlayPalette.faint, fontSize = 16.sp),
                    modifier = Modifier.padding(end = 12.dp),
                )
                Switch(
                    checked = value,
                    onCheckedChange = onValueChange,
                    enabled = active,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = OverlayPalette.text,
                        checkedTrackColor = OverlayPalette.sage,
                        uncheckedThumbColor = OverlayPalette.secondary,
                        uncheckedTrackColor = OverlayPalette.tile,
                        uncheckedBorderColor = OverlayPalette.tileEdge,
                    ),
                )
            }
        }
    }
}

/**
 * A ChoicePicker in the device's look: chips in rows (a mode, a level) or, with the checkbox style, one glass row per
 * option. Mutually exclusive pickers keep exactly one choice; multiple selection toggles each option.
 */
internal object CarA2uiChoicePicker : A2uiBasicCatalogV1.ChoicePicker {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        label: String?,
        options: List<A2uiBasicCatalogV1.ChoicePicker.Option>,
        value: List<String>,
        variant: A2uiBasicCatalogV1.ChoicePicker.Variant,
        displayStyle: A2uiBasicCatalogV1.ChoicePicker.DisplayStyle,
        filterable: Boolean,
        onValueChange: (List<String>) -> Unit,
        enabled: Boolean,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        checks: List<A2uiBasicCatalogV1.CheckRule>,
        modifier: Modifier,
    ) {
        val active = enabled && checks.all { it.condition }
        val multiple = variant == A2uiBasicCatalogV1.ChoicePicker.Variant.MultipleSelection
        fun toggle(option: String) = onValueChange(
            when {
                !multiple -> listOf(option)
                option in value -> value - option
                else -> value + option
            },
        )
        Column(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            label?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = TextStyle(color = OverlayPalette.text, fontSize = 18.sp, fontWeight = FontWeight.Medium))
            }
            if (displayStyle == A2uiBasicCatalogV1.ChoicePicker.DisplayStyle.Chips) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    options.forEach { option -> Chip(option.label, option.value in value, active) { toggle(option.value) } }
                }
            } else {
                options.forEach { option -> CheckRow(option.label, option.value in value, active) { toggle(option.value) } }
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = if (selected) OverlayPalette.tileRecommended else OverlayPalette.tile,
        border = if (selected) BorderStroke(1.5.dp, OverlayPalette.signature) else BorderStroke(1.dp, OverlayPalette.tileEdge),
        modifier = Modifier.heightIn(min = CHIP_HEIGHT),
    ) {
        Box(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(
                text,
                style = TextStyle(
                    color = if (selected) OverlayPalette.text else OverlayPalette.secondary,
                    fontSize = 18.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
            )
        }
    }
}

@Composable
private fun CheckRow(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CONTROL_SHAPE,
        color = if (selected) OverlayPalette.tileRecommended else OverlayPalette.tile,
        border = BorderStroke(1.dp, if (selected) OverlayPalette.sage.copy(alpha = 0.5f) else OverlayPalette.tileEdge),
        modifier = Modifier.fillMaxWidth().heightIn(min = CONTROL_HEIGHT),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
            Text(text, style = TextStyle(color = OverlayPalette.text, fontSize = 18.sp), modifier = Modifier.weight(1f))
            if (selected) {
                Icon(Icons.Filled.CheckCircle, contentDescription = "已选", tint = OverlayPalette.glow, modifier = Modifier.size(28.dp))
            } else {
                // The core icon set has no empty circle; an outline the size of the check takes its place.
                Box(Modifier.size(28.dp).padding(2.dp).border(2.dp, OverlayPalette.faint, CircleShape))
            }
        }
    }
}

/** A hairline in the glass's own colour; the stock divider is too dark to see on it. */
internal object CarA2uiDivider : A2uiBasicCatalogV1.Divider {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        axis: A2uiBasicCatalogV1.Divider.Axis,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        modifier: Modifier,
    ) {
        if (axis == A2uiBasicCatalogV1.Divider.Axis.Vertical) {
            VerticalDivider(modifier.padding(horizontal = 8.dp), color = OverlayPalette.hairline)
        } else {
            HorizontalDivider(modifier.padding(vertical = 8.dp), color = OverlayPalette.hairline)
        }
    }
}

/** Whole numbers for a wide range, one decimal for a narrow one (a temperature in half degrees). */
internal fun sliderValueLabel(value: Float, span: Float): String {
    if (span > WHOLE_NUMBER_SPAN || abs(value - round(value)) < 0.05f) return value.roundToInt().toString()
    return (round(value * 10) / 10).toString()
}

private val CAR_ICON_SIZE = 36.dp
private val SLIDER_HEIGHT = 48.dp
/** The same reach as a pick tile. */
private val CONTROL_HEIGHT = 72.dp
private val CHIP_HEIGHT = 56.dp
private val CONTROL_SHAPE = RoundedCornerShape(20.dp)
private const val WHOLE_NUMBER_SPAN = 10f
