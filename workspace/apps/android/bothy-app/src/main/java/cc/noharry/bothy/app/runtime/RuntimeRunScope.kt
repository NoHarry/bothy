@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.runtime
import android.content.Context
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRuntime
import cc.noharry.agent.runtime.NetworkToolConfirmationPolicy
import cc.noharry.bothy.app.a2a.A2aBridgeProvider
import cc.noharry.bothy.app.console.ArtifactStore
import cc.noharry.bothy.app.console.loadContextPanel
import cc.noharry.bothy.app.console.loadRuntimeMemoryEvidence
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.evidence.AppDiagnostics
import cc.noharry.bothy.app.evidence.DiagnosticEntry
import cc.noharry.bothy.app.evidence.RunEvidenceRecorder
import cc.noharry.bothy.app.evidence.RunReplayResult
import cc.noharry.bothy.app.evidence.RunReplayVerifier
import cc.noharry.bothy.app.evidence.RuntimeMemoryEvidence
import cc.noharry.bothy.app.evidence.appendRunEvidence
import cc.noharry.bothy.app.evidence.appDiagnostics
import cc.noharry.bothy.app.mcp.McpProgressiveEvidenceRecorder
import cc.noharry.bothy.app.mcp.McpProviderState
import cc.noharry.bothy.app.model.ProviderConfig
import cc.noharry.bothy.app.model.ProviderProfile
import cc.noharry.bothy.app.session.ConfirmationMode
import cc.noharry.bothy.app.session.ContextPanelState
import cc.noharry.bothy.app.session.SessionCoordinator
import cc.noharry.bothy.app.session.SessionTurnRecorder
import cc.noharry.bothy.app.session.TranscriptStore
import cc.noharry.bothy.app.skills.BothySubagents
import cc.noharry.bothy.app.skills.MAX_CHILD_LOOKUPS
import cc.noharry.bothy.app.skills.SubagentGuard
import cc.noharry.bothy.app.skills.appSubagentKind
import cc.noharry.bothy.app.trajectory.appSessionEventLogRoot
import cc.noharry.bothy.app.usage.TokenDelta
import cc.noharry.bothy.app.voice.tts.displayReplyText
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class RuntimeRunScope(
    val requestId: String,
    val sessionId: String,
) {
    fun ownsUi(activeRequestId: String?, activeSessionId: String?, selectedSessionId: String): Boolean =
        activeRequestId == requestId && activeSessionId == sessionId && selectedSessionId == sessionId
}

internal data class RuntimeFinalization(
    val replay: RunReplayResult,
    val contextPanel: ContextPanelState?,
)

internal data class RuntimeRunRecorders(
    val evidenceRecorder: McpProgressiveEvidenceRecorder?,
    val runEvidenceRecorder: RunEvidenceRecorder,
    val turnRecorder: SessionTurnRecorder,
)

internal fun scopedDiagnostics(
    context: Context,
    runScope: RuntimeRunScope,
    ownsUi: (RuntimeRunScope) -> Boolean,
    onEntry: (DiagnosticEntry) -> Unit,
    onTokenUsage: (TokenDelta) -> Unit,
): AppDiagnostics =
    appDiagnostics(
        context = context,
        requestId = runScope.requestId,
        onEntry = { entry -> if (ownsUi(runScope)) onEntry(entry) },
        onTokenUsage = { delta -> if (ownsUi(runScope)) onTokenUsage(delta) },
    )

internal fun recordRawToolEventEntry(
    title: String,
    body: String,
    evidenceRecorder: McpProgressiveEvidenceRecorder?,
    runEvidenceRecorder: RunEvidenceRecorder,
    turnRecorder: SessionTurnRecorder,
    ownsUi: Boolean,
    appendRawToolEvent: (String, String) -> Unit,
) {
    evidenceRecorder?.recordRawToolEvent(title, body)
    runEvidenceRecorder.recordRawToolEvent(title, body)
    turnRecorder.recordRawToolEvent(title, body)
    if (ownsUi) appendRawToolEvent(title, body)
}

