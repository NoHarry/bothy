@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.usage
import cc.noharry.agent.kernel.api.SessionEvent
import cc.noharry.agent.kernel.api.SessionEventPayload
import cc.noharry.agent.kernel.api.model.ModelRef
import cc.noharry.agent.kernel.core.SESSION_MODEL_CALL_SOURCE_COMPACTION
import cc.noharry.agent.kernel.core.SESSION_MODEL_CALL_SOURCE_ROUND
import cc.noharry.agent.model.ModelVariantRegistry
import cc.noharry.bothy.app.session.UiState
import cc.noharry.bothy.app.trajectory.TrajectorySnapshot
import java.text.NumberFormat
import java.util.Locale

/** Latest completed business request's inclusive input count, not cumulative run billing. */
internal data class ContextUsage(
    val inputTokens: Long? = null,
    val windowTokens: Long? = null,
    val awaitingAfterCompaction: Boolean = false,
) {
    val fraction: Float? get() = inputTokens?.let { used ->
        windowTokens?.takeIf { it > 0 }?.let { (used.toDouble() / it).toFloat().coerceIn(0f, 1f) }
    }
    val remainingTokens: Long? get() = inputTokens?.let { used -> windowTokens?.let { (it - used).coerceAtLeast(0) } }
    val percentText: String? get() = inputTokens?.let { used ->
        windowTokens?.takeIf { it > 0 }?.let { String.format(Locale.ROOT, "%.1f%%", used.toDouble() / it * PERCENT) }
    }
}

internal fun contextUsage(state: UiState, windowTokens: Long?): ContextUsage {
    val profile = state.configEditor
    val modelRef = ModelVariantRegistry.resolve(ModelRef(profile.provider.wireName, profile.model, profile.variant))
        ?.wireValue().orEmpty()
    val activeRunId = state.currentRequest?.requestId.takeIf { state.isRunning }
    return contextUsage(state.trajectory, modelRef, windowTokens, activeRunId)
}

internal fun contextUsage(
    snapshot: TrajectorySnapshot,
    modelRef: String,
    windowTokens: Long?,
    activeRunId: String? = null,
): ContextUsage {
    val empty = ContextUsage(windowTokens = windowTokens)
    // Repository catalog is newest-first, including live arrivals; the running run leads.
    val active = activeRunId?.let { id -> snapshot.runGroups.find { it.runId == id } }
    // A run carries its session's earlier runs, so until its own first call reports, the context in use is the size of
    // the session's latest request: fall back to earlier runs instead of showing an empty ring at every run start.
    for (group in listOfNotNull(active) + snapshot.runGroups.filter { it !== active }) {
        if (group.sessionId != snapshot.sessionId || group.loadFailed || group.health.hasSequenceGap) continue
        val events = group.records.mapNotNull { it.rich?.rawEvent }
            .filter { it.sessionId == snapshot.sessionId && it.runId == group.runId }.sortedBy { it.sequence }
        val usage = recentContextInput(events, modelRef, empty)
        if (usage.inputTokens != null || usage.awaitingAfterCompaction) return usage
    }
    return empty
}

private fun recentContextInput(events: List<SessionEvent>, modelRef: String, empty: ContextUsage): ContextUsage {
    var request: SessionEventPayload.ModelRequest? = null
    var input = 0L
    var invalid = false
    var last = empty
    for (event in events) {
        when (val payload = event.payload) {
            is SessionEventPayload.ModelRequest -> { request = payload; input = 0; invalid = false }
            is SessionEventPayload.Usage -> if (request != null) {
                // Runtime normalizes cache-exclusive provider usage into inclusive input already.
                if (payload.inputTokens < 0 || payload.inputTokens > Long.MAX_VALUE - input) invalid = true
                else input += payload.inputTokens
            }
            is SessionEventPayload.ModelCallFinished -> {
                last = when {
                    payload.source == SESSION_MODEL_CALL_SOURCE_COMPACTION -> empty.copy(awaitingAfterCompaction = true)
                    payload.source != SESSION_MODEL_CALL_SOURCE_ROUND -> last
                    request?.modelRef != modelRef || payload.modelRef != modelRef -> empty
                    invalid || input <= 0 -> empty
                    else -> empty.copy(inputTokens = input)
                }
                request = null
            }
            else -> Unit
        }
    }
    return last
}

internal fun formatContextTokens(tokens: Long): String = NumberFormat.getIntegerInstance(Locale.US).format(tokens)
private const val PERCENT = 100
