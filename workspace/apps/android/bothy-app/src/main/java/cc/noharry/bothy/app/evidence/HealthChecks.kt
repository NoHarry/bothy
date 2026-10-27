package cc.noharry.bothy.app.evidence
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import cc.noharry.agent.androiduse.AndroidUseAccessibility
import cc.noharry.agent.transport.HttpRequest
import cc.noharry.bothy.voice.JevTurnJudge
import cc.noharry.bothy.voice.SherpaOnnxAsrEngine
import cc.noharry.bothy.voice.VoiceJudgeRequest
import cc.noharry.bothy.voice.VoiceTurnSnapshot
import cc.noharry.bothy.voice.WakeWordListener
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
internal enum class CheckFix(val label: String) {
    MODELS("去模型与服务"),
    IMAGE_MODEL("去生图模型"),
    VOICE("去语音设置"),
    RECORD_PERMISSION("授权"),
    OVERLAY("去授权"),
    ACCESSIBILITY("去打开"),
    NOTIFICATIONS("授权"),
    WAKE_ON("打开唤醒"),
    OVERLAY_START("打开悬浮窗"),
}

internal data class CheckResult(val state: CheckState, val detail: String, val fix: CheckFix? = null)

internal data class HealthCheck(val id: String, val group: String, val title: String, val run: suspend (Context) -> CheckResult)

/**
 * Settings → 检查: the selected models are asked once each (model lists, a Jev judgement, a short MiMo synthesis),
 * a DeepSeek model gets its official-API check, the microphone records half a second (kept in memory), and
 * permissions and services are read.
 */
internal val DEBUG_CHECKS: List<HealthCheck> = listOf(
    HealthCheck("chat", "模型", "对话模型", ::checkChatModel),
    HealthCheck("deepseek", "模型", "DeepSeek 官方能力", ::checkDeepSeek),
    HealthCheck("image", "模型", "生图模型", ::checkImageModel),
    HealthCheck("tools", "模型", "工具目录", ::checkToolCatalog),
    HealthCheck("asr", "语音", "语音识别", ::checkRecognition),
    HealthCheck("jev", "语音", "Jev 对话判断", ::checkJev),
    HealthCheck("tts", "语音", "语音播报", ::checkTts),
    HealthCheck("wake", "语音", "语音唤醒", ::checkWake),
    HealthCheck("mic", "语音", "录音", ::checkRecording),
    HealthCheck("companion", "语音", "陪聊", ::checkCompanion),
    HealthCheck("realtime", "语音", "端到端演示", ::checkRealtime),
    HealthCheck("record_permission", "权限与服务", "麦克风权限", ::checkRecordPermission),
    HealthCheck("overlay", "权限与服务", "悬浮窗权限", ::checkOverlay),
    HealthCheck("accessibility", "权限与服务", "无障碍服务（操作屏幕）", ::checkAccessibility),
    HealthCheck("notifications", "权限与服务", "通知权限", ::checkNotifications),
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
                CheckResult(CheckState.FAIL, "超时：${CHECK_TIMEOUT_MS / 1000} 秒没有结果")
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
    if (!config.isReady(profile)) return fail("$name · ${config.missingMessage(profile)}", CheckFix.MODELS)
    val key = profile.apiKey.ifBlank { DEFAULT_LOCAL_API_KEY.takeIf { profile.baseUrl.isLocalBaseUrl() }.orEmpty() }
    return runCatching { ModelCatalogClient(BenchHttpTransport(context)).fetch(profile.provider, key, profile.baseUrl) }.fold(
        onSuccess = { ids -> chatModelListed(name, profile.model, ids) },
        onFailure = { fail("$name · ${requestProblem(it)}", CheckFix.MODELS) },
    )
}

/**
 * The selected model against the service's list. Not listed reads as the selection being stale, with what is listed
 * (owner 2026-10-19 took 「列出的模型里没有 deepseek-v4-flash」 for the check having that model built in).
 */
