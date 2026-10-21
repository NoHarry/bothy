package cc.noharry.bothy.sample

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugUiScaleTest {
    @Test
    fun benchKeepsTheDesignSize() {
        val unit = DebugUiScale.designUnitPx(2560, 1440, nativeDensity = 1f)

        assertEquals(1f, unit, DELTA)
        assertEquals(1.35f, unit * CAR_OVERLAY_ZOOM, DELTA)
    }

    @Test
    fun tabletScalesTheCarDesignByItsTighterSide() {
        // Lenovo TB323FU: 3040×1904 at 440 dpi; the long side is the tighter fit.
        val unit = DebugUiScale.designUnitPx(3040, 1904, nativeDensity = 2.75f)

        assertEquals(3040f / 2560f, unit, DELTA)
        assertEquals(0.58f, unit * CAR_OVERLAY_ZOOM / 2.75f, 0.01f)
    }

    @Test
    fun aDisplayTallerThanTheDesignFitsItsShortSide() {
        assertEquals(1080f / 1440f, DebugUiScale.designUnitPx(2560, 1080, nativeDensity = 0.5f), DELTA)
    }

    @Test
    fun phonesStopAtTheFloor() {
        val unit = DebugUiScale.designUnitPx(2400, 1080, nativeDensity = 2.75f)

        assertEquals(2.75f * MIN_NATIVE_SHARE, unit, DELTA)
    }

    private companion object {
        const val DELTA = 0.0001f
    }
}
