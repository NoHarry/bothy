@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import android.content.Context
import cc.noharry.agent.androiduse.AndroidUseOptions
import cc.noharry.agent.androiduse.AndroidUseToolProvider
import cc.noharry.agent.androiduse.AndroidUseAccessibility
import cc.noharry.agent.device.DeviceCalendarMcpProvider
import cc.noharry.agent.device.DeviceMediaMcpProvider
import cc.noharry.agent.device.DeviceNotificationMcpProvider
import cc.noharry.agent.kernel.api.model.ServerSideSearchCapability
import cc.noharry.agent.kernel.api.AgentRuntime
import cc.noharry.agent.kernel.api.ToolCatalogCacheIdentity
import cc.noharry.agent.kernel.api.ToolProvider
import cc.noharry.agent.kernel.api.RuntimeHook
import cc.noharry.agent.runtime.AgentRuntimeBuilder
import cc.noharry.agent.runtime.NetworkToolConfirmationPolicy
import cc.noharry.agent.runtime.SessionHistory
import cc.noharry.agent.runtime.SessionToolActivations
import cc.noharry.agent.exec.js.ZiplineExecEngine
import java.io.File

/**
 * @param sessionEventLogRoot app-private no-backup root for this session's durable EventLog.
 *   This is the lowest-priority EventLog source, so the sample must not also install a
 *   packaged or custom `SESSION_EVENT_LOG` capability; doing so silently wins over the root.
 * @param userInput text that will be sent as [cc.noharry.agent.kernel.api.AgentRequest.input].
 * @param benchCaseBound production flag; default **false**. When **true**, enables experimental
 *   [runtimeEvidence] for sample.debug.v1 using a hash of **[userInput]** (must be the bench
 *   prompt). Ordinary runs leave this false and do not call [runtimeEvidence].
 * @param sessionId the production session; when set, the run carries the session's earlier runs (see
 *   [debugSessionHistory]) and the run context no longer restates them. Blank keeps each run on its own.
 */
/**
 * One per process. The Sample builds a runtime per run, so the tools a session's runs found are kept here, and the next
 * run of that session starts with them: the model calls them without searching again, and the tool list stays the same
 * from run to run, so the provider's prompt cache over it holds.
 */
private val debugSessionToolActivations = SessionToolActivations(maxToolsPerSession = 64)

/**
 * One per process, like the tools: each session's earlier runs as the model saw them. A run's requests start with them
 * and the run is appended when it ends, so the conversation grows like one chat and the prompt cache covers it.
 * Compaction at 80% of the context window keeps it short; the default byte cap only guards memory.
 */
private val debugSessionHistory = SessionHistory()

/** When a session is deleted; with no session id, when all runtime data is cleared. */
internal fun forgetDebugSessionContinuity(sessionId: String? = null) {
    DebugSubagents.forget(sessionId)
    if (sessionId == null) {
        debugSessionToolActivations.clearAll()
        debugSessionHistory.clearAll()
    } else {
        debugSessionToolActivations.clearSession(sessionId)
        debugSessionHistory.clearSession(sessionId)
    }
}

