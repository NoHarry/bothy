package cc.noharry.bothy.app.overlay
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import cc.noharry.bothy.app.a2ui.A2uiChoice
import cc.noharry.bothy.app.a2ui.A2uiChoiceSurface
import cc.noharry.bothy.app.a2ui.BothyA2uiSurface
import cc.noharry.bothy.app.a2ui.Control
import cc.noharry.bothy.app.a2ui.DisplayChoice
import cc.noharry.bothy.app.a2ui.WidgetCard
import cc.noharry.bothy.app.a2ui.a2uiCardMessages
import cc.noharry.bothy.app.a2ui.a2uiChoiceMessages
import cc.noharry.bothy.app.a2ui.a2uiPanelMessages
import cc.noharry.bothy.app.a2ui.controlValues
import cc.noharry.bothy.app.a2ui.offerKey
import cc.noharry.bothy.app.a2ui.panelReply

/**
 * Compose needs lifecycle and saved-state owners on the view tree; an overlay window added by a Service has none.
 * One owner lives as long as the overlay and is resumed while it is shown.
 */
internal class OverlayComposeOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    fun attachTo(root: View) {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
        root.setViewTreeLifecycleOwner(this)
        root.setViewTreeSavedStateRegistryOwner(this)
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

/** The finished reply's picks as a host-compiled A2UI surface; a tap reports the matching choice. */
@Composable
internal fun OverlayA2uiChoices(
    choices: List<DisplayChoice>,
    onPick: (DisplayChoice) -> Unit,
    modifier: Modifier = Modifier,
    icons: Map<String, String> = emptyMap(),
) {
    val messages = remember(choices, icons) {
        a2uiChoiceMessages(
            surfaceId = "pick-${choices.hashCode().toUInt()}",
            prompt = null,
            choices = choices.map { A2uiChoice(it.label, it.recommended, it.iconFrom(icons)) },
            firstOrdinal = choices.firstOrNull()?.ordinal ?: 1,
        )
    }
    A2uiChoiceSurface(
        messages,
        onPick = { ordinal -> choices.firstOrNull { it.ordinal == ordinal }?.let(onPick) },
        modifier = modifier,
    )
}

/** The reply's information card (weather and the like), car look; it shows and asks nothing. */
@Composable
internal fun OverlayA2uiCard(card: WidgetCard, modifier: Modifier = Modifier, carLook: Boolean = true) {
    val messages = remember(card) { a2uiCardMessages("card-${card.hashCode().toUInt()}", card) }
    BothyA2uiSurface(messages, onEvent = {}, modifier = modifier, carLook = carLook)
}

/**
 * The reply's panel (sliders, switches, choices); one confirm reports the changed values as the reply the user
 * would say ("亮度设为30%，座椅加热打开").
 */
@Composable
internal fun OverlayA2uiPanel(
    controls: List<Control>,
    onSet: (String) -> Unit,
    modifier: Modifier = Modifier,
    carLook: Boolean = true,
) {
    val messages = remember(controls) { a2uiPanelMessages("panel-${controls.hashCode().toUInt()}", controls) }
    BothyA2uiSurface(
        messages,
        onEvent = { event ->
            event.controlValues(controls)?.let { panelReply(controls, it) }?.takeIf { it.isNotEmpty() }?.let(onSet)
        },
        modifier = modifier,
        carLook = carLook,
    )
}

/** A pick's icon: the offered label its line starts with (the line may add a note), longest first. */
internal fun DisplayChoice.iconFrom(icons: Map<String, String>): String? {
    val line = label.offerKey()
    return icons.entries.filter { (offered, _) -> offered.offerKey().let { it.isNotEmpty() && line.startsWith(it) } }
        .maxByOrNull { it.key.length }?.value
}

