@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.console
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.agent.runtime.SubagentStatus
import cc.noharry.bothy.app.console.ui.ActivityDot
import cc.noharry.bothy.app.console.ui.ApproveDenyRow
import cc.noharry.bothy.app.console.ui.HtmlPreviewBox
import cc.noharry.bothy.app.console.ui.IconFullscreen
import cc.noharry.bothy.app.console.ui.SectionLabel
import cc.noharry.bothy.app.console.ui.ShimmerText
import cc.noharry.bothy.app.console.ui.StatusDot
import cc.noharry.bothy.app.console.ui.formatTurnCount
import cc.noharry.bothy.app.mcp.McpProviderState
import cc.noharry.bothy.app.runtime.RuntimeDemoState
import cc.noharry.bothy.app.runtime.WorkspaceSelectionState
import cc.noharry.bothy.app.runtime.pendingConfirmationActionIds
import cc.noharry.bothy.app.session.LessonEntry
import cc.noharry.bothy.app.session.SessionBoardState
import cc.noharry.bothy.app.session.SkillEntry
import cc.noharry.bothy.app.session.TodoEntry
import cc.noharry.bothy.app.session.UiState
import cc.noharry.bothy.app.skills.BothySubagents
import cc.noharry.bothy.app.skills.chinese
import cc.noharry.bothy.app.skills.logLine
import cc.noharry.bothy.app.usage.TokenUsageState
import kotlinx.coroutines.delay

@Composable
internal fun BoardPane(
    state: UiState,
    actions: ConsoleActions,
    onOpenMcpProviders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val subagentBoard by BothySubagents.board.collectAsState()
    val subagents = subagentBoard[state.activeSessionId].orEmpty()
    val sections = boardSections().filter { state.shouldShowBoardSection(it) }
        .filterNot { it == BoardSection.SUBAGENTS && subagents.isEmpty() }
    PanelBox("Board", modifier) {
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
    BoardSectionCard("Workspace") {
        Text(
            selection.label,
            style = ConsoleType.meta,
            color = colors.text,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = actions.pickSafWorkspace) { Text("Pick SAF") }
            if (selection.safEnabled) {
                TextButton(onClick = actions.clearSafWorkspace) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun McpOneLiner(providers: List<McpProviderState>, onOpen: () -> Unit) {
    val colors = ConsoleColors
    val enabled = providers.filter { it.enabled }
    val names = enabled.joinToString { it.label }.ifBlank { "none" }
    BoardSectionCard("MCP", Modifier.clickable(onClick = onOpen)) {
        Text(
            "${enabled.size}/${providers.size} loaded · $names",
            style = ConsoleType.meta,
            color = colors.text,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Dimens.spaceXs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tap to manage", style = ConsoleType.meta, color = colors.muted)
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
private fun SubagentCard(rows: List<SubagentStatus>) {
    val colors = ConsoleColors
    val running = rows.any { !it.state.finished }
    // Running ones count their seconds even between events.
    val now by produceState(System.currentTimeMillis(), running) {
        while (running) {
            value = System.currentTimeMillis()
            delay(SUBAGENT_TICK_MS)
        }
    }
    BoardSectionCard("Subagents") {
        rows.forEach { row ->
            var open by remember(row.id) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = Dimens.spaceXs)) {
                Text(
                    "● ${row.label} · ${row.state.chinese()}",
                    style = ConsoleType.meta,
                    color = row.state.wireName.planStatusColor(),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(row.logLine(now), style = ConsoleType.meta, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!row.state.finished && row.latest.isNotEmpty()) {
                    Text("最近：${row.latest}", style = ConsoleType.meta, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (open) {
                    Text("任务：${row.task}", style = ConsoleType.meta, color = colors.text)
                    val outcome = row.result.ifEmpty { row.error }
                    if (outcome.isNotEmpty()) {
                        Text(outcome.take(SUBAGENT_RESULT_PREVIEW_CHARS), style = ConsoleType.meta, color = colors.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeAlertCard(demo: RuntimeDemoState, actions: ConsoleActions) {
    val colors = ConsoleColors
    val pending = demo.pendingConfirmationActionIds()
    BoardSectionCard("Needs attention") {
        Text(
            demo.status,
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
    BoardSectionCard("Artifact") {
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
                contentDescription = "Open fullscreen preview",
                tint = colors.muted,
                modifier = Modifier.size(MANAGE_CHEVRON_SIZE),
            )
        }
        Text("${preview.bytes.formatCount()} bytes", style = ConsoleType.meta, color = colors.muted)
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
private fun SessionCard(board: SessionBoardState, state: UiState) {
    val session = board.activeSession
    BoardSectionCard("Run") {
        SessionStatusHeader(state = state, sessionTitle = session?.title)
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
private fun SessionStatusHeader(state: UiState, sessionTitle: String?) {
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
                    text = state.lastRunSummary?.takeIf { it.isNotBlank() } ?: "Ready",
                    style = ConsoleType.meta,
                    color = colors.text,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = sessionTitle ?: "No active session",
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
    BoardSectionCard("Plan") {
        items.take(MAX_PLAN_ROWS).forEach { item ->
            PlanStatusRow(item)
        }
        if (items.size > MAX_PLAN_ROWS) {
            Spacer(Modifier.height(Dimens.spaceXs))
            Text(
                "+${items.size - MAX_PLAN_ROWS} more",
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
    BoardSectionCard("Usage") {
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
            val detail = buildList {
                if (usage.reasoningTokens > 0) add("reason ${usage.reasoningTokens.formatCompact()}")
                if (usage.hasCacheActivity) {
                    add("r/w ${usage.cachedInputTokens.formatCompact()}/${usage.cacheWriteTokens.formatCompact()}")
                }
                if (usage.requestCount > 1) add("${usage.requestCount} req")
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

private fun TokenUsageState.compactUsageLine(): String {
    val cache = cacheHitRate?.let { " · cache $it%" }.orEmpty()
    return "${inputTokens.formatCompact()} in · ${outputTokens.formatCompact()} out$cache"
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
    BoardSectionCard("Memory") {
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
    BoardSectionCard("Skills") {
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
