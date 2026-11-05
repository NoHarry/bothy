package cc.noharry.bothy.app.phone

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.ConfirmationStatus
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.SessionSummary
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ui.StatusDot
import cc.noharry.bothy.app.session.sessionTitleText
import cc.noharry.bothy.protocol.PLAYGROUND_PACKAGE
import java.util.Calendar

internal enum class SessionDay { TODAY, YESTERDAY, THIS_WEEK, EARLIER }

/** Which of the drawer's groups a session falls in: today, yesterday, the five days before, or earlier. */
internal fun sessionDay(updatedAtEpochMillis: Long, nowMillis: Long = System.currentTimeMillis()): SessionDay {
    val dayStart = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    fun daysBack(days: Int) = (dayStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -days) }.timeInMillis
    return when {
        updatedAtEpochMillis >= dayStart.timeInMillis -> SessionDay.TODAY
        updatedAtEpochMillis >= daysBack(1) -> SessionDay.YESTERDAY
        updatedAtEpochMillis >= daysBack(WEEK_DAYS - 1) -> SessionDay.THIS_WEEK
        else -> SessionDay.EARLIER
    }
}

/**
 * Sessions matching [query], grouped by day; a session nothing was asked in yet is left out, as a new chat is until it
 * starts. [started] is the open session when something is under way in it, kept although no turn has ended there yet.
 * Computed once per list or query change.
 */
internal fun groupDrawerSessions(
    sessions: List<SessionSummary>,
    query: String,
    nowMillis: Long = System.currentTimeMillis(),
    started: String? = null,
): List<Pair<SessionDay, List<SessionSummary>>> {
    val begun = sessions.filter { it.turnCount > 0 || it.sessionId == started }
    val filtered = if (query.isBlank()) begun else begun.filter { it.title.contains(query, ignoreCase = true) }
    return SessionDay.entries.mapNotNull { day ->
        val rows = filtered.filter { sessionDay(it.updatedAtEpochMillis, nowMillis) == day }
        if (rows.isEmpty()) null else day to rows
    }
}

internal fun openPlayground(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(PLAYGROUND_PACKAGE)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
    runCatching { context.startActivity(intent) }
}

internal fun playgroundInstalled(context: Context): Boolean =
    context.packageManager.getLaunchIntentForPackage(PLAYGROUND_PACKAGE) != null

/**
 * The phone's drawer, as the design draws it: Bothy, search and a new chat at the top; then plans (with how many are
 * going), A2A, the head unit and the settings; then the chats by day (the open one filled, with a dot while it runs or
 * waits); at the foot, where Bothy runs and on what model.
 */
@Composable
internal fun PhoneDrawer(state: ServerState, open: Boolean, showA2a: Boolean, actions: ConsoleActions, onGo: (PhoneRoute) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val underWay = state.activeSessionId.takeIf { state.items.isNotEmpty() }
    val grouped = remember(state.sessions, query, underWay) { groupDrawerSessions(state.sessions, query, started = underWay) }
    val activeDot = drawerDot(state)
    Column(Modifier.fillMaxHeight().fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp)) {
        Text(BRAND, style = PhoneType.greeting, color = ConsoleColors.text, modifier = Modifier.padding(start = 8.dp, bottom = 16.dp))
        DrawerSearch(query, open) { query = it }
        Spacer(Modifier.height(16.dp))
        NewChatButton { actions.newSession(); onGo(PhoneRoute.CHAT) }
        Spacer(Modifier.height(12.dp))
        DrawerMenu(state, showA2a, onGo)
        Box(Modifier.padding(horizontal = 8.dp, vertical = 12.dp).fillMaxWidth().height(1.dp).background(ConsoleColors.borderSubtle))
        if (grouped.isEmpty()) DrawerEmpty(Modifier.weight(1f)) else LazyColumn(Modifier.weight(1f)) {
            grouped.forEach { (day, rows) ->
                item(key = "day-$day") { DrawerSection(stringResource(day.label)) }
                items(rows, key = { it.sessionId }) { session ->
                    val selected = session.sessionId == state.activeSessionId
                    SessionRow(session, selected, dot = activeDot.takeIf { selected }) {
                        actions.selectSession(session.sessionId)
                        onGo(PhoneRoute.CHAT)
                    }
                }
            }
        }
        DrawerFoot(state)
    }
}

/** The open session's dot in the drawer: working, or waiting for an approval; none when it is idle. */
@Composable
private fun drawerDot(state: ServerState): Color? = when {
    state.items.any { it is ConversationItem.ConfirmationItem && it.status == ConfirmationStatus.PENDING } -> ConsoleColors.warning
    state.isRunning -> ConsoleColors.activity
    else -> null
}

