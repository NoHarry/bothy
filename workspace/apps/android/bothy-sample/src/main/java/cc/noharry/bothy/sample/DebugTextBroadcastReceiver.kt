package cc.noharry.bothy.sample

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

class DebugTextBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!isDebugTextBroadcast(intent.action, intent.component?.className)) return
        applySettingExtras(context, intent)
        applyConsoleSwitches(context, intent)
        if (startScene(context, intent)) return
        applyVoiceExtras(context, intent)
        submit(context, intent)
    }

    /** The A2A client's switches and the mascot preview. */
    private fun applyConsoleSwitches(context: Context, intent: Intent) {
        intent.getStringExtra(DEBUG_A2A_CLIENT_EXTRA)?.let { value ->
            val enabled = parseDebugA2aClientSwitch(value)
            if (enabled == null) Log.w(TAG, "Ignored $DEBUG_A2A_CLIENT_EXTRA=$value; use on or off")
            else DebugA2aClientConsole.setEnabled(context, enabled)
        }
        intent.getStringExtra(DEBUG_A2A_SPEAK_EXTERNAL_EXTRA)?.let { value ->
            val speak = parseDebugA2aClientSwitch(value)
            if (speak == null) Log.w(TAG, "Ignored $DEBUG_A2A_SPEAK_EXTERNAL_EXTRA=$value; use on or off")
            else DebugA2aClientConsole.setSpeakExternal(context, speak)
        }
        intent.getStringExtra(DEBUG_MASCOT_ACT_EXTRA)?.let { name ->
            if (DebugOverlayControl.mascotPreview?.invoke(name.trim()) != true) {
                Log.w(TAG, "Ignored $DEBUG_MASCOT_ACT_EXTRA=$name; open the overlay and use an act or reaction name")
            }
        }
    }

    /** The demo scene's start: the model profile, cleared runtime data (nothing else then) or a new session. */
    private fun startScene(context: Context, intent: Intent): Boolean {
        // The model profile as Settings would pick it (`--es profile manual_local_…`), before the scene's turns.
        intent.getStringExtra(DEBUG_PROFILE_EXTRA)?.trim()?.takeIf { it.isNotEmpty() }?.let { id ->
            DebugRuntimeSession.selectProfile(context.applicationContext, id)
            val active = DebugProviderConfig.load(context.applicationContext).activeProfile
            Log.i(TAG, "Profile selected: ${active.id} (${active.displayLabel}) · ${active.model}")
        }
        // A demo scene starts from nothing (owner 2026-10-19: 测试前先清空 runtime data，测冷启动), as the console's button
        // does: sessions, workspace, evidence and event logs go; memories, settings and keys stay.
        if (intent.getBooleanExtra(DEBUG_CLEAR_RUNTIME_DATA_EXTRA, false)) {
            // Held until done: a process woken only by this broadcast may be frozen once onReceive returns.
            val pending = goAsync()
            DebugRuntimeSession.clearRuntimeData(context.applicationContext) { ok ->
                Log.i(TAG, "Runtime data cleared: ok=$ok")
                pending.finish()
            }
            return true
        }
        // A demo scene starts clean (owner 2026-10-19): the console's next text carries no earlier turns.
        if (intent.getBooleanExtra(DEBUG_NEW_SESSION_EXTRA, false)) DebugRuntimeSession.newSession(context.applicationContext)
        return false
    }

    /** A wake as the wake word would, or recorded words fed to 端到端演示模式. */
    private fun applyVoiceExtras(context: Context, intent: Intent) {
        if (intent.getBooleanExtra(DEBUG_WAKE_EXTRA, false)) {
            // Without the overlay it wakes as the wake word would there: in the console, or with no card.
            DebugOverlayControl.waker?.invoke(DebugWakeSource.ADB) ?: startWokenVoice(context.applicationContext, DebugWakeSource.ADB)
        }
        intent.getStringExtra(DEBUG_REALTIME_SAY_EXTRA)?.takeIf(String::isNotBlank)?.let { text ->
            // As a late result is: 小博 says it while the model hears silence, and the model is told it.
            val app = context.applicationContext
            if (!DebugVoiceController.tellRealtime(text) { DebugRuntimeSession.previewTts(app, text) {} }) {
                Log.w(TAG, "Ignored $DEBUG_REALTIME_SAY_EXTRA: no 端到端 conversation on air")
            }
        }
        intent.getStringExtra(DEBUG_REALTIME_PCM_EXTRA)?.let { name ->
            val pcm = recordedWords(context, name)
            if (pcm == null || !DebugVoiceController.injectRealtimeAudio(pcm)) {
                Log.w(TAG, "Ignored $DEBUG_REALTIME_PCM_EXTRA=$name; wake with 端到端演示模式 on and put 16 kHz PCM16 under files/")
            }
        }
        intent.getStringExtra(DEBUG_VOICE_PCM_EXTRA)?.let { name ->
            val pcm = recordedWords(context, name)
            if (pcm == null || !DebugVoiceController.injectAudio(pcm)) {
                Log.w(TAG, "Ignored $DEBUG_VOICE_PCM_EXTRA=$name; wake first and put 16 kHz PCM16 under files/")
            }
        }
    }

    /** A plain file name in files/, nothing elsewhere. */
    private fun recordedWords(context: Context, name: String): ByteArray? = name.trim().takeIf { it.isNotEmpty() && '/' !in it && ".." !in it }
        ?.let { runCatching { File(context.filesDir, it).readBytes() }.getOrNull() }

    /** The text as a console turn, a simulated voice turn, or a message to the A2A client. */
    private fun submit(context: Context, intent: Intent) {
        val text = intent.getStringExtra(DEBUG_TEXT_EXTRA)?.trim().orEmpty()
        if (text.isEmpty()) return
        // 陪聊 on the bench: the words arrive as a voice turn Jev scored (`--es voice_turn on --es small_talk 0.9`).
        if (intent.getStringExtra(DEBUG_VOICE_TURN_EXTRA) == "on") {
            DebugVoiceController.simulateTurn(context, text, intent.getStringExtra(DEBUG_SMALL_TALK_EXTRA)?.toDoubleOrNull())
            return
        }
        val route = debugTextRoute(intent.getStringExtra(DEBUG_ROUTE_EXTRA), DebugA2aClientConsole.isEnabled(context))
        if (route == DebugTextRoute.A2A_CLIENT) {
            DebugA2aClientConsole.send(context, text, DebugA2aInputSource.ADB)
            return
        }
        val backend = intent.getStringExtra(DEBUG_BACKEND_EXTRA)
        val stream = intent.getStringExtra(DEBUG_STREAM_EXTRA)
        DebugRuntimeSession.submitText(context, text, backend, stream)
    }

    /** Bench: a smaller window for the active model shows compaction sooner; 0 goes back to the model's own. */
    private fun setContextWindow(context: Context, value: Long) {
        val tokens = value.takeIf { it > 0 }
        val config = DebugProviderConfig.load(context)
        saveDebugContextWindow(context, config.editState(config.activeProfile), tokens)
        Log.i(TAG, "Context window for ${config.activeProfile.model}: ${tokens ?: "model default"}")
    }

    private fun applySettingExtras(context: Context, intent: Intent) {
        if (intent.hasExtra(DEBUG_CONTEXT_WINDOW_EXTRA)) setContextWindow(context, intent.getLongExtra(DEBUG_CONTEXT_WINDOW_EXTRA, 0L))
        if (listOf(DEBUG_SUBAGENTS_EXTRA, DEBUG_SUBAGENT_DEVICE_EXTRA, DEBUG_SUBAGENT_MODEL_EXTRA, DEBUG_SUBAGENT_SEARCH_EXTRA).any(intent::hasExtra)) {
            setSubagents(context, intent)
        }
        intent.getStringExtra(DEBUG_SEARCH_BACKEND_EXTRA)?.let { setSearchBackend(context, it) }
        intent.getStringExtra(DEBUG_FETCH_FALLBACK_EXTRA)?.let { value ->
            DebugFetchFallback.set(context.applicationContext, value.trim().lowercase() in setOf("on", "true"))
            Log.i(TAG, "Fetch fallback: ${if (DebugFetchFallback.enabled(context.applicationContext)) "on" else "off"}")
        }
        intent.getStringExtra(DEBUG_SUBAGENT_TYPES_EXTRA)?.let { setSubagentTypes(context, it) }
        intent.getStringExtra(DEBUG_DEEPSEEK_PROBE_EXTRA)?.let { checkDeepSeek(context, it) }
    }

    /** `active` or a profile id: checks it for DeepSeek behind it as Settings → 检测 does, saves and logs the result. */
    private fun checkDeepSeek(context: Context, value: String) {
        val app = context.applicationContext
        val config = DebugProviderConfig.load(app)
        val wanted = value.trim()
        val profile = if (wanted.isEmpty() || wanted == "active") config.activeProfile else config.profiles.firstOrNull { it.id == wanted }
        if (profile == null) {
            Log.w(PROBE_TAG, "Ignored $DEBUG_DEEPSEEK_PROBE_EXTRA=$value; use active or one of ${config.profiles.joinToString { it.id }}")
            return
        }
        val key = config.effectiveApiKey(profile)
        if (key.isBlank()) {
            Log.w(PROBE_TAG, "Ignored $DEBUG_DEEPSEEK_PROBE_EXTRA=$value; ${profile.id} has no API key on this car")
            return
        }
        DebugDeepSeekProbes.launch {
            val probe = DebugDeepSeekProbes.check(app, profile, key)
            Log.i(PROBE_TAG, "DeepSeek check for ${profile.id} (${profile.endpoint.wireName} ${profile.baseUrl}): ${probe.describe()}")
        }
    }

    /** `all`, `none` or the type names to switch on (`trip-transport,trip-days`); the rest are switched off. */
    private fun setSubagentTypes(context: Context, value: String) {
        val wanted = value.split(',').map { it.trim().lowercase() }.filter(String::isNotEmpty).toSet()
        loadDebugSubagentKinds(context).forEach { kind ->
            saveDebugSubagentKind(context, kind.id, "all" in wanted || kind.id in wanted)
        }
        Log.i(TAG, "Subagent types on: ${loadDebugSubagentKinds(context).filter { it.enabled }.joinToString { it.id }.ifEmpty { "none" }}")
    }

    /** `--es subagent_device_control on|off`, `--es subagent_model provider:model[:variant]|inherit`, `--es subagent_search api|inherit`. */
    private fun setSubagents(context: Context, intent: Intent) {
        val current = loadDebugSubagentSettings(context)
        val device = when (intent.getStringExtra(DEBUG_SUBAGENT_DEVICE_EXTRA)?.lowercase()) {
            "on", "true" -> true
            "off", "false" -> false
            else -> current.allowDeviceControl
        }
        val model = intent.getStringExtra(DEBUG_SUBAGENT_MODEL_EXTRA)?.trim()?.let { if (it == "inherit") "" else it } ?: current.model
        val enabled = intent.getStringExtra(DEBUG_SUBAGENTS_EXTRA)?.lowercase()?.let { it == "on" || it == "true" } ?: current.enabled
        val searchApi = intent.getStringExtra(DEBUG_SUBAGENT_SEARCH_EXTRA)?.trim()?.lowercase()?.let { it == "api" } ?: current.searchApi
        val settings = DebugSubagentSettings(device, model, enabled, searchApi)
        saveDebugSubagentSettings(context, settings)
        Log.i(
            TAG,
            "Subagents: ${if (enabled) "on" else "off"}, device control ${if (device) "on" else "off"}, model ${model.ifBlank { "inherit" }}, " +
                "search ${if (searchApi) "api" else "inherit"}",
        )
    }

    /** `api` or `server` for the active profile: DeepSeek's Search API or the provider's own search (bench switch). */
    private fun setSearchBackend(context: Context, value: String) {
        val profile = DebugProviderConfig.load(context.applicationContext).activeProfile
        DebugSearchBackend.set(context.applicationContext, profile.id, value.trim().lowercase() == "api")
        Log.i(TAG, "Search for ${profile.id}: ${if (DebugSearchBackend.usesApi(context, profile.id)) "DeepSeek Search API" else "provider search"}")
    }

    private companion object {
        const val TAG = "DebugA2aClient"
        const val PROBE_TAG = "DebugDeepSeekProbe"
    }
}