internal fun buildScopedRuntime(
    context: Context,
    config: ProviderConfig,
    profile: ProviderProfile,
    streamEnabled: Boolean,
    useSimulatedModel: Boolean,
    workspaceRoot: File,
    sessionContext: String,
    diagnostics: AppDiagnostics,
    runScope: RuntimeRunScope,
    recorders: RuntimeRunRecorders,
    mcpProviders: List<McpProviderState>,
    a2aBridgeProviders: List<A2aBridgeProvider>,
    confirmationMode: () -> ConfirmationMode = { ConfirmationMode.ASK_EVERY_TIME },
    /** Same text as AgentRequest.input — hashed into evidence when [benchCaseBound] is true. */
    userInput: String = "",
    /**
     * Production case-bound flag. Default false. Session runner sets true only for the
     * exact sample bench prompt so [buildRuntime] calls [runtimeEvidence].
     */
    benchCaseBound: Boolean = false,
    previousExchange: PreviousExchange? = null,
): AgentRuntime {
    // A child of this conversation is built like this run's runtime: same connection and tools, its own log, no
    // conversation history or recap, the guard, and the model the settings or the spawning run chose.
    BothySubagents.rememberChildRuntime(runScope.sessionId) { spec ->
        val kind = spec.agentType?.let { appSubagentKind(context, it) }
        buildRuntime(
            context, config, profile.forSubagent(config, spec.modelRef), streamEnabled, useSimulatedModel, workspaceRoot,
            appSessionEventLogRoot(context, spec.sessionId),
            sessionContext = "",
            evidenceRecorder = null,
            diagnostics = appDiagnostics(context, "${spec.sessionId}-run", onEntry = {}, onTokenUsage = {}),
            mcpProviders = mcpProviders,
            onRawToolTranscript = { _, _ -> },
            networkConfirmationPolicy = confirmationMode().toNetworkPolicy(),
            userInput = spec.task,
            hostHook = SubagentGuard(spec.allowDeviceControl, kind?.maxLookups ?: MAX_CHILD_LOOKUPS, kind?.tools, kind?.id.orEmpty()),
            subagentChild = true,
            subagentKind = kind,
        )
    }
    return buildRuntime(
        context, config, profile, streamEnabled, useSimulatedModel, workspaceRoot,
        appSessionEventLogRoot(context, runScope.sessionId),
        sessionContext,
        recorders.evidenceRecorder,
        diagnostics,
        mcpProviders,
        a2aBridgeProviders,
        { title, body ->
            ExecutionEvents.publish(runScope.requestId, ExecutionMessage.RawTool(title, body))
        },
        networkConfirmationPolicy = confirmationMode().toNetworkPolicy(),
        userInput = userInput,
        benchCaseBound = benchCaseBound,
        hostHook = ExecutionToolHook(),
        previousExchange = previousExchange,
        sessionId = runScope.sessionId,
    )
}

/** The profile a child runs on: the parent's, with the model the subagent settings chose (same provider only). */
private fun ProviderProfile.forSubagent(config: ProviderConfig, ref: cc.noharry.agent.kernel.api.model.ModelRef?): ProviderProfile {
    if (ref == null || (ref.providerId == provider.wireName && ref.modelId == model)) return this
    val base = if (ref.providerId == provider.wireName) this else config.profiles.firstOrNull { it.provider.wireName == ref.providerId } ?: return this
    return base.copy(model = ref.modelId, variant = ref.variant.orEmpty())
}

private fun ConfirmationMode.toNetworkPolicy(): NetworkToolConfirmationPolicy =
    if (this == ConfirmationMode.ALLOW_ALL_SESSION) {
        NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS
    } else {
        NetworkToolConfirmationPolicy.REQUIRE_PER_HOST
    }

internal fun scopedEventHandler(
    runScope: RuntimeRunScope,
    ownsUi: (RuntimeRunScope) -> Boolean,
    transcript: TranscriptStore,
    setStatus: (String) -> Unit,
    notifyChanged: () -> Unit,
    confirmationMode: () -> ConfirmationMode = { ConfirmationMode.ASK_EVERY_TIME },
    confirmationDomain: (String) -> String = { "" },
    confirmedDomains: () -> Set<String> = { emptySet() },
): (AgentEvent) -> Unit = { event ->
    handleScopedAgentEvent(
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

internal fun handleScopedAgentEvent(
    event: AgentEvent,
    ownsUi: Boolean,
    transcript: TranscriptStore,
    setStatus: (String) -> Unit,
    notifyChanged: () -> Unit,
    confirmationMode: () -> ConfirmationMode = { ConfirmationMode.ASK_EVERY_TIME },
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
    // Token usage is added to the run's record once, by RuntimeSessionRunner.applyTokenUsage, for every run
    // whether or not it owns the screen; adding it here as well counted each request twice on the Board.
    when (event) {
        is AgentEvent.NativeToolCallStarted -> transcript.recordPublicToolCallStarted(
            toolName = event.toolName,
            toolCallId = event.toolCallId,
            arguments = event.arguments,
        )
        // update_todo board updates are event-sourced in RuntimeSessionRunner.applyTodoBoard;
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
    handleAgentEvent(
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

internal suspend fun finalizeRuntimeRun(
    context: Context,
    runtime: AgentRuntime,
    transcript: TranscriptStore,
    sessionCoordinator: SessionCoordinator,
    evidenceRecorder: McpProgressiveEvidenceRecorder?,
    runEvidenceRecorder: RunEvidenceRecorder,
    turnRecorder: SessionTurnRecorder,
    ownsUi: Boolean,
): RuntimeFinalization {
    if (ownsUi) transcript.markPendingToolCallsUnknown()
    evidenceRecorder?.let { recorder ->
        withContext(Dispatchers.IO) {
            recorder.drainMcpDebugTrace(runtime)
            // Write MCP evidence to disk only — do not dump paths into the chat stream.
            runCatching { recorder.write() }
        }
    }
    val memoryEvidence = withContext(Dispatchers.IO) {
        runCatching { loadRuntimeMemoryEvidence(context) }.getOrDefault(RuntimeMemoryEvidence())
    }
    runEvidenceRecorder.recordMemoryEvidence(memoryEvidence)
    val replay = if (ownsUi) {
        // Always write evidence; only surface a timeline row when replay fails.
        transcript.appendRunEvidence(runEvidenceRecorder, surfaceOnTimeline = false)
    } else {
        withContext(Dispatchers.IO) {
            RunReplayVerifier.verify(runEvidenceRecorder.snapshot()).also {
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
            ArtifactStore.persistHtmlArtifacts(appWorkspaceRoot(context), turn.sessionId, turn.tools)
        }
    }
    return RuntimeFinalization(
        replay = replay,
        contextPanel = if (ownsUi) withContext(Dispatchers.IO) { loadContextPanel(context) } else null,
    )
}
