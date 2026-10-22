@file:OptIn(ExperimentalA2aClientApi::class)

package cc.noharry.bothy.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.noharry.agent.a2a.A2aTask
import cc.noharry.agent.a2a.ExperimentalA2aClientApi

/**
 * The finished reply's picks in the full-screen console, the same host-compiled A2UI surface as the overlay card in
 * the console's own theme. A tap sends the spoken ordinal reply ("第N个") once, like a tap on the card.
 */
@Composable
internal fun DebugConsoleChoices(
    choices: List<DebugDisplayChoice>,
    onPick: (DebugDisplayChoice) -> Unit,
    icons: Map<String, String> = emptyMap(),
) {
    var picked by remember(choices) { mutableStateOf(false) }
    val messages = remember(choices, icons) {
        debugA2uiChoiceMessages(
            surfaceId = "console-pick-${choices.hashCode().toUInt()}",
            prompt = null,
            choices = choices.map { DebugA2uiChoice(it.label, it.recommended, it.iconFrom(icons)) },
        )
    }
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("选一个", style = DebugType.meta, color = DebugColors.muted)
            Spacer(Modifier.weight(1f))
            Text("说「第二个」也可以", style = DebugType.meta, color = DebugColors.tertiary)
        }
        DebugA2uiChoiceSurface(
            messages,
            onPick = { ordinal ->
                val choice = choices.firstOrNull { it.ordinal == ordinal }
                if (choice != null && !picked) {
                    picked = true
                    onPick(choice)
                }
            },
            carLook = false,
        )
    }
}

/**
 * Everything the reply shows beyond its text, in the console's theme: the cards, the panel, then the picks. Each
 * answer goes out once as the reply the user would say ("第2个", "设为30%，座椅加热打开").
 */
@Composable
internal fun DebugConsoleWidgets(pickable: DebugPickableReply, onReply: (String) -> Unit) {
    // Stock A2UI Text and Icon take the content colour; outside a Surface that is black, unreadable on the dark theme.
    CompositionLocalProvider(LocalContentColor provides DebugColors.text) { ConsoleWidgetColumn(pickable, onReply) }
}

@Composable
private fun ConsoleWidgetColumn(pickable: DebugPickableReply, onReply: (String) -> Unit) {
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        pickable.cards.forEach { card ->
            card.framedPicture?.let { GeneratedImageRow(DebugOverlayPicture(it, card.title)) }
            DebugOverlayA2uiCard(card, carLook = false)
        }
        pickable.controls.takeIf { it.isNotEmpty() }?.let { controls ->
            var sent by remember(controls) { mutableStateOf(false) }
            DebugOverlayA2uiPanel(
                controls,
                onSet = { text ->
                    if (!sent) {
                        sent = true
                        onReply(text)
                    }
                },
                carLook = false,
            )
        }
        if (pickable.reply.choices.isNotEmpty()) {
            DebugConsoleChoices(pickable.reply.choices, { choice -> onReply(choice.replyText) }, pickable.icons)
        }
    }
}

/**
 * Client mode's pickable reply, the overlay card's rule: the current task has ended (not cancelled), nothing runs
 * or is mirrored, and its display text lists picks.
 */
internal fun DebugA2aClientState.pickableDisplay(): DebugPickableReply? {
    if (running || mirrors.isNotEmpty()) return null
    val turn = currentTurnItems()
    val terminal = turn.lastOrNull { it is DebugA2aClientItem.Terminal } as? DebugA2aClientItem.Terminal ?: return null
    if (terminal.state == A2aTask.Status.CANCELLED) return null
    val display = turn.lastOrNull { it is DebugA2aClientItem.Display } as? DebugA2aClientItem.Display ?: return null
    // This car's own server registered the offer under the A2A context's session; a remote server's never is.
    val offer = contextId?.let { DebugOfferStore.latest("a2a-$it") }
    return debugPickableReply(display.id, display.text.overlayHudMultiline(Int.MAX_VALUE), offer)
}
