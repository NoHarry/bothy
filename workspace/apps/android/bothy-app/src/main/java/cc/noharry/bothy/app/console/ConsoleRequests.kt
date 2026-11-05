package cc.noharry.bothy.app.console

import android.content.res.Resources
import cc.noharry.agent.appserver.protocol.A2aClientAnswerParams
import cc.noharry.agent.appserver.protocol.A2aClientTextParams
import cc.noharry.agent.appserver.protocol.A2aInputSource
import cc.noharry.agent.appserver.protocol.ApprovalDecision
import cc.noharry.agent.appserver.protocol.ApprovalRespondParams
import cc.noharry.agent.appserver.protocol.ClientRequests
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.DataClearParams
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.EnabledParams
import cc.noharry.agent.appserver.protocol.FeatureRequests
import cc.noharry.agent.appserver.protocol.FlagParams
import cc.noharry.agent.appserver.protocol.IdParams
import cc.noharry.agent.appserver.protocol.ModelSelectParams
import cc.noharry.agent.appserver.protocol.PreferenceRequests
import cc.noharry.agent.appserver.protocol.ProviderCreateParams
import cc.noharry.agent.appserver.protocol.ProviderFetchParams
import cc.noharry.agent.appserver.protocol.ProviderModelsParams
import cc.noharry.agent.appserver.protocol.ProviderSaveParams
import cc.noharry.agent.appserver.protocol.QuestionAnswerParams
import cc.noharry.agent.appserver.protocol.RunSettingsParams
import cc.noharry.agent.appserver.protocol.SettingsRequests
import cc.noharry.agent.appserver.protocol.TextParams
import cc.noharry.agent.appserver.protocol.ThreadIdParams
import cc.noharry.agent.appserver.protocol.TurnInterruptParams
import cc.noharry.agent.appserver.protocol.TurnStartParams
import cc.noharry.bothy.app.a2a.A2aClientActions
import cc.noharry.bothy.app.frontend.FrontEndClient
import cc.noharry.bothy.app.words
import cc.noharry.bothy.protocol.VoiceRequests

/**
 * The console's buttons (the console and the phone screens) as requests to the server over [server]. What only a
 * screen can do stays with it: the folder picker, the overlay and the HTML viewer.
 */
