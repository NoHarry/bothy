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
}
