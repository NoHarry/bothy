package cc.noharry.bothy.app.trajectory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.SettingsRequests
import cc.noharry.agent.appserver.protocol.TrajectoryHealth
import cc.noharry.agent.appserver.protocol.TrajectoryRawParams
import cc.noharry.agent.appserver.protocol.TrajectoryRecord
import cc.noharry.agent.appserver.protocol.view.TrajectoryInspectorTab
import cc.noharry.agent.appserver.protocol.view.inspectorTabsFor
import cc.noharry.agent.appserver.protocol.view.paginateRawText
import cc.noharry.agent.appserver.protocol.view.trajectoryInspectorSummaryEntries
import cc.noharry.agent.appserver.protocol.view.trajectoryInspectorTimingEntries
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.frontend.LocalServer

/**
 * Record details projected from the same canonical SessionEvent as the ledger.
 *
 * Tabs are type-specific. Raw is a paginated canonical payload, never a reconstructed
 * sidecar and never one unbounded Compose [Text] of the whole EventLog file.
 */
@Composable
internal fun TrajectoryInspector(
    record: TrajectoryRecord,
    health: TrajectoryHealth?,
    tab: TrajectoryInspectorTab,
    onTabChange: (TrajectoryInspectorTab) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    durationMs: Long? = null,
) {
    val colors = ConsoleColors
    val tabs = inspectorTabsFor(record)
    val resolvedTab = if (tab in tabs) tab else tabs.first()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Dimens.radiusMd),
        color = colors.panel,
        border = BorderStroke(1.dp, colors.borderSubtle),
    ) {
        Column(Modifier.fillMaxWidth().padding(Dimens.spaceSm)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    record.displayTitle(),
                    style = ConsoleType.panelTitle,
                    color = colors.text,
                    modifier = Modifier.weight(1f),
                )
                TrajectoryTextButton(stringResource(R.string.common_close), onClose)
            }
            Spacer(Modifier.height(Dimens.spaceXs))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
            ) {
                tabs.forEach { entry ->
                    InspectorTabChip(entry, entry == resolvedTab) { onTabChange(entry) }
                }
            }
            Spacer(Modifier.height(Dimens.spaceSm))
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                when (resolvedTab) {
                    TrajectoryInspectorTab.SUMMARY -> SummaryTab(record, health, durationMs)
                    TrajectoryInspectorTab.PREVIEW -> PreviewTab(
                        preview = record.rich?.inputPreview ?: record.rich?.outputText ?: record.summary,
                        hashKeys = emptyList(),
                        record = record,
                    )
                    TrajectoryInspectorTab.RAW -> RawTab(record)
                    TrajectoryInspectorTab.SOURCE -> SourceTab(record)
                    TrajectoryInspectorTab.REQUEST -> PreviewTab(
                        preview = record.rich?.requestText,
                        hashKeys = emptyList(),
                        record = record,
                    )
                    TrajectoryInspectorTab.OPTIONS -> PreviewTab(
                        preview = record.rich?.payloadPreview,
                        hashKeys = emptyList(),
                        record = record,
                    )
                    TrajectoryInspectorTab.TOOLS -> PreviewTab(
                        preview = record.rich?.schemaPreview,
                        hashKeys = emptyList(),
                        record = record,
                    )
                    TrajectoryInspectorTab.THINKING -> PreviewTab(
                        preview = record.rich?.thinkingText,
                        hashKeys = emptyList(),
                        record = record,
                    )
                    TrajectoryInspectorTab.OUTPUT -> PreviewTab(
                        preview = record.rich?.outputText,
                        hashKeys = emptyList(),
                        record = record,
                    )
                    TrajectoryInspectorTab.USAGE -> PreviewTab(
                        preview = record.rich?.usageText ?: record.summary,
                        hashKeys = emptyList(),
                        record = record,
                    )
                    TrajectoryInspectorTab.PAYLOAD -> PreviewTab(
                        preview = record.rich?.payloadPreview,
                        hashKeys = listOf("input", "arguments"),
                        record = record,
                    )
                    TrajectoryInspectorTab.RESULT -> PreviewTab(
                        preview = record.rich?.resultPreview,
                        hashKeys = listOf("result", "response", "content"),
                        record = record,
                    )
                    TrajectoryInspectorTab.SCHEMA -> PreviewTab(
                        preview = record.rich?.schemaPreview,
                        hashKeys = emptyList(),
                        record = record,
                    )
                    TrajectoryInspectorTab.TIMING -> TimingTab(record, durationMs)
                    TrajectoryInspectorTab.HEALTH -> HealthTab(record, health)
                }
            }
        }
    }
}

@Composable
private fun SummaryTab(record: TrajectoryRecord, health: TrajectoryHealth?, durationMs: Long?) {
    TrajectoryMetaRow(trajectoryInspectorSummaryEntries(record, health, durationMs))
    if (record.safeMetadata.isNotEmpty()) {
        Spacer(Modifier.height(Dimens.spaceSm))
        TrajectorySectionHeader(stringResource(R.string.trajectory_safe_metadata))
        TrajectoryMetaRow(record.safeMetadata.toList())
    }
}

