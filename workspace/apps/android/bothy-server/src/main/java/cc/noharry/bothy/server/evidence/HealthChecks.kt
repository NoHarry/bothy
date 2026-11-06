package cc.noharry.bothy.server.evidence
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import cc.noharry.agent.androiduse.AndroidUseAccessibility
import cc.noharry.agent.appserver.FoundException
import cc.noharry.agent.appserver.androiduse.loadAndroidUseEnabled
import cc.noharry.agent.appserver.androiduse.repairAndroidUse
import cc.noharry.agent.appserver.evidence.BenchHttpTransport
import cc.noharry.agent.appserver.finding
import cc.noharry.agent.appserver.image.code
import cc.noharry.agent.appserver.image.fetchImageModels
import cc.noharry.agent.appserver.image.loadImageModelConfig
import cc.noharry.agent.appserver.model.DEFAULT_LOCAL_API_KEY
import cc.noharry.agent.appserver.deepseek.DeepSeekProbe
import cc.noharry.agent.appserver.deepseek.DeepSeekProbes
import cc.noharry.agent.appserver.model.ModelCatalogClient
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.deepseek.fetchDeepSeekBalance
import cc.noharry.agent.appserver.deepseek.isDeepSeek
import cc.noharry.agent.appserver.model.isLocalBaseUrl
import cc.noharry.agent.appserver.deepseek.view
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.FindingKind
import cc.noharry.agent.appserver.protocol.ModelUsage
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.protocol.listing
import cc.noharry.agent.appserver.protocol.parts
import cc.noharry.agent.appserver.protocol.text
import cc.noharry.agent.appserver.usage.UsageStore
import cc.noharry.agent.transport.HttpRequest
import cc.noharry.bothy.protocol.BothyFindingCode
import cc.noharry.bothy.protocol.CheckGroup
import cc.noharry.bothy.protocol.CheckId
import cc.noharry.bothy.protocol.VoiceEngine
import cc.noharry.bothy.server.companion.checkCompanion
import cc.noharry.bothy.server.tools.checkToolCatalog
import cc.noharry.bothy.server.voice.WakeWord
import cc.noharry.bothy.server.voice.XIAOBO_VOICE_IDENTITY
import cc.noharry.bothy.server.voice.loadDashscopeKey
import cc.noharry.bothy.server.voice.loadJevConfig
import cc.noharry.bothy.server.voice.loadJevKey
import cc.noharry.bothy.server.voice.loadVoiceConfig
import cc.noharry.bothy.server.voice.localAsrUnavailable
import cc.noharry.bothy.server.voice.microphone
import cc.noharry.bothy.server.voice.realtime.checkRealtime
import cc.noharry.bothy.server.voice.tts.labelFinding
import cc.noharry.bothy.server.voice.tts.ttsClient
import cc.noharry.bothy.server.voice.tts.ttsConfig
import cc.noharry.bothy.server.voice.ttsStatus
import cc.noharry.bothy.server.voice.wakeModelUnavailable
import cc.noharry.bothy.voice.JevTurnJudge
import cc.noharry.bothy.voice.VoiceJudgeRequest
import cc.noharry.bothy.voice.VoiceTurnSnapshot
import kotlin.math.log10
import kotlin.math.roundToInt
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
internal enum class CheckFix { MODELS, IMAGE_MODEL, VOICE, RECORD_PERMISSION, OVERLAY, ACCESSIBILITY, NOTIFICATIONS, WAKE_ON, OVERLAY_START }

/** [finding] is what the check found, for the screen to word; none while it runs. */
internal data class CheckResult(val state: CheckState, val finding: Finding?, val fix: CheckFix? = null)

internal data class HealthCheck(
    val id: CheckId,
    val group: CheckGroup,
    val run: suspend (Context) -> CheckResult,
)

/**
 * Settings → 检查: the selected models are asked once each (model lists, a Jev judgement, a short MiMo synthesis),
 * a DeepSeek model gets its official-API check, the microphone records half a second (kept in memory), and
 * permissions and services are read.
 */
