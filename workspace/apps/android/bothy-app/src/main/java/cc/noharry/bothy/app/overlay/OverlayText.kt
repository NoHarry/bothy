package cc.noharry.bothy.app.overlay

import android.content.res.Resources
import androidx.annotation.StringRes
import cc.noharry.agent.appserver.protocol.DelegationStage
import cc.noharry.agent.appserver.protocol.view.PARTLY_DONE_HEADLINE
import cc.noharry.agent.appserver.protocol.view.WAITING_FOR_RUN_TEXT
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.runSummaryText

/**
 * A HUD text as the card shows it. The projection keeps its fixed English words (a headline such as "Completed", the
 * run summary "Worked for 12s", the idle prompt) because code and the head unit match on them; here they take the
 * screen's language. Anything else, the assistant's own words, is shown as it is.
 */
internal fun Resources.overlayShownText(text: String): String = when (text) {
    WAITING_FOR_RUN_TEXT -> getString(R.string.overlay_waiting_for_run)
    "Agent working" -> getString(R.string.overlay_headline_working)
    "Completed" -> getString(R.string.overlay_headline_completed)
    "Failed" -> getString(R.string.overlay_headline_failed)
    "Stopped" -> getString(R.string.overlay_headline_stopped)
    "Agent ready" -> getString(R.string.overlay_headline_ready)
    PARTLY_DONE_HEADLINE -> getString(R.string.overlay_status_partly_done)
    else -> runSummaryText(text)
}

/** A delegation's stage for the card; the model reads the stage's own Chinese label. */
@StringRes
internal fun DelegationStage.overlayLabel(): Int = when (this) {
    DelegationStage.DELIVERED -> R.string.overlay_stage_delivered
    DelegationStage.STALLED -> R.string.overlay_stage_stalled
    DelegationStage.STARTED -> R.string.overlay_stage_started
    DelegationStage.WORKING -> R.string.overlay_stage_working
    DelegationStage.REPORTING -> R.string.overlay_stage_reporting
    DelegationStage.DONE -> R.string.overlay_stage_done
    DelegationStage.ASKING -> R.string.overlay_stage_asking
    DelegationStage.FAILED -> R.string.overlay_stage_failed
    DelegationStage.CANCELLED -> R.string.overlay_stage_cancelled
    DelegationStage.LOST -> R.string.overlay_stage_lost
}

/** "0:42" under an hour, else "1 小时 5 分" (in the screen's language). */
internal fun Resources.delegationElapsed(nowMillis: Long, sinceMillis: Long): String {
    val seconds = ((nowMillis - sinceMillis) / 1000).coerceAtLeast(0)
    return if (seconds < 3600) {
        "%d:%02d".format(seconds / 60, seconds % 60)
    } else {
        getString(R.string.overlay_elapsed_hours, seconds / 3600, seconds % 3600 / 60)
    }
}