@Composable
private fun NewChatButton(onNewChat: () -> Unit) {
    val colors = ConsoleColors
    Surface(onClick = onNewChat, shape = CircleShape, color = colors.accent, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = onAccent(), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.console_new_chat), style = PhoneType.label, color = onAccent(), fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Plans, A2A, the head unit (when the Playground is installed) and the settings, one row each. */
@Composable
private fun DrawerMenu(state: ServerState, showA2a: Boolean, onGo: (PhoneRoute) -> Unit) {
    val context = LocalContext.current
    val showPlayground = remember(context) { playgroundInstalled(context) }
    val going = state.plans.count { it.active }
    MenuRow(rememberVectorPainter(Icons.Filled.DateRange), stringResource(R.string.console_view_plans), onClick = { onGo(PhoneRoute.PLANS) }) {
        if (going > 0) CountChip(pluralStringResource(R.plurals.phone_plans_going, going, going))
    }
    if (showA2a) MenuRow(rememberVectorPainter(Icons.Filled.Share), stringResource(R.string.console_view_a2a), onClick = { onGo(PhoneRoute.A2A) })
    if (showPlayground) {
        MenuRow(painterResource(R.drawable.ic_drawer_car), stringResource(R.string.console_open_playground), stringResource(R.string.phone_drawer_car_note), onClick = {
            openPlayground(context)
            onGo(PhoneRoute.CHAT)
        })
    }
    MenuRow(rememberVectorPainter(Icons.Filled.Settings), stringResource(R.string.common_settings), onClick = { onGo(PhoneRoute.SETTINGS) })
}

@Composable
private fun MenuRow(icon: Painter, title: String, note: String? = null, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val colors = ConsoleColors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.inkSoft, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = PhoneType.reply, color = colors.text)
            if (note != null) Text(note, style = PhoneType.small, color = colors.muted)
        }
        trailing()
    }
}

@Composable
private fun CountChip(text: String) {
    val colors = ConsoleColors
    Text(text, style = PhoneType.small, color = colors.accent, modifier = Modifier.clip(CircleShape).background(colors.accentSoft).padding(horizontal = 10.dp, vertical = 3.dp))
}

@Composable
private fun DrawerSection(title: String) {
    Text(title, style = PhoneType.small, color = ConsoleColors.muted, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 6.dp))
}

/** A chat in one line, after a chat mark; the open one is filled, and [dot] says it runs or waits. */
@Composable
private fun SessionRow(session: SessionSummary, selected: Boolean, dot: Color?, onClick: () -> Unit) {
    val colors = ConsoleColors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.accentSoft else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_phone_chat), contentDescription = null, tint = if (selected) colors.accent else colors.muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            text = session.title.ifBlank { null }?.let { sessionTitleText(it) } ?: stringResource(R.string.console_new_chat),
            style = PhoneType.body,
            color = if (selected) colors.accent else colors.text,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (dot != null) StatusDot(color = dot, pulsing = dot == colors.activity, modifier = Modifier.padding(start = 8.dp).size(8.dp))
    }
}

/** No session has started yet. */
@Composable
private fun DrawerEmpty(modifier: Modifier) {
    val colors = ConsoleColors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.phone_drawer_empty), style = PhoneType.body, color = colors.text, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.phone_drawer_empty_note), style = PhoneType.small, color = colors.muted, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Where Bothy runs and on what model, with a dot for how it is doing. */
@Composable
private fun DrawerFoot(state: ServerState) {
    val colors = ConsoleColors
    val status = drawerDot(state)
    val model = state.providerLabel.ifBlank { state.configEditor.model }
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color = status ?: colors.accent, pulsing = status != null, modifier = Modifier.size(7.dp))
        Spacer(Modifier.width(8.dp))
        val where = listOf(stringResource(R.string.phone_this_phone), model).filter { it.isNotBlank() }.joinToString(" · ")
        Text(where, style = PhoneType.label, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Search is a field-like row until it is tapped: a field would take the focus the drawer hands its content when it
 * opens, and the keyboard would come up over the list. Closing the drawer ends the search and puts the keyboard away.
 */
@Composable
private fun DrawerSearch(query: String, open: Boolean, onQueryChange: (String) -> Unit) {
    val colors = ConsoleColors
    val label = stringResource(R.string.console_search_sessions)
    var searching by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(open) {
        if (open || !searching && query.isEmpty()) return@LaunchedEffect
        focusManager.clearFocus()
        onQueryChange("")
        searching = false
    }
    val focus = remember { FocusRequester() }
    val active = searching || query.isNotEmpty()
    if (active) LaunchedEffect(Unit) { if (open) focus.requestFocus() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(colors.elevated)
            .then(if (active) Modifier else Modifier.clickable(role = Role.Button) { searching = true })
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = colors.muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        if (!active) {
            Text(label, style = PhoneType.body, color = colors.muted)
            return@Row
        }
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = PhoneType.body.copy(color = colors.text),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = label },
            decorationBox = { inner ->
                if (query.isEmpty()) Text(label, style = PhoneType.body, color = colors.muted)
                inner()
            },
        )
    }
}

private val SessionDay.label: Int
    get() = when (this) {
        SessionDay.TODAY -> R.string.console_drawer_today
        SessionDay.YESTERDAY -> R.string.console_drawer_yesterday
        SessionDay.THIS_WEEK -> R.string.phone_drawer_this_week
        SessionDay.EARLIER -> R.string.console_drawer_earlier
    }

/** Bothy's name, the same in every language. */
private const val BRAND = "Bothy"
private const val WEEK_DAYS = 7