/** 收到 A2A 播报 on/off: whether other clients' A2A tasks are spoken on the device (they are always shown). */
internal const val DEBUG_A2A_SPEAK_EXTERNAL_EXTRA = "a2a_speak_external"

internal const val DEBUG_VOICE_TURN_EXTRA = "voice_turn"
internal const val DEBUG_SMALL_TALK_EXTRA = "small_talk"

/** 端到端演示模式 on a device by adb: a file under files/ of 16 kHz PCM16 words, heard by the open conversation as if spoken. */
internal const val DEBUG_REALTIME_PCM_EXTRA = "realtime_pcm"

/** The same for whichever session listens: in a Jev one the words are mixed into the microphone (DebugInjectedAudio). */
internal const val DEBUG_VOICE_PCM_EXTRA = "voice_pcm"

/** 端到端 on a device by adb: text 小博 says in the open conversation, as a late result of its own is (`tellLate`). */
internal const val DEBUG_REALTIME_SAY_EXTRA = "realtime_say"

/** Sets the active model's context window (`--el context_window 20000`; 0 restores the model's own) for compaction tests. */
internal const val DEBUG_CONTEXT_WINDOW_EXTRA = "context_window"
internal const val DEBUG_SUBAGENT_DEVICE_EXTRA = "subagent_device_control"
/** `on|off`: whether the agent may start subagents on its own (Settings → Runtime). */
internal const val DEBUG_SUBAGENTS_EXTRA = "subagents"
/** `api|server`: how the active profile searches (Settings → Runtime → 联网搜索). */
internal const val DEBUG_SEARCH_BACKEND_EXTRA = "search_backend"
/** `on|off`: whether web_fetch gets refused pages through DeepSeek's Fetch API (Settings → Runtime). */
internal const val DEBUG_FETCH_FALLBACK_EXTRA = "fetch_fallback"
internal const val DEBUG_SUBAGENT_MODEL_EXTRA = "subagent_model"
/** `all|none|name,name`: which subagent types are switched on (Settings → 子 agent → 子 agent 类型). */
internal const val DEBUG_SUBAGENT_TYPES_EXTRA = "subagent_types"
/** `api|inherit`: whether subagents search with DeepSeek's Search API (Settings → 子 agent). */
internal const val DEBUG_SUBAGENT_SEARCH_EXTRA = "subagent_search"
/** `active|<profile id>`: checks the profile for DeepSeek's own API behind it (Settings → the profile → 检测). */
internal const val DEBUG_DEEPSEEK_PROBE_EXTRA = "deepseek_probe"

