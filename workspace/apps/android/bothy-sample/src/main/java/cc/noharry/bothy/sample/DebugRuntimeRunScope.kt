@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import android.content.Context
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRuntime
import cc.noharry.agent.runtime.NetworkToolConfirmationPolicy
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DebugRuntimeRunScope(
    val requestId: String,
    val sessionId: String,
) {
    fun ownsUi(activeRequestId: String?, activeSessionId: String?, selectedSessionId: String): Boolean =
        activeRequestId == requestId && activeSessionId == sessionId && selectedSessionId == sessionId
}

internal data class DebugRuntimeFinalization(
    val replay: DebugRunReplayResult,
    val contextPanel: DebugContextPanelState?,
)

internal data class DebugRuntimeRunRecorders(
    val evidenceRecorder: McpProgressiveEvidenceRecorder?,
    val runEvidenceRecorder: DebugRunEvidenceRecorder,
    val turnRecorder: DebugSessionTurnRecorder,
)

internal fun scopedDebugDiagnostics(
    context: Context,
    runScope: DebugRuntimeRunScope,
    ownsUi: (DebugRuntimeRunScope) -> Boolean,
    onEntry: (DebugDiagnosticEntry) -> Unit,
    onTokenUsage: (DebugTokenUsageDelta) -> Unit,
): DebugDiagnostics =
    debugDiagnostics(
        context = context,
        requestId = runScope.requestId,
        onEntry = { entry -> if (ownsUi(runScope)) onEntry(entry) },
        onTokenUsage = { delta -> if (ownsUi(runScope)) onTokenUsage(delta) },
    )

internal fun recordDebugRawToolEvent(
    title: String,
    body: String,
    evidenceRecorder: McpProgressiveEvidenceRecorder?,
    runEvidenceRecorder: DebugRunEvidenceRecorder,
    turnRecorder: DebugSessionTurnRecorder,
    ownsUi: Boolean,
    appendRawToolEvent: (String, String) -> Unit,
) {
    evidenceRecorder?.recordRawToolEvent(title, body)
    runEvidenceRecorder.recordRawToolEvent(title, body)
    turnRecorder.recordRawToolEvent(title, body)
    if (ownsUi) appendRawToolEvent(title, body)
}

internal fun buildScopedDebugRuntime(
    context: Context,
    config: DebugProviderConfig,
    profile: DebugProviderProfile,
    streamEnabled: Boolean,
    useSimulatedModel: Boolean,
    workspaceRoot: File,
    sessionContext: String,
    diagnostics: DebugDiagnostics,
    runScope: DebugRuntimeRunScope,
    recorders: DebugRuntimeRunRecorders,
    mcpProviders: List<DebugMcpProviderState>,
    a2aBridgeProviders: List<DebugA2aBridgeProvider>,
    confirmationMode: () -> DebugConfirmationMode = { DebugConfirmationMode.ASK_EVERY_TIME },
    /** Same text as AgentRequest.input — hashed into evidence when [benchCaseBound] is true. */
    userInput: String = "",
    /**
     * Production case-bound flag. Default false. Session runner sets true only for the
     * exact sample bench prompt so [buildDebugRuntime] calls [runtimeEvidence].
     */
    benchCaseBound: Boolean = false,
    previousExchange: DebugPreviousExchange? = null,
): AgentRuntime {
    // A child of this conversation is built like this run's runtime: same connection and tools, its own log, no
    // conversation history or recap, the guard, and the model the settings or the spawning run chose.
    DebugSubagents.rememberChildRuntime(runScope.sessionId) { spec ->
        val kind = spec.agentType?.let { debugSubagentKind(context, it) }
        buildDebugRuntime(
            context, config, profile.forSubagent(config, spec.modelRef), streamEnabled, useSimulatedModel, workspaceRoot,
            debugSessionEventLogRoot(context, spec.sessionId),
            sessionContext = "",
            evidenceRecorder = null,
            diagnostics = debugDiagnostics(context, "${spec.sessionId}-run", onEntry = {}, onTokenUsage = {}),
            mcpProviders = mcpProviders,
            onRawToolTranscript = { _, _ -> },
            networkConfirmationPolicy = confirmationMode().toNetworkPolicy(),
            userInput = spec.task,
            hostHook = DebugSubagentGuard(spec.allowDeviceControl, kind?.maxLookups ?: MAX_CHILD_LOOKUPS, kind?.tools, kind?.id.orEmpty()),
            subagentChild = true,
            subagentKind = kind,
        )
    }
    return buildDebugRuntime(
        context, config, profile, streamEnabled, useSimulatedModel, workspaceRoot,
        debugSessionEventLogRoot(context, runScope.sessionId),
        sessionContext,
        recorders.evidenceRecorder,
        diagnostics,
        mcpProviders,
        a2aBridgeProviders,
        { title, body ->
            DebugExecutionEvents.publish(runScope.requestId, DebugExecutionMessage.RawTool(title, body))
        },
        networkConfirmationPolicy = confirmationMode().toNetworkPolicy(),
        userInput = userInput,
        benchCaseBound = benchCaseBound,
        hostHook = DebugExecutionToolHook(),
        previousExchange = previousExchange,
        sessionId = runScope.sessionId,
    )
}

