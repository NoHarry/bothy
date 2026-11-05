package cc.noharry.bothy.app.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.bothy.app.a2ui.QuestionCards
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.WakeMigrationNote
import cc.noharry.bothy.protocol.voice

/**
 * The phone's conversation, as the design draws it: the top bar, a greeting with things to ask until something is
 * asked, then the turns ([PhoneTimeline]), and the composer pill at the foot. What is typed while a run goes on
 * steers it, as on the tablet.
 */
@Composable
internal fun PhoneChat(
    state: ServerState,
    pendingInput: String,
    onPendingInputConsumed: () -> Unit,
    actions: ConsoleActions,
    onMenu: () -> Unit,
    links: PhoneLinks,
) {
    var input by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(pendingInput) {
        if (pendingInput.isBlank()) return@LaunchedEffect
        input = pendingInput
        onPendingInputConsumed()
    }
    val submit = {
        val text = input.trim()
        if (text.isNotEmpty()) {
            input = ""
            if (state.isRunning) actions.steer(text) else actions.submit(text)
        }
    }
    val voice = state.voice
    val holding = voice.status.isOn && voice.holding
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = PHONE_MARGIN)) {
            PhoneTopBar(state, actions, onMenu)
            ConsoleTypeOnPhone {
                WakeMigrationNote()
                QuestionCards(state, actions)
            }
            // A hold dims the conversation behind what is heard.
            val behind = Modifier.weight(1f).fillMaxWidth().alpha(if (holding) HELD_DIM else 1f)
            if (state.items.isEmpty() && !state.isRunning) {
                PhoneWelcome(onSuggest = { input = it }, modifier = behind)
            } else {
                PhoneTimeline(state, actions, links, behind)
            }
            PhoneComposer(input, { input = it }, submit, state, actions)
            Spacer(Modifier.height(12.dp))
        }
        if (holding) PhoneHold(voice, Modifier.align(Alignment.BottomCenter))
    }
}

private const val HELD_DIM = 0.25f
