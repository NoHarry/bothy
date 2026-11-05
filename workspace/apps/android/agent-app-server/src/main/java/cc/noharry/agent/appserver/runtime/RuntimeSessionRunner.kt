@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime
import android.content.Context
import android.content.res.Resources
import cc.noharry.agent.appserver.InternalAppServerApi
import cc.noharry.agent.appserver.R
import cc.noharry.agent.appserver.a2a.*
import cc.noharry.agent.appserver.console.*
import cc.noharry.agent.appserver.console.ui.UiObserverDispatcher
import cc.noharry.agent.appserver.evidence.*
import cc.noharry.agent.appserver.model.*
import cc.noharry.agent.appserver.protocol.*
import cc.noharry.agent.appserver.session.*
import cc.noharry.agent.appserver.trajectory.TrajectoryRepository
import cc.noharry.agent.appserver.usage.*
import cc.noharry.agent.kernel.api.*
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*

@InternalAppServerApi
class RuntimeSessionRunner(
    private val scope: CoroutineScope,
    private val transcript: TranscriptStore,
    private val sessionCoordinator: SessionCoordinator,
    private val currentConfig: () -> ProviderConfig?,
    private val loadConfig: (Context) -> ProviderConfig,
    private val setProvider: (ModelProvider) -> Unit,
    private val setStatus: (String) -> Unit,
    private val streamEnabled: () -> Boolean,
    private val useSimulatedModel: () -> Boolean,
    private val mcpProviders: () -> List<McpProviderState>,
    private val a2aBridgeProviders: () -> List<A2aBridgeProvider>,
    private val streamLabel: () -> String,
    private val backendLabel: () -> String,
    private val confirmationMode: () -> ConfirmationMode,
    private val notifyChanged: () -> Unit,
    private val trajectoryRepository: () -> TrajectoryRepository? = { null },
    maxActiveSessions: Int = DEBUG_MAX_ACTIVE_SESSION_RUNS,
) {
    private val uiObserver = UiObserverDispatcher(scope, notifyChanged)
    private var announcer: RunAnnouncer? = null
    private val externalRuns = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val registry = ActiveRunRegistry(maxActiveSessions)
    private val sessionUsage by lazy { SessionUsage(sessionCoordinator, scope) }
    private val admissionMutex = Mutex()
    @Volatile private var draining = false
    private val finishedBySession = ConcurrentHashMap<String, ActiveRunSnapshot>()
    private val domains = ConfirmationDomains()
    var tokenUsage: TokenUsageState = TokenUsageState()
        private set
    var currentRequest: CurrentRequest? = null
        private set
    var contextPanelState: ContextPanelState = ContextPanelState(false, emptyList(), emptyList(), emptyList())
        private set
    var runtimeDemo: RuntimeDemoState = RuntimeDemoState()
        private set
    var runStartedAtEpochMillis: Long? = null
        private set
    var lastRunSummary: String? = null
        private set
    val isRunning: Boolean get() = selectedRecord()?.isJobActive == true
    internal val maxActiveSessions: Int get() = registry.maxActiveSessions
    val activeRunCount: Int get() = registry.size

    /** A turn answered outside a run (陪聊, a 计划 reminder) is the card's quoted request, not the last run's. */
    internal fun showOutsideRequest(id: String, text: String) { currentRequest = CurrentRequest(id, text) }

    fun resetForSession(context: Context) {
        tokenUsage = TokenUsageState()
        currentRequest = null
        runtimeDemo = RuntimeDemoState()
        runStartedAtEpochMillis = null
        lastRunSummary = null
        domains.forget(sessionCoordinator.activeSessionId)
        // Project synchronously first: a new session must never flash the previous session's
        // plan while the async lessons/skills reload is still in flight.
        projectSelectedSessionState()
        refreshContextPanel(context)
    }

    internal fun clearRequestAndRefresh(context: Context) = projectSelectedSession(context)

    internal fun projectSelectedSession(context: Context) {
        projectSelectedSessionState()
        refreshContextPanel(context)
    }

    fun projectSelectedSessionState() {
        val sessionId = sessionCoordinator.activeSessionId
        val live = registry.get(sessionId)
        val finished = finishedBySession[sessionId]
        tokenUsage = boardUsage(sessionId)
        currentRequest = live?.currentRequest
        runtimeDemo = live?.runtimeDemo ?: RuntimeDemoState()
        runStartedAtEpochMillis = live?.takeIf { it.isJobActive }?.startedAtEpochMillis
        lastRunSummary = live?.lastRunSummary ?: finished?.lastRunSummary
        // Todo is Context-free event state, so project it synchronously on switch: waiting for
        // the async panel reload would leave the previous session's plan on screen.
        contextPanelState = contextPanelState.copy(todo = eventTodoBoard(sessionId))
    }

    fun stopRun(sessionId: String = sessionCoordinator.activeSessionId): Job = scope.launch { withAdmission { stopSessionRunUnlocked(sessionId) } }

    internal fun stopSession(sessionId: String): Job = scope.launch { withAdmission { stopSessionRunUnlocked(sessionId) } }

    internal fun cancelRun(sessionId: String, runId: String): Job = scope.launch {
        withAdmission { if (registry.getExact(sessionId, runId) != null) stopSessionRunUnlocked(sessionId) }
    }

    internal fun stopAllRuns(): Job = scope.launch { withAdmission { drainAllRunsUnlocked() } }

    /**
     * Drains every run and keeps admission closed while [afterDrain] runs, so Clear runtime
     * data can delete EventLog roots without a newly admitted run writing back into them.
     */
    internal fun stopAllRunsThen(afterDrain: suspend (Boolean) -> Unit): Job =
        scope.launch { withAdmission { drainAllRunsUnlocked(afterDrain) } }

    internal fun discardSession(sessionId: String) {
        sessionCoordinator.markSessionDeleted(sessionId)
        finishedBySession.remove(sessionId)
        val record = registry.discard(sessionId) ?: return
        record.suppressPersist()
        record.job?.cancel()
        record.resources.close()
    }

    /**
     * Cancel+join the exact active run, suppress late persist, then drop registry/finished
     * snapshots. Callers delete store/query files only after this returns.
     *
     * Returns false when the writer did not confirm cancellation within the bounded join, in which
     * case the caller must not delete storage: an unresponsive writer can still append or recreate
     * files after the delete.
     */
    internal suspend fun deleteActiveSession(sessionId: String): Boolean = withAdmission {
        sessionCoordinator.markSessionDeleted(sessionId)
        val record = registry.get(sessionId)
        record?.suppressPersist()
        val stopped = stopSessionRunUnlocked(sessionId, publishStopped = false)
        finishedBySession.remove(sessionId)
        stopped
    }

    fun activeRunSnapshots(): List<ActiveRunSnapshot> {
        val live = registry.snapshots()
        val liveIds = live.map { it.sessionId }.toSet()
        val finished = finishedBySession.values.filter { it.sessionId !in liveIds }
        return live + finished
    }

    internal fun decorateSessions(sessions: List<SessionSummary>): List<SessionSummary> {
        val byId = activeRunSnapshots().associateBy { it.sessionId }
        return sessions.map { it.withRunOverlay(byId[it.sessionId]) }
    }

    /** Appends to the active run's next model request; with [notLanded] (voice), text that cannot land goes there. */
    internal fun postSteeringText(rawText: String, notLanded: ((String) -> Unit)? = null, title: String = "Runtime steering input", shown: String = rawText) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        val record = selectedRecord()
        val runtime = record?.runtime
        val runId = record?.runId
        val sessionId = record?.sessionId
        if (runtime == null || runId == null || sessionId == null || !record.isJobActive) {
            if (notLanded != null) return notLanded(text)
            setStatus("No active run")
            transcript.appendStatus("Steering ignored: no active runtime run.")
            notifyChanged()
            return
        }
        val requestId = RequestIds.next("steer")
        scope.launch {
            val result = postRuntimeSteering(runtime, runId, requestId, text, title, shown.trim())
            if (result.runEnded && notLanded != null) return@launch notLanded(text)
            if (!stillOwnsSelected(sessionId, runId)) return@launch
            handleSteeringPostResult(result)
        }
    }

    /** Shared control input for UI and transports; never starts or replaces an execution. */
    internal suspend fun postInput(sessionId: String, runId: String, requestId: String, text: String, questionId: String?): cc.noharry.agent.kernel.api.PostUserInputResult =
        postRunInput(registry, sessionId, runId, requestId, text, questionId)

    /** Answers into the exact run; a reply to an ended run becomes a new selected-session turn via [lateAnswer]. */
    internal fun answerQuestion(question: UserQuestion, text: String, resources: Resources, lateAnswer: (String) -> Unit = {}) {
        if (text.isBlank()) return
        scope.launch {
            val result = try {
                postInput(question.sessionId, question.runId, RequestIds.next("steer"), text, question.id)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                cc.noharry.agent.kernel.api.PostUserInputResult.Rejected("INPUT_RECORD_FAILED")
            }
            val route = answerRoute(result, isSelected(question.sessionId), isRunning, stillOwnsSelected(question.sessionId, question.runId))
            if (route is AnswerRoute.Failed) {
                val busy = route.reason == SESSION_BUSY_REASON
                val reason = if (busy) resources.getString(R.string.runtime_answer_session_busy) else route.reason
                transcript.appendError(resources.getString(R.string.runtime_answer_not_sent), reason)
            }
            if (route == AnswerRoute.Late) lateAnswer(lateAnswerText(question, text)) else notifyChanged()
        }
    }

    internal fun resolveConfirmation(pendingId: String, approve: Boolean) {
        val id = pendingId.trim()
        if (id.isEmpty()) return
        val active = sessionCoordinator.activeSessionId
        val record = registry.get(sessionCoordinator.transcripts.sessionWaitingFor(id, active) ?: active)
        val runtime = record?.runtime
        val runId = record?.runId
        val sessionId = record?.sessionId
        if (runtime == null || runId == null || sessionId == null || !record.isJobActive) {
            setStatus("No active run")
            transcript.appendStatus("Confirmation ignored: no active runtime run.")
            notifyChanged()
            return
        }
        scope.launch {
            val result = postRuntimeConfirmation(runtime, id, RequestIds.next("confirm"), approve, runId)
            if (registry.getExact(sessionId, runId) == null) return@launch
            recordConfirmationResolution(sessionId, id, approve, result, automatic = false)
            handleConfirmationPostResult(sessionId, result)
            if (result.terminal) {
                finalizeConfirmedTerminalRun(sessionId, runId, runtime, result)
            } else {
                recoverWaitingConfirmationAfterResume(sessionId, runId, runtime)
            }
        }
    }

    internal fun refreshContextPanel(context: Context) {
        val sessionId = sessionCoordinator.activeSessionId
        scope.launch {
            publishReloadedPanel(sessionId) { loadContextPanel(context.applicationContext) }
        }
    }

    /**
     * Shared reload publish path; also the test seam for out-of-order completion.
     *
     * Reloads are async and can land after the user switched sessions, so the selection is
     * re-checked after the suspend point and only the currently selected session may publish.
     */
    suspend fun publishReloadedPanel(
        sessionId: String,
        loadPanel: suspend () -> ContextPanelState,
    ) {
        val panel = loadPanel()
        if (!isSelected(sessionId)) return
        publishContextPanel(sessionId, panel)
        notifyChanged()
    }

    /** Publishes lessons/skills from a reload while todo stays owned by the event projection. */
    private fun publishContextPanel(sessionId: String, panel: ContextPanelState) {
        contextPanelState = panel.copy(todo = eventTodoBoard(sessionId))
    }

    /** See [resolveEventTodoBoard] for the live-versus-finished ownership rule. */
    internal fun eventTodoBoard(sessionId: String): List<TodoEntry> =
        resolveEventTodoBoard(registry.get(sessionId), finishedBySession[sessionId])

    /** [modelText] replaces [text] in the model request only; the transcript, overlay and stored turn keep [text]. */
    fun start(context: Context, text: String, intake: RunIntake? = null, modelText: String? = null): Job = scope.launch {
        val sessionId = intake?.sessionId ?: sessionCoordinator.activeSessionId
        withAdmission {
            sessionCoordinator.forgetDeletedSession(sessionId)
            val admission = admitOrBusy(sessionId) ?: run {
                intake?.let { failExternalIntake(it.requestId, "Execution service is busy") }
                return@withAdmission
            }
            stopSessionRunUnlocked(sessionId, publishStopped = false)
            val activeConfig = currentConfig() ?: loadConfig(context)
            val profile = activeConfig.activeProfile
            if (!prepareRunConfiguration(context, activeConfig, profile, sessionId, intake)) return@withAdmission
            val workspaceRoot = appWorkspaceRoot(context)
            val store = sessionCoordinator.ensure(context)
            val (modelInput, previousExchange) = withContext(Dispatchers.IO) { store.prepareRunInput(sessionId, modelText ?: text) }
            val request = agentRequest(
                intake?.requestId ?: RequestIds.next("debug"), sessionId, modelInput, activeConfig, profile,
            )
            val runScope = RuntimeRunScope(request.requestId, request.sessionId)
            if (intake?.external == true) externalRuns.add(request.requestId)
            if (isSelected(sessionId)) prepareRunUiState(runScope, request, text) else sessionCoordinator.loadTranscript(sessionId).openTurn(text)
            val diagnostics = diagnosticsForRun(context, runScope)
            diagnostics.recordSessionStart(profile.provider, streamLabel(), activeConfig.summary(profile))
            if (isSelected(sessionId)) transcript.appendRuntime("Runtime intake", runtimeIntakeSummary(request, profile.provider, streamLabel(), workspaceRoot))
            val recorders = withContext(Dispatchers.IO) {
                createRunRecorders(context, request, text, profile, useSimulatedModel() && intake?.external != true, streamLabel(), backendLabel())
            }
            val runtime = constructRuntime(
                runScope, recorders, sessionCoordinator, runStartedAtEpochMillis,
                onFailure = { failure ->
                    intake?.let { failExternalIntake(it.requestId, "Runtime initialization failed") }
                    externalRuns.remove(request.requestId)
                    publishStartupFailure(failure)
                },
                onCancelled = { if (isSelected(sessionId)) runStartedAtEpochMillis = null },
            ) {
                buildRunRuntime(context, activeConfig, profile, workspaceRoot, store, diagnostics, runScope, recorders, modelInput, previousExchange)
            } ?: return@withAdmission
            installPreparedRun(
                sessionId = sessionId,
                request = request,
                runtime = runtime,
                resources = ActiveRunResources(closeRuntime = runtime::close, closeMemorySession = {}),
                recorders = recorders,
                context = context.applicationContext,
                text = text,
                admission = admission,
            )
        }
    }

    private fun prepareRunConfiguration(
        context: Context,
        config: ProviderConfig,
        profile: ProviderProfile,
        sessionId: String,
        intake: RunIntake?,
    ): Boolean {
        if ((intake?.external == true || !useSimulatedModel()) && !config.isReady(profile)) {
            intake?.let { failExternalIntake(it.requestId, "Provider configuration incomplete") }
            if (isSelected(sessionId)) {
                setStatus("Provider configuration incomplete")
                transcript.appendMessage(TranscriptRole.ERROR, config.missingMessage(context, profile))
                notifyChanged()
            }
            return false
        }
        if (isSelected(sessionId)) {
            setProvider(profile.provider)
            setStatus("Running ${profile.displayLabel} ${streamLabel()}")
        }
        return true
    }

    private fun publishStartupFailure(failure: RuntimeStartupFailure) {
        val snapshot = failure.snapshot
        finishedBySession[snapshot.sessionId] = snapshot
        if (isSelected(snapshot.sessionId)) {
            runStartedAtEpochMillis = null
            lastRunSummary = snapshot.lastRunSummary
            setStatus(snapshot.lastRunSummary ?: "Failed")
            transcript.appendError("Runtime initialization", failure.message)
        }
        notifyChanged()
    }

    /** Test seam. Production [start] still builds a real runtime; do not start a second protocol. */
    suspend fun startPreparedRun(
        sessionId: String,
        request: AgentRequest,
        runtime: AgentRuntime,
        resources: ActiveRunResources,
        recorders: RuntimeRunRecorders,
        context: Context? = null,
        text: String = request.input,
        reviveDeleted: Boolean = false,
    ): StartRunAdmission = withAdmission {
        if (reviveDeleted) sessionCoordinator.forgetDeletedSession(sessionId)
        val admission = admitOrBusy(sessionId)
        if (admission == null) {
            resources.close()
            publishBusy(sessionId)
            return@withAdmission StartRunAdmission.BUSY_MAX_ACTIVE_SESSIONS
        }
        stopSessionRunUnlocked(sessionId, publishStopped = false)
        val runScope = RuntimeRunScope(request.requestId, sessionId)
        if (isSelected(sessionId)) prepareRunUiState(runScope, request, text) else sessionCoordinator.loadTranscript(sessionId).openTurn(text)
        installPreparedRun(
            sessionId = sessionId,
            request = request,
            runtime = runtime,
            resources = resources,
            recorders = recorders,
            context = context,
            text = text,
            admission = admission,
        )
        admission
    }

    private fun installPreparedRun(
        sessionId: String,
        request: AgentRequest,
        runtime: AgentRuntime,
        resources: ActiveRunResources,
        recorders: RuntimeRunRecorders,
        context: Context?,
        text: String,
        admission: StartRunAdmission,
    ) {
        val runScope = RuntimeRunScope(request.requestId, sessionId)
        val boundResources = bindTrajectorySubscription(
            repository = trajectoryRepository(),
            runtime = runtime,
            sessionId = sessionId,
            resources = resources,
        )
        val record = ActiveRunRecord(
            sessionId = sessionId,
            runId = request.requestId,
            requestId = request.requestId,
            runtime = runtime,
            resources = boundResources,
            context = context,
            startedAtEpochMillis = System.currentTimeMillis(),
            recorders = recorders,
            // Per run; the Board adds the session's stored turns (boardUsage).
            tokenUsage = TokenUsageState(),
            currentRequest = if (isSelected(sessionId)) currentRequest else request.toCurrentRequest(text),
            runtimeDemo = if (isSelected(sessionId)) runtimeDemo else RuntimeDemoState(),
            request = request,
        )
        registry.put(record)
        record.job = launchObservedRuntime(record, runScope)
        if (admission == StartRunAdmission.REPLACED_SAME_SESSION) {
            finishedBySession.remove(sessionId)
        }
        notifyChanged()
    }

    private fun publishBusy(sessionId: String) {
        if (!isSelected(sessionId)) {
            notifyChanged()
            return
        }
        setStatus(busyActiveSessionStatus(registry.maxActiveSessions))
        transcript.appendStatus(busyActiveSessionMessage(registry.maxActiveSessions))
        notifyChanged()
    }

    private fun buildRunRuntime(
        context: Context,
        activeConfig: ProviderConfig,
        profile: ProviderProfile,
        workspaceRoot: java.io.File,
        store: SessionStore,
        diagnostics: AppDiagnostics,
        runScope: RuntimeRunScope,
        recorders: RuntimeRunRecorders,
        modelInput: String, previousExchange: PreviousExchange?,
    ) = buildScopedRuntime(
        context, activeConfig, profile, streamEnabled(), useSimulatedModel() && runScope.requestId !in externalRuns, workspaceRoot,
        store.modelContext(runScope.sessionId), diagnostics, runScope, recorders, mcpProviders(), a2aBridgeProviders(),
        confirmationMode = { if (runScope.requestId in externalRuns) ConfirmationMode.ASK_EVERY_TIME else confirmationMode() },
        userInput = modelInput,
        benchCaseBound = productionBenchCaseBoundDecision(modelInput),
        previousExchange = previousExchange,
    )

    private fun prepareRunUiState(runScope: RuntimeRunScope, request: AgentRequest, text: String) {
        activateSelectedRun(runScope)
        tokenUsage = boardUsage(runScope.sessionId)
        currentRequest = request.toCurrentRequest(text)
        contextPanelState = contextPanelState.copy(todo = emptyList())
        runtimeDemo = RuntimeDemoState()
        runStartedAtEpochMillis = System.currentTimeMillis()
        lastRunSummary = null
    }

    /** Closes timing once; returns a new summary only the first time for this run. */
    private fun finishRunTiming(record: ActiveRunRecord, outcome: RunOutcome): String? {
        if (record.timingClosed) return null
        record.timingClosed = true
        val summary = formatRunSummary(System.currentTimeMillis() - record.startedAtEpochMillis, outcome)
        record.lastRunSummary = summary
        if (isSelected(record.sessionId) && selectedRecord()?.runId == record.runId) {
            runStartedAtEpochMillis = null
            lastRunSummary = summary
        }
        return summary
    }

    private fun publishRunSummary(
        record: ActiveRunRecord,
        outcome: RunOutcome,
        fallbackStatus: String? = null,
    ) {
        val selectedThisRun = isSelected(record.sessionId) && selectedRecord()?.runId == record.runId
        val summary = finishRunTiming(record, outcome)
        if (!selectedThisRun) return
        when {
            summary != null -> {
                setStatus(summary)
                transcript.appendStatus(summary)
            }
            fallbackStatus != null -> setStatus(lastRunSummary ?: fallbackStatus)
        }
    }

    private fun diagnosticsForRun(context: Context, runScope: RuntimeRunScope): AppDiagnostics =
        scopedDiagnostics(
            context,
            runScope,
            ::ownsActiveUi,
            { entry -> uiObserver.dispatch { transcriptOf(runScope)?.let { appendDiagnostics(it, entry) } } },
            // Token board is fed only from AgentEvent.TokenUsageDelta. Diagnostics
            // still parse usage for logs, but must not double-count the same request.
            onTokenUsage = {},
        )

    private fun launchObservedRuntime(
        record: ActiveRunRecord,
        runScope: RuntimeRunScope,
    ): Job {
        val context = record.context
        val handleRuntimeEvent = if (context != null) runtimeEventHandler(runScope) else null
        return scope.launchRuntime(
            record.runtime, record.request, record.recorders.evidenceRecorder,
            record.recorders.runEvidenceRecorder, record.recorders.turnRecorder,
            onEvent = { event ->
                if (event is AgentEvent.TokenUsageDelta) {
                    applyTokenUsage(runScope, event.toUsageDelta())
                }
                event.todoBoardUpdate { registry.getExact(runScope.sessionId, runScope.requestId)?.todoBoard }?.let { applyTodoBoard(runScope, it) }
                if (context != null && runScope.requestId !in externalRuns) {
                    RuntimeVoice.port.speak(event, runScope.requestId, { ownsActiveUi(runScope) }) {
                        announcer(context)?.observe(runScope, event, ::ownsActiveUi)
                    }
                }
                uiObserver.dispatch {
                    val current = registry.getExact(runScope.sessionId, runScope.requestId)
                    if (current != null) domains.track(runScope.sessionId, event)
                    if (current != null && ownsActiveUi(runScope)) {
                        runtimeDemo = runtimeDemo.reduce(event)
                        current.runtimeDemo = runtimeDemo
                    } else {
                        current?.let { it.runtimeDemo = it.runtimeDemo.reduce(event) }
                    }
                    handleRuntimeEvent?.invoke(event)
                    if (runScope.requestId !in externalRuns) maybeAutoResolveConfirmation(event, runScope)
                }
            },
            onStopped = {
                uiObserver.dispatchNotifying {
                    val current = registry.getExact(runScope.sessionId, runScope.requestId) ?: return@dispatchNotifying
                    if (ownsActiveUi(runScope)) {
                        publishRunSummary(current, RunOutcome.STOPPED, fallbackStatus = "Stopped")
                    } else {
                        finishRunTiming(current, RunOutcome.STOPPED)
                    }
                }
            },
            onFailure = { message ->
                uiObserver.dispatchNotifying { handleRunFailure(runScope, message) }
            },
            onFinally = { runRecorder, recorder ->
                val nextRecorders = record.recorders.copy(runEvidenceRecorder = runRecorder, turnRecorder = recorder)
                val current = registry.getExact(runScope.sessionId, runScope.requestId)
                if (current != null) current.recorders = nextRecorders
                if (record.runtime.isWaitingForConfirmation(runScope.requestId)) return@launchRuntime
                finalizeRun(
                    runtime = record.runtime,
                    recorders = nextRecorders,
                    runScope = runScope,
                            resources = record.resources,
                    context = record.context,
                )
            },
            onCompleted = { job ->
                externalRuns.remove(runScope.requestId)
                clearCompletedJob(runScope, job)
            },
            onRawTool = { title, body ->
                uiObserver.dispatch {
                    transcriptOf(runScope)?.let {
                        it.appendRawToolEvent(title, body)
                        uiObserver.requestNotify()
                    }
                }
            },
        )
    }

    /** Speaks [text] before anything the run has queued (a voice append's acknowledgement); false when TTS is off. */
    fun announceFirst(context: Context, text: String): Boolean = announcer(context)?.announceFirst(text) ?: false

    /** Whether the assistant's own announcer is speaking or has something queued. */
    val speaking: Boolean get() = announcer?.busy == true

    /** Speaks a reply that came from outside a run (陪聊), as a run's final answer would be; false when TTS is off. */
    internal fun announceReply(context: Context, text: String): Boolean = announcer(context)?.announce(text) ?: false

    private fun announcer(context: Context): RunAnnouncer? = announcer
        ?: RuntimeVoice.port.deviceAnnouncer(context, scope) { message -> uiObserver.dispatchNotifying { transcript.appendStatus(message) } }
            ?.also { announcer = it }

    private fun runtimeEventHandler(runScope: RuntimeRunScope): (AgentEvent) -> Unit =
        scopedEventHandler(
            runScope, ::ownsActiveUi, ::transcriptOf,
            setStatus, uiObserver::requestNotify,
            confirmationMode = confirmationMode,
            confirmationDomain = domains::of,
            confirmedDomains = { domains.confirmed(runScope.sessionId) },
        )

    private fun activateSelectedRun(runScope: RuntimeRunScope) = domains.runShown(runScope.sessionId, registry.get(runScope.sessionId)?.runId)

    private fun handleRunFailure(runScope: RuntimeRunScope, message: String?) {
        val record = registry.getExact(runScope.sessionId, runScope.requestId) ?: return
        record.lifecycleStatus = RuntimeRunStatus.FAILED.name
        if (!ownsActiveUi(runScope)) {
            transcriptOf(runScope)?.appendError("Runtime", message ?: "Runtime failed.")
            finishRunTiming(record, RunOutcome.FAILED)
            return
        }
        publishRunSummary(record, RunOutcome.FAILED, fallbackStatus = "Failed")
        transcript.appendError("Runtime", message ?: "Runtime failed.")
    }

    private fun clearCompletedJob(runScope: RuntimeRunScope, job: Job) {
        val record = registry.getExact(runScope.sessionId, runScope.requestId) ?: return
        if (record.job === job) {
            record.job = null
            if (isSelected(runScope.sessionId)) notifyChanged()
        }
    }

    private suspend fun finalizeRun(
        runtime: AgentRuntime,
        recorders: RuntimeRunRecorders,
        runScope: RuntimeRunScope,
        resources: ActiveRunResources,
        context: Context?,
    ) {
        val record = registry.getExact(runScope.sessionId, runScope.requestId) ?: recordMatching(runScope)
        if (record != null && !record.tryClaimFinalization()) return
        val persist = record?.isPersistSuppressed() != true &&
            !sessionCoordinator.isSessionDeleted(runScope.sessionId)
        // Capture before registry removal — ownsActiveUi becomes false once the exact run is gone.
        val ownsUi = persist && ownsActiveUi(runScope)
        if (persist && !ownsUi) transcriptOf(runScope)?.markPendingToolCallsUnknown()
        val result = if (!persist) {
            RuntimeFinalization(
                replay = RunReplayResult(
                    passed = true,
                    summary = "",
                    failures = emptyList(),
                    warnings = emptyList(),
                ),
                contextPanel = null,
            )
        } else if (context != null) {
            finalizeRuntimeRun(
                context = context,
                runtime = runtime,
                transcript = transcript,
                sessionCoordinator = sessionCoordinator,
                evidenceRecorder = recorders.evidenceRecorder,
                runEvidenceRecorder = recorders.runEvidenceRecorder,
                turnRecorder = recorders.turnRecorder,
                ownsUi = ownsUi,
            )
        } else {
            recorders.turnRecorder.snapshot().let { turn ->
                val logHealth = withContext(Dispatchers.IO) {
                    runCatching { runtime.getSessionEventLogHealth(turn.sessionId, turn.runId).status.name }.getOrNull()
                }
                persistSessionTurn(turn, logHealth)
            }
            RuntimeFinalization(
                replay = RunReplayResult(
                    passed = true,
                    summary = "",
                    failures = emptyList(),
                    warnings = emptyList(),
                ),
                contextPanel = null,
            )
        }
        val logHealth = withContext(Dispatchers.IO) {
            runCatching { runtime.getSessionEventLogHealth(runScope.sessionId, runScope.requestId).status.name }
                .getOrDefault(SessionEventLogHealthStatus.UNKNOWN.name)
        }
        if (record != null) {
            val runtimeResult = runCatching { runtime.getRuntimeResult(runScope.requestId) }.getOrNull()
            record.lifecycleStatus = runtimeResult?.status?.name
                ?: recorders.turnRecorder.snapshot().finalStatus
            record.completionOutcome = runtimeResult?.completion?.outcome?.name
                ?: recorders.turnRecorder.snapshot().completionOutcome
            record.logHealth = logHealth
            val askedUser = recorders.turnRecorder.snapshot().finalStatus == RuntimeRunStatus.WAITING_USER_INPUT.name
            if (ownsUi) {
                val outcome = sessionRunnerOutcome(
                    runtimeResult = runtimeResult,
                    replayFailed = result.contextPanel != null && !result.replay.passed,
                    askedUser = askedUser,
                )
                publishRunSummary(record, outcome)
            } else {
                val outcome = sessionRunnerOutcome(runtimeResult, replayFailed = false, askedUser = askedUser)
                finishRunTiming(record, outcome)
            }
        }
        if (!runtime.isWaitingForConfirmation(runScope.requestId)) {
            // Reconcile against the still-open writer runtime; a failure here degrades the
            // trajectory only and never changes the run's terminal outcome.
            trajectoryRepository()?.let { repository ->
                withContext(Dispatchers.IO) { runCatching { repository.reconcile(runtime, runScope) } }
            }
            withContext(Dispatchers.IO) { resources.close() }
            // Publish before removing the live owner so observers cannot see a missing session.
            val finishing = registry.getExact(runScope.sessionId, runScope.requestId)
            if (finishing != null && !sessionCoordinator.isSessionDeleted(runScope.sessionId)) {
                rememberFinished(finishing, active = false)
            }
            registry.removeExact(runScope.sessionId, runScope.requestId)
        }
        // ownsUi was captured before the suspending finalization work; the user may have
        // switched sessions since, so re-confirm the selection before touching the panel.
        if (isSelected(runScope.sessionId)) {
            result.contextPanel?.let { panel -> publishContextPanel(runScope.sessionId, panel) }
        } else {
            sessionCoordinator.transcripts.release(runScope.sessionId)
        }
        notifyChanged()
    }

    private fun appendDiagnostics(target: TranscriptStore, entry: DiagnosticEntry) {
        target.appendDiagnostics(entry)
        uiObserver.requestNotify()
    }

    /** Where [runScope]'s items go: its session's live transcript while the run is still the session's own. */
    private fun transcriptOf(runScope: RuntimeRunScope): TranscriptStore? =
        registry.getExact(runScope.sessionId, runScope.requestId)?.let { sessionCoordinator.transcriptOf(runScope.sessionId) }

    /** Whether [sessionId] has a run going on, which keeps its transcript loaded when it is not on screen. */
    internal fun isRunningIn(sessionId: String): Boolean = registry.get(sessionId)?.isJobActive == true

    private fun applyTokenUsage(runScope: RuntimeRunScope, delta: TokenDelta) {
        val record = registry.getExact(runScope.sessionId, runScope.requestId) ?: return
        record.addTokenUsage(delta)
        UsageStore.record(record.request.modelRef?.modelId.orEmpty(), delta.toModelUsage())
        if (isSelected(runScope.sessionId)) {
            tokenUsage = boardUsage(runScope.sessionId)
            uiObserver.requestNotify()
        }
    }

    private fun boardUsage(sessionId: String): TokenUsageState = sessionUsage.of(sessionId, registry.get(sessionId)) {
        if (isSelected(sessionId)) { tokenUsage = boardUsage(sessionId); uiObserver.requestNotify() }
    }

    private fun applyTodoBoard(runScope: RuntimeRunScope, todos: List<TodoEntry>) {
        val record = registry.getExact(runScope.sessionId, runScope.requestId) ?: return
        record.todoBoard = todos
        if (isSelected(runScope.sessionId) && selectedRecord()?.runId == record.runId) {
            contextPanelState = contextPanelState.copy(todo = todos)
            uiObserver.requestNotify()
        }
    }

    private fun ownsActiveUi(runScope: RuntimeRunScope): Boolean {
        val record = registry.getExact(runScope.sessionId, runScope.requestId)
        return record != null && sessionCoordinator.activeSessionId == runScope.sessionId
    }

    private fun handleSteeringPostResult(result: SteeringPostUiResult) {
        setStatus(result.status)
        transcript.append(result.transcript)
        notifyChanged()
    }

    private fun handleConfirmationPostResult(sessionId: String, result: ConfirmationPostUiResult) {
        if (isSelected(sessionId)) {
            result.runtimeResult?.let { runtimeDemo = runtimeDemo.reduce(AgentEvent.ResultAvailable(it)) }
            selectedRecord()?.runtimeDemo = runtimeDemo
            setStatus(result.status)
        }
        sessionCoordinator.transcriptOf(sessionId)?.append(result.transcript)
        notifyChanged()
    }


    private fun maybeAutoResolveConfirmation(event: AgentEvent, runScope: RuntimeRunScope) {
        if (event !is AgentEvent.ConfirmationRequired || registry.getExact(runScope.sessionId, runScope.requestId) == null) return
        val pendingId = event.pending.pendingId
        val approve = confirmationMode().autoDecision(domains.of(pendingId), domains.confirmed(runScope.sessionId)) ?: return
        resolveConfirmationAutomatically(runScope.sessionId, runScope.requestId, pendingId, approve)
    }

    private fun resolveConfirmationAutomatically(
        sessionId: String,
        runId: String,
        pendingId: String,
        approve: Boolean,
    ) {
        val record = registry.getExact(sessionId, runId) ?: return
        val runtime = record.runtime
        scope.launch {
            val result = postRuntimeConfirmation(runtime, pendingId, RequestIds.next("confirm"), approve, runId)
            if (registry.getExact(sessionId, runId) == null) return@launch
            recordConfirmationResolution(sessionId, pendingId, approve, result, automatic = true)
            handleConfirmationPostResult(sessionId, result)
            if (result.terminal) {
                finalizeConfirmedTerminalRun(sessionId, runId, runtime, result)
            } else {
                recoverWaitingConfirmationAfterResume(sessionId, runId, runtime)
            }
        }
    }

    private suspend fun recoverWaitingConfirmationAfterResume(
        sessionId: String,
        runId: String,
        runtime: AgentRuntime,
    ) {
        val recovered = recoverPendingConfirmation(runtime, runId) ?: return
        if (registry.getExact(sessionId, runId) == null) return
        val pending = recovered.pending
        val pendingId = pending.pendingId
        val domain = domains.of(pendingId).ifBlank { recovered.domain }
        domains.remember(sessionId, pendingId, domain)
        if (isSelected(sessionId)) {
            runtimeDemo = runtimeDemo.reduce(AgentEvent.ConfirmationRequired(pending))
            selectedRecord()?.runtimeDemo = runtimeDemo
        }
        val approve = confirmationMode().autoDecision(domain, domains.confirmed(sessionId))
        // When auto-resolved (ALLOW_ALL_SESSION / DENY_ALL / confirmed domain), skip inserting a
        // PENDING card to avoid the flicker of "insert then async-resolve".
        if (approve == null) {
            sessionCoordinator.transcriptOf(sessionId)?.appendConfirmationRequired(pendingId = pendingId, domain = domain, mode = confirmationMode())
            if (isSelected(sessionId)) setStatus("Waiting confirmation")
            notifyChanged()
            return
        }
        resolveConfirmationAutomatically(sessionId, runId, pendingId, approve)
    }

    private suspend fun finalizeConfirmedTerminalRun(
        sessionId: String,
        runId: String,
        runtime: AgentRuntime,
        result: ConfirmationPostUiResult,
    ) {
        val record = registry.getExact(sessionId, runId) ?: return
        val context = record.context
        val recorders = record.recorders
        result.runtimeResult?.let { terminal ->
            recorders.runEvidenceRecorder.recordPendingPublicToolFinishes()
            if (terminal.status == RuntimeRunStatus.CANCELLED) {
                recorders.runEvidenceRecorder.recordPendingRawToolFinishes(RAW_STATUS_CANCELLED)
            }
            recorders.runEvidenceRecorder.recordTerminalResult(terminal)
            recorders.turnRecorder.recordEvent(AgentEvent.ResultAvailable(terminal))
        }
        finalizeRun(
            runtime = runtime,
            recorders = recorders,
            runScope = RuntimeRunScope(runId, sessionId),
            resources = record.resources,
            context = context,
        )
    }

    private fun recordConfirmationResolution(
        sessionId: String,
        pendingId: String,
        approve: Boolean,
        result: ConfirmationPostUiResult,
        automatic: Boolean,
    ) {
        val status = when {
            result.appliedChoice == null -> result.status.toConfirmationStatus()
            approve && automatic -> ConfirmationStatus.AUTO_APPROVED
            approve -> ConfirmationStatus.APPROVED
            automatic -> ConfirmationStatus.AUTO_DENIED
            else -> ConfirmationStatus.DENIED
        }
        sessionCoordinator.transcriptOf(sessionId)?.updateConfirmation(pendingId, status)
        if (status != ConfirmationStatus.FAILED && isSelected(sessionId)) {
            runtimeDemo = runtimeDemo.resolvePendingConfirmation(pendingId)
            selectedRecord()?.runtimeDemo = runtimeDemo
        }
        if (result.appliedChoice == ConfirmationChoice.APPROVE) domains.approved(sessionId, pendingId)
    }


    /** Returns false when the run's job did not finish within the bounded join. */
    private suspend fun stopSessionRunUnlocked(sessionId: String, publishStopped: Boolean = true): Boolean {
        val record = registry.get(sessionId) ?: return true
        val runId = record.runId
        val joined = cancelAndJoinBounded(record.job)
        withContext(Dispatchers.IO) { record.resources.close() }
        val removed = registry.removeExact(sessionId, runId) ?: record
        if (!sessionCoordinator.isSessionDeleted(sessionId)) {
            rememberFinished(removed, active = false, lifecycle = RuntimeRunStatus.CANCELLED.name)
        } else {
            finishedBySession.remove(sessionId)
        }
        if (isSelected(sessionId)) announcer?.stop()
        if (publishStopped && isSelected(sessionId)) {
            publishRunSummary(removed, RunOutcome.STOPPED, fallbackStatus = "Stopped")
        } else {
            finishRunTiming(removed, RunOutcome.STOPPED)
        }
        notifyChanged()
        return joined
    }

    /** [afterDrain] runs only when every writer confirmed cancellation. */
    private suspend fun drainAllRunsUnlocked(afterDrain: suspend (Boolean) -> Unit = {}) {
        draining = true
        try {
            val records = registry.drain()
            records.forEach { it.job?.cancel() }
            val joined = withTimeoutOrNull(DEBUG_ADMISSION_JOIN_TIMEOUT_MS) {
                records.forEach { it.job?.join() }
                true
            } ?: false
            withContext(Dispatchers.IO) { records.forEach { it.resources.close() } }
            records.forEach { record ->
                if (sessionCoordinator.isSessionDeleted(record.sessionId)) {
                    finishedBySession.remove(record.sessionId)
                } else {
                    rememberFinished(record, active = false, lifecycle = RuntimeRunStatus.CANCELLED.name)
                }
            }
            afterDrain(joined)
            announcer?.stop()
            notifyChanged()
        } finally {
            draining = false
        }
    }

    /** Returns false when the job ignored cancellation for longer than the bounded wait. */
    private suspend fun cancelAndJoinBounded(job: Job?): Boolean {
        if (job == null) return true
        job.cancel()
        return withTimeoutOrNull(DEBUG_ADMISSION_JOIN_TIMEOUT_MS) { job.join(); true } ?: false
    }

    private fun selectedRecord(): ActiveRunRecord? = registry.get(sessionCoordinator.activeSessionId)

    private fun isSelected(sessionId: String): Boolean = sessionCoordinator.activeSessionId == sessionId

    private fun stillOwnsSelected(sessionId: String, runId: String): Boolean =
        registry.getExact(sessionId, runId) != null && isSelected(sessionId)

    private fun rememberFinished(record: ActiveRunRecord, active: Boolean, lifecycle: String? = null) {
        if (lifecycle != null) record.lifecycleStatus = lifecycle
        finishedBySession[record.sessionId] = record.snapshot(active = active)
    }

    private suspend fun <T> withAdmission(block: suspend () -> T): T = admissionMutex.withLock { block() }

    private fun admitOrBusy(sessionId: String): StartRunAdmission? {
        if (draining) return null
        val admission = registry.admissionFor(sessionId)
        return admission.takeUnless { it == StartRunAdmission.BUSY_MAX_ACTIVE_SESSIONS }
    }

    private suspend fun persistSessionTurn(turn: SessionTurn, logHealth: String?) {
        withContext(Dispatchers.IO) {
            sessionCoordinator.appendTurn(turn)
        }
    }

    private fun recordMatching(runScope: RuntimeRunScope): ActiveRunRecord? =
        registry.records().firstOrNull { it.matches(runScope.sessionId, runScope.requestId) }

    internal fun isDrainingForTest(): Boolean = draining

    internal fun tokenUsageFor(sessionId: String): TokenUsageState =
        registry.get(sessionId)?.tokenUsage ?: TokenUsageState()

    fun registryForTest(): ActiveRunRegistry = registry
}
