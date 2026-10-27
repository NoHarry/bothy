@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.runtime
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import cc.noharry.bothy.app.a2a.*
import cc.noharry.bothy.app.a2ui.UserQuestion
import cc.noharry.bothy.app.companion.*
import cc.noharry.bothy.app.console.*
import cc.noharry.bothy.app.console.ui.UiObserverDispatcher
import cc.noharry.bothy.app.evidence.*
import cc.noharry.bothy.app.image.deleteSessionGeneratedImages
import cc.noharry.bothy.app.mcp.*
import cc.noharry.bothy.app.memory.UserMemoryStore
import cc.noharry.bothy.app.model.*
import cc.noharry.bothy.app.overlay.*
import cc.noharry.bothy.app.plans.*
import cc.noharry.bothy.app.session.*
import cc.noharry.bothy.app.skills.*
import cc.noharry.bothy.app.trajectory.*
import cc.noharry.bothy.app.voice.*
import cc.noharry.bothy.app.voice.tts.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*

@OptIn(ExperimentalA2aClientApi::class)
/** Process singleton execution service; presentation and transports observe its asynchronous run topics. */
internal object RuntimeSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val trajectoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = linkedSetOf<(UiState) -> Unit>()
    private val trajectoryNotifications = UiObserverDispatcher(scope, ::notifyChanged)
    private val transcript = TranscriptStore()
    private var config: ProviderConfig? = null
    private var provider: ModelProvider = ModelProvider.OPENAI
    private var streamEnabled: Boolean = DEFAULT_STREAM_ENABLED
    private var useSimulatedModel: Boolean = DEFAULT_USE_SIMULATED_MODEL
    private var confirmationMode: ConfirmationMode = ConfirmationMode.ALLOW_ALL_SESSION
    private var mcpProviders: List<McpProviderState> = emptyList()
    private var a2aConfig: A2aModuleConfig? = null
    private val a2aClientPool = A2aClientPool()
    private var a2aState: A2aModuleState = A2aModuleState()
    private var workspaceSelection: WorkspaceSelectionState = WorkspaceSelectionState()
    private var trajectoryGateway: TrajectoryEventLogGateway? = null
    private var trajectoryRepository: DefaultTrajectoryRepository? = null
    private val sessionCoordinator = SessionCoordinator(transcript)
    private var status: String = "Idle"
    private val modelOptions = ModelOptionsController(onChanged = ::notifyChanged)
    private val runner = RuntimeSessionRunner(
        scope = scope,
        transcript = transcript,
        sessionCoordinator = sessionCoordinator,
        currentConfig = { config },
        loadConfig = { context -> ProviderConfig.load(context).also(::applyLoadedConfig) },
        setProvider = { provider = it },
        setStatus = { status = it },
        streamEnabled = { streamEnabled },
        useSimulatedModel = { useSimulatedModel },
        mcpProviders = { mcpProviders },
        a2aBridgeProviders = { a2aBridgeProviders() },
        streamLabel = ::streamLabel,
        backendLabel = ::backendLabel,
        confirmationMode = { confirmationMode },
        notifyChanged = ::notifyChanged,
        trajectoryRepository = { trajectoryRepository },
    )
    fun attach(context: Context, listener: (UiState) -> Unit): () -> Unit {
        ensureConfig(context.applicationContext)
        ensureMcpProviders(context.applicationContext)
        ensureA2aClients(context.applicationContext)
        workspaceSelection = workspaceSelectionState(context.applicationContext)
        sessionCoordinator.ensure(context.applicationContext)
        refreshModelOptions(context.applicationContext)
        listeners += listener
        runner.refreshContextPanel(context.applicationContext)
        refreshTrajectory(context)
        listener(state())
        return { listeners -= listener }
    }
    /**
     * A wake after [SessionPolicy.idleMinutes] without a turn, or every wake when so set (设置 → 通用 → 会话), starts a
     * new conversation (owner 2026-10-17, made settable 2026-10-25), so the model sees a clean context; a wake within it
     * carries on, so 「再高一点」 still follows the last turn. A session with no turns yet, or a run still going, stays. True
     * when a new session was started.
     */
    fun freshSessionForWake(context: Context, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (isRunning) return false
        sessionCoordinator.ensure(context.applicationContext)
        val active = sessionCoordinator.sessions.firstOrNull { it.sessionId == sessionCoordinator.activeSessionId }
        val backgroundWork = active != null && BothySubagents.instance.hasUnfinished(active.sessionId)
        if (!wakeStartsNewSession(active, nowMillis, backgroundWork)) return false
        newSession(context)
        return true
    }

    fun newSession(context: Context) {
        sessionCoordinator.newSession(context.applicationContext)
        status = "New session"
        runner.resetForSession(context.applicationContext)
        refreshTrajectory(context)
        notifyChanged()
        pruneSessions(context)
    }

    /** Deletes the sessions past [SessionPolicy.maxSessions], touched longest ago first; see [sessionsOverLimit]. */
    fun pruneSessions(context: Context) {
        val appContext = context.applicationContext
        sessionCoordinator.ensure(appContext)
        val protected = runner.activeRunSnapshots().map { it.sessionId }.toSet() + sessionCoordinator.activeSessionId +
            sessionCoordinator.sessions.map { it.sessionId }.filter(BothySubagents.instance::hasUnfinished)
        val over = sessionsOverLimit(sessionCoordinator.sessions, SessionSettings.policy.value.maxSessions, protected)
        if (over.isEmpty()) return
        scope.launch {
            over.forEach { id -> runCatching { deleteSessionData(appContext, id) }; forgetSessionContinuity(id) }
            VoiceLog.i("DebugSessions", "deleted ${over.size} session(s) over the limit of ${SessionSettings.policy.value.maxSessions}")
            notifyChanged()
        }
    }
    fun selectSession(context: Context, sessionId: String) {
        sessionCoordinator.selectSession(context.applicationContext, sessionId)
        status = "Session resumed"
        runner.clearRequestAndRefresh(context.applicationContext)
        refreshTrajectory(context)
        notifyChanged()
    }

    /** Background refresh: attach and session switches must never block on EventLog IO. */
    fun refreshTrajectory(context: Context) {
        trajectoryRepository(context.applicationContext)
            .refreshSessionAsync(sessionCoordinator.activeSessionId)
    }

    fun loadTrajectoryRun(context: Context, runId: String) {
        trajectoryRepository(context.applicationContext)
            .loadRunAsync(sessionCoordinator.activeSessionId, runId)
    }

    fun loadOlderTrajectoryRuns(context: Context) {
        trajectoryRepository(context.applicationContext)
            .loadOlderRuns(sessionCoordinator.activeSessionId)
    }
    fun deleteSession(context: Context, sessionId: String) {
        val appContext = context.applicationContext
        scope.launch {
            val deletion = runCatching { deleteSessionData(appContext, sessionId) }
            forgetSessionContinuity(sessionId)
            val result = deletion.getOrNull()
            status = when {
                result == null -> "Session deletion failed"
                result.ok -> "Session deleted"
                else -> "Session deleted; cleanup incomplete"
            }
            when {
                result == null -> transcript.appendStatus(
                    "Session deletion failed: ${deletion.exceptionOrNull()?.message.orEmpty()}",
                )
                !result.ok -> transcript.appendStatus(
                    "Session data was deleted, but cleanup failed for: ${result.failedTargets.joinToString()}. " +
                        "Retry cleanup for this session.",
                )
            }
            runner.clearRequestAndRefresh(appContext)
            notifyChanged()
        }
    }

    private suspend fun deleteSessionData(appContext: Context, sessionId: String): SessionDeletionResult {
        val repository = trajectoryRepository(appContext)
        return deleteRuntimeSession(
            sessionId = sessionId,
            deleteActiveSession = runner::deleteActiveSession,
            dropTrajectory = { repository.dropSession(it); !appSessionEventLogRoot(appContext, it).exists() },
            deletePersistedSession = { sessionCoordinator.deleteSession(appContext, it) },
            // Generated pictures are this session's artifacts too; `and` runs both deletions.
            deleteArtifacts = {
                ArtifactStore.deleteSessionArtifacts(appWorkspaceRoot(appContext), it) and
                    deleteSessionGeneratedImages(appContext, it)
            },
        )
    }

    /**
     * Stops every active run, deletes sessions / memory workspace / evidence logs / EventLog
     * roots, and opens a blank session. Provider config and MCP toggles are preserved.
     *
     * Deletion runs while run admission is still closed, so a new run cannot start writing
     * into a root that is about to be removed.
     */
    fun clearRuntimeData(context: Context, alsoMemories: Boolean = false, onDone: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        val repository = trajectoryRepository(app)
        status = "Clearing runtime data"
        notifyChanged()
        scope.launch {
            var result: RuntimeDataCleanupResult? = null
            var refusedForUnstoppedWriter = false
            var memoriesCleared = true
            runner.stopAllRunsThen { writersStopped ->
                if (!writersStopped) {
                    refusedForUnstoppedWriter = true
                    return@stopAllRunsThen
                }
                // 设置 → 记忆's 清除记忆和对话记录: the memories go only once no run can write one back.
                if (alsoMemories) memoriesCleared = UserMemoryStore.clear(app)
                repository.clear()
                forgetSessionContinuity()
                result = sessionCoordinator.clearAllRuntimeData(app)
            }.join()
            if (refusedForUnstoppedWriter) {
                onDone(false)
                status = "Clear aborted: a run did not stop"
                transcript.appendStatus(
                    "Clear runtime data aborted: a run did not confirm cancellation, so nothing was deleted. Retry.",
                )
                notifyChanged()
                return@launch
            }
            val cleanup = result ?: RuntimeDataCleanupResult(emptyList(), emptyList())
            status = cleanup.summaryLine()
            transcript.appendStatus(
                buildString {
                    append(cleanup.summaryLine())
                    append(" (sessions, generated images, workspace memory, run evidence, logs, trajectory event log)")
                    if (cleanup.failedPaths.isNotEmpty()) {
                        append("; failed: ")
                        append(cleanup.failedPaths.joinToString())
                    }
                },
            )
            runner.resetForSession(app)
            notifyChanged()
            onDone(memoriesCleared && cleanup.failedPaths.isEmpty())
        }
    }
    fun reloadConfig(context: Context) {
        val loaded = ProviderConfig.load(context.applicationContext)
        applyLoadedConfig(loaded)
        status = "Config reloaded"
        transcript.appendStatus(loaded.summary(loaded.activeProfile))
        refreshModelOptions(context.applicationContext)
        notifyChanged()
    }
    fun selectProvider(context: Context, selected: ModelProvider) {
        ensureConfig(context.applicationContext)
        config?.profiles?.firstOrNull { it.provider == selected }?.let { profile ->
            selectProfile(context, profile.id)
            return
        }
        forceLiveBackend()
        provider = selected
        status = "Provider: ${selected.label}"
        transcript.appendStatus("Provider switched to ${selected.label}")
        refreshModelOptions(context.applicationContext)
        notifyChanged()
    }
    @OptIn(ExperimentalA2aClientApi::class)
    fun setA2aProfileEnabled(context: Context, profileId: String, enabled: Boolean) {
        val loaded = A2aModuleConfig.setProfileEnabled(context.applicationContext, profileId, enabled)
        applyA2aConfig(loaded)
        val profileLabel = a2aState.profiles.firstOrNull { it.id == profileId }?.label ?: profileId
        status = a2aState.errorsByProfileId[profileId]
            ?: "A2A client: $profileLabel ${if (enabled) "enabled" else "disabled"}"
        transcript.appendStatus(a2aState.summary)
        notifyChanged()
    }

    /** Compatibility entry point: selecting an A2A profile now enables it alongside existing profiles. */
    fun selectA2aProfile(context: Context, profileId: String) {
        setA2aProfileEnabled(context, profileId, enabled = true)
    }

    /**
     * Persists a user-defined A2A profile (saved disabled; enablement stays explicit) and
     * reconciles the client pool. Returns the validation error, or null on success.
     */
    @OptIn(ExperimentalA2aClientApi::class)
    fun saveA2aProfile(
        context: Context,
        label: String,
        moduleId: String,
        endpoint: String,
        allowLoopbackHttpForDebug: Boolean,
        description: String = "",
        token: String = "",
        replaceSameLabel: Boolean = false,
    ): String? {
        val result = A2aModuleConfig.saveUserProfile(
            context.applicationContext, label, moduleId, endpoint, allowLoopbackHttpForDebug,
            description = description, token = token, replaceSameLabel = replaceSameLabel,
        )
        val saved = when (result) {
            is A2aSaveResult.Rejected -> return result.reason
            is A2aSaveResult.Saved -> result.config
        }
        applyA2aConfig(saved)
        status = "A2A client added: ${label.trim()}"
        transcript.appendStatus(a2aState.summary)
        notifyChanged()
        return null
    }

    /** Deletes a user-defined A2A profile and reconciles the client pool; asset profiles are refused. */
    @OptIn(ExperimentalA2aClientApi::class)
    fun deleteA2aProfile(context: Context, profileId: String) {
        val profileLabel = a2aState.profiles.firstOrNull { it.id == profileId }?.label ?: profileId
        val loaded = A2aModuleConfig.deleteUserProfile(context.applicationContext, profileId) ?: return
        applyA2aConfig(loaded)
        status = "A2A client removed: $profileLabel"
        transcript.appendStatus(a2aState.summary)
        notifyChanged()
    }

    /** One bridge provider per enabled A2A profile; clients resolve lazily from the pool at call time. */
    internal fun a2aBridgeProviders(): List<A2aBridgeProvider> =
        appA2aBridgeProviders(a2aConfig) { profileId -> a2aClientPool.client(profileId) }

    fun selectProfile(context: Context, profileId: String) {
        val loaded = ProviderConfig.saveSelectedProfile(context.applicationContext, profileId)
        forceLiveBackend()
        applyLoadedConfig(loaded)
        status = "Profile: ${loaded.activeProfile.displayLabel}"
        transcript.appendStatus("Profile switched to ${loaded.activeProfile.displayLabel}")
        refreshModelOptions(context.applicationContext)
        notifyChanged()
    }
    fun setManualProfileEnabled(context: Context, enabled: Boolean) {
        val loaded = ProviderConfig.saveManualProfileEnabled(context.applicationContext, enabled)
        forceLiveBackend()
        applyLoadedConfig(loaded)
        status = if (enabled) "Manual profile" else "Config default profile"
        transcript.appendStatus("Profile mode: ${status}")
        refreshModelOptions(context.applicationContext)
        notifyChanged()
    }
    fun saveProviderOverride(
        context: Context,
        selectedProvider: ModelProvider,
        apiKey: String,
        model: String,
        baseUrl: String,
        endpoint: ProviderEndpoint,
    ) {
        val profile = activeProfile(context.applicationContext) ?: return
        config = saveProviderOverrideChoice(context.applicationContext, profile, config, apiKey, model, baseUrl, endpoint, selectedProvider)
        forceLiveBackend()
        provider = config?.activeProfile?.provider ?: profile.provider
        status = "Config saved"
        transcript.appendStatus("${profile.displayLabel} config saved in app settings.")
        refreshModelOptions(context.applicationContext)
        notifyChanged()
    }
    fun saveNewProviderProfile(
        context: Context,
        label: String,
        selectedProvider: ModelProvider,
        endpoint: ProviderEndpoint,
        apiKey: String,
        model: String,
        baseUrl: String,
    ): String {
        val loaded = ProviderConfig.saveNewProfile(
            context.applicationContext,
            label,
            selectedProvider,
            endpoint,
            apiKey,
            model,
            baseUrl,
        )
        forceLiveBackend()
        applyLoadedConfig(loaded)
        status = "Profile added: ${loaded.activeProfile.displayLabel}"
        transcript.appendStatus("${loaded.activeProfile.displayLabel} provider saved in app settings.")
        refreshModelOptions(context.applicationContext)
        notifyChanged()
        return loaded.activeProfile.id
    }
    fun saveProviderModels(context: Context, editor: ProviderEditState, models: List<String>) {
        saveProviderModelList(context.applicationContext, editor, models)
        refreshModelOptions(context.applicationContext)
        notifyChanged()
    }
    fun deleteProviderProfile(context: Context, profileId: String) {
        val loaded = ProviderConfig.deleteSavedProfile(context.applicationContext, profileId)
        if (profileId.isManualProfileId()) deleteProviderModels(context.applicationContext, profileId)
        applyLoadedConfig(loaded)
        status = "Profile deleted"
        transcript.appendStatus("Provider profile deleted.")
        refreshModelOptions(context.applicationContext)
        notifyChanged()
    }
    fun fetchProviderModels(
        context: Context,
        profileId: String,
        selectedProvider: ModelProvider,
        endpoint: ProviderEndpoint,
        apiKey: String,
        model: String,
        baseUrl: String,
        onModels: (Result<List<String>>) -> Unit = {},
    ) {
        val profile = ProviderProfile(profileId, selectedProvider.label, selectedProvider, apiKey, model, baseUrl, endpoint)
        val effectiveKey = apiKey.ifBlank { DEFAULT_TEST_API_KEY.takeIf { baseUrl.isLocalUrl() }.orEmpty() }
        scope.launch {
            status = "Fetching models"
            notifyChanged()
            runCatching {
                ModelCatalogClient(BenchHttpTransport(context.applicationContext)).fetch(selectedProvider, effectiveKey, baseUrl)
            }.onSuccess { models ->
                status = "Models loaded: ${models.size}"
                transcript.appendStatus(
                    "${profile.displayLabel} model list loaded: ${models.size}.",
                )
                onModels(Result.success(models))
            }.onFailure { error ->
                status = "Model fetch failed"
                transcript.appendStatus("Model list fetch failed. Check connection settings.")
                onModels(Result.failure(error))
            }
            notifyChanged()
        }
    }
    fun selectModel(context: Context, model: String) {
        ensureConfig(context.applicationContext)
        val activeConfig = config ?: return
        val profile = activeConfig.activeProfile
        config = saveSelectedModel(context.applicationContext, profile, activeConfig, model) ?: return
        provider = config?.activeProfile?.provider ?: profile.provider
        status = "Model: ${model.trim()}"
        transcript.appendStatus("Model selected: ${model.trim()}")
        notifyChanged()
    }
    fun selectVariant(context: Context, variant: String) {
        ensureConfig(context.applicationContext)
        val activeConfig = config ?: return
        val profile = activeConfig.activeProfile
        config = saveSelectedVariant(context.applicationContext, profile, activeConfig, variant) ?: return
        provider = config?.activeProfile?.provider ?: profile.provider
        val label = variant.trim().takeUnless { it.equals(DEFAULT_DEBUG_VARIANT_ID, ignoreCase = true) }.orEmpty().ifBlank { "Default" }
        status = "Variant: $label"
        transcript.appendStatus("Reasoning variant selected: $label")
        notifyChanged()
    }
    fun toggleStream() {
        streamEnabled = !streamEnabled
        status = streamLabel()
        transcript.appendStatus("Stream mode: ${streamLabel()}")
        notifyChanged()
    }
    fun selectBackend(simulated: Boolean) {
        useSimulatedModel = simulated
        status = backendLabel()
        transcript.appendStatus("Backend: ${backendLabel()}")
        notifyChanged()
    }
    fun selectConfirmationMode(mode: ConfirmationMode) {
        confirmationMode = mode
        status = "Approval: ${mode.label}"
        transcript.appendStatus("Approval mode: ${mode.label}")
        notifyChanged()
    }
    /** Whether the selected session's agent is running. */
    val isRunning: Boolean get() = runner.isRunning

    /** Whether 小博's own announcer is speaking (not 豆包's voice in 端到端). */
    val speaking: Boolean get() = runner.speaking
    fun stopRun(): Job = runner.stopRun()
    internal fun stopAllRuns(): Job = runner.stopAllRuns()
    fun postSteeringText(rawText: String) = runner.postSteeringText(rawText)
    fun announceFirst(context: Context, text: String) = runner.announceFirst(context.applicationContext, text)

    /** 陪聊: the latest exchanges of the selected session (agent and companion alike), oldest first, for its context. */
    suspend fun recentExchanges(context: Context, limit: Int = COMPANION_CONTEXT_TURNS): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            runCatching { sessionCoordinator.ensure(context.applicationContext).readTurns(sessionCoordinator.activeSessionId) }
                .getOrDefault(emptyList())
                .filter { it.userText.isNotBlank() && it.finalResponse.isNotBlank() }
                .takeLast(limit)
                .map { it.userText to it.finalResponse }
        }

    /**
     * 陪聊: a companion reply becomes a turn like the agent's (shown on the card, kept in the session so the agent sees
     * it later) and is spoken in 小博's voice unless [speak] is off (端到端 has already said it). [label] names the
     * service for the status line.
     */
    fun recordCompanionTurn(
        context: Context,
        words: String,
        reply: String,
        label: String,
        speak: Boolean = true,
        scene: String = COMPANION_SCENE,
        statusText: String = "闲聊 · $label",
    ) {
        val app = context.applicationContext
        ensureConfig(app)
        val now = System.currentTimeMillis()
        val id = "$scene-$now"
        transcript.openTurn(words)
        transcript.appendFinalResponse(reply)
        // A chat line while 小博 still runs a task (端到端) must not take that run's card.
        if (!runner.isRunning) runner.showOutsideRequest(id, words)
        status = statusText
        notifyChanged()
        val turn = SessionTurn(
            turnId = id, requestId = id, runId = id, sessionId = sessionCoordinator.activeSessionId, scene = scene,
            userText = words, startedAtEpochMillis = now, completedAtEpochMillis = now, finalStatus = "COMPLETED",
            finalResponse = reply, tools = emptyList(), completionOutcome = "SUCCEEDED",
        )
        scope.launch(Dispatchers.IO) { runCatching { sessionCoordinator.appendTurn(turn) } }
        if (speak) runner.announceReply(app, reply)
    }

    /** Settings → 语音 → 试听: loads the configuration if nothing has run yet, then speaks [text]; false when TTS is off. */
    fun previewTts(context: Context, text: String, done: (Boolean) -> Unit) {
        scope.launch {
            prepareExecutionConfiguration(context.applicationContext)
            done(runner.announceFirst(context.applicationContext, text))
        }
    }
    /** Settings → 语音 saved 播报 settings: replies read them from now on; the chosen model and the rest stay as they are. */
    fun refreshTts(context: Context) {
        val app = context.applicationContext
        // 小博's wake answer follows the voice too, from the next wake on.
        WakeAck.prepare(app)
        scope.launch {
            val fresh = withContext(Dispatchers.IO) { ProviderConfig.load(app) }.values.filterKeys { it in TTS_CONFIG_KEYS }
            config = config?.let { it.copy(values = it.values - TTS_CONFIG_KEYS + fresh) }
        }
    }
    /**
     * A voice turn: appended to the running run's next model request as [wireText] (the transcript shows [rawText]),
     * or [rawText] as a new turn when nothing runs, the model reading [modelText] when given; [onNewTurn] tells the
     * caller which happened.
     */
    fun postSpokenInput(context: Context, rawText: String, wireText: String = rawText, modelText: String? = null, onNewTurn: () -> Unit = {}) {
        runner.postSteeringText(wireText, notLanded = {
            onNewTurn()
            submitText(context.applicationContext, rawText, backend = null, spoken = true, modelText = modelText)
        }, title = VOICE_STEERING_TITLE, shown = rawText)
    }
    /** A 连接应用 event (connected, tested, disconnected) as a visible line in the console, from any thread. */
    fun noteConnection(text: String) {
        scope.launch {
            transcript.appendMessage(TranscriptRole.NOTICE, text)
            notifyChanged()
        }
    }

    /**
     * A delegated task settled ([A2aDelegations]): its report becomes a spoken turn once the device is free, so the
     * overlay opens and the agent tells the user. While the user reads another result on the card it waits
     * (the card shows 有新结果 meanwhile, and 查看 releases it), at most [DELEGATION_REPORT_WAIT_MS]. A car still busy
     * after that gets it appended to the running turn instead, which falls back to a new turn if that turn ends first.
     */
    fun deliverDelegationReport(context: Context, report: A2aReport) {
        val app = context.applicationContext
        val asTurn: () -> Unit = {
            A2aDelegations.markDelivered(report.taskId)
            submitText(app, report.display, backend = null, spoken = true, modelText = report.model)
        }
        scope.launch {
            val since = SystemClock.elapsedRealtime()
            // Reports go one at a time, each once the device is idle and no result is being read, so several delegations
            // settling close together never replace one another on the card (owner 2026-10-12).
            reportQueue.withLock {
                awaitIdleCar()
                turnOrSteer(report.model, report.display, DELEGATION_REPORT_TITLE, asTurn) { A2aDelegations.markDelivered(report.taskId) }
            }
            // Whoever asked for it may well answer the report.
            followUpOnceSpoken(app, since)
        }
    }

    /** A subagent started, stalled, moved again or finished: a status line in its conversation, when on screen. */
    fun noteSubagent(status: cc.noharry.agent.runtime.SubagentStatus, change: SubagentChange) {
        scope.launch {
            if (status.parentSessionId != sessionCoordinator.activeSessionId) return@launch
            // A runtime row, not a status line: the console never shows ambient status.
            val detail = when (change) {
                SubagentChange.STARTED -> "任务：${status.task}"
                SubagentChange.FINISHED -> status.result.ifEmpty { status.error }.take(SUBAGENT_DETAIL_CHARS)
                else -> status.latest
            }
            transcript.appendRuntime(status.transcriptLine(change), detail)
            notifyChanged()
        }
    }

    /** Results or stalls of one run's children that the run did not collect: one turn, delivered like a delegation report. */
    fun deliverSubagentReport(context: Context, report: cc.noharry.agent.runtime.SubagentReport) {
        val app = context.applicationContext
        scope.launch {
            val since = SystemClock.elapsedRealtime()
            reportQueue.withLock {
                awaitIdleCar(holdForReading = false)
                // Read now, not when the report came: siblings may have finished while it waited.
                val working = BothySubagents.instance.statuses(report.parentSessionId).filter { !it.state.finished && it.id !in report.statuses.map { s -> s.id } }
                val turn = report.toReport(working)
                BothySubagents.reportDelivered(report)
                turnOrSteer(turn.model, turn.display, SUBAGENT_REPORT_TITLE, { submitText(app, turn.display, backend = null, spoken = true, modelText = turn.model) })
            }
            followUpOnceSpoken(app, since)
        }
    }

    /**
     * A plan came due ([PlanRunner]): once the device is idle (at most [DELEGATION_REPORT_WAIT_MS], in turn with the
     * delegation reports) the card opens. A reminder is shown and spoken as written, without the model; a task becomes
     * a spoken turn the agent runs. A car still busy then gets either into the running turn, like a late report (the
     * reminder for the agent to pass on, never just spoken over it, where the run's own answer would cut it), and as
     * usual if that turn ends first.
     */
    fun deliverPlan(context: Context, delivery: PlanDelivery) {
        val app = context.applicationContext
        scope.launch {
            val since = SystemClock.elapsedRealtime()
            reportQueue.withLock {
                awaitIdleCar()
                val task = delivery.modelText
                // A reminder already showed in the plan popup and is only spoken; a task opens the card 小博 works on.
                if (task != null) OverlayControl.apply(app, OverlayCommand.SHOW)?.let { android.util.Log.w(PLAN_LOG_TAG, "plan card not shown: $it") }
                val remind = { recordCompanionTurn(app, delivery.display, delivery.reply, PLAN_LABEL, scene = PLAN_SCENE, statusText = PLAN_LABEL) }
                when {
                    task != null -> turnOrSteer(task, delivery.display, PLAN_LABEL, { submitText(app, delivery.display, backend = null, spoken = true, modelText = task) })
                    runner.isRunning -> runner.postSteeringText(planReminderSteer(delivery.reply), notLanded = { remind() }, title = PLAN_LABEL, shown = delivery.display)
                    else -> remind()
                }
            }
            // A task's answer is spoken like a report and may well be answered (2026-10-20: after the plan
            // ordered coffee nothing listened, and the reply went unheard). A reminder only pops up and is spoken.
            if (delivery.modelText != null) followUpOnceSpoken(app, since)
        }
    }

    /**
     * Once the turn just delivered has been answered aloud (at most [DELEGATION_REPORT_WAIT_MS]), listening opens again,
     * so a reply to it is heard without the wake word ([VoiceController.followUp]). Not before: a 端到端 session
     * open meanwhile speaks for 小博, and the answer it never heard went unsaid (bench 2026-10-20, a plan's weather).
     * Not at all when the card's X closed voice after [since].
     */
    private suspend fun followUpOnceSpoken(app: Context, since: Long) {
        if (awaitIdleCar(holdForReading = false) != null && !VoiceController.closedSince(since)) VoiceController.followUp(app)
    }

    /** The card's X: the run going on now finishes without speaking ([RunSpeech]). */
    fun silenceRunning() {
        val shown = sessionCoordinator.activeSessionId
        runner.activeRunSnapshots().firstOrNull { it.sessionId == shown && it.isActive }?.let { RunSpeech.silence(it.requestId) }
    }

    /** One report at a time: the next one checks only after this one's turn has started. */
    private val reportQueue = Mutex()

    /**
     * Until no run is going and no result is being read, at most [DELEGATION_REPORT_WAIT_MS]. The card needs a moment
     * after a run ends to show its result and start holding reports: two reports settling together put the second on
     * the card the second the first finished speaking.
     */
    /**
     * Until no run is going and the card's result has been read. A subagent's report instead waits only until the answer
     * is spoken ([holdForReading] false): it adds to the result on the card rather than replacing it with something else,
     * and holding it for the reading time kept a 网上核实 report back 47 s after that answer ended (bench 2026-10-19).
     */
    private suspend fun awaitIdleCar(holdForReading: Boolean = true) = withTimeoutOrNull(DELEGATION_REPORT_WAIT_MS) {
        val busy = { runner.isRunning || if (holdForReading) OverlayControl.reading() else TtsActivity.active.value }
        do {
            while (busy()) delay(DELEGATION_REPORT_POLL_MS)
            delay(RESULT_SETTLE_MS)
        } while (busy())
    }

    /**
     * [asTurn] on an idle car, waiting until that turn runs, so the next one in [reportQueue] cannot replace it; on a
     * busy car [model] goes into the running turn ([beforeSteer] first), and becomes a turn if that one ends first.
     */
    private suspend fun turnOrSteer(model: String, shown: String, title: String, asTurn: () -> Unit, beforeSteer: () -> Unit = {}) {
        if (!runner.isRunning) {
            asTurn()
            withTimeoutOrNull(REPORT_START_WAIT_MS) { while (!runner.isRunning) delay(DELEGATION_REPORT_POLL_MS / 10) }
        } else {
            beforeSteer()
            runner.postSteeringText(model, notLanded = { asTurn() }, title = title, shown = shown)
        }
    }

    /** Late answers to an ended run become a new turn in the selected session; the ended run never revives. */
    internal fun answerQuestion(context: Context, question: UserQuestion, text: String) =
        runner.answerQuestion(question, text) { lateTurn -> submitText(context, lateTurn) }

    suspend fun postExternalInput(sessionId: String, runId: String, requestId: String, text: String, questionId: String?) =
        runner.postInput(sessionId, runId, requestId, text, questionId)
    fun resolveConfirmation(pendingId: String, approve: Boolean) = runner.resolveConfirmation(pendingId, approve)
    fun toggleTranscriptItem(updated: ConversationItem) {
        if (transcript.toggle(updated)) notifyChanged()
    }
    fun setMcpProviderEnabled(context: Context, id: String, enabled: Boolean) {
        mcpProviders = saveMcpProviderEnabled(context.applicationContext, id, enabled)
        mcpProviders.firstOrNull { it.id == id }?.server?.let { server -> McpToolCatalogs.onServerSwitch(context, server, enabled) }
        status = "MCP: ${mcpProviders.enabledProviderLabels().ifEmpty { listOf("none") }.joinToString()}"
        notifyChanged()
    }
    /** Adds or edits a configured MCP server (设置 → 连接 → MCP); returns why it was refused, or null once saved. */
    fun saveMcpServer(context: Context, draft: McpServerDraft): String? {
        val saved = when (val result = saveMcpServerDraft(context.applicationContext, draft)) {
            is McpSaveResult.Rejected -> return result.reason
            is McpSaveResult.Saved -> result.id
        }
        mcpProviders = loadMcpProviders(context.applicationContext)
        McpToolCatalogs.onServerChanged(context.applicationContext, saved)
        status = "MCP saved: ${draft.label.trim()}"
        notifyChanged()
        return null
    }
    /** Deletes a configured MCP server and its stored token; the device adapter is refused. */
    fun deleteMcpServer(context: Context, id: String) {
        val label = mcpProviders.firstOrNull { it.id == id }?.label ?: id
        if (!deleteMcpServerConfig(context.applicationContext, id)) return
        McpToolCatalogs.onServerChanged(context.applicationContext, id)
        mcpProviders = loadMcpProviders(context.applicationContext)
        status = "MCP removed: $label"
        notifyChanged()
    }
    fun selectSafWorkspace(context: Context, uri: Uri) {
        saveSafWorkspace(context.applicationContext, uri)
        workspaceSelection = workspaceSelectionState(context.applicationContext)
        status = "Workspace: ${workspaceSelection.label}"
        transcript.appendStatus("SAF workspace selected: ${workspaceSelection.label}")
        notifyChanged()
    }
    fun reportSafWorkspaceUnavailable(message: String) {
        status = "Workspace picker unavailable"
        transcript.appendStatus(message)
        notifyChanged()
    }
    fun clearSafWorkspace(context: Context) {
        clearSafWorkspaceSelection(context.applicationContext)
        workspaceSelection = workspaceSelectionState(context.applicationContext)
        status = "Workspace: App private"
        transcript.appendStatus("SAF workspace cleared")
        notifyChanged()
    }
    fun submitText(context: Context, rawText: String) = submitText(context, rawText, backend = null, stream = null)
    /** Shared service intake for external adapters; never changes the selected UI session. */
    suspend fun submitExternal(context: Context, text: String, intake: RunIntake) = withContext(Dispatchers.Main.immediate) {
        prepareExecutionConfiguration(context.applicationContext)
        trajectoryRepository(context.applicationContext)
        runner.start(context.applicationContext, text, intake).join()
    }

    suspend fun cancelExternal(sessionId: String, runId: String) = withContext(Dispatchers.Main.immediate) {
        runner.cancelRun(sessionId, runId).join()
    }
    fun validateHostedSearch(context: Context, rawText: String, stream: String? = null) {
        val appContext = context.applicationContext
        scope.launch {
            val activeConfig = withContext(Dispatchers.IO) { ProviderConfig.load(appContext) }.also(::applyLoadedConfig)
            val profile = activeConfig.activeProfile
            if (!activeConfig.isReady(profile)) {
                status = "Provider configuration incomplete"
                transcript.appendMessage(TranscriptRole.ERROR, activeConfig.missingMessage(profile), collapsed = false)
                notifyChanged()
                return@launch
            }
            val validationStream = stream?.toBooleanOverride() ?: false
            val requestId = RequestIds.next("web-search")
            val diagnostics = appDiagnostics(
                context = appContext,
                requestId = requestId,
                onEntry = transcript::appendDiagnostics,
                onTokenUsage = {},
            )
            diagnostics.recordSessionStart(profile.provider, if (validationStream) "stream" else "non-stream", activeConfig.summary(profile))
            status = "Validating web_search"
            transcript.appendRuntime(
                "web_search validation",
                "profile=${profile.displayLabel} capability=${profile.searchCapability} localTools=0 requestId=$requestId",
            )
            notifyChanged()
            val result = withContext(Dispatchers.IO) { runHostedSearchValidation(
                requestId = requestId,
                profile = profile,
                config = activeConfig,
                client = activeConfig.client(profile, validationStream, diagnostics),
                prompt = rawText.trim().ifBlank { DEFAULT_HOSTED_SEARCH_VALIDATION_PROMPT },
            ) }
            val evidence = withContext(Dispatchers.IO) { writeHostedSearchValidationEvidence(appContext, result) }
            status = if (result.passed) "web_search passed" else "web_search failed"
            transcript.appendRuntime(
                "web_search validation result",
                "passed=${result.passed} completed=${result.completed} searchEvents=${result.searchEventCount} " +
                    "resultCount=${result.resultCount} toolCalls=${result.toolCallCount}\n${result.response}\n${evidence.absolutePath}",
            )
            notifyChanged()
        }
    }

    fun submitTextReplacingRun(context: Context, rawText: String, backend: String?, stream: String? = null) {
        val appContext = context.applicationContext
        scope.launch {
            if (runner.isRunning) runner.stopRun()
            // Only spoken input replaces a run this way (the ASR port, a voice turn that drops the task).
            submitText(appContext, rawText, backend, stream, spoken = true)
        }
    }
    /** [modelText], when given, is what the model reads instead of [rawText], which the transcript and overlay show. */
    fun submitText(
        context: Context,
        rawText: String,
        backend: String?,
        stream: String? = null,
        spoken: Boolean = false,
        modelText: String? = null,
    ) = scope.launch {
        val text = rawText.trim()
        if (text.isEmpty()) return@launch
        prepareExecutionConfiguration(context.applicationContext)
        when (backend?.lowercase()) {
            BACKEND_LIVE, LEGACY_BACKEND_LIVE_ALIAS -> selectBackend(simulated = false)
        }
        when (stream?.lowercase()) {
            "1", "true" -> { streamEnabled = true; transcript.appendStatus("Stream forced on") }
            "0", "false" -> { streamEnabled = false; transcript.appendStatus("Stream forced off") }
        }
        transcript.openTurn(text)
        // A run started on the device itself takes the overlay back from an A2A task.
        OverlayFocus.localRun()
        val intake = if (spoken) RunIntake(SpokenRuns.next()) else null
        runner.start(context.applicationContext, text, intake, modelText)
    }
    private suspend fun prepareExecutionConfiguration(context: Context) {
        val needModel = config == null
        val needMcp = mcpProviders.isEmpty()
        val needA2a = a2aConfig == null
        val loaded = withContext(Dispatchers.IO) {
            Triple(
                if (needModel) ProviderConfig.load(context) else null,
                if (needMcp) loadMcpProviders(context) else null,
                if (needA2a) A2aModuleConfig.load(context) else null,
            )
        }
        if (config == null) loaded.first?.let(::applyLoadedConfig)
        if (mcpProviders.isEmpty()) loaded.second?.let { mcpProviders = it }
        if (a2aConfig == null) loaded.third?.let(::applyA2aConfig)
    }

    private fun trajectoryGateway(context: Context): TrajectoryEventLogGateway {
        val app = context.applicationContext
        return trajectoryGateway ?: TrajectoryEventLogGateway(
            baseRoot = { sessionEventLogBaseRoot(app) },
            rootForSession = { sessionId -> appSessionEventLogRoot(app, sessionId) },
        ).also { trajectoryGateway = it }
    }

    private fun trajectoryRepository(context: Context): DefaultTrajectoryRepository {
        val app = context.applicationContext
        return trajectoryRepository ?: DefaultTrajectoryRepository(
            gateway = trajectoryGateway(app),
            readTurns = { sessionId ->
                runCatching { sessionCoordinator.ensure(app).readTurns(sessionId) }.getOrDefault(emptyList())
            },
            readRunEvidence = { requestId -> readRunEvidenceFile(app, requestId) },
            activeRunSnapshots = runner::activeRunSnapshots,
            ioScope = trajectoryScope,
            onChanged = { sessionId ->
                // Background sessions keep building their own trajectory but must not
                // repaint the currently selected page.
                if (sessionId == sessionCoordinator.activeSessionId) {
                    trajectoryNotifications.requestNotify()
                }
            },
        ).also { trajectoryRepository = it }
    }

    private fun ensureConfig(context: Context) {
        if (config == null) {
            applyLoadedConfig(ProviderConfig.load(context))
        }
    }
    private fun applyLoadedConfig(loaded: ProviderConfig) {
        config = loaded
        provider = loaded.activeProfile.provider
        streamEnabled = loaded.streamEnabled
    }
    private fun activeProfile(context: Context): ProviderProfile? {
        ensureConfig(context)
        return config?.activeProfile
    }
    private fun ensureMcpProviders(context: Context) {
        if (mcpProviders.isEmpty()) {
            mcpProviders = loadMcpProviders(context)
        }
    }
    @OptIn(ExperimentalA2aClientApi::class)
    private fun ensureA2aClients(context: Context) {
        if (a2aConfig == null) {
            applyA2aConfig(A2aModuleConfig.load(context))
        }
    }
    @OptIn(ExperimentalA2aClientApi::class)
    private fun applyA2aConfig(loaded: A2aModuleConfig) {
        a2aConfig = loaded
        val clients = a2aClientPool.reconcile(loaded)
        val profilesById = loaded.state.profiles.associateBy { it.id }
        val loadedModuleIds = clients.keys.associateWith { profileId ->
            requireNotNull(profilesById[profileId]).moduleId
        }
        val errors = loaded.state.errorsByProfileId + a2aClientPool.errorsByProfileId
        val activeId = loadedModuleIds.keys.firstOrNull()
            ?: loaded.state.enabledProfiles.firstOrNull()?.id
        a2aState = loaded.state.copy(
            activeProfileId = activeId,
            loadedModuleId = activeId?.let(loadedModuleIds::get),
            error = loaded.state.error ?: errors.values.firstOrNull(),
            loadedModuleIds = loadedModuleIds,
            errorsByProfileId = errors,
        )
    }
    private fun notifyChanged() {
        val current = state()
        // A copy: a listener may detach (or another attach) while it is told, as 端到端's wait for 小博 does.
        listeners.toList().forEach { it(current) }
    }
    private fun state(): UiState {
        val activeConfig = config
        val profile = activeConfig?.activeProfile
        val providerLabel = profile?.displayLabel ?: provider.label
        val items = transcript.snapshot()
        return UiState(
            status = status,
            providerLabel = providerLabel,
            streamLabel = streamLabel(),
            backendLabel = backendLabel(),
            confirmationMode = confirmationMode,
            isRunning = runner.isRunning,
            configLabel = profile?.let { activeConfig.summary(it) } ?: "Config: not loaded",
            configEditor = appConfigEditor(profile, activeConfig, modelOptions),
            sessions = runner.decorateSessions(sessionCoordinator.sessions),
            activeSessionCount = runner.activeRunCount,
            maxActiveSessions = runner.maxActiveSessions,
            activeSessionId = sessionCoordinator.activeSessionId,
            inputHint = "Message",
            items = items,
            contextPanel = runner.contextPanelState,
            board = sessionCoordinator.board(),
            tokenUsage = runner.tokenUsage,
            runtimeDemo = runner.runtimeDemo,
            mcpProviders = mcpProviders,
            a2aClients = a2aState,
            workspaceSelection = workspaceSelection,
            currentRequest = runner.currentRequest?.withRuntimeLabels(providerLabel, streamLabel(), backendLabel(), status),
            // Derived from the transcript (not a live-run cache) so the preview survives
            // activity recreation, session switches, and runs not owned by the active UI.
            artifactPreview = latestHtmlArtifact(items),
            trajectory = trajectoryRepository?.snapshot(sessionCoordinator.activeSessionId)
                ?: TrajectorySnapshot(sessionId = sessionCoordinator.activeSessionId),
            runStartedAtEpochMillis = runner.runStartedAtEpochMillis,
            lastRunSummary = runner.lastRunSummary,
        )
    }
    private fun refreshModelOptions(context: Context) {
        config?.let { modelOptions.refresh(context, it.activeProfile, it) }
    }

    private fun forceLiveBackend() {
        useSimulatedModel = false
    }

    private fun streamLabel(): String = if (streamEnabled) "stream" else "non-stream"
    private fun backendLabel(): String = if (useSimulatedModel) SIMULATED_MODEL_LABEL else LIVE_MODEL_LABEL
}

private fun String.isLocalUrl(): Boolean {
    val lower = trim().lowercase()
    return lower.startsWith("http://localhost") || lower.startsWith("http://127.0.0.1")
}

private const val DEFAULT_TEST_API_KEY = "local"
private const val DEFAULT_HOSTED_SEARCH_VALIDATION_PROMPT =
    "Use hosted web search. Find the DeepSeek API Docs page about Claude Code integration. Return the page title and one cited URL."

/** A conversation with subagents still working, or results not delivered yet, is kept so the results land in it. */
internal fun wakeStartsNewSession(
    active: SessionSummary?,
    nowMillis: Long,
    backgroundWork: Boolean = false,
    policy: SessionPolicy = SessionSettings.policy.value,
): Boolean =
    active != null && active.turnCount > 0 && !backgroundWork &&
        (policy.everyWake || nowMillis - active.updatedAtEpochMillis >= policy.idleMillis)

private const val SUBAGENT_REPORT_TITLE = "后台任务"
private const val SUBAGENT_DETAIL_CHARS = 600
