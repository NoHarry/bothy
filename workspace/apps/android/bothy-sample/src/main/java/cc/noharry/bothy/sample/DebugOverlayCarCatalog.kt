package cc.noharry.bothy.sample

import androidx.a2ui.compose.runtime.A2uiComponentReference
import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.runtime.A2uiComponentState
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.a2ui.catalog.MaterialA2uiBasicCatalogV1Defaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Car renderers for the A2UI basic catalog. The protocol stays standard, so any agent's Button and Text land in the
 * car style: glass tiles sized for a reaching hand, a gradient numeral when the action carries an ordinal, and the
 * recommended (primary) pick marked by a gradient edge and badge rather than by size.
 */
internal object CarA2uiText : A2uiBasicCatalogV1.Text {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        text: String,
        variant: A2uiBasicCatalogV1.Text.Variant,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        modifier: Modifier,
    ) {
        Text(text = text, style = carTextStyle(variant), modifier = modifier)
    }
}

/**
 * A row of two ends pushed apart (a list item: its name and details, then its reading) gives the trailing child its
 * own width, up to [TRAILING_SHARE] of the row, and the first child the rest. The basic Row measures the first
 * child at full width, so a long detail line left the reading a sliver one or two characters wide (bench 2026-10-19,
 * 「15:30 · 89.7元起」 stacked as 「15: / 30 / • / 89.」). Other rows keep the basic layout.
 */
internal object CarA2uiRow : A2uiBasicCatalogV1.Row {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        children: List<A2uiComponentReference>,
        justify: A2uiBasicCatalogV1.Row.Justify,
        align: A2uiBasicCatalogV1.Row.Align,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        modifier: Modifier,
    ) {
        // Two ends only: a card's fields (up to four, spread evenly) keep the basic layout.
        if (justify != A2uiBasicCatalogV1.Row.Justify.SpaceBetween || children.size != 2) {
            with(MaterialA2uiBasicCatalogV1Defaults.row) { TypedContent(children, justify, align, accessibility, modifier) }
            return
        }
        val vertical = when (align) {
            A2uiBasicCatalogV1.Row.Align.Start -> Alignment.Top
            A2uiBasicCatalogV1.Row.Align.End -> Alignment.Bottom
            else -> Alignment.CenterVertically
        }
        BoxWithConstraints(modifier.fillMaxWidth()) {
            val trailing = maxWidth * TRAILING_SHARE
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = vertical) {
                RowChild(this@TypedContent, children.first(), Modifier.weight(1f))
                children.drop(1).forEach { RowChild(this@TypedContent, it, Modifier.widthIn(max = trailing)) }
            }
        }
    }

    private const val TRAILING_SHARE = 0.4f
}

@Composable
private fun RowChild(scope: A2uiComponentScope, child: A2uiComponentReference, modifier: Modifier) {
    val state = scope.observeA2uiComponentState(child.id, child.baseDataPath)
    Box(modifier) { if (state is A2uiComponentState.Success) A2uiComponent(state.component) }
}

internal object CarA2uiButton : A2uiBasicCatalogV1.Button {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        childId: String,
        variant: A2uiBasicCatalogV1.Button.Variant,
        action: Map<String, Any?>,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        checks: List<A2uiBasicCatalogV1.CheckRule>,
        modifier: Modifier,
    ) {
        val recommended = variant == A2uiBasicCatalogV1.Button.Variant.Primary
        val ordinal = action.pickOrdinal()
        val motion = rememberTileMotion(ordinal, recommended)
        Surface(
            onClick = { dispatchAction(action) },
            enabled = checks.all { it.condition },
            shape = TileShape,
            color = if (recommended) OverlayPalette.tileRecommended else OverlayPalette.tile,
            border = if (recommended) BorderStroke(1.5.dp, OverlayPalette.signature) else BorderStroke(1.dp, OverlayPalette.tileEdge),
            interactionSource = motion.press,
            modifier = modifier.fillMaxWidth().padding(vertical = 5.dp).then(motion.layer).heightIn(min = 72.dp),
        ) {
            Box(motion.shine) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                ) {
                    ordinal?.let { Numeral(it) }
                    Box(Modifier.weight(1f)) { ChildComponent(this@TypedContent, childId) }
                    if (recommended) RecommendedBadge()
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = OverlayPalette.faint, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

/** A tile's motion: [layer] goes on the tile, [shine] inside its clip, [press] to its click source. */
private class TileMotion(val press: MutableInteractionSource, val layer: Modifier, val shine: Modifier)

/**
 * Tiles rise in one after another in reading order, the recommended one catches a single sweep of light once it
 * has landed, and a pressed tile gives under the finger. Every value is read inside a layer or draw block, so the
 * motion repaints tiles without recomposing them, and nothing runs once they have settled.
 */
@Composable
private fun rememberTileMotion(ordinal: Int?, recommended: Boolean): TileMotion {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val give by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "give")
    val rise = remember { Animatable(0f) }
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(((ordinal ?: 1) - 1).coerceAtLeast(0) * TILE_STAGGER_MS)
        rise.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
        if (recommended) sweep.animateTo(1f, tween(SHINE_MS, easing = FastOutSlowInEasing))
    }
    val layer = Modifier.graphicsLayer {
        alpha = rise.value
        translationY = (1f - rise.value) * 18.dp.toPx()
        scaleX = give
        scaleY = give
    }
    val shine = Modifier.drawWithContent {
        drawContent()
        val at = sweep.value
        if (at > 0f && at < 1f) {
            // A slanted band of light crossing from left to right.
            val x = -size.width * 0.3f + at * size.width * 1.6f
            drawRect(
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0f)),
                    start = Offset(x - size.height, 0f),
                    end = Offset(x, size.height),
                ),
            )
        }
    }
    return remember(press) { TileMotion(press, layer, shine) }
}

