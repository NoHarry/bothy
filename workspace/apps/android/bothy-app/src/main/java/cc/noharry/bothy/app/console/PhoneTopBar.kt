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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.session.UiState

/**
 * A portrait phone's top bar, as the phone design draws it: the menu that opens the drawer, what is open (the session's
 * title in the conversation, else the view's name), and in the conversation the model.
 */
@Composable
internal fun PhoneTopBar(state: UiState, view: ConsoleView, actions: ConsoleActions, onMenu: () -> Unit) {
    val colors = ConsoleColors
    val menuLabel = stringResource(R.string.console_open_menu)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            onClick = onMenu,
            shape = CircleShape,
            color = colors.elevated,
            border = BorderStroke(1.dp, colors.borderSubtle),
            modifier = Modifier.size(TOP_BAR_HEIGHT).semantics { contentDescription = menuLabel },
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Menu, contentDescription = null, tint = colors.text) }
        }
        Box(Modifier.weight(1f)) { TitlePill(phoneTitle(state, view)) }
        if (view == ConsoleView.DEBUG) ModelMenu(state.configEditor, actions)
    }
}

/** The conversation's session title, or a new chat's; another view's name. */
@Composable
private fun phoneTitle(state: UiState, view: ConsoleView): String {
    if (view != ConsoleView.DEBUG) return stringResource(view.label)
    val title = state.sessions.firstOrNull { it.sessionId == state.activeSessionId }?.title
    return title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.console_new_chat)
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
private val MARK_SIZE = 36.dp