internal fun buildDebugRuntime(
    context: Context,
    config: DebugProviderConfig,
    requestedProfile: DebugProviderProfile,
    streamEnabled: Boolean,
    useSimulatedModel: Boolean,
    workspaceRoot: File,
    sessionEventLogRoot: File,
    sessionContext: String,
    evidenceRecorder: McpProgressiveEvidenceRecorder?,
    diagnostics: DebugDiagnostics,
    mcpProviders: List<DebugMcpProviderState>,
    a2aBridgeProviders: List<DebugA2aBridgeProvider> = emptyList(),
    onRawToolTranscript: (String, String) -> Unit,
    networkConfirmationPolicy: NetworkToolConfirmationPolicy = NetworkToolConfirmationPolicy.REQUIRE_PER_HOST,
    userInput: String = "",
    benchCaseBound: Boolean = false,
    hostHook: RuntimeHook? = null,
    /**
     * A subagent's runtime: it opens pages for their pictures even when hosted search hides web_fetch from the main
     * agent, and leaves out the main agent's workflows (a child given a trip task tried to spawn three of its own).
     */
    subagentChild: Boolean = false,
    /** A typed child's kind: its own search setting ([DebugSubagentKind.searchApi]) over the subagent setting. */
    subagentKind: DebugSubagentKind? = null,
    previousExchange: DebugPreviousExchange? = null,
    sessionId: String = "",
): AgentRuntime {
    // DeepSeek's Search API in place of the provider's server-side search, when the profile (or, for a subagent, the
    // subagent settings) say so.
    val childSearchApi = subagentChild && (subagentKind?.searchApi ?: loadDebugSubagentSettings(context).searchApi)
    // A type that needs DeepSeek's lookups (网上核实) is offered only while some profile has them.
    if (!subagentChild) DebugSubagents.refreshTypes(context, config.hasDeepSeekKey(context))
    // A type without 联网搜索 gets neither web_search nor the provider's own search (the guard cannot stop the latter).
    val searches = subagentKind?.searches ?: true
    val webSearch = if (searches) debugWebSearch(context, config, requestedProfile, forceApi = childSearchApi) else null
    // DeepSeek's Fetch API for pages that refuse the device: its own switch, for the main agent and subagents alike.
    val fetchFallback = debugWebFetchFallback(context, config, requestedProfile)
    val profile = if (webSearch != null || !searches) requestedProfile.copy(serverSideSearch = false) else requestedProfile
    val basicTools = loadDebugBasicTools(context).withSubagentSwitch(loadDebugSubagentSettings(context).enabled)
    val skills = loadDebugRuntimeSkills(context).filterNot { subagentChild && it.mainAgentOnly }
    val workflows = selectedWorkflowSkills(context, skills, userInput)
    val carried = if (sessionId.isBlank()) emptyList() else debugSessionHistory.messages(sessionId)
    val deepSeekNative = config.usesDeepSeekNativeUpdates(context, profile)
    // The earlier runs already hold the conversation and the skills they loaded; a new session (or one from before a
    // restart) still gets the recap and the previous exchange.
    // The time and the app in front, both per run (the run context, never the cached system prompt).
    val clock = listOf(debugClockLine(System.currentTimeMillis()), debugForegroundLine(context), DebugPlaygroundMode.line())
        .filter(String::isNotBlank).joinToString("\n")
    val verifyAgent = !subagentChild && DebugSubagents.offers(WEB_VERIFY_KIND) && basicTools.any { it.name == "spawn_agent" && it.enabled }
    val mainSearch = !subagentChild && (webSearch != null || profile.usesVisibleWebSearchTool())
    val prompt = if (carried.isEmpty()) {
        debugRuntimePrompt(sessionContext, basicTools, debugPreloadedSkillBodies(context, skills, userInput), previousExchange, deepSeekNative, clock, verifyAgent, mainSearch)
    } else {
        val bodies = debugPreloadedSkillBodies(context, skills, userInput).filterNot { (id, _) -> carried.carriesSkill(id) }
        debugRuntimePrompt("", basicTools, bodies, nativeTools = deepSeekNative, clock = clock, verifyAgent = verifyAgent, webSearch = mainSearch)
    }
    val jev = loadDebugJevConfig(context).let { if (useSimulatedModel) it.copy(enabled = false) else it }
    val imageInput = loadDebugModelImageInput(context, config.editState(profile))
    return sampleRuntimeAssembly(
    model =
        DebugModelClient(
            delegate = if (useSimulatedModel) {
                DebugFixtureModelClient()
            } else {
                config.client(profile, streamEnabled, diagnostics)
            },
            // Only a stream sends its first event early; a whole reply can take longer than the watchdog.
            firstEventMillis = FIRST_EVENT_TIMEOUT_MS.takeIf { streamEnabled && !useSimulatedModel },
            onModelRequestTools = evidenceRecorder?.let { recorder ->
                { names -> recorder.recordFirstRoundTools(names) }
            },
            onPromptPrefix = diagnostics::recordPromptPrefix,
        ),
    prompt = prompt.system,
    workspace = debugWorkspaceVfs(context, workspaceRoot, skills),
    eventLogRoot = sessionEventLogRoot,
    )
    // Install host instructions before NativeRoundRunner builds and records ModelRequest.
    // DebugModelClient must not enrich after SessionEventLog capture or Raw would diverge
    // from the provider-visible request.
    .applyDebugMcpProviders(context, mcpProviders, jev, sessionContext, workflows)
    .applyDebugA2aBridgeProviders(a2aBridgeProviders, userInput)
    .applyDebugAndroidUse(context, imageInput)
    .applyDebugPlans(context)
    .applyDebugUserMemory(context)
    // Semantic VFS stays the workspace; the EventLog gets its own no-backup root so the
    // sample keeps a durable lossless v2 stream instead of falling back to in-memory.
    // Hide web_fetch only when the provider exposes a model-visible web_search server tool and no fetch fallback is on
    // (with one, the main agent opens pages that refuse the device, as subagents do).
    // OpenAI chat-options search is request-side and opaque, so web_fetch remains the fallback.
    .contextWindow(loadDebugContextWindow(context, config.editState(profile)) ?: defaultDebugContextWindow(profile.model))
    .imageInput(imageInput)
    .nativeWebFetchEnabled(subagentChild || fetchFallback != null || !profile.usesVisibleWebSearchTool())
    .webSearch(webSearch)
    .webFetchFallback(fetchFallback)
    .disabledNativeTools(disabledDebugBasicTools(basicTools))
    .networkConfirmationPolicy(networkConfirmationPolicy)
    .sessionToolActivations(debugSessionToolActivations)
    // Backend tools through call_tool: the tool list stays the same all session, so the history stays cached. DeepSeek's
    // official endpoint loads deferred tools natively instead (schema-checked calls) and takes the context as a system
    // update; gateways drop those fields (measured 2026-10-17), so they keep call_tool and a user-role context unless a
    // check found the relay passing them on (Settings → the profile → 检测).
    .deferredBackendTools()
    .apply { if (deepSeekNative) nativeDeferredTools().runContextAsSystem() }
    .runContext(prompt.runContext)
    .apply { if (sessionId.isNotBlank()) sessionHistory(debugSessionHistory) }
    // A child's runtime gets the same instance, so its tool list matches its parent's; depth 1 refuses its spawns.
    .subagents(DebugSubagents.instance)
    .hook(DebugRawToolTranscriptHook(onRawToolTranscript))
    .apply {
        hostHook?.let(::hook)
        if (verifyAgent) hook(DebugWebVerifyGate(userInput))
        // Orders and payments wait for the user's yes (DebugActionGate), read in the user's own words, never in a
        // quoted reply or a child's task.
        hook(DebugActionGate(userInput, child = subagentChild))
        if (basicTools.any { it.name == "exec_js" && it.enabled }) execEngine(ZiplineExecEngine())
        // Production entry: only when host explicitly sets benchCaseBound=true.
        // Hash [userInput] as-is (same bytes as AgentRequest.input) — no trim/normalize.
        if (benchCaseBound) {
            runtimeEvidence(sampleDebugBenchEvidenceConfig(context, userInput))
        }
    }
    .build()
}

