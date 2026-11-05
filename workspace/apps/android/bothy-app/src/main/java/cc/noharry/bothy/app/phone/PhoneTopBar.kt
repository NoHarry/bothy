package cc.noharry.bothy.app.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.session.sessionTitleText

/**
 * The conversation's top bar, as the design keeps it: the menu that opens the drawer and the chat's title; a new chat
 * on the right once something was asked. The model is the composer's ([PhoneComposer]).
 */
@Composable
internal fun PhoneTopBar(state: ServerState, actions: ConsoleActions, onMenu: () -> Unit) {
    BarRow {
        RoundButton(Icons.Filled.Menu, stringResource(R.string.console_open_menu), onMenu)
        BarTitle(phoneTitle(state), subtitle = null, Modifier.weight(1f))
        // A chat nothing was asked in yet is already new.
        if (state.items.isNotEmpty() || state.isRunning) RoundButton(Icons.Filled.Add, stringResource(R.string.console_new_chat), actions.newSession)
    }
}

/**
 * A page opened over the conversation: back to it, the page's name (and a line under it), what the page offers on the
 * right ([action]), then the page.
 */
@Composable
internal fun PhonePage(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = PHONE_MARGIN)) {
        BarRow {
            RoundButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), onBack)
            BarTitle(title, subtitle, Modifier.weight(1f))
            action?.invoke()
        }
        content()
    }
}

@Composable
private fun BarRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(BAR_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BAR_GAP),
        content = content,
    )
}

@Composable
private fun BarTitle(title: String, subtitle: String?, modifier: Modifier) {
    Column(modifier) {
        Text(title, style = PhoneType.title, color = ConsoleColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, style = PhoneType.label, color = ConsoleColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit) = RoundButton(rememberVectorPainter(icon), label, onClick)

/** A round button on the paper: the bars' menu, back and new chat. */
@Composable
internal fun RoundButton(icon: Painter, label: String, onClick: () -> Unit) {
    val colors = ConsoleColors
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = colors.panel,
        modifier = Modifier.size(ROUND_BUTTON_SIZE).semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = colors.text, modifier = Modifier.size(24.dp)) }
    }
}

/** The open session's title, or "New chat" until something is asked in it. */
@Composable
private fun phoneTitle(state: ServerState): String {
    val session = state.sessions.firstOrNull { it.sessionId == state.activeSessionId }?.takeIf { it.turnCount > 0 || state.items.isNotEmpty() }
    return session?.title?.takeIf { it.isNotBlank() }?.let { sessionTitleText(it) } ?: stringResource(R.string.console_new_chat)
}

private val BAR_HEIGHT = 64.dp
private val BAR_GAP = 16.dp
private val ROUND_BUTTON_SIZE = 44.dp
