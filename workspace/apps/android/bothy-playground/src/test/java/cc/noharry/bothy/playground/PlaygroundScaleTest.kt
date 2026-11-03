package cc.noharry.bothy.playground

import android.content.res.Configuration
import cc.noharry.bothy.playground.headunit.usesPortraitHeadUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun portraitPhoneUsesTheStackedHeadUnit() {
        assertTrue(usesPortraitHeadUnit(411, Configuration.ORIENTATION_PORTRAIT))
        assertFalse(usesPortraitHeadUnit(411, Configuration.ORIENTATION_LANDSCAPE))
        assertFalse(usesPortraitHeadUnit(800, Configuration.ORIENTATION_PORTRAIT))
    }

    @Test
    fun tabletIsUnscaledInEitherOrientation() {
        assertEquals(1f, playgroundContentScale(phone = false, landscape = false), 0f)
        assertEquals(1f, playgroundContentScale(phone = false, landscape = true), 0f)
    }
}
