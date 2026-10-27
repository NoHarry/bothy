package cc.noharry.bothy.app.console
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleAdaptiveLayoutTest {
    @Test
    fun compactWidthEndsBeforeSixHundredDp() {
        assertEquals(WindowClass.COMPACT, consoleLayoutPolicy(599f, 800f).windowClass)
        assertEquals(WindowClass.MEDIUM, consoleLayoutPolicy(600f, 800f).windowClass)
    }

    @Test
    fun expandedWidthStartsAtEightHundredFortyDp() {
        assertEquals(WindowClass.MEDIUM, consoleLayoutPolicy(839f, 800f).windowClass)
        assertEquals(WindowClass.EXPANDED, consoleLayoutPolicy(840f, 800f).windowClass)
    }

    @Test
    fun compactUsesOneDestinationWithoutPersistentSidePanes() {
        val policy = consoleLayoutPolicy(360f, 800f)

        assertFalse(policy.showPersistentSessions)
        assertFalse(policy.showPersistentBoard)
        assertTrue(policy.showBottomNavigation)
    }

    @Test
    fun mediumKeepsSessionsButNotBoard() {
        val policy = consoleLayoutPolicy(700f, 1000f)

        assertTrue(policy.showPersistentSessions)
        assertFalse(policy.showPersistentBoard)
        assertTrue(policy.showBottomNavigation)
    }

    @Test
    fun expandedKeepsThreePaneConversationChrome() {
        val policy = consoleLayoutPolicy(1280f, 800f)

        assertTrue(policy.showPersistentSessions)
        assertTrue(policy.showPersistentBoard)
        assertFalse(policy.showBottomNavigation)
    }

    @Test
    fun compactHeightPreventsLandscapeThreePaneStacking() {
        val policy = consoleLayoutPolicy(800f, 360f)

        assertEquals(WindowClass.COMPACT, policy.windowClass)
        assertFalse(policy.showPersistentSessions)
        assertFalse(policy.showPersistentBoard)
        assertFalse(policy.useUltraCompactHeightChrome)
    }

    @Test
    fun aPhoneHeldSidewaysTakesTheRailAndKeepsTheConversation() {
        // Pixel 5 sideways without its bars: 851×393 dp.
        val policy = consoleLayoutPolicy(851f, 393f)

        assertTrue(policy.useRailChrome)
        assertTrue(policy.showTrailingNavigationRail)
        assertFalse(policy.showBottomNavigation)
        assertFalse(policy.useUltraCompactHeightChrome)
    }

    @Test
    fun physicalBenchContentHeightUsesUltraCompactRailChrome() {
        val policy = consoleLayoutPolicy(800f, 184f)

        assertEquals(WindowClass.COMPACT, policy.windowClass)
        assertTrue(policy.useUltraCompactHeightChrome)
        assertTrue(policy.showTrailingNavigationRail)
        assertFalse(policy.showBottomNavigation)
    }

    @Test
    fun ultraCompactHeightBoundaryIsStable() {
        assertTrue(consoleLayoutPolicy(800f, 239f).useUltraCompactHeightChrome)
        assertFalse(consoleLayoutPolicy(800f, 240f).useUltraCompactHeightChrome)
    }

    @Test
    fun railHeightBoundaryIsStable() {
        assertTrue(consoleLayoutPolicy(800f, 479f).useRailChrome)
        assertFalse(consoleLayoutPolicy(800f, 480f).useRailChrome)
        assertFalse(consoleLayoutPolicy(470f, 479f).useRailChrome)
    }

    @Test
    fun compactPortraitKeepsRegularConversationChrome() {
        val policy = consoleLayoutPolicy(360f, 800f)

        assertFalse(policy.useUltraCompactHeightChrome)
        assertFalse(policy.showTrailingNavigationRail)
        assertTrue(policy.showBottomNavigation)
    }
}
