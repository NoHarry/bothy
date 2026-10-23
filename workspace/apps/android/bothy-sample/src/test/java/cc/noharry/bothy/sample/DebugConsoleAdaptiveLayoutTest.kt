package cc.noharry.bothy.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugConsoleAdaptiveLayoutTest {
    @Test
    fun compactWidthEndsBeforeSixHundredDp() {
        assertEquals(DebugWindowClass.COMPACT, debugConsoleLayoutPolicy(599f, 800f).windowClass)
        assertEquals(DebugWindowClass.MEDIUM, debugConsoleLayoutPolicy(600f, 800f).windowClass)
    }

    @Test
    fun expandedWidthStartsAtEightHundredFortyDp() {
        assertEquals(DebugWindowClass.MEDIUM, debugConsoleLayoutPolicy(839f, 800f).windowClass)
        assertEquals(DebugWindowClass.EXPANDED, debugConsoleLayoutPolicy(840f, 800f).windowClass)
    }

    @Test
    fun compactUsesOneDestinationWithoutPersistentSidePanes() {
        val policy = debugConsoleLayoutPolicy(360f, 800f)

        assertFalse(policy.showPersistentSessions)
        assertFalse(policy.showPersistentBoard)
        assertTrue(policy.showBottomNavigation)
    }

    @Test
    fun mediumKeepsSessionsButNotBoard() {
        val policy = debugConsoleLayoutPolicy(700f, 1000f)

        assertTrue(policy.showPersistentSessions)
        assertFalse(policy.showPersistentBoard)
        assertTrue(policy.showBottomNavigation)
    }

    @Test
    fun expandedKeepsThreePaneConversationChrome() {
        val policy = debugConsoleLayoutPolicy(1280f, 800f)

        assertTrue(policy.showPersistentSessions)
        assertTrue(policy.showPersistentBoard)
        assertFalse(policy.showBottomNavigation)
    }

    @Test
    fun compactHeightPreventsLandscapeThreePaneStacking() {
        val policy = debugConsoleLayoutPolicy(800f, 360f)

        assertEquals(DebugWindowClass.COMPACT, policy.windowClass)
        assertFalse(policy.showPersistentSessions)
        assertFalse(policy.showPersistentBoard)
        assertFalse(policy.useUltraCompactHeightChrome)
    }

    @Test
    fun aPhoneHeldSidewaysTakesTheRailAndKeepsTheConversation() {
        // Pixel 5 sideways without its bars: 851×393 dp.
        val policy = debugConsoleLayoutPolicy(851f, 393f)

        assertTrue(policy.useRailChrome)
        assertTrue(policy.showTrailingNavigationRail)
        assertFalse(policy.showBottomNavigation)
        assertFalse(policy.useUltraCompactHeightChrome)
    }

    @Test
    fun physicalBenchContentHeightUsesUltraCompactRailChrome() {
        val policy = debugConsoleLayoutPolicy(800f, 184f)

        assertEquals(DebugWindowClass.COMPACT, policy.windowClass)
        assertTrue(policy.useUltraCompactHeightChrome)
        assertTrue(policy.showTrailingNavigationRail)
        assertFalse(policy.showBottomNavigation)
    }

    @Test
    fun ultraCompactHeightBoundaryIsStable() {
        assertTrue(debugConsoleLayoutPolicy(800f, 239f).useUltraCompactHeightChrome)
        assertFalse(debugConsoleLayoutPolicy(800f, 240f).useUltraCompactHeightChrome)
    }

    @Test
    fun railHeightBoundaryIsStable() {
        assertTrue(debugConsoleLayoutPolicy(800f, 479f).useRailChrome)
        assertFalse(debugConsoleLayoutPolicy(800f, 480f).useRailChrome)
        assertFalse(debugConsoleLayoutPolicy(470f, 479f).useRailChrome)
    }

    @Test
    fun compactPortraitKeepsRegularConversationChrome() {
        val policy = debugConsoleLayoutPolicy(360f, 800f)

        assertFalse(policy.useUltraCompactHeightChrome)
        assertFalse(policy.showTrailingNavigationRail)
        assertTrue(policy.showBottomNavigation)
    }
}
