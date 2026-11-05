package cc.noharry.bothy.protocol

import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.IdsParams
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.serialization.Serializable

@Serializable
enum class CheckStatus { RUNNING, OK, WARN, FAIL, OFF }

/**
 * What fixes a check, for the screen to do: open a Settings section ([MODELS], [IMAGE_MODEL], [VOICE]), ask for a
 * permission, open a system screen, switch the wake word on, or start the overlay.
 */
@Serializable
enum class CheckFixKind { MODELS, IMAGE_MODEL, VOICE, RECORD_PERMISSION, OVERLAY, ACCESSIBILITY, NOTIFICATIONS, WAKE_ON, OVERLAY_START }

/** The checks Settings → 检查 runs, in order; [CheckRequests.RUN] takes their names. */
@Serializable
enum class CheckId {
    CHAT, DEEPSEEK, IMAGE, TOOLS, ASR, JEV, TTS, WAKE, MIC, COMPANION, REALTIME, RECORD_PERMISSION, OVERLAY, ACCESSIBILITY, NOTIFICATIONS,
}

/** The heading a check comes under. */
@Serializable
enum class CheckGroup { MODELS, VOICE, PERMISSIONS }

/** One check with its latest result: [finding] is what it found, [fix] what would set it right. */
@Serializable
data class CheckView(
    val id: CheckId,
    val group: CheckGroup,
    val status: CheckStatus? = null,
    val finding: Finding? = null,
    val fix: CheckFixKind? = null,
)

/** Settings → 检查: every check in order, whether some still run, and when the last round ended (epoch milliseconds). */
@Serializable
data class ChecksView(
    val checks: List<CheckView> = emptyList(),
    val running: Boolean = false,
    val checkedAtEpochMillis: Long? = null,
)

@Serializable
data class ChecksSection(val checks: ChecksView = ChecksView())

object CheckRequests {
    /** Runs the checks with these [CheckId] names, every check when none; results come in the checks section as each ends. */
    val RUN = RequestMethod("checks/run", IdsParams.serializer(), Empty.serializer())
}
