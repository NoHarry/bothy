package cc.noharry.bothy.app.console
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.ArtifactPreview
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.LessonEntry
import cc.noharry.agent.appserver.protocol.McpProviderState
import cc.noharry.agent.appserver.protocol.RuntimeDemoState
import cc.noharry.agent.appserver.protocol.SessionBoardState
import cc.noharry.agent.appserver.protocol.SkillEntry
import cc.noharry.agent.appserver.protocol.SubagentLane
import cc.noharry.agent.appserver.protocol.SubagentRunState
import cc.noharry.agent.appserver.protocol.TodoEntry
import cc.noharry.agent.appserver.protocol.TokenUsageState
import cc.noharry.agent.appserver.protocol.WorkspaceSelectionState
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.pendingConfirmationActionIds
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ui.ActivityDot
import cc.noharry.bothy.app.console.ui.ApproveDenyRow
import cc.noharry.bothy.app.console.ui.HtmlPreviewBox
import cc.noharry.bothy.app.console.ui.IconFullscreen
import cc.noharry.bothy.app.console.ui.SectionLabel
import cc.noharry.bothy.app.console.ui.ShimmerText
import cc.noharry.bothy.app.console.ui.StatusDot
import cc.noharry.bothy.app.console.ui.formatTurnCount
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.app.session.sessionTitleText
import cc.noharry.bothy.protocol.ConnectorRequests
import kotlinx.coroutines.delay

@Composable
internal fun BoardPane(
    state: ServerState,
    actions: ConsoleActions,
    onOpenMcpProviders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val subagents = state.subagents[state.activeSessionId].orEmpty()
    val sections = boardSections().filter { state.shouldShowBoardSection(it) }
        .filterNot { it == BoardSection.SUBAGENTS && subagents.isEmpty() }
    PanelBox(stringResource(R.string.console_board), modifier) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceXxs),
        ) {
            for (section in boardSectionOrder()) {
                if (section !in sections) continue
                item(key = section.name) {
                    when (section) {
                        BoardSection.SESSION_STATUS -> SessionCard(state.board, state)
                        BoardSection.PLAN -> TodoCard(state.contextPanel.todo)
                        BoardSection.TOKEN_USAGE -> TokenUsageCard(state.tokenUsage)
                        BoardSection.SUBAGENTS -> SubagentCard(subagents)
                        BoardSection.ARTIFACT -> ArtifactPreviewCard(state.artifactPreview, actions)
                        BoardSection.WORKSPACE -> WorkspaceOneLiner(state.workspaceSelection, actions)
                        BoardSection.MCP -> McpOneLiner(state.mcpProviders, onOpenMcpProviders)
                        BoardSection.MEMORY -> MemoryCard(state.contextPanel.lessons)
                        BoardSection.SKILLS -> SkillCard(state.contextPanel.skills)
                        BoardSection.RUNTIME_VISIBILITY -> RuntimeAlertCard(state.runtimeDemo, actions)
                        BoardSection.CURRENT_REQUEST,
                        BoardSection.LAST_ACTION,
                        BoardSection.RECENT_TURNS,
                        -> Unit
                    }
                }
            }
        }
    }
}

private fun boardSectionOrder(): List<BoardSection> =
    listOf(
        BoardSection.SESSION_STATUS,
        BoardSection.PLAN,
        BoardSection.TOKEN_USAGE,
        BoardSection.SUBAGENTS,
        BoardSection.ARTIFACT,
        BoardSection.WORKSPACE,
        BoardSection.MCP,
        BoardSection.RUNTIME_VISIBILITY,
        BoardSection.MEMORY,
        BoardSection.SKILLS,
    )

@Composable
private fun WorkspaceOneLiner(selection: WorkspaceSelectionState, actions: ConsoleActions) {
    val colors = ConsoleColors
    BoardSectionCard(stringResource(R.string.console_workspace)) {
        Text(
            if (selection.safEnabled) selection.label else stringResource(R.string.console_workspace_app_private),
            style = ConsoleType.meta,
            color = colors.text,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = actions.pickSafWorkspace) { Text(stringResource(R.string.console_pick_saf)) }
            if (selection.safEnabled) {
                TextButton(onClick = actions.clearSafWorkspace) { Text(stringResource(R.string.console_clear_saf)) }
            }
        }
    }
}