@Composable
private fun ChildComponent(scope: A2uiComponentScope, id: String) {
    val state = scope.observeA2uiComponentState(id)
    if (state is A2uiComponentState.Success) A2uiComponent(state.component)
}

@Composable
private fun Numeral(ordinal: Int) {
    Text(
        "$ordinal",
        style = TextStyle(brush = OverlayPalette.signature, fontSize = 26.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center),
        modifier = Modifier.widthIn(min = 24.dp),
    )
}

@Composable
private fun RecommendedBadge() {
    Text(
        "推荐",
        color = OverlayPalette.onSignature,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier
            .background(OverlayPalette.signature, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** The pick ordinal our host compiler puts in `event.context.ordinal`; other agents' buttons simply have none. */
private fun Map<String, Any?>.pickOrdinal(): Int? {
    val event = this["event"] as? Map<*, *> ?: return null
    val context = event["context"] as? Map<*, *> ?: return null
    return (context["ordinal"] as? Number)?.toInt()
}

private fun carTextStyle(variant: A2uiBasicCatalogV1.Text.Variant): TextStyle = when (variant) {
    A2uiBasicCatalogV1.Text.Variant.H1 -> TextStyle(color = OverlayPalette.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
    A2uiBasicCatalogV1.Text.Variant.H2 -> TextStyle(color = OverlayPalette.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
    A2uiBasicCatalogV1.Text.Variant.H3 -> TextStyle(color = OverlayPalette.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    A2uiBasicCatalogV1.Text.Variant.H4 -> TextStyle(color = OverlayPalette.text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    A2uiBasicCatalogV1.Text.Variant.H5 -> TextStyle(color = OverlayPalette.text, fontSize = 18.sp, fontWeight = FontWeight.Medium, lineHeight = 25.sp)
    A2uiBasicCatalogV1.Text.Variant.Caption -> TextStyle(color = OverlayPalette.secondary, fontSize = 14.sp, lineHeight = 20.sp)
    A2uiBasicCatalogV1.Text.Variant.Body -> TextStyle(color = OverlayPalette.text, fontSize = 17.sp, lineHeight = 25.sp)
}

private val TileShape = RoundedCornerShape(20.dp)
/** Long enough to read as a sequence, short enough that eight tiles are all in within a third of a second. */
private const val TILE_STAGGER_MS = 45L
private const val SHINE_MS = 750

/**
 * Glass palette for the overlay. The card is a tinted pane that lets the map show through; text keeps full contrast
 * and hierarchy comes from white at falling opacity. The signature gradient (sage to the hut window's glow) marks the
 * assistant and its recommendation; the glow alone marks what is running or picked.
 */
internal object OverlayPalette {
    /**
     * Denser where the header text sits, thinner below: the map shows through without other floating widgets'
     * large type (a nav card's "64 米") bleeding into the status line.
     */
    val glass = Brush.verticalGradient(
        0f to Color(0xFF0D1210).copy(alpha = 0.92f),
        0.35f to Color(0xFF0D1210).copy(alpha = 0.86f),
        1f to Color(0xFF0D1210).copy(alpha = 0.78f),
    )
    val glassInset = Color.White.copy(alpha = 0.05f)
    val edge = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.05f)))
    val tile = Color.White.copy(alpha = 0.06f)
    val tileEdge = Color.White.copy(alpha = 0.10f)
    val tileRecommended = Color(0xFF8CC7A5).copy(alpha = 0.14f)
    val hairline = Color.White.copy(alpha = 0.10f)
    val text = Color(0xFFF5F2EA)
    val secondary = Color.White.copy(alpha = 0.66f)
    val faint = Color.White.copy(alpha = 0.40f)
    val sage = Color(0xFF8CC7A5)
    val glow = Color(0xFFF5C26B)
    val heather = Color(0xFFB79AC8)
    val signature = Brush.linearGradient(listOf(sage, glow))
    val onSignature = Color(0xFF0D1210)
    /** The confirmed pick: the signature gradient as tinted glass. */
    val chosenFill = Brush.linearGradient(listOf(sage.copy(alpha = 0.24f), glow.copy(alpha = 0.14f)))
    val live = Color(0xFF4ADE80)
    val warm = Color(0xFFF2925A)
    val amber = Color(0xFFF5B85C)
}
