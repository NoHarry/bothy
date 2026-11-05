package cc.noharry.bothy.app.trajectory
import cc.noharry.bothy.app.console.consoleLayoutPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Inspector placement and timeline affordances per window class (360x800, 800x184, 700x1000, 1280x800). */
class TrajectoryAdaptiveLayoutUiTest {

    @Test
    fun compactUsesAFullScreenInspectorWithoutDragRange() {
        val layout = trajectoryLayoutPolicy(consoleLayoutPolicy(360f, 800f))

        assertEquals(TrajectoryInspectorPresentation.FULL_SCREEN, layout.inspector)
        assertTrue(layout.showTimeline)
        assertFalse(layout.allowDragRange)
    }

    @Test
    fun ultraCompactHeightDropsTheTimelineToKeepTheLedgerUsable() {
        val layout = trajectoryLayoutPolicy(consoleLayoutPolicy(800f, 184f))

        assertEquals(TrajectoryInspectorPresentation.FULL_SCREEN, layout.inspector)
        assertFalse(layout.showTimeline)
    }

    @Test
    fun mediumUsesABottomSheetInspector() {
        val layout = trajectoryLayoutPolicy(consoleLayoutPolicy(700f, 1000f))

        assertEquals(TrajectoryInspectorPresentation.BOTTOM_SHEET, layout.inspector)
        assertTrue(layout.showTimeline)
        assertTrue(layout.allowDragRange)
    }

    @Test
    fun expandedUsesASidePaneWithinTheAllowedWidthBand() {
        val layout = trajectoryLayoutPolicy(consoleLayoutPolicy(1280f, 800f))

        assertEquals(TrajectoryInspectorPresentation.SIDE_PANE, layout.inspector)
        assertTrue(layout.inspectorWidth >= TRAJECTORY_INSPECTOR_MIN_WIDTH)
        assertTrue(layout.inspectorWidth <= TRAJECTORY_INSPECTOR_MAX_WIDTH)
    }

    @Test
    fun landscapeCompactStillAvoidsASidePaneInspector() {
        val layout = trajectoryLayoutPolicy(consoleLayoutPolicy(800f, 360f))

        assertEquals(TrajectoryInspectorPresentation.FULL_SCREEN, layout.inspector)
    }

    @Test
    fun aPhoneHeldSidewaysDropsTheTimelineToKeepTheLedgerUsable() {
        assertFalse(trajectoryLayoutPolicy(consoleLayoutPolicy(851f, 393f)).showTimeline)
        assertTrue(trajectoryLayoutPolicy(consoleLayoutPolicy(393f, 851f)).showTimeline)
    }
}
