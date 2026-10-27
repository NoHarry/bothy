package cc.noharry.bothy.app.console

import cc.noharry.bothy.app.runtime.pendingConfirmationActionIds
import cc.noharry.bothy.app.session.UiState
/**
 * Board sections for the Debug console view.
 *
 * Primary rows are always considered (still hidden when empty); environment
 * one-liners follow. CURRENT_REQUEST / LAST_ACTION / RECENT_TURNS stay in the
 * enum for tests and future drawers but are not part of the default IA.
 */
internal enum class BoardSection {
    SESSION_STATUS,
    PLAN,
    TOKEN_USAGE,
    SUBAGENTS,
    ARTIFACT,
    WORKSPACE,
    MCP,
    MEMORY,
    SKILLS,
    RUNTIME_VISIBILITY,
    CURRENT_REQUEST,
    LAST_ACTION,
    RECENT_TURNS,
}

/** Debug primary + environment rows; no Request / Last action / Recent turns dump. */
internal fun boardSections(): Set<BoardSection> =
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
    )

/** Whether a section should render given live state (empty = hide). */
internal fun UiState.shouldShowBoardSection(section: BoardSection): Boolean =
    when (section) {
        BoardSection.SESSION_STATUS -> true
        BoardSection.PLAN -> contextPanel.todo.isNotEmpty()
        BoardSection.TOKEN_USAGE -> tokenUsage.hasUsage
        // Shown from the subagent board flow, which the pane collects; empty there hides it.
        BoardSection.SUBAGENTS -> true
        BoardSection.ARTIFACT -> artifactPreview != null
        BoardSection.WORKSPACE -> true
        BoardSection.MCP -> true
        BoardSection.MEMORY -> contextPanel.lessons.isNotEmpty()
        BoardSection.SKILLS -> contextPanel.skills.isNotEmpty()
        BoardSection.RUNTIME_VISIBILITY ->
            runtimeDemo.pendingConfirmationActionIds().isNotEmpty() ||
                runtimeDemo.status.contains("block", ignoreCase = true) ||
                runtimeDemo.status.contains("confirm", ignoreCase = true)
        BoardSection.CURRENT_REQUEST,
        BoardSection.LAST_ACTION,
        BoardSection.RECENT_TURNS,
        -> false
    }
