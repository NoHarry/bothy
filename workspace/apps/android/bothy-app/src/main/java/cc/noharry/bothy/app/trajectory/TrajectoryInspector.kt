@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.model.summary
import cc.noharry.bothy.app.runtime.formatWorkedDuration

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
                Text(record.title, style = ConsoleType.panelTitle, color = colors.text, modifier = Modifier.weight(1f))
                TrajectoryTextButton("Close", onClose)
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
        TrajectorySectionHeader("SAFE METADATA")
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
            text = "No EventLog health for this record.",
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
            text = "Not recorded by durable EventLog.",
            style = ConsoleType.meta,
            color = colors.muted,
        )
        return
    }
    preview?.takeIf { it.isNotBlank() }?.let { body ->
        TrajectorySectionHeader("CANONICAL · ${record.sources.joinToString { it.name }}")
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
        TrajectorySectionHeader("CONTENT HASHES")
        TrajectoryMetaRow(hashes.toList())
    }
}

@Composable
private fun RawTab(record: TrajectoryRecord) {
    val colors = ConsoleColors
    val raw = record.rich?.rawText.orEmpty()
    if (raw.isBlank()) {
        Text(
            text = "No canonical payload for this record.",
            style = ConsoleType.meta,
            color = colors.muted,
        )
        return
    }
    val pages = remember(raw) { paginateRawText(raw) }
    var page by remember(record.id) { mutableIntStateOf(0) }
    val safePage = page.coerceIn(0, pages.lastIndex)
    TrajectorySectionHeader("RAW ${safePage + 1}/${pages.size} · ${raw.length} chars")
    Spacer(Modifier.height(Dimens.spaceXxs))
    if (pages.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
            TrajectoryTextButton("Prev") { page = (safePage - 1).coerceAtLeast(0) }
            TrajectoryTextButton("Next") { page = (safePage + 1).coerceAtMost(pages.lastIndex) }
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

internal fun trajectoryInspectorSummaryEntries(
    record: TrajectoryRecord,
    health: TrajectoryHealth?,
    durationMs: Long?,
): List<Pair<String, String>> = buildList {
    add("id" to record.id.value)
    add("kind" to record.kind.name)
    add("lane" to record.lane.name)
    record.status?.let { add("status" to it) }
    add("sequence" to (record.sequence?.toString() ?: "not persisted"))
    add("duration" to formatTrajectoryDurationLabel(durationMs))
    add("sources" to record.sources.joinToString { it.name })
    record.correlation.toolCallId?.let { add("toolCallId" to it) }
    record.correlation.modelCallId?.let { add("modelCallId" to it) }
    record.correlation.lifecyclePhase?.let { add("phase" to it) }
    if (record.sourceEventSequences.isNotEmpty()) {
        add("causedBy" to record.sourceEventSequences.joinToString())
    }
    health?.let { add("eventLog" to it.badgeLabel) }
    add("details" to record.richAvailability.name)
}

internal fun trajectoryInspectorTimingEntries(
    record: TrajectoryRecord,
    durationMs: Long?,
): List<Pair<String, String>> = buildList {
    add("time" to (record.occurredAtEpochMillis?.toString() ?: "unknown"))
    add("timeQuality" to record.timeQuality.name)
    add("duration" to formatTrajectoryDurationLabel(durationMs))
    durationMs?.let { add("durationMs" to it.toString()) }
    record.rich?.timing?.forEach { (key, value) ->
        if (key != DURATION_MS_KEY) add(key to value.toString())
    }
}

internal fun trajectoryDurationMs(
    record: TrajectoryRecord,
    siblings: List<TrajectoryRecord>,
): Long? {
    recordedDurationMs(record)?.let { return it }
    toolCallDurationMs(siblings, record.correlation.toolCallId)?.let { return it }
    val modelId = record.correlation.modelCallId ?: return null
    return siblings.firstNotNullOfOrNull { sibling ->
        sibling.takeIf { it.correlation.modelCallId == modelId }?.let(::recordedDurationMs)
    }
}

internal fun recordedDurationMs(record: TrajectoryRecord): Long? =
    record.safeMetadata[DURATION_MS_KEY]?.toLongOrNull()
        ?: record.rich?.timing?.get(DURATION_MS_KEY)

internal fun formatTrajectoryDuration(durationMs: Long): String {
    val ms = durationMs.coerceAtLeast(0L)
    if (ms < MILLIS_PER_SECOND) return "${ms}ms"
    if (ms < MILLIS_PER_MINUTE) {
        val hundredths = (ms % MILLIS_PER_SECOND) / 10L
        return "${ms / MILLIS_PER_SECOND}.${hundredths.toString().padStart(2, '0')}s"
    }
    return formatWorkedDuration(ms)
}

internal fun formatTrajectoryDurationLabel(durationMs: Long?): String =
    durationMs?.let(::formatTrajectoryDuration) ?: UNKNOWN_DURATION_LABEL

private const val DURATION_MS_KEY = "durationMs"
private const val UNKNOWN_DURATION_LABEL = "—"
private const val MILLIS_PER_SECOND = 1000L
private const val MILLIS_PER_MINUTE = 60_000L

@Composable
private fun InspectorTabChip(tab: TrajectoryInspectorTab, selected: Boolean, onClick: () -> Unit) {
    val colors = ConsoleColors
    Surface(
        shape = RoundedCornerShape(Dimens.radiusFull),
        color = if (selected) colors.accentSoft else colors.elevated,
        border = BorderStroke(1.dp, colors.borderSubtle),
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = tab.name
                role = Role.Tab
                this.selected = selected
            },
    ) {
        Text(
            text = tab.name.lowercase().replaceFirstChar { it.uppercase() },
            style = ConsoleType.label,
            color = if (selected) colors.accent else colors.muted,
            modifier = Modifier.padding(horizontal = Dimens.spaceSm, vertical = Dimens.spaceXxs),
        )
    }
}