internal fun chatModelListed(name: String, model: String, ids: List<String>): CheckResult {
    if (ids.isEmpty() || model in ids) return ok("$name · 服务可用")
    val listed = ids.take(LISTED_MODELS).joinToString("、") + if (ids.size > LISTED_MODELS) " 等 ${ids.size} 个" else ""
    return warn("$name · 服务可用，但当前选的 $model 不在它列出的模型里（可能已下线），请在输入框旁换一个：$listed", CheckFix.MODELS)
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
        return CheckResult(CheckState.OFF, "${profile.displayLabel} · ${profile.model} 不是 DeepSeek 模型，没检测")
    }
    val key = config.effectiveApiKey(profile)
    if (key.isBlank()) return fail("${profile.displayLabel} · 缺少 API key", CheckFix.MODELS)
    if (!official) return DeepSeekProbes.check(context, profile, key).checkResult(profile.displayLabel)
    val features = if (profile.endpoint == ProviderEndpoint.MESSAGES) "原生延迟工具、Search/Fetch" else "Search/Fetch"
    return fetchDeepSeekBalance(key).fold(
        onSuccess = { balance ->
            if (balance.available) ok("官方接口 · $features 自动可用 · 余额：${balance.summary()}")
            else warn("官方接口 · 余额：${balance.summary()}", CheckFix.MODELS)
        },
        onFailure = { fail("官方接口 · 查余额失败：${it.message}", CheckFix.MODELS) },
    )
}

/** A relay's DeepSeek check as one line: what passed, and why the rest did not. */
internal fun DeepSeekProbe.checkResult(label: String): CheckResult {
    val checks = listOfNotNull("余额" to balance, "Search/Fetch" to tools, passthrough?.let { "原生延迟工具" to it })
    val detail = "$label · " + checks.joinToString(" · ") { (name, check) -> if (check.passed) "$name ✓" else "$name ✗ ${check.note}" }
    return if (checks.any { it.second.passed }) ok(detail) else warn("$detail；照常能用，只是没有 DeepSeek 专属能力", CheckFix.MODELS)
}

private suspend fun checkImageModel(context: Context): CheckResult {
    val config = loadImageModelConfig(context)
    when {
        !config.enabled -> return CheckResult(CheckState.OFF, "没打开", CheckFix.IMAGE_MODEL)
        !config.hasApiKey -> return fail("缺少 API key", CheckFix.IMAGE_MODEL)
        config.model.isBlank() -> return fail("没选模型", CheckFix.IMAGE_MODEL)
    }
    config.validationError()?.let { return fail(it, CheckFix.IMAGE_MODEL) }
    return fetchImageModels(context, config.baseUrl).fold(
        onSuccess = { ids ->
            if (ids.isEmpty() || config.model in ids) ok("${config.model} · 服务可用")
            else warn("${config.model} · 服务可用，但它列出的模型里没有这个", CheckFix.IMAGE_MODEL)
        },
        onFailure = { fail("${config.model} · ${requestProblem(it)}", CheckFix.IMAGE_MODEL) },
    )
}

private suspend fun checkRecognition(context: Context): CheckResult {
    val config = loadVoiceConfig(context)
    if (config.engine == VoiceEngine.LOCAL) {
        return SherpaOnnxAsrEngine.availabilityError(context.assets)?.let { fail(it) } ?: ok("本机识别 · 模型已装")
    }
    val key = loadDashscopeKey(context)
    if (key.isEmpty()) return fail("用百炼识别，但缺少百炼 key：到「密钥」页填写", CheckFix.VOICE)
    val status = BenchHttpTransport(context).execute(
        HttpRequest("GET", DASHSCOPE_MODELS_URL, mapOf("Authorization" to "Bearer $key")),
    ).statusCode
    return if (status in SUCCESS) ok("百炼 · ${config.dashscopeModel} · key 可用")
    else fail("百炼 · ${statusProblem(status)}", CheckFix.VOICE)
}

private suspend fun checkJev(context: Context): CheckResult {
    val jev = loadJevConfig(context)
    val key = loadJevKey(context)
    if (key.isEmpty()) return CheckResult(CheckState.OFF, "没配置：语音的每句话直接交给 agent（到「密钥」页填 Jev key 可开启判断）", CheckFix.VOICE)
    if (!loadVoiceConfig(context).jevTurns) return CheckResult(CheckState.OFF, "已关闭：语音的每句话直接交给 agent", CheckFix.VOICE)
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
    if (!tts.enabled && tts.apiKey.isNotBlank()) return CheckResult(CheckState.OFF, "播报回复已关闭", CheckFix.VOICE)
    if (!tts.ready) return fail(ttsStatusLine(tts), CheckFix.VOICE)
    return runCatching { ttsClient(BenchHttpTransport(context)).synthesize(tts, TTS_CHECK_TEXT) }.fold(
        onSuccess = { audio -> ok("${tts.label} · 合成了 ${audio.bytes.size / 1024} KB") },
        onFailure = { fail("${tts.label} · ${it.message}", CheckFix.VOICE) },
    )
}

