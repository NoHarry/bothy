package cc.noharry.bothy.playground

import android.content.res.Configuration
import android.os.Looper
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
 * PlaygroundActivity handles orientation itself, so a rotation must change the scale without a new activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp-port")
class PlaygroundRotationScaleTest {
    @Test
    fun updatingTheScaleSourceChangesTheDensity() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val source = PlaygroundScaleSource(activity.resources.configuration)
        val seen = mutableListOf<Float>()
        activity.setContent {
            PlaygroundPhoneScale(source) {
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
        source.update(next)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(portrait * 0.75f, seen.last(), 0.02f)
    }

    @Test
    fun theActivityWritesAHandledRotationIntoTheScaleSource() {
        val activity = Robolectric.buildActivity(PlaygroundActivity::class.java).setup().get()
        assertEquals(1f, playgroundScaleFor(activity.scaleSource.smallestWidthDp, activity.scaleSource.orientation), 0f)

        val next = Configuration(activity.resources.configuration)
        next.orientation = Configuration.ORIENTATION_LANDSCAPE
        next.smallestScreenWidthDp = 411
        activity.onConfigurationChanged(next)

        assertEquals(0.75f, playgroundScaleFor(activity.scaleSource.smallestWidthDp, activity.scaleSource.orientation), 0f)
    }
}
