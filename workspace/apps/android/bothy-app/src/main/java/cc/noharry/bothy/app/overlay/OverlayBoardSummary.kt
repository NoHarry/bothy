package cc.noharry.bothy.app.overlay

import cc.noharry.bothy.app.plans.planStatus
internal data class OverlayBoardRow(
    val text: String,
    val planStatus: OverlayPlanStatus? = null,
)

internal enum class OverlayPlanStatus {
    ACTIVE,
    DONE,
    FAILED,
    WAITING,
}

internal data class OverlayBoardSection(
    val title: String,
    val rows: List<OverlayBoardRow>,
) {
    val lines: List<String> get() = rows.map { it.text }
}

// Board section projection lives in OverlayHud.kt (HUD-first model).
