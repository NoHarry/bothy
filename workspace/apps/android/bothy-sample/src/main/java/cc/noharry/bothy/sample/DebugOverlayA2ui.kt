package cc.noharry.bothy.sample

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

/**
 * Compose needs lifecycle and saved-state owners on the view tree; an overlay window added by a Service has none.
 * One owner lives as long as the overlay and is resumed while it is shown.
 */
internal class DebugOverlayComposeOwner : LifecycleOwner, SavedStateRegistryOwner {
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
internal fun DebugOverlayA2uiChoices(
    choices: List<DebugDisplayChoice>,
    onPick: (DebugDisplayChoice) -> Unit,
    modifier: Modifier = Modifier,
    icons: Map<String, String> = emptyMap(),
) {
    val messages = remember(choices, icons) {
        debugA2uiChoiceMessages(
            surfaceId = "pick-${choices.hashCode().toUInt()}",
            prompt = null,
            choices = choices.map { DebugA2uiChoice(it.label, it.recommended, it.iconFrom(icons)) },
            firstOrdinal = choices.firstOrNull()?.ordinal ?: 1,
        )
    }
    DebugA2uiChoiceSurface(
        messages,
        onPick = { ordinal -> choices.firstOrNull { it.ordinal == ordinal }?.let(onPick) },
        modifier = modifier,
    )
}

/** The reply's information card (weather and the like), car look; it shows and asks nothing. */
@Composable
internal fun DebugOverlayA2uiCard(card: DebugCard, modifier: Modifier = Modifier, carLook: Boolean = true) {
    val messages = remember(card) { debugA2uiCardMessages("card-${card.hashCode().toUInt()}", card) }
    DebugA2uiSurface(messages, onEvent = {}, modifier = modifier, carLook = carLook)
}

/**
 * The reply's panel (sliders, switches, choices); one confirm reports the changed values as the reply the user
 * would say ("亮度设为30%，座椅加热打开").
 */
@Composable
internal fun DebugOverlayA2uiPanel(
    controls: List<DebugControl>,
    onSet: (String) -> Unit,
    modifier: Modifier = Modifier,
    carLook: Boolean = true,
) {
    val messages = remember(controls) { debugA2uiPanelMessages("panel-${controls.hashCode().toUInt()}", controls) }
    DebugA2uiSurface(
        messages,
        onEvent = { event ->
            event.controlValues(controls)?.let { debugPanelReply(controls, it) }?.takeIf { it.isNotEmpty() }?.let(onSet)
        },
        modifier = modifier,
        carLook = carLook,
    )
}

/** A pick's icon: the offered label its line starts with (the line may add a note), longest first. */
internal fun DebugDisplayChoice.iconFrom(icons: Map<String, String>): String? {
    val line = label.offerKey()
    return icons.entries.filter { (offered, _) -> offered.offerKey().let { it.isNotEmpty() && line.startsWith(it) } }
        .maxByOrNull { it.key.length }?.value
}

