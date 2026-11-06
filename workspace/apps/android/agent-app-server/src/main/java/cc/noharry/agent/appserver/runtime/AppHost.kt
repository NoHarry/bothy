package cc.noharry.agent.appserver.runtime

import android.content.Context
import cc.noharry.agent.appserver.InternalAppServerApi
import cc.noharry.agent.appserver.a2a.A2aHost
import cc.noharry.agent.appserver.a2ui.CardHooks
import cc.noharry.agent.appserver.evidence.AppDiagnostics
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.skills.RuntimeSkill
import cc.noharry.agent.appserver.skills.SubagentKind
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.RuntimeHook
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolProvider
import cc.noharry.agent.kernel.api.model.ModelClient

/**
 * Models the host app runs itself (an on-device model), beside the providers': their profiles, whether one can run,
 * its client and its model list. An app without any keeps [NONE].
 */
interface HostModels {
    /** [profiles] with the host's own added. */
    fun withProfiles(context: Context, profiles: List<ProviderProfile>): List<ProviderProfile> = profiles

    /** Whether [profileId] names one of the host's own profiles. */
    fun owns(profileId: String): Boolean = false

    fun isReady(profile: ProviderProfile): Boolean = false

    fun client(profile: ProviderProfile, stream: Boolean, diagnostics: AppDiagnostics?): ModelClient =
        error("${profile.id} is not one of the host's models")

    /** What [profile] lacks before it can run, for 检查 and for the transcript. */
    fun missing(profile: ProviderProfile): Finding = error("${profile.id} is not one of the host's models")

    fun missingMessage(context: Context, profile: ProviderProfile): String = error("${profile.id} is not one of the host's models")

    /** The models [profileId] offers. */
    fun models(profileId: String): List<String> = emptyList()

    /** The context window [model] runs with when it is one of the host's (an on-device server's -c), else null. */
    fun contextTokens(model: String): Long? = null

    /** How long its first event may take (a local model may still be loading); null for the usual watchdog. */
    fun firstEventTimeoutMs(profile: ProviderProfile): Long? = null

    /** [profile] became the selected one. */
    fun selected(profile: ProviderProfile) = Unit

    /** Values the host saved itself, read with the providers' and winning over local.properties (its TTS settings). */
    fun savedValues(context: Context): Map<String, String> = emptyMap()

    companion object {
        val NONE: HostModels = object : HostModels {}
    }
}

/**
 * What the host adds to each run it builds: lines for the run's context, rules for its system prompt and gates on its
 * tool calls, a wrapper for every mounted tool provider (a tool search picking tools before the first model call), how
 * a remote tool reads, whether the bundled example tools may be mounted, tool providers, skills and subagent types of its
 * own, which of its servers work devices, what its cards get before they show, and what a finished run must have done.
 * An app without any keeps [NONE]: no rules, no gates, cards as the model drew them.
 */
interface RunAddOns {
    /** Lines for this run's context, which changes every run (never the cached system prompt). */
    fun contextLines(facts: RunFacts): List<String> = emptyList()

    /**
     * Rules for the system prompt beside the server's own, in the persona's language. The prompt is cached across runs,
     * so they may depend only on what stays the same from run to run ([RunFacts.webSearch], [RunFacts.subagentTypes]).
     */
    fun systemRules(facts: RunFacts): List<String> = emptyList()

    /** Gates on this run's tool calls (a yes before an order, say), after the server's own. */
    @ExperimentalAgentRuntimeApi
    fun runHooks(facts: RunFacts): List<RuntimeHook> = emptyList()

    /**
     * A tool as the model reads it, each time its list is read: a configured MCP server's, and the server's own plan tool
     * ([cc.noharry.agent.appserver.plans.PLAN_TOOL]); a host adds a note on what one does or when to offer it.
     */
    fun describeTool(tool: ToolDescriptor): ToolDescriptor = tool

    /** What show_card does with a card in this run: a check, what it gets before it shows, how the reply closes. */
    fun cards(context: Context): CardHooks = CardHooks.NONE

    /**
     * Whether [request] asks for something a service must do (navigate, play, set): a run that ends without any such tool
     * call fails its replay check (REPLAY_FAILED). None does by default.
     */
    fun requiresAction(request: String): Boolean = false

    /**
     * What the host finds wrong with a run's [answer] that passed otherwise; failures fail its replay. [request] is the run's
     * input as its evidence keeps it: what the model read (a short reply framed with what it answers), spaces collapsed,
     * at most 120 characters.
     */
    fun answerProblems(request: String, answer: String): AnswerProblems = AnswerProblems.NONE

    /** How each mounted tool provider is wrapped in a run with [sessionContext]; [simulated] for the fixture model. */
    fun toolWrapper(context: Context, sessionContext: String, simulated: Boolean): (ToolProvider) -> ToolProvider = { it }

    fun exampleToolsAllowed(): Boolean = true

    fun toolProviders(context: Context): List<ToolProvider> = emptyList()

    /** The skills the host ships (SKILL.md in its assets), named in [context]'s language; each can be switched off. */
    fun builtinSkills(context: Context): List<RuntimeSkill> = emptyList()

    /** The subagent types the host ships (AGENT.md in its assets), offered by spawn_agent. */
    fun subagentKinds(): List<SubagentKind> = emptyList()

    /**
     * The tool servers of the host's own that work things around the user (a car, a home), besides the screen, media and
     * example car tools every app has: a subagent only queries them unless the settings let it operate devices. Each is
     * the `<id>` of its tools' `mcp__<id>__<tool>`; a tool whose name has a query word (get, status, search, …) and no
     * action word (set, play, open, …) is a query. Read each time a subagent starts.
     */
    fun deviceServers(context: Context): Set<String> = emptySet()

