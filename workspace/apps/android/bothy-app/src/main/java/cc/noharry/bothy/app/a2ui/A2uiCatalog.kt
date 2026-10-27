package cc.noharry.bothy.app.a2ui
import android.icu.text.MessageFormat
import androidx.a2ui.compose.runtime.A2uiComponentProperties
import androidx.a2ui.compose.runtime.A2uiComponentReference
import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.runtime.A2uiComponentState
import androidx.a2ui.compose.runtime.A2uiProperty
import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.a2ui.model.catalog.functions.A2uiLocaleProvider
import androidx.a2ui.model.catalog.functions.A2uiMessageFormatter
import androidx.a2ui.model.catalog.functions.A2uiUrlOpener
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.a2ui.catalog.A2uiAudioPlayerRenderer
import androidx.compose.material3.a2ui.catalog.A2uiImageRenderer
import androidx.compose.material3.a2ui.catalog.A2uiVideoRenderer
import androidx.compose.material3.a2ui.catalog.MaterialA2uiBasicCatalogV1Defaults
import androidx.compose.material3.a2ui.catalog.materialA2uiBasicCatalogV1
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.ui.icon
import cc.noharry.bothy.app.image.GENERATED_IMAGES_DIR
import cc.noharry.bothy.app.image.rememberPicture
import cc.noharry.bothy.app.overlay.CarA2uiButton
import cc.noharry.bothy.app.overlay.CarA2uiChoicePicker
import cc.noharry.bothy.app.overlay.CarA2uiDivider
import cc.noharry.bothy.app.overlay.CarA2uiIcon
import cc.noharry.bothy.app.overlay.CarA2uiRow
import cc.noharry.bothy.app.overlay.CarA2uiSlider
import cc.noharry.bothy.app.overlay.CarA2uiSwitch
import cc.noharry.bothy.app.overlay.CarA2uiText
import cc.noharry.bothy.app.overlay.OverlayPalette
import cc.noharry.bothy.app.plans.number

/**
 * The host's A2UI catalog: the Material basic catalog plus components it lacks, under the host's own catalog ID.
 * The protocol stays standard; only `Progress`, `Grid` and a trip's `Ticket`, `Stop` and `Stay` are ours. [carLook] is the overlay's glass; without it the
 * stock Material look follows the console's theme.
 *
 * Images load pictures saved on the device or public https photos (guarded, through Glide); video, audio and URL
 * opening stay inert.
 */
internal fun a2uiCatalog(carLook: Boolean = true): A2uiCatalog {
    val image = MaterialA2uiBasicCatalogV1Defaults.image(PictureRenderer)
    val video = MaterialA2uiBasicCatalogV1Defaults.video(NoMediaRenderer)
    val audioPlayer = MaterialA2uiBasicCatalogV1Defaults.audioPlayer(NoMediaRenderer)
    val basic = if (carLook) {
        materialA2uiBasicCatalogV1(
            image = image,
            video = video,
            audioPlayer = audioPlayer,
            urlOpener = A2uiUrlOpener { },
            messageFormatter = IcuMessageFormatter,
            localeProvider = A2uiLocaleProvider.Default,
            // Same protocol and component names; only the look is the device's.
            text = CarA2uiText,
            icon = CarA2uiIcon,
            row = CarA2uiRow,
            button = CarA2uiButton,
            slider = CarA2uiSlider,
            checkBox = CarA2uiSwitch,
            choicePicker = CarA2uiChoicePicker,
            divider = CarA2uiDivider,
        )
    } else {
        materialA2uiBasicCatalogV1(
            image = image,
            video = video,
            audioPlayer = audioPlayer,
            urlOpener = A2uiUrlOpener { },
            messageFormatter = IcuMessageFormatter,
            localeProvider = A2uiLocaleProvider.Default,
        )
    }
    return A2uiCatalog(
        DEBUG_A2UI_CATALOG_URI,
        basic.components + listOf(
            A2uiProgress(carLook), A2uiGrid(carLook),
            A2uiTicket(carLook), A2uiStop(carLook), A2uiStay(carLook),
        ),
        basic.functions,
        basic.themeSchema,
        false,
    )
}

/**
 * `Progress`: a labelled bar for a value within 0..max (battery, a delegated task's steps, the way still to drive).
 * [TEXT] replaces the computed percentage when the value reads better in its own unit ("12/30 km").
 */
internal class A2uiProgress(private val carLook: Boolean) : A2uiComponent {
    override val name: String = PROGRESS_COMPONENT
    override val description: String = "A labelled horizontal bar showing value within 0..max."
    override val properties: List<A2uiProperty<*>> = listOf(LABEL, VALUE, MAX, TEXT)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val max = properties[MAX]?.toFloat()?.takeIf { it > 0f } ?: 1f
        val fraction = ((properties[VALUE]?.toFloat() ?: 0f) / max).coerceIn(0f, 1f)
        val label = properties[LABEL].orEmpty()
        val shown = properties[TEXT]?.takeIf { it.isNotBlank() } ?: "${(fraction * 100).toInt()}%"
        if (carLook) CarProgress(label, shown, fraction, modifier) else ConsoleProgress(label, shown, fraction, modifier)
    }

    private companion object {
        val LABEL = A2uiProperty.string("label", false, "What the bar measures, such as 电量.")
        val VALUE = A2uiProperty.number("value", true, "The current value, 0..max.")
        val MAX = A2uiProperty.number("max", false, "The full value; 1 when absent.")
        val TEXT = A2uiProperty.string("text", false, "The value as shown, such as 78% or 12/30 km.")
    }
}