@Composable
private fun SourceTab(record: TrajectoryRecord) {
    TrajectoryMetaRow(
        buildList {
            add("source" to "SessionEventLog v2")
            add("eventId" to (record.eventId ?: "unknown"))
            add("sessionId" to record.sessionId)
            add("runId" to record.runId)
            record.requestId?.let { add("requestId" to it) }
            add("identity" to record.id.value)
        },
    )
}

@Composable
private fun HealthTab(record: TrajectoryRecord, health: TrajectoryHealth?) {
    if (health == null) {
        Text(
            text = stringResource(R.string.trajectory_no_health),
            style = ConsoleType.meta,
            color = ConsoleColors.muted,
        )
        return
    }
    TrajectoryMetaRow(
        buildList {
            add("status" to health.badgeLabel)
            add("durability" to health.durability.name)
            add("attempted" to health.attemptedCount.toString())
            add("appended" to health.appendedCount.toString())
            add("failed" to health.failedCount.toString())
            add("lastAccepted" to (health.lastAcceptedSequence?.toString() ?: "none"))
            add("lastPersisted" to (health.lastPersistedSequence?.toString() ?: "none"))
            add("gap" to health.hasSequenceGap.toString())
            health.safeReasonCode?.let { add("reason" to it) }
            add("record" to record.id.value)
        },
    )
}

@Composable
private fun PreviewTab(preview: String?, hashKeys: List<String>, record: TrajectoryRecord) {
    val colors = ConsoleColors
    val hashes = record.contentHashes.filterKeys { it in hashKeys }
    if (preview.isNullOrBlank() && hashes.isEmpty()) {
        Text(
            text = stringResource(R.string.trajectory_not_recorded),
            style = ConsoleType.meta,
            color = colors.muted,
        )
        return
    }
    preview?.takeIf { it.isNotBlank() }?.let { body ->
        TrajectorySectionHeader(stringResource(R.string.trajectory_canonical, record.sources.joinToString { it.name }))
        Spacer(Modifier.height(Dimens.spaceXxs))
        Text(
            text = body,
            style = ConsoleType.code,
            color = colors.text,
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.codeBg, RoundedCornerShape(Dimens.radiusSm))
                .padding(Dimens.spaceSm),
        )
        Spacer(Modifier.height(Dimens.spaceSm))
    }
    if (hashes.isNotEmpty()) {
        TrajectorySectionHeader(stringResource(R.string.trajectory_content_hashes))
        TrajectoryMetaRow(hashes.toList())
    }
}

@Composable
private fun RawTab(record: TrajectoryRecord) {
    val colors = ConsoleColors
    val server = LocalServer.current
    // The event in full is asked for when the tab shows it: sent with every record, it would ride each change of a run.
    var loaded by remember(record.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(record.id) {
        val eventId = record.eventId
        if (eventId == null) {
            loaded = ""
        } else {
            server.send(SettingsRequests.TRAJECTORY_RAW, TrajectoryRawParams(record.sessionId, eventId), onRefused = { loaded = "" }) {
                loaded = it.text.orEmpty()
            }
        }
    }
    val raw = loaded ?: return
    if (raw.isBlank()) {
        Text(
            text = stringResource(R.string.trajectory_no_payload),
            style = ConsoleType.meta,
            color = colors.muted,
        )
        return
    }
    val pages = remember(raw) { paginateRawText(raw) }
    var page by remember(record.id) { mutableIntStateOf(0) }
    val safePage = page.coerceIn(0, pages.lastIndex)
    TrajectorySectionHeader(stringResource(R.string.trajectory_raw_header, safePage + 1, pages.size, raw.length))
    Spacer(Modifier.height(Dimens.spaceXxs))
    if (pages.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
            TrajectoryTextButton(stringResource(R.string.trajectory_prev)) { page = (safePage - 1).coerceAtLeast(0) }
            TrajectoryTextButton(stringResource(R.string.trajectory_next)) {
                page = (safePage + 1).coerceAtMost(pages.lastIndex)
            }
        }
        Spacer(Modifier.height(Dimens.spaceXs))
    }
    Text(
        text = pages[safePage],
        style = ConsoleType.code,
        color = colors.text,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.codeBg, RoundedCornerShape(Dimens.radiusSm))
            .padding(Dimens.spaceSm),
    )
}

@Composable
private fun TimingTab(record: TrajectoryRecord, durationMs: Long?) {
    TrajectoryMetaRow(trajectoryInspectorTimingEntries(record, durationMs))
}

@Composable
private fun InspectorTabChip(tab: TrajectoryInspectorTab, selected: Boolean, onClick: () -> Unit) {
    val colors = ConsoleColors
    val label = stringResource(tab.labelRes())
    Surface(
        shape = RoundedCornerShape(Dimens.radiusFull),
        color = if (selected) colors.accentSoft else colors.elevated,
        border = BorderStroke(1.dp, colors.borderSubtle),
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Tab
                this.selected = selected
            },
    ) {
        Text(
            text = label,
            style = ConsoleType.label,
            color = if (selected) colors.accent else colors.muted,
            modifier = Modifier.padding(horizontal = Dimens.spaceSm, vertical = Dimens.spaceXxs),
        )
    }
}
