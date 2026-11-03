package cc.noharry.bothy.app.phone

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.a2ui.QuestionCards
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConversationEmptyState
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
    onTrajectory: () -> Unit,
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
            ConversationEmptyState(Modifier.weight(1f).fillMaxWidth())
        } else {
            PhoneTimeline(state, actions, onTrajectory, Modifier.weight(1f).fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        PhoneComposer(input, { input = it }, submit, state, actions)
        Spacer(Modifier.height(8.dp))
    }
}
