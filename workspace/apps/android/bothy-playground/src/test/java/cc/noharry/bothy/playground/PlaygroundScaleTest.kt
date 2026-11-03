package cc.noharry.bothy.playground

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaygroundScaleTest {
    @Test
    fun portraitPhoneKeepsPlatformDensity() {
        assertEquals(1f, playgroundContentScale(phone = true, landscape = false), 0f)
    }

    @Test
    fun landscapePhoneStillShrinksTheWideHeadUnit() {
        assertEquals(0.75f, playgroundContentScale(phone = true, landscape = true), 0f)
    }

    @Test
    fun scaleFollowsTheConfigurationARotationWouldReport() {
        assertEquals(1f, playgroundScaleFor(411, Configuration.ORIENTATION_PORTRAIT), 0f)
        assertEquals(0.75f, playgroundScaleFor(411, Configuration.ORIENTATION_LANDSCAPE), 0f)
        assertEquals(1f, playgroundScaleFor(800, Configuration.ORIENTATION_LANDSCAPE), 0f)
    }

    @Test
    fun tabletIsUnscaledInEitherOrientation() {
        assertEquals(1f, playgroundContentScale(phone = false, landscape = false), 0f)
        assertEquals(1f, playgroundContentScale(phone = false, landscape = true), 0f)
    }
}