    companion object {
        val NONE: RunAddOns = object : RunAddOns {}
    }
}

/**
 * What the server offers beyond the conversation, each off until the host turns it on: [plans] (plan_manage, timed
 * and watched plans and their alarms), [androidUse] (operating the screen through the accessibility service, which the
 * server lists itself at start while its switch is on), [deviceMedia] (playback and volume, on from the start), [cards]
 * (the option, panel and card tools) and [startOnBoot] (the app's process starts after a reboot or an update, so plans
 * are set again and the host's own work resumes; plans need it).
 */
class BuiltInFeatures(
    val plans: Boolean = false,
    val androidUse: Boolean = false,
    val deviceMedia: Boolean = false,
    val cards: Boolean = false,
    val startOnBoot: Boolean = false,
) {
    companion object {
        val NONE = BuiltInFeatures()
    }
}

/** What a run is, for the host's lines, rules and gates ([RunAddOns]). */
class RunFacts(
    val context: Context,
    /** The request as the model reads it this run: a short reply framed with what it answers, a pick's frame. */
    val userInput: String,
    /** A subagent's run: its input is the task the main agent wrote, never the user's words. */
    val child: Boolean,
    /** The main agent searches the web itself (a web_search tool or the provider's own search); false in a subagent's run. */
    val webSearch: Boolean,
    /** The subagent types the main agent can start now ([SubagentKind.id]); none in a child or with spawn_agent off. */
    val subagentTypes: Set<String>,
    /** The model may draw a page now: show_html is offered and the conversation is on screen. */
    val drawsPages: Boolean,
)

/** What a host's check found in a run's answer: [failures] fail its replay, [warnings] are only logged. */
class AnswerProblems(val failures: List<String> = emptyList(), val warnings: List<String> = emptyList()) {
    companion object {
        val NONE = AnswerProblems()
    }
}

/**
 * Who the assistant is: its [name] as the user calls it, the [identity] line its system prompt opens with (who it is, in
 * which app), and the [language] it answers in (a BCP 47 tag such as `zh-CN`), which the server's own lines to the model
 * are in too; null answers in the user's own language, with those lines in English. Text the model reads about itself
 * says "you" and needs no name.
 */
class Persona(val name: String, val identity: String, val language: String? = null) {
    companion object {
        @InternalAppServerApi
        val GENERIC = Persona(
            name = "Assistant",
            identity = "You are the assistant of this app, running on the user's device. Help with the user's tasks through " +
                "tools; do not present yourself as a generic cloud chatbot or model provider. Do not expose API keys or secrets.",
        )
    }
}

/**
 * The model services a host build carries (from its local.properties, say): the profiles file's text, local overrides
 * of single profiles, fixed values, and where they came from (for the settings page). [sections] are the config file's
 * objects of the host's own, by name, each with the prefix its fields' keys get (`"tts" to "VOICE_"` reads
 * `"tts": {"apiKey": …}` as `VOICE_API_KEY`). A host without any keeps [NONE].
 */
class ProviderPresets(
    val configText: String = "",
    val localOverridesText: String = "",
    val values: Map<String, String> = emptyMap(),
    val source: String = "",
    val sections: Map<String, String> = emptyMap(),
) {
    companion object {
        val NONE = ProviderPresets()
    }
}

/**
 * Where the host's screens let the user see what some tools leave, named in the words the model reads: [memoryPage]
 * where the user reads and edits what is remembered, [picturesShownOn] where a generated picture appears (it follows
 * 「显示在」, or "shows it right away": "on the screen"). They are in the persona's language ([Persona.language]). Words
 * with a null place leave it out. A host that names none keeps [NONE].
 */
class HostPlaces(val memoryPage: String? = null, val picturesShownOn: String? = null) {
    companion object {
        val NONE = HostPlaces()
    }
}

/**
 * What the host app plugs into the runtime ([HostModels], [RunAddOns], its [Persona], its [ProviderPresets], its
 * [HostPlaces], its [A2aHost]); none until it installs them.
 */
@InternalAppServerApi
object AppHost {
    @Volatile
    var models: HostModels = HostModels.NONE
        private set

    @Volatile
    var runs: RunAddOns = RunAddOns.NONE
        private set

    @Volatile
    var persona: Persona = Persona.GENERIC
        private set

    @Volatile
    private var wordsFor: Pair<String?, ModelWords>? = null

    /** What the server's own code tells the model, in [persona]'s language. */
    val words: ModelWords
        get() {
            val language = persona.language
            wordsFor?.takeIf { it.first == language }?.let { return it.second }
            return ModelWords.forLanguage(language).also { wordsFor = language to it }
        }

    @Volatile
    var presets: ProviderPresets = ProviderPresets.NONE
        private set

    @Volatile
    var places: HostPlaces = HostPlaces.NONE
        private set

    @Volatile
    var a2a: A2aHost = A2aHost.NONE
        private set

    @Volatile
    var builtIns: BuiltInFeatures = BuiltInFeatures.NONE
        private set

    /** The host's extra trusted roots, a raw resource of PEM certificates; null for the system's alone. */
    @Volatile
    var extraTrustRoots: Int? = null
        private set

    fun install(
        models: HostModels = this.models,
        runs: RunAddOns = this.runs,
        persona: Persona = this.persona,
        presets: ProviderPresets = this.presets,
        places: HostPlaces = this.places,
        a2a: A2aHost = this.a2a,
        builtIns: BuiltInFeatures = this.builtIns,
        extraTrustRoots: Int? = this.extraTrustRoots,
    ) {
        this.builtIns = builtIns
        this.extraTrustRoots = extraTrustRoots
        this.models = models
        this.runs = runs
        this.persona = persona
        this.presets = presets
        this.places = places
        this.a2a = a2a
    }
}