internal fun consoleActions(
    server: FrontEndClient,
    resources: Resources,
    isFolded: (ConversationItem) -> Boolean,
    pickSafWorkspace: () -> Unit,
    openOverlay: () -> Unit,
): ConsoleActions = ConsoleActions(
    submit = { text -> server.send(ClientRequests.TURN_START, TurnStartParams(text)) },
    steer = { text -> server.send(ClientRequests.TURN_STEER, TextParams(text)) },
    answerQuestion = { question, text -> server.send(SettingsRequests.QUESTION_ANSWER, QuestionAnswerParams(question, text)) },
    approveConfirmation = { id -> server.send(ClientRequests.APPROVAL_RESPOND, ApprovalRespondParams(id, ApprovalDecision.APPROVE)) },
    denyConfirmation = { id -> server.send(ClientRequests.APPROVAL_RESPOND, ApprovalRespondParams(id, ApprovalDecision.DENY)) },
    selectConfirmationMode = { mode -> server.send(SettingsRequests.RUN_SET, RunSettingsParams(confirmationMode = mode)) },
    stop = { server.send(ClientRequests.TURN_INTERRUPT, TurnInterruptParams()) },
    newSession = { server.send(ClientRequests.THREAD_START, Empty) },
    selectSession = { id -> server.send(ClientRequests.THREAD_SELECT, ThreadIdParams(id)) },
    deleteSession = { id -> server.send(ClientRequests.THREAD_DELETE, ThreadIdParams(id)) },
    clearRuntimeData = { server.send(SettingsRequests.DATA_CLEAR, DataClearParams()) },
    clearMemoriesAndRuntimeData = { done ->
        server.send(SettingsRequests.DATA_CLEAR, DataClearParams(alsoMemories = true), onRefused = { done(false) }) { done(it.cleared) }
    },
    selectProvider = { provider -> server.send(SettingsRequests.MODEL_SELECT, ModelSelectParams(provider = provider)) },
    selectProfile = { id -> server.send(SettingsRequests.MODEL_SELECT, ModelSelectParams(profileId = id)) },
    setManualProfileEnabled = { on -> server.send(SettingsRequests.MODEL_SELECT, ModelSelectParams(manualProfileEnabled = on)) },
    selectModel = { model -> server.send(SettingsRequests.MODEL_SELECT, ModelSelectParams(model = model)) },
    selectVariant = { variant -> server.send(SettingsRequests.MODEL_SELECT, ModelSelectParams(variant = variant)) },
    toggleStream = { server.send(SettingsRequests.RUN_SET, RunSettingsParams(toggleStream = true)) },
    selectBackend = { simulated -> server.send(SettingsRequests.RUN_SET, RunSettingsParams(simulatedBackend = simulated)) },
    reloadConfig = { server.send(SettingsRequests.CONFIG_RELOAD, Empty) },
    saveConfig = { provider, apiKey, model, baseUrl, endpoint ->
        server.send(SettingsRequests.PROVIDER_SAVE, ProviderSaveParams(provider, apiKey, model, baseUrl, endpoint))
    },
    saveNewProfile = { label, provider, endpoint, apiKey, model, baseUrl, saved ->
        server.send(SettingsRequests.PROVIDER_CREATE, ProviderCreateParams(label, provider, endpoint, apiKey, model, baseUrl)) { saved(it.profileId) }
    },
    saveProviderModels = { editor, models -> server.send(SettingsRequests.PROVIDER_SAVE_MODELS, ProviderModelsParams(editor, models)) },
    deleteProfile = { id -> server.send(SettingsRequests.PROVIDER_DELETE, IdParams(id)) },
    fetchModels = { profileId, provider, endpoint, apiKey, model, baseUrl, onModels ->
        server.send(
            SettingsRequests.PROVIDER_FETCH_MODELS,
            ProviderFetchParams(profileId, provider, endpoint, apiKey, model, baseUrl),
            onRefused = { onModels(Result.failure(it)) },
        ) { onModels(Result.success(it.models)) }
    },
    toggleItem = { item ->
        val folded = !isFolded(item)
        ItemFolds.set(server.state.activeSessionId, item.id, folded)
        server.client.setFolded(item.id, folded)
    },
    isFolded = isFolded,
    setMcpProviderEnabled = { id, enabled -> server.send(SettingsRequests.MCP_SET_ENABLED, EnabledParams(id, enabled)) },
    saveMcpServer = { draft, done -> server.send(SettingsRequests.MCP_SAVE, draft, onRefused = { done(it.message) }) { done(it.error?.let(resources::words)) } },
    deleteMcpServer = { id -> server.send(SettingsRequests.MCP_DELETE, IdParams(id)) },
    setA2aProfileEnabled = { id, enabled -> server.send(SettingsRequests.A2A_PROFILE_SET_ENABLED, EnabledParams(id, enabled)) },
    saveA2aProfile = { draft, done -> server.send(SettingsRequests.A2A_PROFILE_SAVE, draft, onRefused = { done(it.message) }) { done(it.error?.let(resources::words)) } },
    deleteA2aProfile = { id -> server.send(SettingsRequests.A2A_PROFILE_DELETE, IdParams(id)) },
    pickSafWorkspace = pickSafWorkspace,
    clearSafWorkspace = { server.send(SettingsRequests.WORKSPACE_CLEAR, Empty) },
    openOverlay = openOverlay,
    // Overridden by the Compose screen, which owns the viewer dialog state.
    openHtmlViewer = { _, _ -> },
    setPlanEnabled = { id, on -> server.send(FeatureRequests.PLAN_SET_ENABLED, EnabledParams(id, on)) },
    deletePlan = { id -> server.send(FeatureRequests.PLAN_DELETE, IdParams(id)) },
    setWakeWord = { on -> server.send(VoiceRequests.SET_WAKE_WORD, FlagParams(on)) },
    setSessionPolicy = { policy -> server.send(PreferenceRequests.SET_SESSION_POLICY, policy) },
    trajectory = TrajectoryActions(
        refresh = { server.send(SettingsRequests.TRAJECTORY_REFRESH, Empty) },
        loadOlderRuns = { server.send(SettingsRequests.TRAJECTORY_LOAD_OLDER, Empty) },
        loadRun = { runId -> server.send(SettingsRequests.TRAJECTORY_LOAD_RUN, IdParams(runId)) },
    ),
)

/** Client mode's buttons as requests: what is typed here is sent as typed on the console. */
internal fun consoleA2aActions(server: FrontEndClient, openOverlay: () -> Unit): A2aClientActions = A2aClientActions(
    setEnabled = { on -> server.send(FeatureRequests.A2A_CLIENT_SET_ENABLED, FlagParams(on)) },
    send = { text -> server.send(FeatureRequests.A2A_CLIENT_SEND, A2aClientTextParams(text, A2aInputSource.CONSOLE)) },
    steer = { text -> server.send(FeatureRequests.A2A_CLIENT_STEER, A2aClientTextParams(text, A2aInputSource.CONSOLE)) },
    answer = { id, text -> server.send(FeatureRequests.A2A_CLIENT_ANSWER, A2aClientAnswerParams(id, text, A2aInputSource.CONSOLE)) },
    cancel = { server.send(FeatureRequests.A2A_CLIENT_CANCEL, Empty) },
    newConversation = { server.send(FeatureRequests.A2A_CLIENT_NEW_CONVERSATION, Empty) },
    openOverlay = openOverlay,
    setSpeakExternal = { on -> server.send(FeatureRequests.A2A_CLIENT_SET_SPEAK_EXTERNAL, FlagParams(on)) },
)
