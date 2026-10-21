package cc.noharry.bothy.sample

internal data class DebugOverlayBoardRow(
    val text: String,
    val planStatus: DebugOverlayPlanStatus? = null,
)

internal enum class DebugOverlayPlanStatus {
    ACTIVE,
    DONE,
    FAILED,
    WAITING,
}

internal data class DebugOverlayBoardSection(
    val title: String,
    val rows: List<DebugOverlayBoardRow>,
) {
    val lines: List<String> get() = rows.map { it.text }
}

// Board section projection lives in DebugOverlayHud.kt (HUD-first model).
