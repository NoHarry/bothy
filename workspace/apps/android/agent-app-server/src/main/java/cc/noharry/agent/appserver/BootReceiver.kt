package cc.noharry.agent.appserver

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import cc.noharry.agent.appserver.plans.PlanRunner
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.BuiltInFeatures

/**
 * Starts the app's process after a reboot, so the host's work resumes even if nobody opens the app, and after an
 * update: alarms set before it may name a receiver the update moved, and starting sets them anew. Sets plans again when
 * the clock or the zone changes. Off in the server's manifest; at start the server turns it on only for a host that
 * wants it ([BuiltInFeatures.plans], [BuiltInFeatures.startOnBoot]), so no app starts at boot just because it carries
 * the server. A host that wants it may also turn it on in its own manifest, so the update that first brings it starts
 * the app too.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The clock or zone changed: 计划's due times move with it (boot itself starts the app, which checks them).
        val clock = intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED
        if (clock && AppHost.builtIns.plans) PlanRunner.init(context)
    }

    internal companion object {
        /** Turns the receiver on or off to match the host; the system keeps the setting across restarts. */
        fun follow(context: Context, wanted: Boolean) {
            val component = ComponentName(context, BootReceiver::class.java)
            val state = if (wanted) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            runCatching {
                if (context.packageManager.getComponentEnabledSetting(component) != state) {
                    context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                }
            }.onFailure { Log.w("BootReceiver", "could not turn ${if (wanted) "on" else "off"}: ${it.javaClass.simpleName}") }
        }
    }
}
