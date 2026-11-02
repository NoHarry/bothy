package cc.noharry.bothy.app.evidence
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.StringRes
import cc.noharry.agent.androiduse.AndroidUseAccessibility
import cc.noharry.agent.transport.HttpRequest
import cc.noharry.bothy.app.voice.localAsrUnavailable
import cc.noharry.bothy.app.voice.wakeModelUnavailable
import cc.noharry.bothy.voice.JevTurnJudge
import cc.noharry.bothy.voice.VoiceJudgeRequest
import cc.noharry.bothy.voice.VoiceTurnSnapshot
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.androiduse.loadAndroidUseEnabled
import cc.noharry.bothy.app.androiduse.repairAndroidUse
import cc.noharry.bothy.app.companion.checkCompanion
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.image.fetchImageModels
import cc.noharry.bothy.app.image.loadImageModelConfig
import cc.noharry.bothy.app.model.DEFAULT_LOCAL_API_KEY
import cc.noharry.bothy.app.model.DeepSeekProbe
import cc.noharry.bothy.app.model.DeepSeekProbes
import cc.noharry.bothy.app.model.ModelCatalogClient
import cc.noharry.bothy.app.model.ProviderConfig
import cc.noharry.bothy.app.model.ProviderEndpoint
import cc.noharry.bothy.app.model.fetchDeepSeekBalance
import cc.noharry.bothy.app.model.isDeepSeek
import cc.noharry.bothy.app.model.isLocalBaseUrl
import cc.noharry.bothy.app.model.summary
import cc.noharry.bothy.app.tools.checkToolCatalog
import cc.noharry.bothy.app.usage.ModelUsage
import cc.noharry.bothy.app.usage.UsageStore
import cc.noharry.bothy.app.voice.VoiceConfig
import cc.noharry.bothy.app.voice.VoiceEngine
import cc.noharry.bothy.app.voice.WakeWord
import cc.noharry.bothy.app.voice.XIAOBO_VOICE_IDENTITY
import cc.noharry.bothy.app.voice.loadDashscopeKey
import cc.noharry.bothy.app.voice.loadJevConfig
import cc.noharry.bothy.app.voice.loadJevKey
import cc.noharry.bothy.app.voice.loadVoiceConfig
import cc.noharry.bothy.app.voice.microphone
import cc.noharry.bothy.app.voice.realtime.checkRealtime
import cc.noharry.bothy.app.voice.tts.ttsClient
import cc.noharry.bothy.app.voice.tts.ttsConfig
import cc.noharry.bothy.app.voice.tts.validationError
import cc.noharry.bothy.app.voice.ttsStatusLine
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** One line on Settings → 检查. [OFF] is something switched off on purpose, not a fault. */
internal enum class CheckState { RUNNING, OK, WARN, FAIL, OFF }

/** What fixes a check: a Settings section, a system settings screen, or an action on the page. */
internal enum class CheckFix(@StringRes val label: Int) {
    MODELS(R.string.checks_fix_models),
    IMAGE_MODEL(R.string.checks_fix_image_model),
    VOICE(R.string.checks_fix_voice),
    RECORD_PERMISSION(R.string.checks_fix_allow),
    OVERLAY(R.string.checks_fix_allow_in_settings),
    ACCESSIBILITY(R.string.checks_fix_turn_on),
    NOTIFICATIONS(R.string.checks_fix_allow),
    WAKE_ON(R.string.checks_fix_wake_on),
    OVERLAY_START(R.string.checks_fix_overlay_start),
}

/** [detail] is shown as it is: checks write it in the app's language. */
internal data class CheckResult(val state: CheckState, val detail: String, val fix: CheckFix? = null)

internal data class HealthCheck(
    val id: String,
    @StringRes val group: Int,
    @StringRes val title: Int,
    val run: suspend (Context) -> CheckResult,
)

/**
 * Settings → 检查: the selected models are asked once each (model lists, a Jev judgement, a short MiMo synthesis),
 * a DeepSeek model gets its official-API check, the microphone records half a second (kept in memory), and
 * permissions and services are read.
 */