internal val DEBUG_CHECKS: List<HealthCheck> = listOf(
    HealthCheck(CheckId.CHAT, CheckGroup.MODELS, ::checkChatModel),
    HealthCheck(CheckId.DEEPSEEK, CheckGroup.MODELS, ::checkDeepSeek),
    HealthCheck(CheckId.IMAGE, CheckGroup.MODELS, ::checkImageModel),
    HealthCheck(CheckId.TOOLS, CheckGroup.MODELS, ::checkToolCatalog),
    HealthCheck(CheckId.ASR, CheckGroup.VOICE, ::checkRecognition),
    HealthCheck(CheckId.JEV, CheckGroup.VOICE, ::checkJev),
    HealthCheck(CheckId.TTS, CheckGroup.VOICE, ::checkTts),
    HealthCheck(CheckId.WAKE, CheckGroup.VOICE, ::checkWake),
    HealthCheck(CheckId.MIC, CheckGroup.VOICE, ::checkRecording),
    HealthCheck(CheckId.COMPANION, CheckGroup.VOICE, ::checkCompanion),
    HealthCheck(CheckId.REALTIME, CheckGroup.VOICE, ::checkRealtime),
    HealthCheck(CheckId.RECORD_PERMISSION, CheckGroup.PERMISSIONS, ::checkRecordPermission),
    HealthCheck(CheckId.OVERLAY, CheckGroup.PERMISSIONS, ::checkOverlay),
    HealthCheck(CheckId.ACCESSIBILITY, CheckGroup.PERMISSIONS, ::checkAccessibility),
    HealthCheck(CheckId.NOTIFICATIONS, CheckGroup.PERMISSIONS, ::checkNotifications),
)

/** Runs [checks] at once, each within [CHECK_TIMEOUT_MS]; [onResult] hears each on the caller's thread as it ends. */
internal suspend fun runHealthChecks(
    context: Context,
    checks: List<HealthCheck> = DEBUG_CHECKS,
    onResult: (CheckId, CheckResult) -> Unit,
) = coroutineScope {
    checks.map { check ->
        launch {
            val result = try {
                withTimeout(CHECK_TIMEOUT_MS) { withContext(Dispatchers.IO) { check.run(context.applicationContext) } }
            } catch (_: TimeoutCancellationException) {
                CheckResult(CheckState.FAIL, finding(BothyFindingCode.CHECKS_TIMEOUT, CHECK_TIMEOUT_MS / 1000))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CheckResult(CheckState.FAIL, error.finding())
            }
            onResult(check.id, result)
        }
    }.joinAll()
}

private fun ok(finding: Finding) = CheckResult(CheckState.OK, finding)
private fun ok(code: FindingKind) = ok(finding(code))
private fun warn(finding: Finding, fix: CheckFix? = null) = CheckResult(CheckState.WARN, finding, fix)
private fun fail(finding: Finding, fix: CheckFix? = null) = CheckResult(CheckState.FAIL, finding, fix)
private fun off(code: FindingKind, fix: CheckFix? = null) = CheckResult(CheckState.OFF, finding(code), fix)

internal suspend fun checkChatModel(context: Context): CheckResult {
    val config = ProviderConfig.load(context)
    val profile = config.activeProfile
    val name = parts(text(profile.displayLabel), text(profile.model))
    if (!config.isReady(profile)) return fail(parts(name, config.missingFinding(profile)), CheckFix.MODELS)
    val key = profile.apiKey.ifBlank { DEFAULT_LOCAL_API_KEY.takeIf { profile.baseUrl.isLocalBaseUrl() }.orEmpty() }
    return runCatching { ModelCatalogClient(BenchHttpTransport(context)).fetch(profile.provider, key, profile.baseUrl) }.fold(
        onSuccess = { ids -> chatModelListed(name, profile.model, ids) },
        onFailure = { fail(parts(name, requestProblem(it)), CheckFix.MODELS) },
    )
}

/**
 * The selected model against the service's list. Not listed reads as the selection being stale, with what is listed
 * (owner 2026-10-19 took 「列出的模型里没有 deepseek-v4-flash」 for the check having that model built in).
 */
