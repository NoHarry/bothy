package cc.noharry.bothy.app.plans
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import cc.noharry.bothy.app.voice.PLAN_CHIME_PEAK
import cc.noharry.bothy.app.voice.SoftNote
import cc.noharry.bothy.app.voice.SoftTimbre
import cc.noharry.bothy.app.voice.softNotesOf
import cc.noharry.bothy.app.voice.softTimbreOf

/** A chime to try instead of the plan chime: other [notes], another [timbre] (null keeps the plan chime's), or [peak]. */
internal data class ChimeTry(val notes: List<SoftNote>?, val timbre: SoftTimbre?, val peak: Double)

/**
 * adb only (DUMP): the plan popup and chime now, without making a plan and waiting for it, to see and hear them on the
 * car itself. `--es notes "329.63,0,8;493.88,0.13,5"` plays other notes (see [softNotesOf]), `--es timbre
 * "octave=0.35,room=0.25"` another sound (see [softTimbreOf]), `--ef peak 0.6` another loudness, `--es title "2 电钢琴"`
 * names the try in the popup, `--ez popup false` only plays the chime.
 */
class PlanNoticeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PLAN_NOTICE_ACTION) return
        val tried = try {
            chimeTry(intent)
        } catch (e: IllegalArgumentException) {
            Log.w(PLAN_LOG_TAG, "PLAN_NOTICE ignored: ${e.message}")
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE)?.trim()?.takeIf(String::isNotEmpty)
        if (intent.getBooleanExtra(EXTRA_POPUP, true)) {
            PlanNotice.show(context, title?.let { SAMPLE_ENTRY.copy(title = it) } ?: SAMPLE_ENTRY, tried)
        } else {
            PlanNotice.chime(tried)
        }
        Log.i(PLAN_LOG_TAG, "PLAN_NOTICE ${title ?: "sample"} notes=${tried?.notes?.size ?: "default"} timbre=${tried?.timbre ?: "default"} peak=${tried?.peak ?: PLAN_CHIME_PEAK}")
    }

    /** Null when nothing about the chime was asked for: the plan chime as it is. */
    private fun chimeTry(intent: Intent): ChimeTry? {
        if (!intent.hasExtra(EXTRA_NOTES) && !intent.hasExtra(EXTRA_TIMBRE) && !intent.hasExtra(EXTRA_PEAK)) return null
        return ChimeTry(
            notes = intent.getStringExtra(EXTRA_NOTES)?.let(::softNotesOf),
            timbre = intent.getStringExtra(EXTRA_TIMBRE)?.let { softTimbreOf(it) },
            peak = if (intent.hasExtra(EXTRA_PEAK)) intent.getFloatExtra(EXTRA_PEAK, 0f).toDouble().coerceIn(MIN_PEAK, MAX_PEAK) else PLAN_CHIME_PEAK,
        )
    }
}

private val SAMPLE_ENTRY = PlanNoticeEntry("sample", "提醒", "示例计划", "提醒你：计划到点时就是这样", null)
private const val PLAN_NOTICE_ACTION = "cc.noharry.bothy.DEBUG_PLAN_NOTICE"
private const val EXTRA_NOTES = "notes"
private const val EXTRA_TIMBRE = "timbre"
private const val EXTRA_PEAK = "peak"
private const val EXTRA_TITLE = "title"
private const val EXTRA_POPUP = "popup"
private const val MIN_PEAK = 0.03
private const val MAX_PEAK = 0.9
