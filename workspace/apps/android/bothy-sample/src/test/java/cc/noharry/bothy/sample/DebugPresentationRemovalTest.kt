package cc.noharry.bothy.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Demo presentation mode is gone: the console has Debug and Trajectory plus the opt-in A2A client
 * mode, and the transcript is a single full-fidelity Debug stream with no mode filter.
 */
class DebugPresentationRemovalTest {
    @Test
    fun consoleHasDebugTrajectoryAndOptInA2aViews() {
        assertEquals(
            listOf(DebugConsoleView.DEBUG, DebugConsoleView.TRAJECTORY, DebugConsoleView.A2A, DebugConsoleView.PLANS),
            DebugConsoleView.entries.toList(),
        )
        // 计划 sits right of A2A (owner 2026-10-15).
        assertEquals(listOf("Debug", "Trajectory", "A2A", "计划"), DebugConsoleView.entries.map { it.label })
    }

    @Test
    fun a2aViewFollowsClientModeOnly() {
        assertEquals(DebugConsoleView.A2A, debugConsoleViewFor("TRAJECTORY", clientMode = true))
        assertEquals(DebugConsoleView.TRAJECTORY, debugConsoleViewFor("TRAJECTORY", clientMode = false))
        assertEquals(DebugConsoleView.DEBUG, debugConsoleViewFor("A2A", clientMode = false))
    }

    @Test
    fun coldStartDefaultViewIsDebug() {
        assertEquals(DebugConsoleView.DEBUG, DEFAULT_DEBUG_CONSOLE_VIEW)
        assertEquals(DebugConsoleView.DEBUG, "DEBUG".toDebugConsoleView())
        assertEquals(DebugConsoleView.TRAJECTORY, "TRAJECTORY".toDebugConsoleView())
        assertEquals(DebugConsoleView.DEBUG, "DEMO".toDebugConsoleView())
        assertEquals(DebugConsoleView.DEBUG, "nope".toDebugConsoleView())
    }

    @Test
    fun transcriptRenderIsFullPassThrough() {
        val items = listOf(
            DebugConversationItem.TurnHeader(1, "You", "hello", "hello"),
            DebugConversationItem.MessageItem(2, DebugTranscriptRole.THINKING, "plan"),
            DebugConversationItem.RuntimePhaseItem(3, "intake", "body"),
            DebugConversationItem.ToolCallItem(
                id = 4,
                toolName = "web_fetch",
                argumentsJson = null,
                resultJson = "{}",
                status = DebugToolCallStatus.DONE,
                startedAt = 1L,
                finishedAt = 2L,
            ),
            DebugConversationItem.MessageItem(5, DebugTranscriptRole.ASSISTANT, "hi"),
        )

        val rendered = items.toRenderItems()
        val singles = rendered.filterIsInstance<DebugConversationRenderItem.Single>().map { it.item }

        // Runtime phases stay visible; only ambient STATUS is dropped by the shared projection.
        assertTrue(singles.any { it is DebugConversationItem.RuntimePhaseItem })
        assertTrue(singles.any { it is DebugConversationItem.MessageItem && it.role == DebugTranscriptRole.THINKING })
        assertTrue(singles.any { it is DebugConversationItem.ToolCallItem })
        assertTrue(singles.any { it is DebugConversationItem.MessageItem && it.role == DebugTranscriptRole.ASSISTANT })
    }

    @Test
    fun boardSectionsAreFixedDebugIa() {
        assertEquals(
            setOf(
                DebugBoardSection.SESSION_STATUS,
                DebugBoardSection.PLAN,
                DebugBoardSection.TOKEN_USAGE,
                DebugBoardSection.SUBAGENTS,
                DebugBoardSection.ARTIFACT,
                DebugBoardSection.WORKSPACE,
                DebugBoardSection.MCP,
                DebugBoardSection.MEMORY,
                DebugBoardSection.SKILLS,
                DebugBoardSection.RUNTIME_VISIBILITY,
            ),
            debugBoardSections(),
        )
        assertFalse(debugBoardSections().contains(DebugBoardSection.CURRENT_REQUEST))
        assertFalse(debugBoardSections().contains(DebugBoardSection.LAST_ACTION))
        assertFalse(debugBoardSections().contains(DebugBoardSection.RECENT_TURNS))
    }

    @Test
    fun emptyPolicyHidesPlanUsageAndArtifact() {
        val empty = emptyDebugUiState()

        assertTrue(empty.shouldShowBoardSection(DebugBoardSection.SESSION_STATUS))
        assertFalse(empty.shouldShowBoardSection(DebugBoardSection.PLAN))
        assertFalse(empty.shouldShowBoardSection(DebugBoardSection.TOKEN_USAGE))
        assertFalse(empty.shouldShowBoardSection(DebugBoardSection.ARTIFACT))
        assertTrue(empty.shouldShowBoardSection(DebugBoardSection.MCP))
        assertTrue(empty.shouldShowBoardSection(DebugBoardSection.WORKSPACE))
    }
}

internal fun emptyDebugUiState(): DebugUiState =
    DebugUiState(
        status = "Idle",
        providerLabel = "OpenAI",
        streamLabel = "stream",
        backendLabel = "live",
        confirmationMode = DebugConfirmationMode.ASK_EVERY_TIME,
        isRunning = false,
        configLabel = "c",
        configEditor = DebugProviderEditState("", "m", "", ""),
        sessions = emptyList(),
        activeSessionId = "s",
        inputHint = "Message",
        items = emptyList(),
        contextPanel = DebugContextPanelState(false, emptyList(), emptyList(), emptyList()),
        board = DebugSessionBoardState(null, emptyList(), null),
        tokenUsage = DebugTokenUsageState(),
        runtimeDemo = DebugRuntimeDemoState(),
        mcpProviders = emptyList(),
        currentRequest = null,
        artifactPreview = null,
    )
