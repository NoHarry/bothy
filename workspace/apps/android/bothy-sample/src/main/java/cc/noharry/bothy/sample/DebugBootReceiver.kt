package cc.noharry.bothy.sample

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the app process after boot, so its A2A and ASR servers listen even if nobody ever opens the console
 * ([DebugSampleApplication] starts them). The screen-control service, when enabled, does the same.
 */
class DebugBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The clock or zone changed: 计划's due times move with it (boot itself starts the app, which checks them).
        if (intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
            DebugPlanRunner.init(context)
        }
    }
}
