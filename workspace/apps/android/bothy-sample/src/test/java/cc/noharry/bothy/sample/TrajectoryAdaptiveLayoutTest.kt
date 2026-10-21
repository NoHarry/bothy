@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Inspector placement and timeline affordances per window class (360x800, 800x184, 700x1000, 1280x800). */
class TrajectoryAdaptiveLayoutTest {

    @Test
    fun compactUsesAFullScreenInspectorWithoutDragRange() {
        val layout = trajectoryLayoutPolicy(debugConsoleLayoutPolicy(360f, 800f))

        assertEquals(TrajectoryInspectorPresentation.FULL_SCREEN, layout.inspector)
        assertTrue(layout.showTimeline)
        assertFalse(layout.allowDragRange)
    }

    @Test
    fun ultraCompactHeightDropsTheTimelineToKeepTheLedgerUsable() {
        val layout = trajectoryLayoutPolicy(debugConsoleLayoutPolicy(800f, 184f))

        assertEquals(TrajectoryInspectorPresentation.FULL_SCREEN, layout.inspector)
        assertFalse(layout.showTimeline)
    }

    @Test
    fun mediumUsesABottomSheetInspector() {
        val layout = trajectoryLayoutPolicy(debugConsoleLayoutPolicy(700f, 1000f))

        assertEquals(TrajectoryInspectorPresentation.BOTTOM_SHEET, layout.inspector)
        assertTrue(layout.showTimeline)
        assertTrue(layout.allowDragRange)
    }

    @Test
    fun expandedUsesASidePaneWithinTheAllowedWidthBand() {
        val layout = trajectoryLayoutPolicy(debugConsoleLayoutPolicy(1280f, 800f))

        assertEquals(TrajectoryInspectorPresentation.SIDE_PANE, layout.inspector)
        assertTrue(layout.inspectorWidth >= TRAJECTORY_INSPECTOR_MIN_WIDTH)
        assertTrue(layout.inspectorWidth <= TRAJECTORY_INSPECTOR_MAX_WIDTH)
    }

    @Test
    fun landscapeCompactStillAvoidsASidePaneInspector() {
        val layout = trajectoryLayoutPolicy(debugConsoleLayoutPolicy(800f, 360f))

        assertEquals(TrajectoryInspectorPresentation.FULL_SCREEN, layout.inspector)
    }

    @Test
    fun inspectorTabsAreDynamicByKindAndRawIsPaginatedCanonicalPayload() {
        val user = inspectorTabsFor(sessionEvent(type = cc.noharry.bothy.kernel.api.SessionEventType.USER_MESSAGE).toTrajectoryRecord())
        val tool = inspectorTabsFor(
            sessionEvent(
                type = cc.noharry.bothy.kernel.api.SessionEventType.TOOL_RESULT,
                safeMetadata = mapOf("callId" to "c1", "toolName" to "read_file"),
            ).toTrajectoryRecord(),
        )

        assertTrue(user.contains(TrajectoryInspectorTab.RAW))
        assertTrue(user.contains(TrajectoryInspectorTab.SUMMARY))
        assertFalse(user.contains(TrajectoryInspectorTab.RESULT))
        assertTrue(tool.contains(TrajectoryInspectorTab.RESULT))
        assertFalse(tool.contains(TrajectoryInspectorTab.REQUEST))
        assertEquals(listOf(""), paginateRawText(""))
    }
}