internal fun chatModelListed(name: Finding, model: String, ids: List<String>): CheckResult {
    if (ids.isEmpty() || model in ids) return ok(Finding(BothyFindingCode.CHECKS_SERVICE_AVAILABLE, more = listOf(name)))
    val shown = listing(ids.take(LISTED_MODELS).map(::text))
    val listed = if (ids.size > LISTED_MODELS) Finding(BothyFindingCode.CHECKS_LISTED_MORE, more = listOf(shown, text(ids.size)))
    else shown
    return warn(Finding(BothyFindingCode.CHECKS_MODEL_NOT_LISTED, more = listOf(name, text(model), listed)), CheckFix.MODELS)
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
        return CheckResult(CheckState.OFF, finding(BothyFindingCode.CHECKS_NOT_DEEPSEEK, profile.displayLabel, profile.model))
    }
    val key = config.effectiveApiKey(profile)
    if (key.isBlank()) return fail(finding(BothyFindingCode.CHECKS_LABEL_NO_KEY, profile.displayLabel), CheckFix.MODELS)
    if (!official) return DeepSeekProbes.check(context, profile, key).checkResult(profile.displayLabel)
    val features = if (profile.endpoint == ProviderEndpoint.MESSAGES) {
        finding(BothyFindingCode.CHECKS_DEEPSEEK_FEATURES_MESSAGES)
    } else {
        text("Search/Fetch")
    }
    return fetchDeepSeekBalance(key).fold(
        onSuccess = { balance ->
            if (balance.available) ok(Finding(BothyFindingCode.CHECKS_DEEPSEEK_OFFICIAL_OK, more = listOf(features), balance = balance.view()))
            else warn(Finding(BothyFindingCode.CHECKS_DEEPSEEK_OFFICIAL_BALANCE, balance = balance.view()), CheckFix.MODELS)
        },
        onFailure = { fail(Finding(BothyFindingCode.CHECKS_DEEPSEEK_OFFICIAL_FAILED, more = listOf(it.finding())), CheckFix.MODELS) },
    )
}

/** A relay's DeepSeek check as one line: what passed, and why the rest did not. */
internal fun DeepSeekProbe.checkResult(label: String): CheckResult {
    val checks = listOfNotNull(
        finding(BothyFindingCode.CHECKS_DEEPSEEK_BALANCE) to balance,
        text("Search/Fetch") to tools,
        passthrough?.let { finding(BothyFindingCode.CHECKS_DEEPSEEK_NATIVE_TOOLS) to it },
    )
    val results = checks.map { (name, check) ->
        if (check.passed) Finding(BothyFindingCode.CHECKS_PART_PASSED, more = listOf(name))
        else Finding(BothyFindingCode.CHECKS_PART_FAILED, more = listOf(name, check.note))
    }
    val detail = parts(text(label), *results.toTypedArray())
    return if (checks.any { it.second.passed }) {
        ok(detail)
    } else {
        warn(Finding(BothyFindingCode.CHECKS_DEEPSEEK_NONE, more = listOf(detail)), CheckFix.MODELS)
    }
}

private suspend fun checkImageModel(context: Context): CheckResult {
    val config = loadImageModelConfig(context)
    when {
        !config.enabled -> return off(BothyFindingCode.CHECKS_OFF, CheckFix.IMAGE_MODEL)
        !config.hasApiKey -> return fail(finding(BothyFindingCode.CHECKS_NO_KEY), CheckFix.IMAGE_MODEL)
        config.model.isBlank() -> return fail(finding(BothyFindingCode.CHECKS_NO_MODEL), CheckFix.IMAGE_MODEL)
    }
    config.validationProblem()?.let { return fail(finding(it.code), CheckFix.IMAGE_MODEL) }
    return fetchImageModels(context, config.baseUrl).fold(
        onSuccess = { ids ->
            if (ids.isEmpty() || config.model in ids) {
                ok(Finding(BothyFindingCode.CHECKS_SERVICE_AVAILABLE, more = listOf(text(config.model))))
            } else {
                warn(finding(BothyFindingCode.CHECKS_IMAGE_NOT_LISTED, config.model), CheckFix.IMAGE_MODEL)
            }
        },
        onFailure = { fail(parts(text(config.model), requestProblem(it)), CheckFix.IMAGE_MODEL) },
    )
}

