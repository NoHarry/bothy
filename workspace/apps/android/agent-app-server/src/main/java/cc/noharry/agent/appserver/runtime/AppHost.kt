package cc.noharry.agent.appserver.runtime

import android.content.Context
import cc.noharry.agent.appserver.InternalAppServerApi
import cc.noharry.agent.appserver.evidence.AppDiagnostics
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.skills.RuntimeSkill
import cc.noharry.agent.appserver.skills.SubagentKind
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
 * What the host adds to each run it builds: lines for the run's context, a wrapper for every mounted tool provider (a
 * tool search picking tools before the first model call), whether the bundled example tools may be mounted, tool
 * providers, skills and subagent types of its own, and which of its servers work devices. An app without any keeps
 * [NONE].
 */
interface RunAddOns {
    fun contextLines(context: Context): List<String> = emptyList()

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
 * Who the assistant is: its [name] as the user calls it, and the [identity] line its system prompt opens with (who it
 * is, in which app). Text the model reads about itself says "你" and needs neither.
 */
class Persona(val name: String, val identity: String) {
    companion object {
        @InternalAppServerApi
        val GENERIC = Persona(
            name = "助手",
            identity = "You are the assistant of this app, running on the user's device. Answer in concise Chinese. Help with the " +
                "user's tasks through tools; do not present yourself as a generic cloud chatbot or model provider. Do not expose " +
                "API keys or secrets.",
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
 * 「显示在」). Words with a null place leave it out. A host that names none keeps [NONE].
 */
class HostPlaces(val memoryPage: String? = null, val picturesShownOn: String? = null) {
    companion object {
        val NONE = HostPlaces()
    }
}

/**
 * What the host app plugs into the runtime ([HostModels], [RunAddOns], its [Persona], its [ProviderPresets], its
 * [HostPlaces]); none until it installs them.
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
    var presets: ProviderPresets = ProviderPresets.NONE
        private set

    @Volatile
    var places: HostPlaces = HostPlaces.NONE
        private set

    fun install(
        models: HostModels = this.models,
        runs: RunAddOns = this.runs,
        persona: Persona = this.persona,
        presets: ProviderPresets = this.presets,
        places: HostPlaces = this.places,
    ) {
        this.models = models
        this.runs = runs
        this.persona = persona
        this.presets = presets
        this.places = places
    }
}
