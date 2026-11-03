package cc.noharry.bothy.playground

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
    fun tabletIsUnscaledInEitherOrientation() {
        assertEquals(1f, playgroundContentScale(phone = false, landscape = false), 0f)
        assertEquals(1f, playgroundContentScale(phone = false, landscape = true), 0f)
    }
}
