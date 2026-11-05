@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime
import android.content.Context
import cc.noharry.agent.androiduse.AndroidUseAccessibility
import cc.noharry.agent.androiduse.AndroidUseOptions
import cc.noharry.agent.androiduse.AndroidUseToolProvider
import cc.noharry.agent.appserver.InternalAppServerApi
import cc.noharry.agent.appserver.a2a.A2aBridgeProvider
import cc.noharry.agent.appserver.a2a.A2aModuleConfig
import cc.noharry.agent.appserver.a2a.A2aNamingGate
import cc.noharry.agent.appserver.a2ui.BindableTools
import cc.noharry.agent.appserver.a2ui.BoundCallHints
import cc.noharry.agent.appserver.a2ui.OfferToolProvider
import cc.noharry.agent.appserver.a2ui.cardFinisher
import cc.noharry.agent.appserver.androiduse.AppAndroidUsePresenter
import cc.noharry.agent.appserver.androiduse.androidUseReady
import cc.noharry.agent.appserver.evidence.AppDiagnostics
import cc.noharry.agent.appserver.evidence.benchEvidenceConfig
import cc.noharry.agent.appserver.image.ImageToolProvider
import cc.noharry.agent.appserver.image.loadImageModelConfig
import cc.noharry.agent.appserver.mcp.DEBUG_MCP_PROVIDER_DEVICE_CALENDAR
import cc.noharry.agent.appserver.mcp.DEBUG_MCP_PROVIDER_DEVICE_MEDIA
import cc.noharry.agent.appserver.mcp.DEBUG_MCP_PROVIDER_DEVICE_NOTIFICATION
import cc.noharry.agent.appserver.mcp.DEBUG_MCP_PROVIDER_TOOLS_JSON
import cc.noharry.agent.appserver.mcp.McpProgressiveEvidenceRecorder
import cc.noharry.agent.appserver.mcp.ToolsJsonMcpProvider
import cc.noharry.agent.appserver.mcp.httpMcpToolProvider
import cc.noharry.agent.appserver.mcp.isProviderEnabled
import cc.noharry.agent.appserver.memory.UserMemoryContext
import cc.noharry.agent.appserver.memory.UserMemoryToolProvider
import cc.noharry.agent.appserver.memory.userMemoryEnabled
import cc.noharry.agent.appserver.model.AppModelClient
import cc.noharry.agent.appserver.model.DeepSeekProbes
import cc.noharry.agent.appserver.model.FIRST_EVENT_TIMEOUT_MS
import cc.noharry.agent.appserver.model.FixtureModelClient
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.model.appWebFetchFallback
import cc.noharry.agent.appserver.model.appWebSearch
import cc.noharry.agent.appserver.model.defaultContextWindow
import cc.noharry.agent.appserver.model.hasDeepSeekKey
import cc.noharry.agent.appserver.model.loadContextWindow
import cc.noharry.agent.appserver.model.loadModelImageInput
import cc.noharry.agent.appserver.pages.PageToolProvider
import cc.noharry.agent.appserver.plans.PlanContext
import cc.noharry.agent.appserver.plans.PlanToolProvider
import cc.noharry.agent.appserver.protocol.McpProviderState
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.server.AppScreen
import cc.noharry.agent.appserver.skills.AppSubagents
import cc.noharry.agent.appserver.skills.SkillToolProvider
import cc.noharry.agent.appserver.skills.SubagentKind
import cc.noharry.agent.appserver.skills.WEB_VERIFY_KIND
import cc.noharry.agent.appserver.skills.WorkflowSkill
import cc.noharry.agent.appserver.skills.loadRuntimeSkills
import cc.noharry.agent.appserver.skills.loadSubagentSettings
import cc.noharry.agent.appserver.skills.preloadedSkillBodies
import cc.noharry.agent.appserver.skills.selectedWorkflowSkills
import cc.noharry.agent.appserver.skills.systemPromptIds
import cc.noharry.agent.appserver.skills.withSubagentSwitch
import cc.noharry.agent.appserver.tools.ActionGate
import cc.noharry.agent.appserver.tools.MediaToolHints
import cc.noharry.agent.appserver.tools.WebVerifyGate
import cc.noharry.agent.device.DeviceCalendarMcpProvider
import cc.noharry.agent.device.DeviceMediaMcpProvider
import cc.noharry.agent.device.DeviceNotificationMcpProvider
import cc.noharry.agent.exec.js.ZiplineExecEngine
import cc.noharry.agent.kernel.api.AgentRuntime
import cc.noharry.agent.kernel.api.RuntimeHook
import cc.noharry.agent.kernel.api.ToolCatalogCacheIdentity
import cc.noharry.agent.kernel.api.ToolProvider
import cc.noharry.agent.kernel.api.model.ServerSideSearchCapability
import cc.noharry.agent.runtime.AgentRuntimeBuilder
import cc.noharry.agent.runtime.NetworkToolConfirmationPolicy
import cc.noharry.agent.runtime.SessionHistory
import cc.noharry.agent.runtime.SessionToolActivations
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
 *   [appSessionHistory]) and the run context no longer restates them. Blank keeps each run on its own.
 */
