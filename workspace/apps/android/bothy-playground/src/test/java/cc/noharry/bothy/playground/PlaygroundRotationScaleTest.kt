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
 * PlaygroundActivity handles orientation itself. [androidx.compose.ui.platform.LocalConfiguration] still changes,
 * because the application notifies the callbacks Compose registered, and the scale is read from that.
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
        // The framework delivers this to the decor view. Robolectric's Activity.onConfigurationChanged does not, so the
        // test dispatches it the same way ViewGroup would.
        val dispatch = View::class.java.getDeclaredMethod("dispatchConfigurationChanged", Configuration::class.java)
        dispatch.isAccessible = true
        dispatch.invoke(activity.window.decorView, next)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(portrait * 0.75f, seen.last(), 0.02f)
    }
}