internal val DEBUG_CHECKS: List<HealthCheck> = listOf(
    HealthCheck("chat", R.string.checks_group_models, R.string.checks_chat, ::checkChatModel),
    HealthCheck("deepseek", R.string.checks_group_models, R.string.checks_deepseek, ::checkDeepSeek),
    HealthCheck("image", R.string.checks_group_models, R.string.checks_image, ::checkImageModel),
    HealthCheck("tools", R.string.checks_group_models, R.string.checks_tools, ::checkToolCatalog),
    HealthCheck("asr", R.string.checks_group_voice, R.string.checks_asr, ::checkRecognition),
    HealthCheck("jev", R.string.checks_group_voice, R.string.checks_jev, ::checkJev),
    HealthCheck("tts", R.string.checks_group_voice, R.string.checks_tts, ::checkTts),
    HealthCheck("wake", R.string.checks_group_voice, R.string.checks_wake, ::checkWake),
    HealthCheck("mic", R.string.checks_group_voice, R.string.checks_mic, ::checkRecording),
    HealthCheck("companion", R.string.checks_group_voice, R.string.checks_companion, ::checkCompanion),
    HealthCheck("realtime", R.string.checks_group_voice, R.string.checks_realtime, ::checkRealtime),
    HealthCheck(
        "record_permission", R.string.checks_group_permissions, R.string.checks_record_permission, ::checkRecordPermission,
    ),
    HealthCheck("overlay", R.string.checks_group_permissions, R.string.checks_overlay, ::checkOverlay),
    HealthCheck("accessibility", R.string.checks_group_permissions, R.string.checks_accessibility, ::checkAccessibility),
    HealthCheck("notifications", R.string.checks_group_permissions, R.string.checks_notifications, ::checkNotifications),
)

/** Runs [checks] at once, each within [CHECK_TIMEOUT_MS]; [onResult] hears each on the caller's thread as it ends. */
internal suspend fun runHealthChecks(
    context: Context,
    checks: List<HealthCheck> = DEBUG_CHECKS,
    onResult: (String, CheckResult) -> Unit,
) = coroutineScope {
    checks.map { check ->
        launch {
            val result = try {
                withTimeout(CHECK_TIMEOUT_MS) { withContext(Dispatchers.IO) { check.run(context.applicationContext) } }
            } catch (_: TimeoutCancellationException) {
                val seconds = (CHECK_TIMEOUT_MS / 1000).toInt()
                CheckResult(CheckState.FAIL, context.getString(R.string.checks_timeout, seconds))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CheckResult(CheckState.FAIL, error.message ?: error.javaClass.simpleName)
            }
            onResult(check.id, result)
        }
    }.joinAll()
}

private fun ok(detail: String) = CheckResult(CheckState.OK, detail)
private fun warn(detail: String, fix: CheckFix? = null) = CheckResult(CheckState.WARN, detail, fix)
private fun fail(detail: String, fix: CheckFix? = null) = CheckResult(CheckState.FAIL, detail, fix)

internal suspend fun checkChatModel(context: Context): CheckResult {
    val config = ProviderConfig.load(context)
    val profile = config.activeProfile
    val name = "${profile.displayLabel} · ${profile.model}"
    if (!config.isReady(profile)) return fail("$name · ${config.missingMessage(context, profile)}", CheckFix.MODELS)
    val key = profile.apiKey.ifBlank { DEFAULT_LOCAL_API_KEY.takeIf { profile.baseUrl.isLocalBaseUrl() }.orEmpty() }
    return runCatching { ModelCatalogClient(BenchHttpTransport(context)).fetch(profile.provider, key, profile.baseUrl) }.fold(
        onSuccess = { ids -> chatModelListed(context, name, profile.model, ids) },
        onFailure = { fail("$name · ${requestProblem(context, it)}", CheckFix.MODELS) },
    )
}

/**
 * The selected model against the service's list. Not listed reads as the selection being stale, with what is listed
 * (owner 2026-10-19 took 「列出的模型里没有 deepseek-v4-flash」 for the check having that model built in).
 */
