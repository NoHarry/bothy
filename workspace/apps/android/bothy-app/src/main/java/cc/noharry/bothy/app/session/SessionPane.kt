package cc.noharry.bothy.app.session
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.console.BothyAlertDialog
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.FilesPane
import cc.noharry.bothy.app.console.PanelBox
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.ui.formatTurnCount
import cc.noharry.bothy.app.mcp.SidebarFooter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SessionPane(
    state: UiState,
    actions: ConsoleActions,
    settingsSelected: Boolean,
    onShowConversation: () -> Unit,
    onShowSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onShowA2aProfiles: () -> Unit = onShowSettings,
) {
    var confirmClear by remember { mutableStateOf(false) }
    var showFiles by rememberSaveable { mutableStateOf(false) }
    PanelBox(if (showFiles) "Files" else "Sessions", modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
            PaneModeChip("Sessions", selected = !showFiles) { showFiles = false }
            PaneModeChip("Files", selected = showFiles) { showFiles = true }
        }
        Spacer(Modifier.height(Dimens.spaceSm))
        if (showFiles) {
            FilesPane(actions, Modifier.weight(1f))
        } else {
            Button(
                onClick = {
                    onShowConversation()
                    actions.newSession()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Dimens.radiusMd),
            ) {
                Text("New session")
            }
            Spacer(Modifier.height(Dimens.spaceSm))
            OutlinedButton(
                onClick = { confirmClear = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Dimens.radiusMd),
            ) {
                // Single line on narrow rails — meta size keeps the label from wrapping.
                Text("Clear runtime data", style = ConsoleType.meta, maxLines = 1)
            }
            Spacer(Modifier.height(Dimens.spaceSm))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(Dimens.spaceXsMd),
                modifier = Modifier.weight(1f),
            ) {
                items(state.sessions, key = { it.sessionId }) { session ->
                    SessionRow(
                        session = session,
                        selected = session.sessionId == state.activeSessionId,
                        onClick = {
                            onShowConversation()
                            actions.selectSession(session.sessionId)
                        },
                        onDelete = { actions.deleteSession(session.sessionId) },
                    )
                }
            }
        }
        Spacer(Modifier.height(Dimens.spaceSm))
        SidebarFooter(settingsSelected, onShowSettings, onShowA2aProfiles)
    }
    if (confirmClear) {
        ClearRuntimeDataDialog(
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                actions.clearRuntimeData()
                onShowConversation()
            },
        )
    }
}

@Composable
private fun PaneModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = ConsoleColors
    Text(
        text = label,
        style = ConsoleType.meta,
        color = if (selected) colors.accent else colors.muted,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(Dimens.radiusFull))
            .background(if (selected) colors.accentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.spaceSm, vertical = Dimens.spaceXs),
    )
}

@Composable
internal fun ClearRuntimeDataDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = ConsoleColors
    BothyAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear runtime data?") },
        text = {
            Text(
                "Deletes all sessions, workspace memory/todo/artifacts, run evidence, and debug logs. " +
                    "Provider API keys and MCP toggles are kept. Active runs are stopped.",
                color = colors.muted,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Clear all", color = colors.danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/** Session row: one selected language — accentSoft fill + 3dp accent bar, same as the NavRail. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun SessionRow(
    session: SessionSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = ConsoleColors
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.radiusSm))
            .background(if (selected) colors.accentSoft else colors.elevated)
            .combinedClickable(onClick = onClick, onLongClick = onDelete),
    ) {
        if (selected) {
            // Same pill indicator as the NavRail (3dp x 24dp, centered on the leading edge).
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(SELECTED_BAR_WIDTH)
                    .height(SELECTED_BAR_HEIGHT)
                    .background(colors.accent, RoundedCornerShape(Dimens.radiusFull)),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = SELECTED_BAR_WIDTH + Dimens.spaceMd,
                    top = Dimens.spaceMd,
                    end = Dimens.spaceXs,
                    bottom = Dimens.spaceMd,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    style = ConsoleType.body,
                    color = colors.text,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Dimens.spaceXs))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTurnCount(session.turnCount), style = ConsoleType.meta, color = colors.muted)
                    Text(shortTime(session.updatedAtEpochMillis), style = ConsoleType.meta, color = colors.muted)
                }
            }
            Spacer(Modifier.width(Dimens.spaceXs))
            // Visible delete affordance; long-press on the row still deletes too.
            Box(
                Modifier
                    .size(DELETE_TOUCH_SIZE)
                    .clip(RoundedCornerShape(Dimens.radiusFull))
                    .clickable(onClick = onDelete)
                    .semantics { role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete session",
                    tint = colors.muted,
                    modifier = Modifier.size(DELETE_ICON_SIZE),
                )
            }
        }
    }
}

private fun shortTime(epochMillis: Long): String =
    if (epochMillis <= 0L) {
        "--:--"
    } else {
        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(epochMillis))
    }

private val SELECTED_BAR_WIDTH = 3.dp
private val SELECTED_BAR_HEIGHT = 24.dp
private val DELETE_ICON_SIZE = 18.dp
private val DELETE_TOUCH_SIZE = 32.dp
