package cc.noharry.bothy.app.a2ui

import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.runtime.A2uiComponentState
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.a2ui.catalog.MaterialA2uiBasicCatalogV1Defaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.app.console.ConsoleColors

/** Whether the A2UI surfaces here take the phone's look ([HostA2uiButton], [HostA2uiText]); the phone's screens say so. */
internal val LocalPhoneA2ui = staticCompositionLocalOf { false }

/**
 * A Button of the console's catalog: stock Material on a tablet; on a phone a row like the phone's cards, as its
 * choices are drawn: the title and its reason, an arrow at the end, the recommended one in the accent's tint.
 */
internal object HostA2uiButton : A2uiBasicCatalogV1.Button {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        childId: String,
        variant: A2uiBasicCatalogV1.Button.Variant,
        action: Map<String, Any?>,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        checks: List<A2uiBasicCatalogV1.CheckRule>,
        modifier: Modifier,
    ) {
        if (!LocalPhoneA2ui.current) {
            with(MaterialA2uiBasicCatalogV1Defaults.button) { TypedContent(childId, variant, action, accessibility, checks, modifier) }
            return
        }
        val colors = ConsoleColors
        val recommended = variant == A2uiBasicCatalogV1.Button.Variant.Primary
        Surface(
            onClick = { dispatchAction(action) },
            enabled = checks.all { it.condition },
            shape = RoundedCornerShape(PHONE_ROW_RADIUS),
            color = if (recommended) colors.accentSoft else colors.elevated,
            border = BorderStroke(1.dp, if (recommended) colors.accent.copy(alpha = RECOMMENDED_EDGE) else colors.border),
            modifier = modifier.fillMaxWidth().padding(vertical = 3.dp).described(accessibility),
        ) {
            Row(Modifier.padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    val state = observeA2uiComponentState(childId)
                    if (state is A2uiComponentState.Success) A2uiComponent(state.component)
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = if (recommended) colors.accent else colors.muted,
                    modifier = Modifier.padding(start = 8.dp).size(20.dp),
                )
            }
        }
    }
}

/** A Text of the console's catalog: stock Material on a tablet, the phone's sizes and colours on a phone. */
internal object HostA2uiText : A2uiBasicCatalogV1.Text {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        text: String,
        variant: A2uiBasicCatalogV1.Text.Variant,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        modifier: Modifier,
    ) {
        if (!LocalPhoneA2ui.current) {
            with(MaterialA2uiBasicCatalogV1Defaults.text) { TypedContent(text, variant, accessibility, modifier) }
            return
        }
        Text(text = text, style = phoneTextStyle(variant), modifier = modifier.described(accessibility))
    }
}

/** What the surface says of a component for screen readers (its label, then its description), as the stock catalog passes it on. */
private fun Modifier.described(accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?): Modifier {
    val words = listOfNotNull(accessibility?.label, accessibility?.description).filter { it.isNotBlank() }.joinToString(", ")
    return if (words.isEmpty()) this else semantics { contentDescription = words }
}

/**
 * The phone's A2UI type. A phone draws these inside the console's panes, which read 16/14 larger there
 * (`ConsoleTypeOnPhone`): a choice's title (H5) shows at 16sp, its reason (Caption) at 13sp, as the phone's reply and labels.
 */
@Composable
private fun phoneTextStyle(variant: A2uiBasicCatalogV1.Text.Variant): TextStyle {
    val colors = ConsoleColors
    return when (variant) {
        A2uiBasicCatalogV1.Text.Variant.H1 -> TextStyle(color = colors.text, fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
        A2uiBasicCatalogV1.Text.Variant.H2 -> TextStyle(color = colors.text, fontSize = 17.5.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
        A2uiBasicCatalogV1.Text.Variant.H3 -> TextStyle(color = colors.text, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
        A2uiBasicCatalogV1.Text.Variant.H4 -> TextStyle(color = colors.text, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
        A2uiBasicCatalogV1.Text.Variant.H5 -> TextStyle(color = colors.text, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
        A2uiBasicCatalogV1.Text.Variant.Caption -> TextStyle(color = colors.muted, fontSize = 11.5.sp, lineHeight = 16.sp)
        A2uiBasicCatalogV1.Text.Variant.Body -> TextStyle(color = colors.text, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

private val PHONE_ROW_RADIUS = 14.dp
private const val RECOMMENDED_EDGE = 0.45f