internal fun chatModelListed(context: Context, name: String, model: String, ids: List<String>): CheckResult {
    if (ids.isEmpty() || model in ids) return ok(context.getString(R.string.checks_service_available, name))
    val shown = ids.take(LISTED_MODELS).joinToString(context.getString(R.string.checks_list_separator))
    val listed = if (ids.size > LISTED_MODELS) context.getString(R.string.checks_listed_more, shown, ids.size)
    else shown
    return warn(context.getString(R.string.checks_model_not_listed, name, model, listed), CheckFix.MODELS)
}

/**
 * What of DeepSeek's own API the current model gets (owner 2026-10-19: 检测里面也加上这个检测): on api.deepseek.com the
 * key's balance, the rest following from the host; behind a relay the model page's 检测, whose saved result unlocks what
 * passed. A model not named DeepSeek is left alone; a gateway that renames it can still be checked on its model page.
 */
private suspend fun checkDeepSeek(context: Context): CheckResult {
    val config = ProviderConfig.load(context)
    val profile = config.activeProfile
    val official = profile.isDeepSeek()
    if (!official && !profile.model.contains(DEEPSEEK_MODEL_MARK, ignoreCase = true)) {
        val detail = context.getString(R.string.checks_not_deepseek, profile.displayLabel, profile.model)
        return CheckResult(CheckState.OFF, detail)
    }
    val key = config.effectiveApiKey(profile)
    if (key.isBlank()) {
        return fail(context.getString(R.string.checks_label_no_key, profile.displayLabel), CheckFix.MODELS)
    }
    if (!official) return DeepSeekProbes.check(context, profile, key).checkResult(context, profile.displayLabel)
    val features = if (profile.endpoint == ProviderEndpoint.MESSAGES) {
        context.getString(R.string.checks_deepseek_features_messages)
    } else {
        "Search/Fetch"
    }
    return fetchDeepSeekBalance(context, key).fold(
        onSuccess = { balance ->
            val summary = balance.summary(context)
            if (balance.available) ok(context.getString(R.string.checks_deepseek_official_ok, features, summary))
            else warn(context.getString(R.string.checks_deepseek_official_balance, summary), CheckFix.MODELS)
        },
        onFailure = { fail(context.getString(R.string.checks_deepseek_official_failed, it.message), CheckFix.MODELS) },
    )
}

/** A relay's DeepSeek check as one line: what passed, and why the rest did not. */
internal fun DeepSeekProbe.checkResult(context: Context, label: String): CheckResult {
    val checks = listOfNotNull(
        context.getString(R.string.checks_deepseek_balance) to balance,
        "Search/Fetch" to tools,
        passthrough?.let { context.getString(R.string.checks_deepseek_native_tools) to it },
    )
    val detail = "$label · " + checks.joinToString(" · ") { (name, check) -> if (check.passed) "$name ✓" else "$name ✗ ${check.note}" }
    return if (checks.any { it.second.passed }) {
        ok(detail)
    } else {
        warn(context.getString(R.string.checks_deepseek_none, detail), CheckFix.MODELS)
    }
}

private suspend fun checkImageModel(context: Context): CheckResult {
    val config = loadImageModelConfig(context)
    when {
        !config.enabled ->
            return CheckResult(CheckState.OFF, context.getString(R.string.checks_off), CheckFix.IMAGE_MODEL)
        !config.hasApiKey -> return fail(context.getString(R.string.checks_no_key), CheckFix.IMAGE_MODEL)
        config.model.isBlank() -> return fail(context.getString(R.string.checks_no_model), CheckFix.IMAGE_MODEL)
    }
    config.validationProblem()?.let { return fail(context.getString(it.shown), CheckFix.IMAGE_MODEL) }
    return fetchImageModels(context, config.baseUrl).fold(
        onSuccess = { ids ->
            if (ids.isEmpty() || config.model in ids) {
                ok(context.getString(R.string.checks_service_available, config.model))
            } else {
                warn(context.getString(R.string.checks_image_not_listed, config.model), CheckFix.IMAGE_MODEL)
            }
        },
        onFailure = { fail("${config.model} · ${requestProblem(context, it)}", CheckFix.IMAGE_MODEL) },
    )
}