/** DeepSeek's own Anthropic endpoint: system updates and `tool_addition` in history, `defer_loading` declarations. */
internal fun DebugProviderProfile.usesDeepSeekNativeUpdates(): Boolean =
    endpoint == DebugProviderEndpoint.MESSAGES && runCatching { java.net.URI(baseUrl).host }.getOrNull() == "api.deepseek.com"

/** The same, or a Messages relay whose check ([DebugDeepSeekProbes]) found it passing DeepSeek's fields on unchanged. */
internal fun DebugProviderConfig.usesDeepSeekNativeUpdates(context: Context, profile: DebugProviderProfile): Boolean =
    profile.usesDeepSeekNativeUpdates() || profile.endpoint == DebugProviderEndpoint.MESSAGES &&
        DebugDeepSeekProbes.current(context, profile, effectiveApiKey(profile))?.passthrough?.passed == true

internal fun DebugProviderProfile.usesVisibleWebSearchTool(): Boolean {
    if (!serverSideSearch) return false
    return when (ServerSideSearchCapability.fromWireName(searchCapability)) {
        ServerSideSearchCapability.ANTHROPIC_SERVER_TOOL,
        ServerSideSearchCapability.OPENAI_RESPONSES_TOOL,
        -> true
        ServerSideSearchCapability.OPENAI_CHAT_OPTIONS,
        ServerSideSearchCapability.NONE,
        -> false
    }
}