/**
 * One per process. The Sample builds a runtime per run, so the tools a session's runs found are kept here, and the next
 * run of that session starts with them: the model calls them without searching again, and the tool list stays the same
 * from run to run, so the provider's prompt cache over it holds.
 */
private val appSessionToolActivations = SessionToolActivations(maxToolsPerSession = 64)

/**
 * One per process, like the tools: each session's earlier runs as the model saw them. A run's requests start with them
 * and the run is appended when it ends, so the conversation grows like one chat and the prompt cache covers it.
 * Compaction at 80% of the context window keeps it short; the default byte cap only guards memory.
 */
private val appSessionHistory = SessionHistory()

/** When a session is deleted; with no session id, when all runtime data is cleared. */
internal fun forgetSessionContinuity(sessionId: String? = null) {
    AppSubagents.forget(sessionId)
    if (sessionId == null) {
        appSessionToolActivations.clearAll()
        appSessionHistory.clearAll()
    } else {
        appSessionToolActivations.clearSession(sessionId)
        appSessionHistory.clearSession(sessionId)
    }
}

@InternalAppServerApi
fun buildRuntime(
    context: Context,
    config: ProviderConfig,
    requestedProfile: ProviderProfile,
    streamEnabled: Boolean,
    useSimulatedModel: Boolean,
    workspaceRoot: File,
    sessionEventLogRoot: File,
    sessionContext: String,
    evidenceRecorder: McpProgressiveEvidenceRecorder?,
    diagnostics: AppDiagnostics,
    mcpProviders: List<McpProviderState>,
    a2aBridgeProviders: List<A2aBridgeProvider> = emptyList(),
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
    /** A typed child's kind: its own search setting ([SubagentKind.searchApi]) over the subagent setting. */
    subagentKind: SubagentKind? = null,
    previousExchange: PreviousExchange? = null,
    sessionId: String = "",
): AgentRuntime {
    // DeepSeek's Search API in place of the provider's server-side search, when the profile (or, for a subagent, the
    // subagent settings) say so.
    val childSearchApi = subagentChild && (subagentKind?.searchApi ?: loadSubagentSettings(context).searchApi)
    // A type that needs DeepSeek's lookups (网上核实) is offered only while some profile has them.
    if (!subagentChild) AppSubagents.refreshTypes(context, config.hasDeepSeekKey(context))
    // A type without 联网搜索 gets neither web_search nor the provider's own search (the guard cannot stop the latter).
    val searches = subagentKind?.searches ?: true
    val webSearch = if (searches) appWebSearch(context, config, requestedProfile, forceApi = childSearchApi) else null
    // DeepSeek's Fetch API for pages that refuse the device: its own switch, for the main agent and subagents alike.
    val fetchFallback = appWebFetchFallback(context, config, requestedProfile)
    val profile = if (webSearch != null || !searches) requestedProfile.copy(serverSideSearch = false) else requestedProfile
    val basicTools = loadBasicTools(context).withSubagentSwitch(loadSubagentSettings(context).enabled)
    val skills = loadRuntimeSkills(context).filterNot { subagentChild && it.mainAgentOnly }
    val workflows = selectedWorkflowSkills(context, skills, userInput)
    val carried = if (sessionId.isBlank()) emptyList() else appSessionHistory.messages(sessionId)
    val deepSeekNative = config.usesDeepSeekNativeUpdates(context, profile)
    // The earlier runs already hold the conversation and the skills they loaded; a new session (or one from before a
    // restart) still gets the recap and the previous exchange.
    // The time, the app in front and where delegations stand, all per run (the run context, never the cached system prompt).
    val drawPages = !subagentChild && pagesOffered(profile)
    val clock = (listOf(clockLine(System.currentTimeMillis()), appForegroundLine(context)) + AppHost.runs.contextLines(context) +
        a2aBridgeProviders.map(A2aBridgeProvider::runNote) + listOfNotNull(PAGES_RULE.takeIf { drawPages }))
        .filter(String::isNotBlank).joinToString("\n")
    val verifyAgent = !subagentChild && AppSubagents.offers(WEB_VERIFY_KIND) && basicTools.any { it.name == "spawn_agent" && it.enabled }
    val mainSearch = !subagentChild && (webSearch != null || profile.usesVisibleWebSearchTool())
    val systemSkills = skills.systemPromptIds()
    val prompt = if (carried.isEmpty()) {
        runtimePrompt(
            sessionContext, basicTools, preloadedSkillBodies(context, skills, userInput), previousExchange, deepSeekNative, clock,
            verifyAgent, mainSearch, systemSkills,
        )
    } else {
        val bodies = preloadedSkillBodies(context, skills, userInput).filterNot { (id, _) -> carried.carriesSkill(id) }
        runtimePrompt(
            "", basicTools, bodies, nativeTools = deepSeekNative, clock = clock, verifyAgent = verifyAgent, webSearch = mainSearch,
            systemSkills = systemSkills,
        )
    }
    val wrapTools = AppHost.runs.toolWrapper(context, sessionContext, useSimulatedModel)
    val imageInput = loadModelImageInput(context, config.editState(profile))
    return runtimeAssembly(
    model =
        AppModelClient(
            delegate = if (useSimulatedModel) {
                FixtureModelClient()
            } else {
                config.client(profile, streamEnabled, diagnostics)
            },
            // Only a stream sends its first event early; a whole reply can take longer than the watchdog.
            // The on-device server may still be loading the model when the first call goes out.
            firstEventMillis = (AppHost.models.firstEventTimeoutMs(profile) ?: FIRST_EVENT_TIMEOUT_MS)
                .takeIf { streamEnabled && !useSimulatedModel },
            onModelRequestTools = evidenceRecorder?.let { recorder ->
                { names -> recorder.recordFirstRoundTools(names) }
            },
            onPromptPrefix = diagnostics::recordPromptPrefix,
        ),
    prompt = prompt.system,
    workspace = workspaceVfs(context, workspaceRoot, skills),
    eventLogRoot = sessionEventLogRoot,
    )
    // Install host instructions before NativeRoundRunner builds and records ModelRequest.
    // AppModelClient must not enrich after SessionEventLog capture or Raw would diverge
    // from the provider-visible request.
    .applyMcpProviders(context, mcpProviders, wrapTools, workflows)
    .applyA2aBridgeProviders(a2aBridgeProviders, userInput)
    .applyAndroidUse(context, imageInput)
    .applyPages(context, offered = drawPages)
    .applyPlans(context)
    .applyUserMemory(context)
    // Semantic VFS stays the workspace; the EventLog gets its own no-backup root so the
    // sample keeps a durable lossless v2 stream instead of falling back to in-memory.
    // Hide web_fetch only when the provider exposes a model-visible web_search server tool and no fetch fallback is on
    // (with one, the main agent opens pages that refuse the device, as subagents do).
    // OpenAI chat-options search is request-side and opaque, so web_fetch remains the fallback.
    .contextWindow(loadContextWindow(context, config.editState(profile)) ?: defaultContextWindow(profile.model))
    .imageInput(imageInput)
    .nativeWebFetchEnabled(subagentChild || fetchFallback != null || !profile.usesVisibleWebSearchTool())
    .webSearch(webSearch)
    .webFetchFallback(fetchFallback)
    .disabledNativeTools(disabledBasicTools(basicTools))
    .networkConfirmationPolicy(networkConfirmationPolicy)
    .sessionToolActivations(appSessionToolActivations)
    // Backend tools through call_tool: the tool list stays the same all session, so the history stays cached. DeepSeek's
    // official endpoint loads deferred tools natively instead (schema-checked calls) and takes the context as a system
    // update; gateways drop those fields (measured 2026-10-17), so they keep call_tool and a user-role context unless a
    // check found the relay passing them on (the profile's check).
    .deferredBackendTools()
    .apply { if (deepSeekNative) nativeDeferredTools().runContextAsSystem() }
    .runContext(prompt.runContext)
    .apply { if (sessionId.isNotBlank()) sessionHistory(appSessionHistory) }
    // A child's runtime gets the same instance, so its tool list matches its parent's; depth 1 refuses its spawns.
    .subagents(AppSubagents.instance)
    .hook(RawToolTranscriptHook(onRawToolTranscript))
    .apply {
        hostHook?.let(::hook)
        if (verifyAgent) hook(WebVerifyGate(userInput))
        // Orders and payments wait for the user's yes (ActionGate), read in the user's own words, never in a
        // quoted reply or a child's task.
        hook(ActionGate(userInput, child = subagentChild))
        if (basicTools.any { it.name == "exec_js" && it.enabled }) execEngine(ZiplineExecEngine())
        // Production entry: only when host explicitly sets benchCaseBound=true.
        // Hash [userInput] as-is (same bytes as AgentRequest.input) — no trim/normalize.
        if (benchCaseBound) {
            runtimeEvidence(benchEvidenceConfig(context, userInput))
        }
    }
    .build()
}