private suspend fun checkRecognition(context: Context): CheckResult {
    val config = loadVoiceConfig(context)
    if (config.engine == VoiceEngine.LOCAL) {
        return localAsrUnavailable(context)?.let { fail(it) }
            ?: ok(context.getString(R.string.checks_asr_local_ok))
    }
    val key = loadDashscopeKey(context)
    if (key.isEmpty()) return fail(context.getString(R.string.checks_asr_no_key), CheckFix.VOICE)
    val status = BenchHttpTransport(context).execute(
        HttpRequest("GET", DASHSCOPE_MODELS_URL, mapOf("Authorization" to "Bearer $key")),
    ).statusCode
    return if (status in SUCCESS) ok(context.getString(R.string.checks_asr_ok, config.dashscopeModel))
    else fail(context.getString(R.string.checks_asr_failed, statusProblem(context, status)), CheckFix.VOICE)
}

private suspend fun checkJev(context: Context): CheckResult {
    val jev = loadJevConfig(context)
    val key = loadJevKey(context)
    if (key.isEmpty()) {
        return CheckResult(CheckState.OFF, context.getString(R.string.checks_jev_not_set), CheckFix.VOICE)
    }
    if (!loadVoiceConfig(context).jevTurns) {
        return CheckResult(CheckState.OFF, context.getString(R.string.checks_jev_off), CheckFix.VOICE)
    }
    val judge = JevTurnJudge(jev.endpoint, key, jev.model, jev.timeoutMillis, identity = XIAOBO_VOICE_IDENTITY, onUsage = { input, output ->
        UsageStore.record(jev.model, ModelUsage(1, input.toLong(), outputTokens = output.toLong()))
    })
    val started = SystemClock.elapsedRealtime()
    return runCatching {
        judge.judge(VoiceJudgeRequest(VoiceTurnSnapshot(1, JEV_CHECK_TEXT, emptyList(), 2_000, 800, asrFinal = true), emptyList()))
    }.fold(
        onSuccess = { ok("${jev.model} · ${SystemClock.elapsedRealtime() - started} ms") },
        onFailure = { fail("${jev.model} · ${it.message}", CheckFix.VOICE) },
    )
}

private suspend fun checkTts(context: Context): CheckResult {
    val tts = ProviderConfig.load(context).ttsConfig()
    if (!tts.enabled && tts.apiKey.isNotBlank()) {
        return CheckResult(CheckState.OFF, context.getString(R.string.checks_tts_off), CheckFix.VOICE)
    }
    if (!tts.ready) return fail(ttsStatusLine(context, tts), CheckFix.VOICE)
    return runCatching { ttsClient(BenchHttpTransport(context)).synthesize(tts, TTS_CHECK_TEXT) }.fold(
        onSuccess = { audio -> ok(context.getString(R.string.checks_tts_ok, tts.label(context), audio.bytes.size / 1024)) },
        onFailure = { fail("${tts.label(context)} · ${it.message}", CheckFix.VOICE) },
    )
}

private suspend fun checkWake(context: Context): CheckResult {
    wakeModelUnavailable(context)?.let { return fail(it) }
    if (!loadVoiceConfig(context).overlayWake) {
        return warn(context.getString(R.string.checks_wake_off), CheckFix.WAKE_ON)
    }
    // The listener lives on the main thread; reading the switch there also starts it when nothing blocks it.
    val status = withContext(Dispatchers.Main) {
        WakeWord.load(context)
        WakeWord.status.value
    }
    return if (status.listening) {
        ok(context.getString(R.string.checks_wake_ok))
    } else {
        warn(context.getString(R.string.checks_wake_not_listening, status.message))
    }
}

