package cc.noharry.bothy.playground

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where AMap is asked to keep the car so it lands in the middle of the map the cards leave open. */
class NaviMapTest {
    @Test
    fun aLandscapeShareIsAskedForPastAMapsOwnPanel() {
        // AMap puts the car at 0.315 + 0.63 × the share asked for: asking the inverse lands it where meant.
        listOf(0.5, 0.637, 0.8).forEach { want -> assertEquals(want, 0.315 + 0.63 * landscapeLockX(want), 1e-9) }
        assertEquals("never off the map's left", 0.0, landscapeLockX(0.1), 0.0)
        assertEquals("never off the map's right", 1.0, landscapeLockX(0.99), 0.0)
    }

    @Test
    fun theCarSeenOffTheSameWayTwiceMovesItsPlaceByBoth() {
        val drift = CarDrift()
        assertEquals("once is not enough", false, drift.seen(WIDE, 0.10))
        assertEquals(true, drift.seen(WIDE, 0.12))
        assertEquals(0.11, drift.nudge(WIDE), 1e-9)
        assertEquals(false, drift.seen(WIDE, -0.05))
        assertEquals("the other way, it starts over", false, drift.seen(WIDE, 0.05))
        assertEquals(0.11, drift.nudge(WIDE), 1e-9)
    }

    @Test
    fun eachWidthOfTheMapLearnsApart() {
        val drift = CarDrift()
        drift.seen(NARROW, -0.07)
        assertEquals("a sighting on the narrow map is not one on the wide", false, drift.seen(WIDE, -0.07))
        drift.seen(WIDE, -0.05)
        assertEquals(-0.06, drift.nudge(WIDE), 1e-9)
        assertEquals(0.0, drift.nudge(NARROW), 0.0)
    }

    @Test
    fun theCarCloseToTheMiddleOrNowhereNearItLeavesItsPlace() {
        val drift = CarDrift()
        listOf(0.01, 0.01, -0.02, 0.5, 0.5, -0.4, -0.4).forEach { assertEquals(false, drift.seen(WIDE, it)) }
        assertEquals(0.0, drift.nudge(WIDE), 0.0)
        drift.seen(WIDE, 0.2)
        drift.seen(WIDE, 0.2)
        drift.reset()
        assertEquals(0.0, drift.nudge(WIDE), 0.0)
    }

    private companion object {
        const val WIDE = 2222
        const val NARROW = 1492
    }
}