private suspend fun checkRecognition(context: Context): CheckResult {
    val config = loadVoiceConfig(context)
    if (config.engine == VoiceEngine.LOCAL) {
        return localAsrUnavailable(context)?.let { fail(it) } ?: ok(BothyFindingCode.CHECKS_ASR_LOCAL_OK)
    }
    val key = loadDashscopeKey(context)
    if (key.isEmpty()) return fail(finding(BothyFindingCode.CHECKS_ASR_NO_KEY), CheckFix.VOICE)
    val status = BenchHttpTransport(context).execute(
        HttpRequest("GET", DASHSCOPE_MODELS_URL, mapOf("Authorization" to "Bearer $key")),
    ).statusCode
    return if (status in SUCCESS) ok(finding(BothyFindingCode.CHECKS_ASR_OK, config.dashscopeModel))
    else fail(Finding(BothyFindingCode.CHECKS_ASR_FAILED, more = listOf(statusProblem(status))), CheckFix.VOICE)
}

private suspend fun checkJev(context: Context): CheckResult {
    val jev = loadJevConfig(context)
    val key = loadJevKey(context)
    if (key.isEmpty()) return off(BothyFindingCode.CHECKS_JEV_NOT_SET, CheckFix.VOICE)
    if (!loadVoiceConfig(context).jevTurns) return off(BothyFindingCode.CHECKS_JEV_OFF, CheckFix.VOICE)
    val judge = JevTurnJudge(jev.endpoint, key, jev.model, jev.timeoutMillis, identity = XIAOBO_VOICE_IDENTITY, onUsage = { input, output ->
        UsageStore.record(jev.model, ModelUsage(1, input.toLong(), outputTokens = output.toLong()))
    })
    val started = SystemClock.elapsedRealtime()
    return runCatching {
        judge.judge(VoiceJudgeRequest(VoiceTurnSnapshot(1, JEV_CHECK_TEXT, emptyList(), 2_000, 800, asrFinal = true), emptyList()))
    }.fold(
        onSuccess = { ok(parts(text(jev.model), finding(BothyFindingCode.CHECKS_MILLIS, SystemClock.elapsedRealtime() - started))) },
        onFailure = { fail(parts(text(jev.model), it.finding()), CheckFix.VOICE) },
    )
}

private suspend fun checkTts(context: Context): CheckResult {
    val tts = ProviderConfig.load(context).ttsConfig()
    if (!tts.enabled && tts.apiKey.isNotBlank()) return off(BothyFindingCode.CHECKS_TTS_OFF, CheckFix.VOICE)
    if (!tts.ready) return fail(ttsStatus(context, tts), CheckFix.VOICE)
    return runCatching { ttsClient(BenchHttpTransport(context)).synthesize(tts, TTS_CHECK_TEXT) }.fold(
        onSuccess = { audio -> ok(Finding(BothyFindingCode.CHECKS_TTS_OK, more = listOf(tts.labelFinding(context), text(audio.bytes.size / 1024)))) },
        onFailure = { fail(parts(tts.labelFinding(context), it.finding()), CheckFix.VOICE) },
    )
}

private suspend fun checkWake(context: Context): CheckResult {
    wakeModelUnavailable(context)?.let { return fail(it) }
    if (!loadVoiceConfig(context).overlayWake) return warn(finding(BothyFindingCode.CHECKS_WAKE_OFF), CheckFix.WAKE_ON)
    // The listener lives on the main thread; reading the switch there also starts it when nothing blocks it.
    val status = withContext(Dispatchers.Main) {
        WakeWord.load(context)
        WakeWord.status.value
    }
    return if (status.listening) {
        ok(BothyFindingCode.CHECKS_WAKE_OK)
    } else {
        warn(Finding(BothyFindingCode.CHECKS_WAKE_NOT_LISTENING, more = listOfNotNull(status.finding)))
    }
}

