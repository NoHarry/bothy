package cc.noharry.bothy.app.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.ui.StatusDot
import cc.noharry.bothy.app.session.ConfirmationStatus
import cc.noharry.bothy.app.session.ConversationItem
import cc.noharry.bothy.app.session.UiState
import cc.noharry.bothy.app.session.sessionTitleText

/**
 * The conversation's top bar: the menu that opens the drawer, the session and how it is doing, the model, and a new
 * chat.
 */
@Composable
internal fun PhoneTopBar(state: UiState, actions: ConsoleActions, onMenu: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RoundButton(Icons.Filled.Menu, stringResource(R.string.console_open_menu), onMenu)
        TitleAndModel(Modifier.weight(1f)) {
            TitlePill(phoneTitle(state), phoneStatus(state))
            PhoneModelPill(state, actions)
        }
        // A chat nothing was asked in yet is already new.
        if (state.items.isNotEmpty() || state.isRunning) RoundButton(Icons.Filled.Edit, stringResource(R.string.console_new_chat), actions.newSession)
    }
}

/**
 * The title on the left and the model on the right, each as wide as it needs while both fit. When they do not, the
 * title keeps at least half the room and the model shortens first.
 */
@Composable
private fun TitleAndModel(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val (title, model) = measurables
        val room = constraints.maxWidth
        val gap = PILL_GAP.roundToPx()
        val height = constraints.maxHeight.takeIf { it != Constraints.Infinity } ?: Constraints.Infinity
        val titleWants = title.maxIntrinsicWidth(height)
        val modelWants = model.maxIntrinsicWidth(height)
        val modelWidth = minOf(modelWants, maxOf(room - gap - titleWants, (room - gap) / 2)).coerceAtLeast(0)
        val titleWidth = minOf(titleWants, room - gap - modelWidth).coerceAtLeast(0)
        val placedModel = model.measure(Constraints(maxWidth = modelWidth))
        val placedTitle = title.measure(Constraints(maxWidth = titleWidth))
        val rowHeight = maxOf(placedTitle.height, placedModel.height)
        layout(room, rowHeight) {
            placedTitle.place(0, (rowHeight - placedTitle.height) / 2)
            placedModel.place(room - placedModel.width, (rowHeight - placedModel.height) / 2)
        }
    }
}

/** How the session is doing, under its title, with the dot's colour. */
@Composable
private fun phoneStatus(state: UiState): Pair<String, androidx.compose.ui.graphics.Color> {
    val colors = ConsoleColors
    val waiting = state.items.any { it is ConversationItem.ConfirmationItem && it.status == ConfirmationStatus.PENDING }
    return when {
        waiting -> stringResource(R.string.phone_status_waiting) to colors.warning
        state.isRunning -> stringResource(R.string.phone_status_working) to colors.activity
        else -> stringResource(R.string.phone_this_phone) to colors.success
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
 * The model, as a pill that brings up the run's choices from the bottom ([PhoneModelSheet]): the model, its variant and
 * approval. A warning approval keeps its amber dot on the pill.
 */
@Composable
private fun PhoneModelPill(state: UiState, actions: ConsoleActions) {
    val colors = ConsoleColors
    var open by remember { mutableStateOf(false) }
    val model = state.configEditor.model.ifBlank { stringResource(R.string.console_choose_model) }
    Surface(
        onClick = { open = true },
        shape = RoundedCornerShape(Dimens.radiusFull),
        color = colors.elevated,
        shadowElevation = PILL_SHADOW,
        modifier = Modifier.widthIn(max = MODEL_PILL_MAX_WIDTH),
    ) {
        Row(Modifier.heightIn(min = TOP_BUTTON_SIZE).padding(start = 14.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.confirmationMode.warning) {
                StatusDot(color = colors.warning, modifier = Modifier.size(7.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                model,
                style = ConsoleType.meta,
                color = colors.text,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                // A model's name ends in what tells it from its siblings ("…-flash", "…-pro"): cut its middle.
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = colors.muted, modifier = Modifier.padding(start = 2.dp).size(18.dp))
        }
    }
    if (open) PhoneModelSheet(state, actions) { open = false }
}

@Composable
internal fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit) = RoundButton(rememberVectorPainter(icon), label, onClick)

/** A raised round button: the bars' menu, back and new chat, and the drawer's foot. */
@Composable
internal fun RoundButton(icon: Painter, label: String, onClick: () -> Unit) {
    val colors = ConsoleColors
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = colors.elevated,
        shadowElevation = PILL_SHADOW,
        modifier = Modifier.size(TOP_BUTTON_SIZE).semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = colors.text, modifier = Modifier.size(22.dp)) }
    }
}

/** The open session's title, or "New chat" until something is asked in it. */
@Composable
private fun phoneTitle(state: UiState): String {
    val session = state.sessions.firstOrNull { it.sessionId == state.activeSessionId }?.takeIf { it.turnCount > 0 || state.items.isNotEmpty() }
    return session?.title?.takeIf { it.isNotBlank() }?.let { sessionTitleText(it) } ?: stringResource(R.string.console_new_chat)
}

/** The session's title, and under it a dot and how it is doing. */
@Composable
private fun TitlePill(title: String, status: Pair<String, androidx.compose.ui.graphics.Color>) {
    val colors = ConsoleColors
    Surface(shape = RoundedCornerShape(Dimens.radiusFull), color = colors.elevated, shadowElevation = PILL_SHADOW) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
            Text(title, style = ConsoleType.panelTitle.copy(fontSize = TITLE_SIZE), color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color = status.second, modifier = Modifier.size(6.dp))
                Text(status.first, style = ConsoleType.meta.copy(fontSize = STATUS_SIZE), color = colors.muted, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

private val TOP_BUTTON_SIZE = 46.dp
private val MODEL_PILL_MAX_WIDTH = 200.dp
private val PILL_GAP = 8.dp
private val TITLE_SIZE = 14.sp
private val STATUS_SIZE = 11.sp
private val PILL_SHADOW = 2.dp
