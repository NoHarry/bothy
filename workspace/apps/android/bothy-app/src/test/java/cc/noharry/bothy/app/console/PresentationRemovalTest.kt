package cc.noharry.bothy.app.console
import cc.noharry.bothy.app.model.ProviderEditState
import cc.noharry.bothy.app.overlay.clientMode
import cc.noharry.bothy.app.runtime.RuntimeDemoState
import cc.noharry.bothy.app.session.ConfirmationMode
import cc.noharry.bothy.app.session.ContextPanelState
import cc.noharry.bothy.app.session.ConversationItem
import cc.noharry.bothy.app.session.SessionBoardState
import cc.noharry.bothy.app.session.ToolCallStatus
import cc.noharry.bothy.app.session.TranscriptRole
import cc.noharry.bothy.app.session.UiState
import cc.noharry.bothy.app.usage.TokenUsageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Demo presentation mode is gone: the console has Debug and Trajectory plus the opt-in A2A client
 * mode, and the transcript is a single full-fidelity Debug stream with no mode filter.
 */
class PresentationRemovalTest {
    @Test
    fun consoleHasTrajectoryAndOptInA2aViews() {
        assertEquals(
            listOf(ConsoleView.DEBUG, ConsoleView.TRAJECTORY, ConsoleView.A2A, ConsoleView.PLANS),
            ConsoleView.entries.toList(),
        )
        // 计划 sits right of A2A (owner 2026-10-15).
        assertEquals(listOf("Debug", "Trajectory", "A2A", "计划"), ConsoleView.entries.map { it.label })
    }

    @Test
    fun a2aViewFollowsClientModeOnly() {
        assertEquals(ConsoleView.A2A, consoleViewFor("TRAJECTORY", clientMode = true))
        assertEquals(ConsoleView.TRAJECTORY, consoleViewFor("TRAJECTORY", clientMode = false))
        assertEquals(ConsoleView.DEBUG, consoleViewFor("A2A", clientMode = false))
    }

    @Test
    fun coldStartDefaultViewIsDebug() {
        assertEquals(ConsoleView.DEBUG, DEFAULT_DEBUG_CONSOLE_VIEW)
        assertEquals(ConsoleView.DEBUG, "DEBUG".toConsoleView())
        assertEquals(ConsoleView.TRAJECTORY, "TRAJECTORY".toConsoleView())
        assertEquals(ConsoleView.DEBUG, "DEMO".toConsoleView())
        assertEquals(ConsoleView.DEBUG, "nope".toConsoleView())
    }

    @Test
    fun transcriptRenderIsFullPassThrough() {
        val items = listOf(
            ConversationItem.TurnHeader(1, "You", "hello", "hello"),
            ConversationItem.MessageItem(2, TranscriptRole.THINKING, "plan"),
            ConversationItem.RuntimePhaseItem(3, "intake", "body"),
            ConversationItem.ToolCallItem(
                id = 4,
                toolName = "web_fetch",
                argumentsJson = null,
                resultJson = "{}",
                status = ToolCallStatus.DONE,
                startedAt = 1L,
                finishedAt = 2L,
            ),
            ConversationItem.MessageItem(5, TranscriptRole.ASSISTANT, "hi"),
        )

        val rendered = items.toRenderItems()
        val singles = rendered.filterIsInstance<ConversationRenderItem.Single>().map { it.item }

        // Runtime phases stay visible; only ambient STATUS is dropped by the shared projection.
        assertTrue(singles.any { it is ConversationItem.RuntimePhaseItem })
        assertTrue(singles.any { it is ConversationItem.MessageItem && it.role == TranscriptRole.THINKING })
        assertTrue(singles.any { it is ConversationItem.ToolCallItem })
        assertTrue(singles.any { it is ConversationItem.MessageItem && it.role == TranscriptRole.ASSISTANT })
    }

    @Test
    fun boardSectionsAreFixedIa() {
        assertEquals(
            setOf(
                BoardSection.SESSION_STATUS,
                BoardSection.PLAN,
                BoardSection.TOKEN_USAGE,
                BoardSection.SUBAGENTS,
                BoardSection.ARTIFACT,
                BoardSection.WORKSPACE,
                BoardSection.MCP,
                BoardSection.MEMORY,
                BoardSection.SKILLS,
                BoardSection.RUNTIME_VISIBILITY,
            ),
            boardSections(),
        )
        assertFalse(boardSections().contains(BoardSection.CURRENT_REQUEST))
        assertFalse(boardSections().contains(BoardSection.LAST_ACTION))
        assertFalse(boardSections().contains(BoardSection.RECENT_TURNS))
    }

    @Test
    fun emptyPolicyHidesPlanUsageAndArtifact() {
        val empty = emptyUiState()

        assertTrue(empty.shouldShowBoardSection(BoardSection.SESSION_STATUS))
        assertFalse(empty.shouldShowBoardSection(BoardSection.PLAN))
        assertFalse(empty.shouldShowBoardSection(BoardSection.TOKEN_USAGE))
        assertFalse(empty.shouldShowBoardSection(BoardSection.ARTIFACT))
        assertTrue(empty.shouldShowBoardSection(BoardSection.MCP))
        assertTrue(empty.shouldShowBoardSection(BoardSection.WORKSPACE))
    }
}

internal fun emptyUiState(): UiState =
    UiState(
        status = "Idle",
        providerLabel = "OpenAI",
        streamLabel = "stream",
        backendLabel = "live",
        confirmationMode = ConfirmationMode.ASK_EVERY_TIME,
        isRunning = false,
        configLabel = "c",
        configEditor = ProviderEditState("", "m", "", ""),
        sessions = emptyList(),
        activeSessionId = "s",
        inputHint = "Message",
        items = emptyList(),
        contextPanel = ContextPanelState(false, emptyList(), emptyList(), emptyList()),
        board = SessionBoardState(null, emptyList(), null),
        tokenUsage = TokenUsageState(),
        runtimeDemo = RuntimeDemoState(),
        mcpProviders = emptyList(),
        currentRequest = null,
        artifactPreview = null,
    )
