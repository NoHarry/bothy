package cc.noharry.bothy.app.console.ui
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiScaleTest {
    @Test
    fun benchKeepsTheDesignSize() {
        val unit = UiScale.designUnitPx(2560, 1440, nativeDensity = 1f)

        assertEquals(1f, unit, DELTA)
        assertEquals(1.35f, unit * CAR_OVERLAY_ZOOM, DELTA)
    }

    @Test
    fun tabletScalesTheCarDesignByItsTighterSide() {
        // Lenovo TB323FU: 3040×1904 at 440 dpi; the long side is the tighter fit.
        val unit = UiScale.designUnitPx(3040, 1904, nativeDensity = 2.75f)

        assertEquals(3040f / 2560f, unit, DELTA)
        assertEquals(0.58f, unit * CAR_OVERLAY_ZOOM / 2.75f, 0.01f)
    }

    @Test
    fun aDisplayTallerThanTheDesignFitsItsShortSide() {
        assertEquals(1080f / 1440f, UiScale.designUnitPx(2560, 1080, nativeDensity = 0.5f), DELTA)
    }

    @Test
    fun phonesKeepThePlatformDp() {
        // Pixel 5: 2340×1080 at 440 dpi, 393 dp on its short side.
        assertEquals(2.75f, UiScale.designUnitPx(2340, 1080, nativeDensity = 2.75f), DELTA)
        assertEquals(PHONE_OVERLAY_ZOOM, UiScale.overlayStageScale(2340, 1080, nativeDensity = 2.75f), DELTA)
        assertTrue(UiScale.phone(1080, 2.75f))
    }

    @Test
    fun theTabletIsNotAPhone() {
        assertFalse(UiScale.phone(1904, 2.75f))
        assertEquals(1.35f, UiScale.overlayStageScale(2560, 1440, nativeDensity = 1f), DELTA)
    }

    @Test
    fun aSmallTabletStopsAtTheFloor() {
        // 1920×1200 at 320 dpi: 600 dp on its short side, a tablet, at the floor.
        assertEquals(2f * MIN_NATIVE_SHARE, UiScale.designUnitPx(1920, 1200, nativeDensity = 2f), DELTA)
    }

    private companion object {
        const val DELTA = 0.0001f
    }
}
