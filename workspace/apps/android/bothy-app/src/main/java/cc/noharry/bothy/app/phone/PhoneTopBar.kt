package cc.noharry.bothy.app.phone

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import cc.noharry.bothy.app.console.ConfirmationModeItems
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.ModelMenu
import cc.noharry.bothy.app.console.VariantItems
import cc.noharry.bothy.app.console.ui.PopupMenuItem
import cc.noharry.bothy.app.console.ui.PopupSectionLabel
import cc.noharry.bothy.app.session.UiState
import cc.noharry.bothy.app.session.sessionTitleText

/** The conversation's top bar, as the phone design draws it: the menu that opens the drawer, the session, the model. */
@Composable
internal fun PhoneTopBar(state: UiState, actions: ConsoleActions, onMenu: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RoundButton(Icons.Filled.Menu, stringResource(R.string.console_open_menu), onMenu)
        Box(Modifier.weight(1f)) { TitlePill(phoneTitle(state)) }
        PhoneModelMenu(state, actions)
    }
}

/** A page opened over the conversation: back to it, the page's name, then the page. */
@Composable
internal fun PhonePage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), onBack)
            Text(title, style = ConsoleType.hero, color = ConsoleColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        content()
    }
}

/**
 * The model, and the run's other choices with it: approval, the variant and the actions, which the tablet's composer
 * shows as chips of their own. A warning approval keeps its amber dot on the button.
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
internal fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = ConsoleColors
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = colors.elevated,
        border = BorderStroke(1.dp, colors.borderSubtle),
        modifier = Modifier.size(TOP_BUTTON_SIZE).semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = colors.text) }
    }
}

/** The open session's title, or a new chat's. */
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

private val TOP_BUTTON_SIZE = 48.dp
private val MARK_SIZE = 30.dp
private val MODEL_PILL_MAX_WIDTH = 180.dp
