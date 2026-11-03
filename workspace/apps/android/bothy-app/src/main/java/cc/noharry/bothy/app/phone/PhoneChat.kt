package cc.noharry.bothy.app.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.a2ui.QuestionCards
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.WakeMigrationNote
import cc.noharry.bothy.app.session.UiState

/**
 * The phone's conversation, as the phone design draws it: the top bar, the turns ([PhoneTimeline]) and the composer
 * pill. What is typed while a run goes on steers it, as on the tablet.
 */
@Composable
internal fun PhoneChat(
    state: UiState,
    pendingInput: String,
    onPendingInputConsumed: () -> Unit,
    actions: ConsoleActions,
    onMenu: () -> Unit,
    onTrajectory: (turn: Long) -> Unit,
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
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        PhoneTopBar(state, actions, onMenu)
        WakeMigrationNote()
        QuestionCards(state, actions)
        if (state.items.isEmpty() && !state.isRunning) {
            PhoneWelcome(onSuggest = { input = it }, modifier = Modifier.weight(1f).fillMaxWidth())
        } else {
            PhoneTimeline(state, actions, onTrajectory, Modifier.weight(1f).fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        // 小博 sits on the composer's top edge, as it floats over other apps; the run's activity shows just above.
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                PhoneActivityStrip(state)
                PhoneComposer(input, { input = it }, submit, state, actions)
            }
            PhoneMascot(state, Modifier.align(Alignment.TopEnd).offset(x = (-64).dp, y = (-46).dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}
