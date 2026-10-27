package cc.noharry.bothy.app
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cc.noharry.bothy.app.plans.PlanRunner

/**
 * Starts the app process after boot, so its A2A and ASR servers listen even if nobody ever opens the console
 * ([BothyApplication] starts them). The screen-control service, when enabled, does the same.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The clock or zone changed: 计划's due times move with it (boot itself starts the app, which checks them).
        if (intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
            PlanRunner.init(context)
        }
    }
}
