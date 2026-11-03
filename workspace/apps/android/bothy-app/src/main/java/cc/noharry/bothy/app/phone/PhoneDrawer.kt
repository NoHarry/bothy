package cc.noharry.bothy.app.phone

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.session.sessionTitleText
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ui.StatusDot
import cc.noharry.bothy.app.session.ConfirmationStatus
import cc.noharry.bothy.app.session.ConversationItem
import cc.noharry.bothy.app.session.SessionSummary
import cc.noharry.bothy.app.session.UiState
import cc.noharry.bothy.app.tools.PLAYGROUND_PACKAGE
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
 * The phone's drawer: Bothy and a new chat at the top, search, the sessions by day (the open one filled, with a dot
 * while it runs or waits), and at the foot how 小博 is doing, the other features behind a button, and the settings.
 */
@Composable
internal fun PhoneDrawer(state: UiState, open: Boolean, showA2a: Boolean, actions: ConsoleActions, onGo: (PhoneRoute) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val underWay = state.activeSessionId.takeIf { state.items.isNotEmpty() }
    val grouped = remember(state.sessions, query, underWay) { groupDrawerSessions(state.sessions, query, started = underWay) }
    val activeDot = drawerDot(state)
    Column(Modifier.fillMaxHeight().fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        DrawerHeader { actions.newSession(); onGo(PhoneRoute.CHAT) }
        Spacer(Modifier.height(12.dp))
        DrawerSearch(query, open) { query = it }
        if (grouped.isEmpty()) DrawerEmpty(Modifier.weight(1f)) else LazyColumn(Modifier.weight(1f).padding(top = 4.dp)) {
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
        DrawerFoot(state, showA2a, onGo)
    }
}

/** The open session's dot in the drawer: working, or waiting for an approval; none when it is idle. */
@Composable
private fun drawerDot(state: UiState): Color? = when {
    state.items.any { it is ConversationItem.ConfirmationItem && it.status == ConfirmationStatus.PENDING } -> ConsoleColors.warning
    state.isRunning -> ConsoleColors.activity
    else -> null
}

@Composable
private fun DrawerHeader(onNewChat: () -> Unit) {
    val colors = ConsoleColors
    val onAccent = if (colors.isDark) colors.bg else Color.White
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.bothy_mark), contentDescription = null, modifier = Modifier.size(34.dp).clip(CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(BRAND, style = ConsoleType.hero, color = colors.text, modifier = Modifier.weight(1f))
        Row(
            Modifier
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(Dimens.radiusFull))
                .background(colors.accent)
                .clickable(role = Role.Button, onClick = onNewChat)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Edit, contentDescription = null, tint = onAccent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.console_new_chat), style = ConsoleType.panelTitle, color = onAccent)
        }
    }
}

@Composable
private fun DrawerSection(title: String) {
    Text(title, style = ConsoleType.meta, color = ConsoleColors.muted, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp))
}

/** A session in one line; the open one is filled, and [dot] says it runs or waits. */
@Composable
private fun SessionRow(session: SessionSummary, selected: Boolean, dot: Color?, onClick: () -> Unit) {
    val colors = ConsoleColors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(Dimens.radiusLg))
            .background(if (selected) colors.accentSoft else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = session.title.ifBlank { null }?.let { sessionTitleText(it) } ?: stringResource(R.string.console_new_chat),
            style = ConsoleType.body,
            color = colors.text,
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
        Text(stringResource(R.string.phone_drawer_empty), style = ConsoleType.body, color = colors.text, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.phone_drawer_empty_note), style = ConsoleType.meta, color = colors.muted, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Where Bothy runs and how it is doing; then plans, A2A and the head unit behind one button; then the settings. */
@Composable
private fun DrawerFoot(state: UiState, showA2a: Boolean, onGo: (PhoneRoute) -> Unit) {
    val colors = ConsoleColors
    val context = LocalContext.current
    val showPlayground = remember(context) { playgroundInstalled(context) }
    var more by remember { mutableStateOf(false) }
    val status = drawerDot(state)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_phone_device), contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.phone_this_phone), style = ConsoleType.body, color = colors.text, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color = status ?: colors.success, modifier = Modifier.size(6.dp))
                val words = when (status) {
                    null -> R.string.phone_status_ready
                    colors.warning -> R.string.phone_status_waiting
                    else -> R.string.phone_status_working
                }
                Text(stringResource(words), style = ConsoleType.meta, color = colors.muted, modifier = Modifier.padding(start = 5.dp))
            }
        }
        RoundButton(painterResource(R.drawable.ic_phone_more), stringResource(R.string.console_drawer_features)) { more = true }
        Spacer(Modifier.width(10.dp))
        RoundButton(rememberVectorPainter(Icons.Filled.Settings), stringResource(R.string.common_settings)) { onGo(PhoneRoute.SETTINGS) }
    }
    if (more) {
        val plans = SheetTile(rememberVectorPainter(Icons.Filled.DateRange), stringResource(R.string.console_view_plans)) { onGo(PhoneRoute.PLANS) }
        val a2a = SheetTile(rememberVectorPainter(Icons.Filled.Share), stringResource(R.string.console_view_a2a)) { onGo(PhoneRoute.A2A) }
        val car = SheetTile(painterResource(R.drawable.ic_drawer_car), stringResource(R.string.console_open_playground)) {
            openPlayground(context)
            onGo(PhoneRoute.CHAT)
        }
        PhoneMoreSheet(listOfNotNull(plans, a2a.takeIf { showA2a }, car.takeIf { showPlayground })) { more = false }
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
            .clip(RoundedCornerShape(Dimens.radiusFull))
            .background(colors.elevated)
            .then(if (active) Modifier else Modifier.clickable(role = Role.Button) { searching = true })
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = colors.muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        if (!active) {
            Text(label, style = ConsoleType.body, color = colors.muted)
            return@Row
        }
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = ConsoleType.body.copy(color = colors.text),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = label },
            decorationBox = { inner ->
                if (query.isEmpty()) Text(label, style = ConsoleType.body, color = colors.muted)
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

/** Bothy's name, the same in every language, beside its mark. */
private const val BRAND = "Bothy"
private const val WEEK_DAYS = 7