@Composable
private fun CarProgress(label: String, shown: String, fraction: Float, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, style = TextStyle(color = OverlayPalette.text, fontSize = 18.sp, fontWeight = FontWeight.Medium))
            Spacer(Modifier.weight(1f))
            Text(shown, style = TextStyle(brush = OverlayPalette.signature, fontSize = 24.sp, fontWeight = FontWeight.Light))
        }
        Box(Modifier.fillMaxWidth().height(PROGRESS_TRACK).clip(RoundedCornerShape(50)).background(OverlayPalette.tileEdge)) {
            if (fraction > 0f) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(50)).background(OverlayPalette.signature))
            }
        }
    }
}

@Composable
private fun ConsoleProgress(label: String, shown: String, fraction: Float, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current)
            Spacer(Modifier.weight(1f))
            Text(shown, style = MaterialTheme.typography.titleMedium, color = LocalContentColor.current)
        }
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * `Grid`: children in rows of [COLUMNS] equal-width cells, for 2×2 readings and side-by-side comparisons. A basic Row
 * sizes its children by their content, so columns of different text would not line up. Each cell is a tile unless
 * [FRAMED] is false.
 */
internal class A2uiGrid(private val carLook: Boolean) : A2uiComponent {
    override val name: String = GRID_COMPONENT
    override val description: String = "Children laid out in rows of equal-width columns."
    override val properties: List<A2uiProperty<*>> = listOf(CHILDREN, COLUMNS, FRAMED)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val children = properties.bindChildReferences(CHILDREN).orEmpty()
        val columns = (properties[COLUMNS]?.toInt() ?: 2).coerceIn(1, MAX_GRID_COLUMNS)
        val framed = properties[FRAMED] ?: true
        val gap = if (carLook) 12.dp else 8.dp
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
            children.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { child -> Cell(this@Content, child, framed, Modifier.weight(1f)) }
                    // A short last row keeps the column width instead of stretching its cells.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    @Composable
    private fun Cell(scope: A2uiComponentScope, child: A2uiComponentReference, framed: Boolean, modifier: Modifier) {
        val state = scope.observeA2uiComponentState(child.id, child.baseDataPath)
        val frame = when {
            !framed -> modifier
            carLook -> modifier
                .clip(GRID_CELL_SHAPE)
                .background(OverlayPalette.tile)
                .border(1.dp, OverlayPalette.tileEdge, GRID_CELL_SHAPE)
                .padding(horizontal = 16.dp, vertical = 14.dp)
            else -> modifier
                .clip(GRID_CELL_SHAPE)
                .border(1.dp, LocalContentColor.current.copy(alpha = 0.16f), GRID_CELL_SHAPE)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        }
        // The child fills its cell, so a column's own alignment (a strip's centred day) is measured across the cell.
        Box(frame) { if (state is A2uiComponentState.Success) androidx.a2ui.compose.ui.A2uiComponent(state.component, Modifier.fillMaxWidth()) }
    }

    private companion object {
        val CHILDREN = A2uiProperty.childList("children", true, "The cells, in reading order.")
        val COLUMNS = A2uiProperty.number("columns", false, "Cells per row, 1..6; 2 when absent.")
        val FRAMED = A2uiProperty.boolean("framed", false, "Draw each cell as a tile; true when absent.")
    }
}

/**
 * A picture saved on the device (generated, or delegated and downloaded from the relay) or a public https URL, loaded
 * by Glide ([rememberPicture]); anything else draws nothing. While it loads, the slot holds a faint tile so the card
 * does not jump when the photo arrives.
 */
private object PictureRenderer : A2uiImageRenderer {
    @Composable
    override fun Image(
        url: String,
        contentDescription: String?,
        contentScale: ContentScale,
        modifier: Modifier,
        onError: (Throwable?) -> Unit,
    ) {
        val pixels = with(LocalDensity.current) { PICTURE_DECODE.roundToPx() }
        val picture by rememberPicture(url, pixels)
        val shaped = modifier.clip(RoundedCornerShape(12.dp))
        picture?.let { Image(it, contentDescription, shaped, contentScale = contentScale) }
            ?: Box(shaped.background(LocalContentColor.current.copy(alpha = 0.06f)))
    }
}

private object NoMediaRenderer : A2uiVideoRenderer, A2uiAudioPlayerRenderer {
    @Composable
    override fun Video(url: String, modifier: Modifier, onError: (Throwable?) -> Unit) = Unit

    @Composable
    override fun AudioPlayer(url: String, contentDescription: String?, modifier: Modifier, onError: (Throwable?) -> Unit) = Unit
}

private object IcuMessageFormatter : A2uiMessageFormatter {
    override fun format(pattern: String, locale: java.util.Locale, arguments: Map<String, Any>): String =
        MessageFormat(pattern, locale).format(arguments)
}

/** A picture the device saved itself; `show_card.image` takes this or a public https URL ([remotePictureUrl]). */
internal val SAVED_PICTURE = Regex("$GENERATED_IMAGES_DIR/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+\\.(?:jpg|png|webp)")

/** Our catalog's ID: createSurface must name it, or the engine creates no surface and reports nothing. */
internal const val DEBUG_A2UI_CATALOG_URI = "urn:bothy:a2ui:glass-catalog:v1"
internal const val PROGRESS_COMPONENT = "Progress"
internal const val GRID_COMPONENT = "Grid"
internal const val MAX_GRID_COLUMNS = 6
private val PROGRESS_TRACK = 10.dp
private val GRID_CELL_SHAPE = RoundedCornerShape(16.dp)
private val PICTURE_DECODE = 480.dp