private suspend fun checkWake(context: Context): CheckResult {
    WakeWordListener.availabilityError(context.assets)?.let { return fail(it) }
    if (!loadVoiceConfig(context).overlayWake) return warn("没打开：说「你好，小博」不会响应", CheckFix.WAKE_ON)
    // The listener lives on the main thread; reading the switch there also starts it when nothing blocks it.
    val status = withContext(Dispatchers.Main) {
        WakeWord.load(context)
        WakeWord.status.value
    }
    return if (status.listening) ok("正在听「你好，小博」和「OK Bothy」") else warn("已打开，但没在听：${status.message}")
}

/** Half a second through the configured capture: silence everywhere means the source is held or muted elsewhere. */
private suspend fun checkRecording(context: Context): CheckResult {
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        return fail("没有麦克风权限", CheckFix.RECORD_PERMISSION)
    }
    val config = loadVoiceConfig(context)
    val capture = captureDescription(config)
    if (!WakeWord.micLease.tryLock()) return ok("$capture · 麦克风正被唤醒词或语音会话使用")
    val frames = try {
        microphone(config).frames().take(RECORD_CHECK_FRAMES).toList()
    } finally {
        WakeWord.micLease.unlock()
    }
    val samples = frames.sumOf { it.size }
    val energy = frames.sumOf { frame -> frame.sumOf { (it / FULL_SCALE) * (it / FULL_SCALE) } }
    if (samples == 0 || energy == 0.0) {
        return fail("$capture · 录到的全是静音：录音可能被别的应用占用（如系统语音助手）或被系统静音")
    }
    return ok("$capture · 有声音（${"%.0f".format(20 * log10(sqrt(energy / samples)))} dBFS）")
}

private fun captureDescription(config: VoiceConfig): String = if (config.echoCancel) "单声道 · 系统回声消除" else "单声道"

private fun checkRecordPermission(context: Context): CheckResult =
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) ok("已授权")
    else fail("没授权：语音和唤醒都用不了", CheckFix.RECORD_PERMISSION)

private fun checkOverlay(context: Context): CheckResult =
    if (Settings.canDrawOverlays(context)) ok("已授权") else fail("没授权：小博悬浮窗和语音唤醒都需要", CheckFix.OVERLAY)

/**
 * Connected is what counts: a service the system lists but never binds (after a force-stop, on some Android 11 devices) leaves 小博
 * unable to touch the screen. Such a service, or a missing one, is repaired here and the outcome reported.
 */
private suspend fun checkAccessibility(context: Context): CheckResult = when {
    !loadAndroidUseEnabled(context) -> CheckResult(CheckState.OFF, "操作屏幕没打开（基础工具）")
    AndroidUseAccessibility.isConnected() -> ok("已连接")
    repairAndroidUse(context) -> warn("系统没连上它（App 被强行停止后常见），已重新打开并连上")
    AndroidUseAccessibility.isEnabled(context) -> fail("已打开但系统一直没连上，重新打开也没用：小博不能操作屏幕", CheckFix.ACCESSIBILITY)
    else -> fail("没打开：小博不能操作屏幕", CheckFix.ACCESSIBILITY)
}

private fun checkNotifications(context: Context): CheckResult = when {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> ok("这个系统版本不需要")
    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> ok("已授权")
    else -> warn("没授权：后台录音的通知不显示，不影响录音和唤醒", CheckFix.NOTIFICATIONS)
}

/** A failed request in words; the message never carries the key, only the status or the connection error. */
internal fun requestProblem(error: Throwable): String {
    val status = STATUS_IN_MESSAGE.find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    return status?.let(::statusProblem) ?: "连不上：${error.message ?: error.javaClass.simpleName}"
}

internal fun statusProblem(status: Int): String = when (status) {
    401, 403 -> "HTTP $status：key 不对或没有权限"
    404 -> "HTTP 404：地址不对"
    429 -> "HTTP 429：请求太多或额度用完"
    in 500..599 -> "HTTP $status：服务出错"
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
