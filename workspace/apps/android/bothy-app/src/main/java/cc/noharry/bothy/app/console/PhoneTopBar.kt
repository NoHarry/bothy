package cc.noharry.bothy.app.console

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ui.PopupMenuItem
import cc.noharry.bothy.app.console.ui.PopupSectionLabel
import cc.noharry.bothy.app.session.sessionTitleText
import cc.noharry.bothy.app.session.UiState

/**
 * A portrait phone's top bar, as the phone design draws it. In the conversation: the menu that opens the drawer, the
 * session's title and the model ([PhoneModelMenu]). Elsewhere (plans, the trajectory, A2A): back to the conversation, and the view's name.
 */
@Composable
internal fun PhoneTopBar(state: UiState, view: ConsoleView, actions: ConsoleActions, onMenu: () -> Unit, onBack: () -> Unit) {
    val colors = ConsoleColors
    val conversation = view == ConsoleView.DEBUG
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (conversation) {
            RoundButton(Icons.Filled.Menu, stringResource(R.string.console_open_menu), onMenu)
            Box(Modifier.weight(1f)) { TitlePill(phoneTitle(state)) }
            PhoneModelMenu(state, actions)
        } else {
            RoundButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), onBack)
            Text(stringResource(view.label), style = ConsoleType.panelTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The model, and the run's other choices with it on a phone: approval, the variant and the actions, which the wide
 * composer shows as chips of their own. A warning approval keeps its amber dot on the button.
 */
@Composable
private fun PhoneModelMenu(state: UiState, actions: ConsoleActions) {
    val mode = state.confirmationMode
    ModelMenu(
        state.configEditor,
        actions,
        Modifier.widthIn(max = MODEL_PILL_MAX_WIDTH),
        leadingDot = ConsoleColors.warning.takeIf { mode.warning },
    ) { close ->
        ConfirmationModeItems(mode, actions, compact = true, onPicked = close)
        VariantItems(state.configEditor, actions, onPicked = close)
        PopupSectionLabel(stringResource(R.string.console_actions))
        PopupMenuItem(label = stringResource(R.string.console_floating_card), selected = false, onClick = { close(); actions.openOverlay() })
        PopupMenuItem(label = stringResource(R.string.console_toggle_stream), selected = false, onClick = { close(); actions.toggleStream() })
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = ConsoleColors
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = colors.elevated,
        border = BorderStroke(1.dp, colors.borderSubtle),
        modifier = Modifier.size(TOP_BAR_HEIGHT).semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = colors.text) }
    }
}

/** The conversation's session title, or a new chat's. */
@Composable
private fun phoneTitle(state: UiState): String {
    val title = state.sessions.firstOrNull { it.sessionId == state.activeSessionId }?.title
    return title?.takeIf { it.isNotBlank() }?.let { sessionTitleText(it) } ?: stringResource(R.string.console_new_chat)
}

@Composable
private fun TitlePill(title: String) {
    val colors = ConsoleColors
    Surface(shape = RoundedCornerShape(Dimens.radiusFull), color = colors.elevated, border = BorderStroke(1.dp, colors.borderSubtle)) {
        Row(Modifier.padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.bothy_mark), contentDescription = null, modifier = Modifier.size(MARK_SIZE).clip(CircleShape))
            Text(
                text = title,
                style = ConsoleType.panelTitle,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

private val TOP_BAR_HEIGHT = 48.dp
private val MARK_SIZE = 30.dp
private val MODEL_PILL_MAX_WIDTH = 150.dp
