package cc.noharry.bothy.app.console

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.session.SessionSummary
import cc.noharry.bothy.app.tools.PLAYGROUND_PACKAGE
import java.util.Calendar

/** Set on a portrait phone so a tool group can offer the trajectory without taking over the header tap. */
internal val LocalOpenTrajectory = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Set on a portrait phone so the inline plan card opens the plans list. */
internal val LocalOpenPlans = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Portrait phone: hold the mic to talk. Wider layouts tap to start and stop. */
internal val LocalHoldToTalk = staticCompositionLocalOf { false }

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

@Composable
internal fun PhoneDrawer(
    sessions: List<SessionSummary>,
    activeSessionId: String,
    showA2a: Boolean,
    conversationSelected: Boolean,
    onConversation: () -> Unit,
    onNewChat: () -> Unit,
    onSelectSession: (String) -> Unit,
    onPlans: () -> Unit,
    onTrajectory: () -> Unit,
    onA2a: () -> Unit,
    onPlayground: () -> Unit,
    onSettings: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val colors = ConsoleColors
    val context = LocalContext.current
    val showPlayground = remember(context) { playgroundInstalled(context) }
    val grouped = remember(sessions, query) { groupDrawerSessions(sessions, query) }
    val searchLabel = stringResource(R.string.console_search_sessions)
    Column(Modifier.fillMaxHeight().fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        DrawerRow(stringResource(R.string.console_nav_chat), onConversation, selected = conversationSelected)
        DrawerRow(stringResource(R.string.console_new_chat), onNewChat) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = colors.accent)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = colors.muted)
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                textStyle = ConsoleType.body.copy(color = colors.text),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
                    .semantics { contentDescription = searchLabel },
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(searchLabel, style = ConsoleType.body, color = colors.muted)
                    }
                    inner()
                },
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            grouped.forEach { (day, rows) ->
                item(key = "day-$day") {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(day.label), style = ConsoleType.meta, color = colors.muted)
                }
                items(rows, key = { it.sessionId }) { session ->
                    DrawerRow(
                        label = session.title.ifBlank { stringResource(R.string.console_nav_chat) },
                        onClick = { onSelectSession(session.sessionId) },
                        selected = session.sessionId == activeSessionId,
                    )
                }
            }
            item(key = "features") {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.console_drawer_features), style = ConsoleType.meta, color = colors.muted)
                DrawerRow(stringResource(R.string.console_view_plans), onPlans)
                DrawerRow(stringResource(R.string.console_view_trajectory), onTrajectory)
                if (showA2a) DrawerRow(stringResource(R.string.console_view_a2a), onA2a)
                if (showPlayground) DrawerRow(stringResource(R.string.console_open_playground), onPlayground)
            }
        }
        Spacer(Modifier.height(8.dp))
        DrawerRow(stringResource(R.string.common_settings), onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = null, tint = colors.accent)
        }
    }
}

private val SessionDay.label: Int
    get() = when (this) {
        SessionDay.TODAY -> R.string.console_drawer_today
        SessionDay.YESTERDAY -> R.string.console_drawer_yesterday
        SessionDay.EARLIER -> R.string.console_drawer_earlier
    }

@Composable
private fun DrawerRow(
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = ConsoleColors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = label,
            style = ConsoleType.body,
            color = if (selected) colors.accent else colors.text,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