@Composable
private fun McpOneLiner(providers: List<McpProviderState>, onOpen: () -> Unit) {
    val colors = ConsoleColors
    val server = LocalServer.current
    val enabled = providers.filter { it.enabled }
    // A catalog app's server is saved under its Chinese name, which the model reads; the board shows its title.
    var titles by remember { mutableStateOf(emptyMap<String, String>()) }
    LaunchedEffect(Unit) {
        server.send(ConnectorRequests.LIST, Empty) { catalog -> titles = catalog.connectors.associate { it.name to it.title } }
    }
    val names = enabled.map { provider -> titles[provider.label] ?: provider.label }
        .joinToString().ifBlank { stringResource(R.string.common_none) }
    BoardSectionCard("MCP", Modifier.clickable(onClick = onOpen)) {
        Text(
            stringResource(R.string.console_mcp_loaded, enabled.size, providers.size, names),
            style = ConsoleType.meta,
            color = colors.text,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Dimens.spaceXs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.console_tap_to_manage), style = ConsoleType.meta, color = colors.muted)
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.muted,
                modifier = Modifier.size(MANAGE_CHEVRON_SIZE),
            )
        }
    }
}

/** The conversation's background subagents: state, time, latest step; a tap shows the task and the result. */
@Composable
private fun SubagentCard(rows: List<SubagentLane>) {
    val colors = ConsoleColors
    val running = rows.any { !it.state.finished }
    // Running ones count their seconds even between events.
    val now by produceState(System.currentTimeMillis(), running) {
        while (running) {
            value = System.currentTimeMillis()
            delay(SUBAGENT_TICK_MS)
        }
    }
    val resources = LocalResources.current
    BoardSectionCard(stringResource(R.string.console_subagents)) {
        rows.forEach { row ->
            var open by remember(row.id) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = Dimens.spaceXs)) {
                Text(
                    "● ${row.label} · ${stringResource(row.state.boardLabel())}",
                    style = ConsoleType.meta,
                    color = row.state.wireName.planStatusColor(),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    resources.subagentLine(row, now),
                    style = ConsoleType.meta,
                    color = colors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!row.state.finished && row.latest.isNotEmpty()) {
                    Text(
                        stringResource(R.string.console_subagent_latest, row.latest),
                        style = ConsoleType.meta,
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (open) {
                    Text(stringResource(R.string.console_subagent_task, row.task), style = ConsoleType.meta, color = colors.text)
                    val outcome = row.result.ifEmpty { row.error }
                    if (outcome.isNotEmpty()) {
                        Text(outcome.take(SUBAGENT_RESULT_PREVIEW_CHARS), style = ConsoleType.meta, color = colors.text)
                    }
                }
            }
        }
    }
}

@StringRes
private fun SubagentRunState.boardLabel(): Int = when (this) {
    SubagentRunState.RUNNING -> R.string.console_subagent_running
    SubagentRunState.STALLED -> R.string.console_subagent_stalled
    SubagentRunState.COMPLETED -> R.string.console_subagent_completed
    SubagentRunState.FAILED -> R.string.console_subagent_failed
    SubagentRunState.CANCELLED -> R.string.console_subagent_cancelled
    SubagentRunState.TIMED_OUT -> R.string.console_subagent_timed_out
}

/** Seconds, tool and model calls, tokens and cache hit, and the error if any. */
private fun Resources.subagentLine(row: SubagentLane, now: Long): String {
    val end = if (row.finishedAtEpochMillis > 0) row.finishedAtEpochMillis else now
    val seconds = (end - row.startedAtEpochMillis) / MILLIS_PER_SECOND
    val line = if (row.inputTokens > 0) {
        val cache = row.cachedTokens * PERCENT / row.inputTokens
        getString(
            R.string.console_subagent_log_cached,
            seconds, row.toolCalls, row.modelCalls, row.inputTokens, cache, row.outputTokens,
        )
    } else {
        getString(R.string.console_subagent_log, seconds, row.toolCalls, row.modelCalls, row.inputTokens, row.outputTokens)
    }
    return if (row.error.isNotEmpty()) "$line · ${row.error}" else line
}

@Composable
private fun RuntimeAlertCard(demo: RuntimeDemoState, actions: ConsoleActions) {
    val colors = ConsoleColors
    val pending = demo.pendingConfirmationActionIds()
    BoardSectionCard(stringResource(R.string.console_needs_attention)) {
        Text(
            demoStatusText(demo.status),
            style = ConsoleType.meta,
            color = colors.text,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
        )
        pending.take(MAX_CONFIRMATION_ACTION_ROWS).forEach { pendingId ->
            Spacer(Modifier.height(Dimens.spaceSm))
            Text(
                pendingId,
                style = ConsoleType.meta,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Dimens.spaceXs))
            ApproveDenyRow(
                onApprove = { actions.approveConfirmation(pendingId) },
                onDeny = { actions.denyConfirmation(pendingId) },
            )
        }
    }
}