/** The profile a child runs on: the parent's, with the model the subagent settings chose (same provider only). */
private fun DebugProviderProfile.forSubagent(config: DebugProviderConfig, ref: cc.noharry.agent.kernel.api.model.ModelRef?): DebugProviderProfile {
    if (ref == null || (ref.providerId == provider.wireName && ref.modelId == model)) return this
    val base = if (ref.providerId == provider.wireName) this else config.profiles.firstOrNull { it.provider.wireName == ref.providerId } ?: return this
    return base.copy(model = ref.modelId, variant = ref.variant.orEmpty())
}

private fun DebugConfirmationMode.toNetworkPolicy(): NetworkToolConfirmationPolicy =
    if (this == DebugConfirmationMode.ALLOW_ALL_SESSION) {
        NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS
    } else {
        NetworkToolConfirmationPolicy.REQUIRE_PER_HOST
    }

internal fun scopedDebugEventHandler(
    runScope: DebugRuntimeRunScope,
    ownsUi: (DebugRuntimeRunScope) -> Boolean,
    transcript: DebugTranscriptStore,
    setStatus: (String) -> Unit,
    notifyChanged: () -> Unit,
    confirmationMode: () -> DebugConfirmationMode = { DebugConfirmationMode.ASK_EVERY_TIME },
    confirmationDomain: (String) -> String = { "" },
    confirmedDomains: () -> Set<String> = { emptySet() },
): (AgentEvent) -> Unit = { event ->
    handleScopedDebugAgentEvent(
        event = event,
        ownsUi = ownsUi(runScope),
        transcript = transcript,
        setStatus = setStatus,
        notifyChanged = notifyChanged,
        confirmationMode = confirmationMode,
        confirmationDomain = confirmationDomain,
        confirmedDomains = confirmedDomains,
    )
}

