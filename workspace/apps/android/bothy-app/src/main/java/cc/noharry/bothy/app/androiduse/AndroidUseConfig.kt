package cc.noharry.bothy.app.androiduse
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import cc.noharry.agent.androiduse.AndroidUseAccessibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 操作屏幕 (Android Use) switch on the 基础工具 page. On by default (owner, 2026-10-09), allowed while
 * driving and with no package allowlist for now. The platform signature grants WRITE_SECURE_SETTINGS,
 * so the switch enables the accessibility service itself; other services such as 可见即可说 stay on.
 */
internal fun loadAndroidUseEnabled(context: Context): Boolean =
    context.getSharedPreferences(ANDROID_USE_PREFS, Context.MODE_PRIVATE).getBoolean(ANDROID_USE_ENABLED, true)

internal fun saveAndroidUseEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(ANDROID_USE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(ANDROID_USE_ENABLED, enabled).apply()
    applyAndroidUseSwitch(context, enabled)
}

/** Keeps the accessibility service in step with the switch; runs at app start and on every toggle. */
internal fun applyAndroidUseSwitch(context: Context, enabled: Boolean = loadAndroidUseEnabled(context)): Boolean =
    if (enabled) AndroidUseAccessibility.enable(context) else AndroidUseAccessibility.disable(context)

/** The tools are mounted only while the switch is on and the service is bound, so they always work. */
internal fun androidUseReady(context: Context): Boolean =
    loadAndroidUseEnabled(context) && AndroidUseAccessibility.isConnected()

internal fun androidUseStatus(context: Context): String = when {
    !loadAndroidUseEnabled(context) -> "已关闭"
    AndroidUseAccessibility.isConnected() -> "已连接 · 助手可查看并操作屏幕"
    AndroidUseAccessibility.isEnabled(context) -> "已启用 · 等待系统连接无障碍服务"
    else -> "未能启用 · 需要平台签名授予 WRITE_SECURE_SETTINGS"
}

/**
 * Brings 操作屏幕 back when the switch is on but the system never binds the accessibility service (owner 2026-10-12:
 * 「怎么这次安装后android use没生效了」). On some Android 11 devices a force-stop drops the service from
 * `enabled_accessibility_services` and marks it crashed. The start-up [applyAndroidUseSwitch] lists it again, but
 * the system does not bind a crashed service until it sees it leave the list. Taking this entry out and, [gapMillis]
 * later, putting it back does that. True once the service is connected, within [waitMillis] of putting it back.
 */
internal suspend fun repairAndroidUse(
    context: Context,
    gapMillis: Long = REBIND_GAP_MS,
    waitMillis: Long = CONNECT_WAIT_MS,
): Boolean = repairLock.withLock {
    if (AndroidUseAccessibility.isConnected()) return@withLock true
    if (AndroidUseAccessibility.isEnabled(context) && !AndroidUseAccessibility.disable(context)) return@withLock false
    // The system must see the entry gone before it comes back, or it reads the same list and the service stays crashed.
    delay(gapMillis)
    if (!AndroidUseAccessibility.enable(context)) return@withLock false
    withTimeoutOrNull(waitMillis) {
        while (!AndroidUseAccessibility.isConnected()) delay(CONNECT_POLL_MS)
        true
    } ?: false
}

/**
 * At app start the system binds a listed service within a second or two. One the switch wants that is still not
 * connected after [STARTUP_CHECK_MS] is repaired once ([repairAndroidUse]); Settings → 检查 repairs it again.
 */
internal fun scheduleAndroidUseRepair(context: Context) {
    val app = context.applicationContext
    // On the main looper, not a timer of its own: unit tests create the application once per test, and their main
    // looper only moves when a test moves it, so no test's repair fires in the middle of another.
    Handler(Looper.getMainLooper()).postDelayed({
        if (!loadAndroidUseEnabled(app) || AndroidUseAccessibility.isConnected()) return@postDelayed
        Log.w(TAG, "android use: switched on but not connected ${STARTUP_CHECK_MS / 1000} s after start; re-enabling its service")
        repairScope.launch { Log.i(TAG, "android use repair: ${if (repairAndroidUse(app)) "connected" else "still not connected"}") }
    }, STARTUP_CHECK_MS)
}

private val repairScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private val repairLock = Mutex()
private const val TAG = "DebugAndroidUse"
private const val STARTUP_CHECK_MS = 8_000L
/** On the bench a gap of one second was enough for the system to drop the crashed entry. */
private const val REBIND_GAP_MS = 1_000L
private const val CONNECT_WAIT_MS = 5_000L
private const val CONNECT_POLL_MS = 200L
private const val ANDROID_USE_PREFS = "debug_android_use"
private const val ANDROID_USE_ENABLED = "enabled"