@Composable
private fun ArtifactPreviewCard(preview: ArtifactPreview?, actions: ConsoleActions) {
    val colors = ConsoleColors
    if (preview == null) return
    BoardSectionCard(stringResource(R.string.console_artifact)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                preview.path,
                style = ConsoleType.meta,
                color = colors.text,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Dimens.spaceSm))
            Icon(
                imageVector = IconFullscreen,
                contentDescription = stringResource(R.string.console_open_fullscreen_preview),
                tint = colors.muted,
                modifier = Modifier.size(MANAGE_CHEVRON_SIZE),
            )
        }
        Text(
            stringResource(R.string.console_bytes, preview.bytes.formatCount()),
            style = ConsoleType.meta,
            color = colors.muted,
        )
        Spacer(Modifier.height(Dimens.spaceXsMd))
        HtmlPreviewBox(
            html = preview.html,
            refreshToken = preview.refreshToken,
            onOpen = { actions.openHtmlViewer(preview.path, preview.html) },
            modifier = Modifier
                .fillMaxWidth()
                .height(ARTIFACT_PREVIEW_HEIGHT),
        )
    }
}

@Composable
private fun SessionCard(board: SessionBoardState, state: ServerState) {
    val session = board.activeSession
    BoardSectionCard(stringResource(R.string.console_run)) {
        SessionStatusHeader(state = state, sessionTitle = session?.title?.let { sessionTitleText(it) })
        if ((session?.turnCount ?: 0) > 0) {
            Spacer(Modifier.height(Dimens.spaceXs))
            Text(
                formatTurnCount(session?.turnCount ?: 0),
                style = ConsoleType.meta,
                color = ConsoleColors.muted,
            )
        }
        session?.compactedSummary?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(Dimens.spaceXs))
            Text(
                it,
                style = ConsoleType.meta,
                color = ConsoleColors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SessionStatusHeader(state: ServerState, sessionTitle: String?) {
    val colors = ConsoleColors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (state.isRunning) {
            ActivityDot(color = colors.activity)
        } else {
            StatusDot(color = colors.success.copy(alpha = IDLE_DOT_ALPHA))
        }
        Spacer(Modifier.width(Dimens.spaceSm))
        Column(Modifier.weight(1f)) {
            if (state.isRunning) {
                ShimmerText(text = statusHeader(state), baseColor = colors.muted, highlightColor = colors.text)
            } else {
                Text(
                    text = state.lastRun?.let { runResultText(it) }
                        ?: stringResource(R.string.console_ready),
                    style = ConsoleType.meta,
                    color = colors.text,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = sessionTitle ?: stringResource(R.string.console_no_active_session),
                style = ConsoleType.meta,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TodoCard(items: List<TodoEntry>) {
    if (items.isEmpty()) return
    BoardSectionCard(stringResource(R.string.console_plan)) {
        items.take(MAX_PLAN_ROWS).forEach { item ->
            PlanStatusRow(item)
        }
        if (items.size > MAX_PLAN_ROWS) {
            Spacer(Modifier.height(Dimens.spaceXs))
            Text(
                stringResource(R.string.console_more_rows, items.size - MAX_PLAN_ROWS),
                style = ConsoleType.meta,
                color = ConsoleColors.muted,
            )
        }
    }
}

@Composable
private fun TokenUsageCard(usage: TokenUsageState) {
    if (!usage.hasUsage) return
    val colors = ConsoleColors
    BoardSectionCard(stringResource(R.string.console_usage)) {
        Text(
            usage.compactUsageLine(),
            style = ConsoleType.meta,
            color = colors.text,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Dimens.spaceSm))
        // Thin cache-hit-rate bar; empty track until the provider reports cache activity.
        UsageProgressBar(fraction = usage.cacheHitRate?.let { it / PERCENT_FLOAT } ?: 0f)
        if (usage.hasCacheActivity || usage.reasoningTokens > 0) {
            Spacer(Modifier.height(Dimens.spaceXs))
            val resources = LocalContext.current.resources
            val detail = buildList {
                if (usage.reasoningTokens > 0) {
                    add(resources.getString(R.string.console_usage_reasoning, usage.reasoningTokens.formatCompact()))
                }
                if (usage.hasCacheActivity) {
                    val read = usage.cachedInputTokens.formatCompact()
                    val write = usage.cacheWriteTokens.formatCompact()
                    add(resources.getString(R.string.console_usage_cache_rw, read, write))
                }
                if (usage.requestCount > 1) {
                    add(resources.getString(R.string.console_usage_requests, usage.requestCount))
                }
            }.joinToString(" · ")
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = ConsoleType.meta,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun UsageProgressBar(fraction: Float) {
    val colors = ConsoleColors
    Box(
        Modifier
            .fillMaxWidth()
            .height(USAGE_BAR_HEIGHT)
            .background(colors.borderSubtle, RoundedCornerShape(Dimens.radiusFull)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(USAGE_BAR_HEIGHT)
                .background(colors.accent, RoundedCornerShape(Dimens.radiusFull)),
        )
    }
}

@Composable
private fun TokenUsageState.compactUsageLine(): String {
    val cache = cacheHitRate
    return if (cache != null) {
        val input = inputTokens.formatCompact()
        stringResource(R.string.console_usage_line_cache, input, outputTokens.formatCompact(), cache)
    } else {
        stringResource(R.string.console_usage_line, inputTokens.formatCompact(), outputTokens.formatCompact())
    }
}

/** The runtime's state words for the alert card; the ones it is shown for are put into the screen's language. */
@Composable
private fun demoStatusText(status: String): String = when (status) {
    "Waiting confirmation" -> stringResource(R.string.console_demo_waiting_confirmation)
    "Blocked" -> stringResource(R.string.console_status_blocked)
    else -> status
}

@Composable
private fun PlanStatusRow(item: TodoEntry) {
    val colors = ConsoleColors
    val active = item.status.isPlanActive()
    Row(
        Modifier.fillMaxWidth().padding(vertical = Dimens.spaceXxs),
        verticalAlignment = Alignment.Top,
    ) {
        StatusDot(
            color = item.status.planStatusColor(),
            pulsing = active,
            modifier = Modifier.padding(top = Dimens.spaceXs),
        )
        Spacer(Modifier.width(Dimens.spaceSm))
        Text(
            text = item.content,
            style = ConsoleType.meta,
            color = colors.text,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MemoryCard(lessons: List<LessonEntry>) {
    if (lessons.isEmpty()) return
    val colors = ConsoleColors
    BoardSectionCard(stringResource(R.string.console_memory)) {
        lessons.take(MAX_MEMORY_ROWS).forEach { lesson ->
            Text(
                lesson.content,
                style = ConsoleType.meta,
                color = colors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Dimens.spaceXs))
        }
    }
}

@Composable
private fun SkillCard(skills: List<SkillEntry>) {
    if (skills.isEmpty()) return
    val colors = ConsoleColors
    BoardSectionCard(stringResource(R.string.console_skills)) {
        Text(
            skills.take(MAX_SKILL_NAMES).joinToString { it.name },
            style = ConsoleType.meta,
            color = colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Board rail section: label-style header, content, hairline — no elevated card wall.
 * Section padding stays on Dimens.spaceMd; inner element gaps use spaceXs/spaceSm.
 */
@Composable
private fun BoardSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = ConsoleColors
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.spaceMd),
    ) {
        SectionLabel(title)
        Spacer(Modifier.height(Dimens.spaceSm))
        content()
        Spacer(Modifier.height(Dimens.spaceSm))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.borderSubtle),
        )
    }
}

private val ARTIFACT_PREVIEW_HEIGHT = 180.dp
private val USAGE_BAR_HEIGHT = 3.dp
private val MANAGE_CHEVRON_SIZE = 16.dp
private const val MAX_CONFIRMATION_ACTION_ROWS = 3
private const val SUBAGENT_TICK_MS = 1_000L
private const val MILLIS_PER_SECOND = 1000L
private const val PERCENT = 100
private const val SUBAGENT_RESULT_PREVIEW_CHARS = 600
private const val MAX_PLAN_ROWS = 8
private const val MAX_MEMORY_ROWS = 3
private const val MAX_SKILL_NAMES = 4
private const val PERCENT_FLOAT = 100f
private const val IDLE_DOT_ALPHA = 0.75f

@Composable
private fun String.planStatusColor(): Color {
    val colors = ConsoleColors
    return when {
        isPlanActive() -> colors.activity
        lowercase() in setOf("completed", "done", "success") -> colors.success
        lowercase().contains("fail") || lowercase() in setOf("stalled", "timed_out") -> colors.danger
        else -> colors.muted
    }
}

private fun String.isPlanActive(): Boolean =
    lowercase() in setOf("in_progress", "running", "active", "doing")

internal fun Int.formatCount(): String = "%,d".format(this)

internal fun Long.formatCount(): String = "%,d".format(this)

/** Compact counts for rail density (1.2k, 15). */
private fun Int.formatCompact(): String =
    when {
        this >= 1_000_000 -> "%.1fM".format(this / 1_000_000.0)
        this >= 10_000 -> "${this / 1000}k"
        this >= 1000 -> "%.1fk".format(this / 1000.0)
        else -> toString()
    }
