package cc.noharry.agent.appserver

import android.app.AlarmManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.plans.PlanRunner
import cc.noharry.agent.appserver.protocol.AndroidUseState
import cc.noharry.agent.appserver.androiduse.androidUseState
import cc.noharry.agent.appserver.androiduse.applyAndroidUseSwitch
import cc.noharry.agent.appserver.protocol.PlanTrigger
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.BuiltInFeatures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The server's own features stay off until the host turns them on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BuiltInFeaturesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun none() = AppHost.install(builtIns = BuiltInFeatures.NONE)

    @Test
    fun theBootReceiverFollowsTheHost() {
        val component = ComponentName(context, BootReceiver::class.java)
        BootReceiver.follow(context, wanted = true)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, context.packageManager.getComponentEnabledSetting(component))
        BootReceiver.follow(context, wanted = false)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, context.packageManager.getComponentEnabledSetting(component))
    }

    @Test
    fun withoutTheScreenFeatureTheAccessibilityServiceIsNeverListed() {
        assertFalse(applyAndroidUseSwitch(context, enabled = true))
        assertEquals(null, Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        assertEquals(AndroidUseState.OFF, androidUseState(context))
        // With it, the switch lists the service.
        AppHost.install(builtIns = BuiltInFeatures(androidUse = true))
        applyAndroidUseSwitch(context, enabled = true)
        assertTrue(Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().contains("AndroidUseAccessibilityService"))
    }

    @Test
    fun withoutPlansNothingDueFiresOrSetsAnAlarm() {
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        PlanRunner.write(context) { it.add("拿快递", "去拿快递", null, PlanTrigger.Once(System.currentTimeMillis() + 3_600_000L), said = null) }
        assertTrue(alarms.scheduledAlarms.isEmpty())
        PlanRunner.check(context)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        AppHost.install(builtIns = BuiltInFeatures(plans = true))
        PlanRunner.check(context)
        assertEquals(1, alarms.scheduledAlarms.size)
        // Turned off again: the alarm left from before is let go.
        AppHost.install(builtIns = BuiltInFeatures.NONE)
        PlanRunner.stop(context)
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }
}