private fun AgentRuntimeBuilder.applyDebugMcpProviders(
    context: Context,
    providers: List<DebugMcpProviderState>,
    jev: DebugJevConfig,
    sessionContext: String,
    workflows: List<DebugWorkflowSkill>,
): AgentRuntimeBuilder = apply {
    val key = if (jev.enabled) loadDebugJevKey(context) else ""
    val configure: (ToolProvider) -> ToolProvider = { provider ->
        if (jev.enabled && jev.validationError() == null && key.isNotBlank()) {
            DebugJevPrefetchProvider(provider, jev, key, sessionContext)
        } else {
            provider
        }
    }
    val mounted = mutableListOf<ToolProvider>()
    // Outermost, so a picked option's tool is offered first; a cache-identified catalog keeps its identity.
    val mount: (ToolProvider) -> Unit = { provider ->
        mounted += provider
        toolProvider(if (provider is ToolCatalogCacheIdentity) provider else DebugBoundCallHints(provider))
    }
    // The demo car's tools; with the Playground in real navigation there is no car to control ([DebugPlaygroundMode]).
    if (providers.isProviderEnabled(DEBUG_MCP_PROVIDER_TOOLS_JSON) && !DebugPlaygroundMode.real) {
        mount(configure(ToolsJsonMcpProvider.fromAssets(context)))
    }
    // The configured servers (设置 → 连接 → MCP), in their order; the workflow skills ride on the tools they bind.
    providers.filter { it.enabled }.mapNotNull { it.server }.forEach { server ->
        val provider = configure(httpMcpToolProvider(context, server))
        mount(if (workflows.isEmpty()) provider else DebugSkillToolProvider(provider, workflows))
    }
    if (providers.isProviderEnabled(DEBUG_MCP_PROVIDER_DEVICE_CALENDAR)) {
        mount(configure(DeviceCalendarMcpProvider(context)))
    }
    if (providers.isProviderEnabled(DEBUG_MCP_PROVIDER_DEVICE_NOTIFICATION)) {
        mount(configure(DeviceNotificationMcpProvider(context)))
    }
    if (providers.isProviderEnabled(DEBUG_MCP_PROVIDER_DEVICE_MEDIA)) {
        // The app in front, so 「暂停」 with nothing playing goes to what is on screen (a video app's session reads stopped).
        val media = DeviceMediaMcpProvider(context, foregroundPackage = { AndroidUseAccessibility.foregroundApp(context)?.packageName })
        mount(DebugMediaToolHints(configure(media)))
    }
    if (mounted.isNotEmpty()) {
        toolProvider(
            DebugOfferToolProvider(
                DebugBindableTools(mounted),
                finishCard = debugCardFinisher(context),
                cloudAssistant = DebugA2aModuleConfig.hasEnabledProfiles(context),
            ),
        )
    }
    // The image model is configured in its own settings entry; its switch, not the MCP list, mounts the tool.
    if (loadDebugImageModelConfig(context).ready) {
        toolProvider(DebugImageToolProvider(context))
    }
    // 「回到车机」: there is a head unit to go back to.
    if (playgroundInstalled(context)) toolProvider(DebugPlaygroundToolProvider(context))
}

/**
 * 操作屏幕: mounted only while its switch is on and the accessibility service is bound, so the model never sees
 * tools that cannot work. A 仅文本 model gets elements without screenshots.
 */
private fun AgentRuntimeBuilder.applyDebugAndroidUse(context: Context, imageInput: Boolean): AgentRuntimeBuilder = apply {
    if (debugAndroidUseReady(context)) {
        toolProvider(AndroidUseToolProvider(context, DebugAndroidUsePresenter(), AndroidUseOptions(screenshots = imageInput)))
    }
}

/** 计划: the tool, and today's date with the plans going; registered before 记忆, so its facts always fit. */
private fun AgentRuntimeBuilder.applyDebugPlans(context: Context): AgentRuntimeBuilder = apply {
    toolProvider(DebugPlanToolProvider(context))
    contextProvider(DebugPlanContext(context))
}

/** 记忆: the tool and what it remembers before each round, both only while 设置 → 记忆's switch is on. */
private fun AgentRuntimeBuilder.applyDebugUserMemory(context: Context): AgentRuntimeBuilder = apply {
    if (debugUserMemoryEnabled(context)) {
        toolProvider(DebugUserMemoryToolProvider(context))
        contextProvider(DebugUserMemoryContext(context))
    }
}

// Experimental A2A integration, not MCP: each enabled A2A profile mounts one bridge
// provider that only reuses the McpClientProvider host-tool injection SPI to expose
// its a2a_message_send tool. Toggling a profile takes effect on the next run, the
// same lifecycle as the MCP providers above. A send goes out only when [userInput] names the agent.
private fun AgentRuntimeBuilder.applyDebugA2aBridgeProviders(
    providers: List<DebugA2aBridgeProvider>,
    userInput: String,
): AgentRuntimeBuilder = apply {
    providers.forEach(::toolProvider)
    if (providers.isNotEmpty()) hook(DebugA2aNamingGate(userInput, providers.map { it.agentLabel }))
}