/** Half a second through the configured capture: silence everywhere means the source is held or muted elsewhere. */
private suspend fun checkRecording(context: Context): CheckResult {
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        return fail(finding(BothyFindingCode.CHECKS_MIC_NO_PERMISSION), CheckFix.RECORD_PERMISSION)
    }
    val config = loadVoiceConfig(context)
    val capture = finding(if (config.echoCancel) BothyFindingCode.CHECKS_CAPTURE_MONO_AEC else BothyFindingCode.CHECKS_CAPTURE_MONO)
    if (!WakeWord.micLease.tryLock()) return ok(Finding(BothyFindingCode.CHECKS_MIC_BUSY, more = listOf(capture)))
    val frames = try {
        microphone(config).frames().take(RECORD_CHECK_FRAMES).toList()
    } finally {
        WakeWord.micLease.unlock()
    }
    val samples = frames.sumOf { it.size }
    val energy = frames.sumOf { frame -> frame.sumOf { (it / FULL_SCALE) * (it / FULL_SCALE) } }
    if (samples == 0 || energy == 0.0) return fail(Finding(BothyFindingCode.CHECKS_MIC_SILENT, more = listOf(capture)))
    val decibels = (20 * log10(sqrt(energy / samples))).roundToInt()
    return ok(Finding(BothyFindingCode.CHECKS_MIC_OK, more = listOf(capture, text(decibels))))
}

private fun checkRecordPermission(context: Context): CheckResult =
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
        ok(BothyFindingCode.CHECKS_ALLOWED)
    } else {
        fail(finding(BothyFindingCode.CHECKS_RECORD_DENIED), CheckFix.RECORD_PERMISSION)
    }

private fun checkOverlay(context: Context): CheckResult =
    if (Settings.canDrawOverlays(context)) ok(BothyFindingCode.CHECKS_ALLOWED)
    else fail(finding(BothyFindingCode.CHECKS_OVERLAY_DENIED), CheckFix.OVERLAY)

/**
 * Connected is what counts: a service the system lists but never binds (after a force-stop, on some Android 11 devices) leaves 小博
 * unable to touch the screen. Such a service, or a missing one, is repaired here and the outcome reported.
 */
private suspend fun checkAccessibility(context: Context): CheckResult = when {
    !loadAndroidUseEnabled(context) -> off(BothyFindingCode.CHECKS_ACCESSIBILITY_OFF)
    AndroidUseAccessibility.isConnected() -> ok(BothyFindingCode.CHECKS_ACCESSIBILITY_CONNECTED)
    repairAndroidUse(context) -> warn(finding(BothyFindingCode.CHECKS_ACCESSIBILITY_REPAIRED))
    AndroidUseAccessibility.isEnabled(context) -> fail(finding(BothyFindingCode.CHECKS_ACCESSIBILITY_STUCK), CheckFix.ACCESSIBILITY)
    else -> fail(finding(BothyFindingCode.CHECKS_ACCESSIBILITY_DISABLED), CheckFix.ACCESSIBILITY)
}

private fun checkNotifications(context: Context): CheckResult = when {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> ok(BothyFindingCode.CHECKS_NOTIFICATIONS_NOT_NEEDED)
    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ->
        ok(BothyFindingCode.CHECKS_ALLOWED)
    else -> warn(finding(BothyFindingCode.CHECKS_NOTIFICATIONS_DENIED), CheckFix.NOTIFICATIONS)
}

/** A failed request: its HTTP status when the message carries one, else the connection error; never the key. */
internal fun requestProblem(error: Throwable): Finding {
    if (error is FoundException) return error.finding
    val status = STATUS_IN_MESSAGE.find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    return status?.let(::statusProblem)
        ?: finding(BothyFindingCode.CHECKS_CANNOT_CONNECT, error.message ?: error.javaClass.simpleName)
}

internal fun statusProblem(status: Int): Finding = when (status) {
    401, 403 -> finding(BothyFindingCode.CHECKS_HTTP_KEY, status)
    404 -> finding(BothyFindingCode.CHECKS_HTTP_404)
    429 -> finding(BothyFindingCode.CHECKS_HTTP_429)
    in 500..599 -> finding(BothyFindingCode.CHECKS_HTTP_SERVER, status)
    else -> finding(FindingCode.HTTP_STATUS, status)
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
