package cc.noharry.bothy.playground

import android.content.res.Configuration
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * PlaygroundActivity handles orientation itself, so it is not recreated. Compose reads
 * [androidx.compose.ui.platform.LocalConfiguration] from the decor view. Robolectric does not
 * deliver an activity configuration change to that view, so the test calls
 * [View.dispatchConfigurationChanged] on it directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp-port")
class PlaygroundRotationScaleTest {
    @Test
    fun aHandledRotationChangesTheDensity() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val seen = mutableListOf<Float>()
        activity.setContent {
            PlaygroundPhoneScale {
                val density = LocalDensity.current.density
                SideEffect { if (seen.lastOrNull() != density) seen += density }
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        val portrait = seen.last()

        val next = Configuration(activity.resources.configuration)
        next.orientation = Configuration.ORIENTATION_LANDSCAPE
        next.screenWidthDp = 891
        next.screenHeightDp = 411
        next.smallestScreenWidthDp = 411
        activity.window.decorView.dispatchConfigurationChanged(next)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(portrait * 0.75f, seen.last(), 0.02f)
    }
}