/** DeepSeek's own Anthropic endpoint: system updates and `tool_addition` in history, `defer_loading` declarations. */
internal fun ProviderProfile.usesDeepSeekNativeUpdates(): Boolean =
    endpoint == ProviderEndpoint.MESSAGES && runCatching { java.net.URI(baseUrl).host }.getOrNull() == "api.deepseek.com"

/** The same, or a Messages relay whose check ([DeepSeekProbes]) found it passing DeepSeek's fields on unchanged. */
internal fun ProviderConfig.usesDeepSeekNativeUpdates(context: Context, profile: ProviderProfile): Boolean =
    profile.usesDeepSeekNativeUpdates() || profile.endpoint == ProviderEndpoint.MESSAGES &&
        DeepSeekProbes.current(context, profile, effectiveApiKey(profile))?.passthrough?.passed == true

@InternalAppServerApi
fun ProviderProfile.usesVisibleWebSearchTool(): Boolean {
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

private fun AgentRuntimeBuilder.applyMcpProviders(
    context: Context,
    providers: List<McpProviderState>,
    configure: (ToolProvider) -> ToolProvider,
    workflows: List<WorkflowSkill>,
): AgentRuntimeBuilder = apply {
    val mounted = mutableListOf<ToolProvider>()
    // Outermost, so a picked option's tool is offered first; a cache-identified catalog keeps its identity.
    val mount: (ToolProvider) -> Unit = { provider ->
        mounted += provider
        toolProvider(if (provider is ToolCatalogCacheIdentity) provider else BoundCallHints(provider))
    }
    // The bundled example tools (a demo car); the host may hold them back ([RunAddOns.exampleToolsAllowed]).
    if (providers.isProviderEnabled(DEBUG_MCP_PROVIDER_TOOLS_JSON) && AppHost.runs.exampleToolsAllowed()) {
        mount(configure(ToolsJsonMcpProvider.fromAssets(context)))
    }
    // The configured MCP servers, in their order; the workflow skills ride on the tools they bind.
    providers.filter { it.enabled }.mapNotNull { it.server }.forEach { server ->
        val provider = configure(httpMcpToolProvider(context, server))
        mount(if (workflows.isEmpty()) provider else SkillToolProvider(provider, workflows))
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
        mount(MediaToolHints(configure(media)))
    }
    if (mounted.isNotEmpty()) {
        toolProvider(
            OfferToolProvider(
                BindableTools(mounted),
                finishCard = cardFinisher(context),
                cloudAssistant = A2aModuleConfig.hasEnabledProfiles(context),
            ),
        )
    }
    // The image model is configured in its own settings entry; its switch, not the MCP list, mounts the tool.
    if (loadImageModelConfig(context).ready) {
        toolProvider(ImageToolProvider(context))
    }
    AppHost.runs.toolProviders(context).forEach(::toolProvider)
}

/**
 * 操作屏幕: mounted only while its switch is on and the accessibility service is bound, so the model never sees
 * tools that cannot work. A 仅文本 model gets elements without screenshots.
 */
private fun AgentRuntimeBuilder.applyAndroidUse(context: Context, imageInput: Boolean): AgentRuntimeBuilder = apply {
    if (androidUseReady(context)) {
        toolProvider(AndroidUseToolProvider(context, AppAndroidUsePresenter(), AndroidUseOptions(screenshots = imageInput)))
    }
}

/**
 * When to draw (owner 2026-11-05: 长段的东西生成式 UI 应该更积极): a rule of the run while pages are offered, since in
 * the tool's description alone the model kept a 978-character analysis as text and offered a drawing at its end.
 */
private const val PAGES_RULE =
    "用户正看着对话页，可以画界面（show_html）：回答会超过三四百字，或有三个以上并列的要点、层次、步骤，或是比较、" +
        "数字、时间线、行程、计划、简报、分析时，先用一句话说结论，再把内容画成页面，finish 只补一两句；不要长篇只用文字，" +
        "也不要问要不要画。一两句能答的、闲聊、要复制的纯文本照常用文字。"

/** 画界面 ([PageToolProvider]), when [offered]. */
private fun AgentRuntimeBuilder.applyPages(context: Context, offered: Boolean): AgentRuntimeBuilder = apply {
    if (offered) toolProvider(PageToolProvider(context))
}

/**
 * Whether a run may draw pages: only while the app's own screens are in front ([AppScreen]), the one place a page
 * shows (the overlay and a head unit's surface draw cards, not pages), and with a service's model, as the host's own
 * on-device models write no page that works.
 */
internal fun pagesOffered(profile: ProviderProfile): Boolean = AppScreen.inFront && !AppHost.models.owns(profile.id)

/** 计划: the tool, and today's date with the plans going; registered before 记忆, so its facts always fit. */
private fun AgentRuntimeBuilder.applyPlans(context: Context): AgentRuntimeBuilder = apply {
    toolProvider(PlanToolProvider(context))
    contextProvider(PlanContext(context))
}

/** Memory: the tool and what it remembers before each round, both only while the memory switch is on. */
private fun AgentRuntimeBuilder.applyUserMemory(context: Context): AgentRuntimeBuilder = apply {
    if (userMemoryEnabled(context)) {
        toolProvider(UserMemoryToolProvider(context))
        contextProvider(UserMemoryContext(context))
    }
}

// Experimental A2A integration, not MCP: each enabled A2A profile mounts one bridge
// provider that only reuses the McpClientProvider host-tool injection SPI to expose
// its a2a_message_send tool. Toggling a profile takes effect on the next run, the
// same lifecycle as the MCP providers above. A send goes out only when [userInput] names the agent.
private fun AgentRuntimeBuilder.applyA2aBridgeProviders(
    providers: List<A2aBridgeProvider>,
    userInput: String,
): AgentRuntimeBuilder = apply {
    providers.forEach(::toolProvider)
    if (providers.isNotEmpty()) hook(A2aNamingGate(userInput, providers.map { it.agentLabel }))
}