internal fun handleScopedDebugAgentEvent(
    event: AgentEvent,
    ownsUi: Boolean,
    transcript: DebugTranscriptStore,
    setStatus: (String) -> Unit,
    notifyChanged: () -> Unit,
    confirmationMode: () -> DebugConfirmationMode = { DebugConfirmationMode.ASK_EVERY_TIME },
    confirmationDomain: (String) -> String = { "" },
    confirmedDomains: () -> Set<String> = { emptySet() },
) {
    if (!ownsUi) return
    if (event is AgentEvent.ResultAvailable) {
        transcript.markPendingToolCallsUnknown()
        // Issue banner only when tools failed; successful result body is the Assistant message.
        val issues = transcript.toolIssuesForCurrentTurn()
        if (issues.hasIssues) {
            transcript.appendRuntimeResult(event.result.status.name, event.result.response.displayReplyText())
        }
        transcript.appendFinalResponse(event.result.response.displayReplyText())
        notifyChanged()
        return
    }
    // Token usage is added to the run's record once, by DebugRuntimeSessionRunner.applyTokenUsage, for every run
    // whether or not it owns the screen; adding it here as well counted each request twice on the Board.
    when (event) {
        is AgentEvent.NativeToolCallStarted -> transcript.recordPublicToolCallStarted(
            toolName = event.toolName,
            toolCallId = event.toolCallId,
            arguments = event.arguments,
        )
        // update_todo board updates are event-sourced in DebugRuntimeSessionRunner.applyTodoBoard;
        // no workspace re-read happens here.
        is AgentEvent.NativeToolCallFinished -> transcript.recordPublicToolCallFinished(
            toolName = event.toolName,
            toolCallId = event.toolCallId,
            result = event.result,
        )
        is AgentEvent.SearchActivity -> transcript.recordSearchActivity(
            provider = event.provider,
            queryPreview = event.queryPreview,
            resultCount = event.resultCount,
        )
        else -> Unit
    }
    handleDebugAgentEvent(
        event,
        appendThinking = transcript::appendThinkingDelta,
        appendAssistant = transcript::appendAssistantDelta,
        appendRuntime = { title, body -> transcript.appendRuntime(title, body) },
        appendError = transcript::appendError,
        appendConfirmation = { pending ->
            val mode = confirmationMode()
            val domain = confirmationDomain(pending.pendingId)
            // When the active mode auto-resolves (ALLOW_ALL_SESSION / DENY_ALL / confirmed domain),
            // skip inserting a PENDING card to avoid the flicker of "insert then async-resolve".
            if (!shouldSuppressConfirmationCard(mode, domain, confirmedDomains())) {
                transcript.appendConfirmationRequired(
                    pendingId = pending.pendingId,
                    domain = domain,
                    mode = mode,
                )
            }
        },
    )?.let(setStatus)
    notifyChanged()
}

internal suspend fun finalizeDebugRuntimeRun(
    context: Context,
    runtime: AgentRuntime,
    transcript: DebugTranscriptStore,
    sessionCoordinator: DebugSessionCoordinator,
    evidenceRecorder: McpProgressiveEvidenceRecorder?,
    runEvidenceRecorder: DebugRunEvidenceRecorder,
    turnRecorder: DebugSessionTurnRecorder,
    ownsUi: Boolean,
): DebugRuntimeFinalization {
    if (ownsUi) transcript.markPendingToolCallsUnknown()
    evidenceRecorder?.let { recorder ->
        withContext(Dispatchers.IO) {
            recorder.drainMcpDebugTrace(runtime)
            // Write MCP evidence to disk only — do not dump paths into the chat stream.
            runCatching { recorder.write() }
        }
    }
    val memoryEvidence = withContext(Dispatchers.IO) {
        runCatching { loadDebugRuntimeMemoryEvidence(context) }.getOrDefault(DebugRuntimeMemoryEvidence())
    }
    runEvidenceRecorder.recordMemoryEvidence(memoryEvidence)
    val replay = if (ownsUi) {
        // Always write evidence; only surface a timeline row when replay fails.
        transcript.appendRunEvidence(runEvidenceRecorder, surfaceOnTimeline = false)
    } else {
        withContext(Dispatchers.IO) {
            DebugRunReplayVerifier.verify(runEvidenceRecorder.snapshot()).also {
                runCatching { runEvidenceRecorder.write() }
            }
        }
    }
    turnRecorder.recordReplay(replay)
    val turn = turnRecorder.snapshot()
    withContext(Dispatchers.IO) { sessionCoordinator.appendTurn(turn) }
    // Persist HTML pages next to the session so they outlive run scratch cleanup.
    runCatching {
        withContext(Dispatchers.IO) {
            DebugArtifactStore.persistHtmlArtifacts(debugWorkspaceRoot(context), turn.sessionId, turn.tools)
        }
    }
    return DebugRuntimeFinalization(
        replay = replay,
        contextPanel = if (ownsUi) withContext(Dispatchers.IO) { loadContextPanel(context) } else null,
    )
}
