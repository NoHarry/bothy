package cc.noharry.agent.appserver.protocol.view

data class OverlayBoardRow(
    val text: String,
    val planStatus: OverlayPlanStatus? = null,
)

enum class OverlayPlanStatus {
    ACTIVE,
    DONE,
    FAILED,
    WAITING,
}

data class OverlayBoardSection(
    val title: String,
    val rows: List<OverlayBoardRow>,
) {
    val lines: List<String> get() = rows.map { it.text }
}

// Board section projection lives in OverlayHud.kt (HUD-first model).
