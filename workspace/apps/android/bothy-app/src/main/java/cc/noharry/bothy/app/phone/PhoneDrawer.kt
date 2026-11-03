package cc.noharry.bothy.app.phone

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.graphics.painter.Painter
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
import cc.noharry.bothy.app.session.SessionSummary
import cc.noharry.bothy.app.session.UiState
import cc.noharry.bothy.app.tools.PLAYGROUND_PACKAGE
import java.util.Calendar

internal enum class SessionDay { TODAY, YESTERDAY, EARLIER }

internal fun sessionDay(updatedAtEpochMillis: Long, nowMillis: Long = System.currentTimeMillis()): SessionDay {
    val today = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val then = Calendar.getInstance().apply { timeInMillis = updatedAtEpochMillis }
    if (sameDay(today, then)) return SessionDay.TODAY
    val yesterday = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        add(Calendar.DAY_OF_YEAR, -1)
    }
    return if (sameDay(yesterday, then)) SessionDay.YESTERDAY else SessionDay.EARLIER
}

private fun sameDay(left: Calendar, right: Calendar): Boolean =
    left.get(Calendar.YEAR) == right.get(Calendar.YEAR) &&
        left.get(Calendar.DAY_OF_YEAR) == right.get(Calendar.DAY_OF_YEAR)

/** Sessions matching [query], grouped by day. Computed once per list or query change. */
internal fun groupDrawerSessions(
    sessions: List<SessionSummary>,
    query: String,
    nowMillis: Long = System.currentTimeMillis(),
): List<Pair<SessionDay, List<SessionSummary>>> {
    val filtered = if (query.isBlank()) sessions else sessions.filter { it.title.contains(query, ignoreCase = true) }
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
 * A portrait phone's drawer, as the phone design draws it: a new chat, search, the sessions by day, the features
 * (plans, A2A, the head unit) and 小博's settings at the foot. A session opens the conversation; the trajectory is
 * reached from a run's tool steps.
 */
@Composable
internal fun PhoneDrawer(state: UiState, open: Boolean, showA2a: Boolean, actions: ConsoleActions, onGo: (PhoneRoute) -> Unit) {
    val sessions = state.sessions
    val activeSessionId = state.activeSessionId
    val onNewChat = { actions.newSession(); onGo(PhoneRoute.CHAT) }
    val onSelectSession = { id: String -> actions.selectSession(id); onGo(PhoneRoute.CHAT) }
    val onPlans = { onGo(PhoneRoute.PLANS) }
    val onA2a = { onGo(PhoneRoute.A2A) }
    val onSettings = { onGo(PhoneRoute.SETTINGS) }
    var query by rememberSaveable { mutableStateOf("") }
    val colors = ConsoleColors
    val context = LocalContext.current
    val showPlayground = remember(context) { playgroundInstalled(context) }
    val grouped = remember(sessions, query) { groupDrawerSessions(sessions, query) }
    Column(Modifier.fillMaxHeight().fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        NewChatButton(onNewChat)
        Spacer(Modifier.height(10.dp))
        DrawerSearch(query, open) { query = it }
        LazyColumn(Modifier.weight(1f)) {
            grouped.forEach { (day, rows) ->
                item(key = "day-$day") { DrawerSection(stringResource(day.label)) }
                items(rows, key = { it.sessionId }) { session ->
                    SessionRow(session, selected = session.sessionId == activeSessionId) { onSelectSession(session.sessionId) }
                }
            }
            item(key = "features") {
                DrawerSection(stringResource(R.string.console_drawer_features))
                FeatureRow(rememberVectorPainter(Icons.Filled.DateRange), stringResource(R.string.console_view_plans), stringResource(R.string.console_drawer_plans_all), onPlans)
                if (showA2a) FeatureRow(rememberVectorPainter(Icons.Filled.Share), stringResource(R.string.console_view_a2a), null, onA2a)
                if (showPlayground) FeatureRow(painterResource(R.drawable.ic_drawer_car), stringResource(R.string.console_open_playground), null) { openPlayground(context); onGo(PhoneRoute.CHAT) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderSubtle))
        SettingsFoot(onSettings)
    }
}

@Composable
private fun NewChatButton(onClick: () -> Unit) {
    val colors = ConsoleColors
    val onAccent = if (colors.isDark) colors.bg else Color.White
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(Dimens.radiusLg))
            .background(colors.accent)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Edit, contentDescription = null, tint = onAccent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.console_new_chat), style = ConsoleType.panelTitle, color = onAccent)
    }
}

@Composable
private fun DrawerSection(title: String) {
    Text(title, style = ConsoleType.meta, color = ConsoleColors.muted, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}

/** A session: its title, and when it was last used; the open one is filled. */
@Composable
private fun SessionRow(session: SessionSummary, selected: Boolean, onClick: () -> Unit) {
    val colors = ConsoleColors
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(Dimens.radiusMd))
            .background(if (selected) colors.accentSoft else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = session.title.ifBlank { null }?.let { sessionTitleText(it) } ?: stringResource(R.string.console_new_chat),
            style = ConsoleType.body,
            color = if (selected) colors.accent else colors.text,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(sessionWhen(context, session.updatedAtEpochMillis), style = ConsoleType.meta, color = colors.muted, maxLines = 1)
    }
}

/** When a session was last used: the time today and yesterday, the weekday and time before that. */
internal fun sessionWhen(context: Context, atMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val time = DateUtils.FORMAT_SHOW_TIME
    val flags = if (sessionDay(atMillis, nowMillis) == SessionDay.EARLIER) time or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY else time
    return DateUtils.formatDateTime(context, atMillis, flags)
}

@Composable
private fun FeatureRow(icon: Painter, title: String, subtitle: String?, onClick: () -> Unit) {
    val colors = ConsoleColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = ConsoleType.body, color = colors.text, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, style = ConsoleType.meta, color = colors.muted)
        }
    }
}

/** 小博 and its settings, at the drawer's foot. */
@Composable
private fun SettingsFoot(onClick: () -> Unit) {
    val colors = ConsoleColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick).padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.bothy_mark), contentDescription = null, modifier = Modifier.size(44.dp).clip(CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.console_drawer_assistant), style = ConsoleType.body, color = colors.text, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.common_settings), style = ConsoleType.meta, color = colors.muted)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.muted)
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
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(Dimens.radiusLg))
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
        SessionDay.EARLIER -> R.string.console_drawer_earlier
    }
