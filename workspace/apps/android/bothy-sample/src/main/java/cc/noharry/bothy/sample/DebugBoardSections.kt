package cc.noharry.bothy.sample

/**
 * Board sections for the Debug console view.
 *
 * Primary rows are always considered (still hidden when empty); environment
 * one-liners follow. CURRENT_REQUEST / LAST_ACTION / RECENT_TURNS stay in the
 * enum for tests and future drawers but are not part of the default IA.
 */
internal enum class DebugBoardSection {
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
internal fun debugBoardSections(): Set<DebugBoardSection> =
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
    )

/** Whether a section should render given live state (empty = hide). */
internal fun DebugUiState.shouldShowBoardSection(section: DebugBoardSection): Boolean =
    when (section) {
        DebugBoardSection.SESSION_STATUS -> true
        DebugBoardSection.PLAN -> contextPanel.todo.isNotEmpty()
        DebugBoardSection.TOKEN_USAGE -> tokenUsage.hasUsage
        // Shown from the subagent board flow, which the pane collects; empty there hides it.
        DebugBoardSection.SUBAGENTS -> true
        DebugBoardSection.ARTIFACT -> artifactPreview != null
        DebugBoardSection.WORKSPACE -> true
        DebugBoardSection.MCP -> true
        DebugBoardSection.MEMORY -> contextPanel.lessons.isNotEmpty()
        DebugBoardSection.SKILLS -> contextPanel.skills.isNotEmpty()
        DebugBoardSection.RUNTIME_VISIBILITY ->
            runtimeDemo.pendingConfirmationActionIds().isNotEmpty() ||
                runtimeDemo.status.contains("block", ignoreCase = true) ||
                runtimeDemo.status.contains("confirm", ignoreCase = true)
        DebugBoardSection.CURRENT_REQUEST,
        DebugBoardSection.LAST_ACTION,
        DebugBoardSection.RECENT_TURNS,
        -> false
    }
