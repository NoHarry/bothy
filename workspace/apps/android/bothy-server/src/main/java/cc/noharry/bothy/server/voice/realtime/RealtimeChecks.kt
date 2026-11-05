package cc.noharry.bothy.server.voice.realtime

import android.content.Context
import cc.noharry.agent.appserver.finding
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.protocol.text
import cc.noharry.bothy.protocol.BothyFindingCode
import cc.noharry.bothy.server.R
import cc.noharry.bothy.server.evidence.CheckFix
import cc.noharry.bothy.server.evidence.CheckResult
import cc.noharry.bothy.server.evidence.CheckState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the connection test says, and whether it failed. */
internal suspend fun realtimeConnectionLine(context: Context, config: RealtimeConfig): Pair<Finding, Boolean> {
    val key = withContext(Dispatchers.IO) { realtimeKey(context) }
    if (key.isEmpty()) return finding(BothyFindingCode.REALTIME_FAILED_NO_KEY) to true
    return probeRealtime(context, key, config).fold(
        onSuccess = {
            val voice = DoubaoRealtimeVoice.labelOf(context, config.voice)
            finding(BothyFindingCode.REALTIME_CONNECTED, it, voice, config.personaLabel(context)) to false
        },
        onFailure = { Finding(BothyFindingCode.REALTIME_FAILED, more = listOf(it.finding())) to true },
    )
}

private suspend fun probeRealtime(context: Context, key: String, config: RealtimeConfig): Result<Long> = try {
    withContext(Dispatchers.IO) { probeDoubaoRealtime(context, doubaoRealtimeClient(context), key, config) }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}

/** 端到端 in a report: on, 允许打断, the voice and the persona. */
internal fun RealtimeConfig.reportLine(context: Context): String = context.getString(
    R.string.realtime_report_line,
    context.getString(if (bargeIn) R.string.common_on else R.string.common_off),
    context.getString(if (lateSpeech) R.string.common_on else R.string.common_off),
    DoubaoRealtimeVoice.labelOf(context, voice),
    personaLabel(context),
)

/** 一键检查 → 端到端演示: off, no key, or how long a session takes to open. */
internal suspend fun checkRealtime(context: Context): CheckResult {
    val config = loadRealtimeConfig(context)
    if (!config.enabled) return CheckResult(CheckState.OFF, finding(BothyFindingCode.REALTIME_CHECK_OFF))
    val key = withContext(Dispatchers.IO) { realtimeKey(context) }
    if (key.isEmpty()) return CheckResult(CheckState.FAIL, finding(BothyFindingCode.REALTIME_CHECK_NO_KEY), CheckFix.VOICE)
    val voice = text(DoubaoRealtimeVoice.labelOf(context, config.voice))
    val bargeIn = finding(if (config.bargeIn) BothyFindingCode.COMMON_ON else BothyFindingCode.COMMON_OFF)
    return probeRealtime(context, key, config).fold(
        onSuccess = {
            val persona = text(config.personaLabel(context))
            CheckResult(CheckState.OK, Finding(BothyFindingCode.REALTIME_CHECK_OK, more = listOf(voice, persona, bargeIn, text(it))))
        },
        onFailure = { CheckResult(CheckState.FAIL, it.finding()) },
    )
}