/** Wakes the overlay as 你好，小博 would, interrupting whatever 小博 is doing. */
internal const val DEBUG_WAKE_EXTRA = "wake"

/** `--es profile <id>`: selects that model profile, as Settings does; logs the profile that is active after. */
internal const val DEBUG_PROFILE_EXTRA = "profile"

/** `--ez clear_runtime_data true`: the console's Clear runtime data (memories kept); logs 「Runtime data cleared」 when done. */
internal const val DEBUG_CLEAR_RUNTIME_DATA_EXTRA = "clear_runtime_data"

/** `--ez new_session true`: the console starts a new session, as its New session button does. */
internal const val DEBUG_NEW_SESSION_EXTRA = "new_session"

/** Plays one of 小博's acts or reactions (e.g. `DONE`, `SEARCHING`, `GIGGLE`) on the open overlay, for review. */
internal const val DEBUG_MASCOT_ACT_EXTRA = "mascot_act"

internal fun isDebugTextBroadcast(action: String?, componentClassName: String?): Boolean =
    action == DEBUG_BROADCAST_ACTION ||
        (action == null && componentClassName == DebugTextBroadcastReceiver::class.java.name)

internal enum class DebugTextRoute { LOCAL, A2A_CLIENT }

/** An explicit `route` wins; otherwise text follows the persisted client mode. */
internal fun debugTextRoute(routeExtra: String?, clientMode: Boolean): DebugTextRoute = when (routeExtra?.trim()?.lowercase()) {
    "a2a" -> DebugTextRoute.A2A_CLIENT
    "local" -> DebugTextRoute.LOCAL
    else -> if (clientMode) DebugTextRoute.A2A_CLIENT else DebugTextRoute.LOCAL
}

internal fun parseDebugA2aClientSwitch(value: String): Boolean? = when (value.trim().lowercase()) {
    "on", "1", "true" -> true
    "off", "0", "false" -> false
    else -> null
}
