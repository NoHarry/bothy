package cc.noharry.bothy.app.console
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.ContextPanelState
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.ProviderEditState
import cc.noharry.agent.appserver.protocol.RuntimeDemoState
import cc.noharry.agent.appserver.protocol.SessionBoardState
import cc.noharry.agent.appserver.protocol.TokenUsageState
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.agent.appserver.protocol.client.ServerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Demo presentation mode is gone: the console has Debug and Trajectory plus the opt-in A2A client
 * mode, and the transcript is a single full-fidelity Debug stream with no mode filter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PresentationRemovalUiTest {
    @Test
    @Config(qualifiers = "zh")
    fun consoleHasTrajectoryAndOptInA2aViews() {
        assertEquals(
            listOf(ConsoleView.DEBUG, ConsoleView.TRAJECTORY, ConsoleView.A2A, ConsoleView.PLANS),
            ConsoleView.entries.toList(),
        )
        // 计划 sits right of A2A (owner 2026-10-15).
        assertEquals(listOf("调试", "轨迹", "A2A", "计划"), viewLabels())
    }

    @Test
    fun consoleViewsAreNamedInEnglishByDefault() {
        assertEquals(listOf("Debug", "Trajectory", "A2A", "Plans"), viewLabels())
    }

    private fun viewLabels(): List<String> {
        val resources = ApplicationProvider.getApplicationContext<Application>().resources
        return ConsoleView.entries.map { resources.getString(it.label) }
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
        val empty = emptyState()

        assertTrue(empty.shouldShowBoardSection(BoardSection.SESSION_STATUS))
        assertFalse(empty.shouldShowBoardSection(BoardSection.PLAN))
        assertFalse(empty.shouldShowBoardSection(BoardSection.TOKEN_USAGE))
        assertFalse(empty.shouldShowBoardSection(BoardSection.ARTIFACT))
        assertTrue(empty.shouldShowBoardSection(BoardSection.MCP))
        assertTrue(empty.shouldShowBoardSection(BoardSection.WORKSPACE))
    }
}

private fun emptyState(): ServerState =
    ServerState(
        providerLabel = "OpenAI",
        stream = true,
        confirmationMode = ConfirmationMode.ASK_EVERY_TIME,
        isRunning = false,
        configSource = "c",
        configEditor = ProviderEditState("m", "", ""),
        sessions = emptyList(),
        activeSessionId = "s",
        items = emptyList(),
        contextPanel = ContextPanelState(false, emptyList(), emptyList(), emptyList()),
        board = SessionBoardState(null, emptyList(), null),
        tokenUsage = TokenUsageState(),
        runtimeDemo = RuntimeDemoState(),
        mcpProviders = emptyList(),
        currentRequest = null,
        artifactPreview = null,
    )
