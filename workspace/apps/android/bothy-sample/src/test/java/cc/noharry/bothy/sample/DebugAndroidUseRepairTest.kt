package cc.noharry.bothy.sample

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 操作屏幕 listed by the system but never bound (after a force-stop on some Android 11 devices): the repair and Settings → 检查. */
@RunWith(RobolectricTestRunner::class)
class DebugAndroidUseRepairTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val ours = "${context.packageName}/cc.noharry.bothy.androiduse.AndroidUseAccessibilityService"
    private fun enabledList(): String? = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)

    @Test
    fun theRepairTakesOnlyItsOwnEntryOutThenPutsItBack() = runBlocking {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "com.car.speak/.Visible:$ours")
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, "0")
        val repair = launch { assertFalse("nothing binds it under Robolectric", repairDebugAndroidUse(context, gapMillis = 200, waitMillis = 50)) }
        delay(100)
        val duringGap = enabledList()
        repair.join()

        assertEquals("out for the gap, so the system sees a change", "com.car.speak/.Visible", duringGap)
        assertEquals("back once, the car's own service kept", "com.car.speak/.Visible:$ours", enabledList())
        assertEquals("1", Settings.Secure.getString(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED))
    }

    @Test
    fun theCheckReadsTheSwitchOffAsOffNotAFault() = runBlocking {
        saveDebugAndroidUseEnabled(context, false)
        val check = DEBUG_CHECKS.first { it.id == "accessibility" }
        assertEquals(DebugCheckState.OFF, check.run(context).state)
    }
}