/** Half a second through the configured capture: silence everywhere means the source is held or muted elsewhere. */
private suspend fun checkRecording(context: Context): CheckResult {
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        return fail(context.getString(R.string.checks_mic_no_permission), CheckFix.RECORD_PERMISSION)
    }
    val config = loadVoiceConfig(context)
    val capture = captureDescription(context, config)
    if (!WakeWord.micLease.tryLock()) return ok(context.getString(R.string.checks_mic_busy, capture))
    val frames = try {
        microphone(config).frames().take(RECORD_CHECK_FRAMES).toList()
    } finally {
        WakeWord.micLease.unlock()
    }
    val samples = frames.sumOf { it.size }
    val energy = frames.sumOf { frame -> frame.sumOf { (it / FULL_SCALE) * (it / FULL_SCALE) } }
    if (samples == 0 || energy == 0.0) {
        return fail(context.getString(R.string.checks_mic_silent, capture))
    }
    return ok(context.getString(R.string.checks_mic_ok, capture, "%.0f".format(20 * log10(sqrt(energy / samples)))))
}

private fun captureDescription(context: Context, config: VoiceConfig): String =
    context.getString(if (config.echoCancel) R.string.checks_capture_mono_aec else R.string.checks_capture_mono)

private fun checkRecordPermission(context: Context): CheckResult =
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
        ok(context.getString(R.string.checks_allowed))
    } else {
        fail(context.getString(R.string.checks_record_denied), CheckFix.RECORD_PERMISSION)
    }

private fun checkOverlay(context: Context): CheckResult =
    if (Settings.canDrawOverlays(context)) ok(context.getString(R.string.checks_allowed))
    else fail(context.getString(R.string.checks_overlay_denied), CheckFix.OVERLAY)

/**
 * Connected is what counts: a service the system lists but never binds (after a force-stop, on some Android 11 devices) leaves 小博
 * unable to touch the screen. Such a service, or a missing one, is repaired here and the outcome reported.
 */
private suspend fun checkAccessibility(context: Context): CheckResult = when {
    !loadAndroidUseEnabled(context) -> CheckResult(CheckState.OFF, context.getString(R.string.checks_accessibility_off))
    AndroidUseAccessibility.isConnected() -> ok(context.getString(R.string.checks_accessibility_connected))
    repairAndroidUse(context) -> warn(context.getString(R.string.checks_accessibility_repaired))
    AndroidUseAccessibility.isEnabled(context) ->
        fail(context.getString(R.string.checks_accessibility_stuck), CheckFix.ACCESSIBILITY)
    else -> fail(context.getString(R.string.checks_accessibility_disabled), CheckFix.ACCESSIBILITY)
}

private fun checkNotifications(context: Context): CheckResult = when {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ->
        ok(context.getString(R.string.checks_notifications_not_needed))
    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ->
        ok(context.getString(R.string.checks_allowed))
    else -> warn(context.getString(R.string.checks_notifications_denied), CheckFix.NOTIFICATIONS)
}

/** A failed request in words; the message never carries the key, only the status or the connection error. */
internal fun requestProblem(context: Context, error: Throwable): String {
    val status = STATUS_IN_MESSAGE.find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    return status?.let { statusProblem(context, it) }
        ?: context.getString(R.string.checks_cannot_connect, error.message ?: error.javaClass.simpleName)
}

internal fun statusProblem(context: Context, status: Int): String = when (status) {
    401, 403 -> context.getString(R.string.checks_http_key, status)
    404 -> context.getString(R.string.checks_http_404)
    429 -> context.getString(R.string.checks_http_429)
    in 500..599 -> context.getString(R.string.checks_http_server, status)
    else -> "HTTP $status"
}

private const val DASHSCOPE_MODELS_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/models"
private const val DEEPSEEK_MODEL_MARK = "deepseek"
private const val LISTED_MODELS = 5
private const val JEV_CHECK_TEXT = "你好小博，今天天气怎么样"
private const val TTS_CHECK_TEXT = "检查"
private const val CHECK_TIMEOUT_MS = 20_000L
private const val RECORD_CHECK_FRAMES = 5
private const val FULL_SCALE = 32_768.0
private val SUCCESS = 200..299
private val STATUS_IN_MESSAGE = Regex("""(?:status=|HTTP )(\d{3})""")
