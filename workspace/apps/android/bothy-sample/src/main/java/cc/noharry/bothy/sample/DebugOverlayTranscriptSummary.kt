package cc.noharry.bothy.sample

/**
 * Overlay projection of a conversation item.
 * Tools keep Arguments/Result for expand-in-place (same priority as fullscreen ToolRow).
 */
internal data class DebugOverlayEntry(
    val id: Long,
    val title: String,
    val body: String,
    val isError: Boolean,
    val isTool: Boolean = false,
    val toolName: String = "",
    val toolStatus: String = "",
    val running: Boolean = false,
    val durationLabel: String? = null,
    val argumentsJson: String? = null,
    val resultJson: String? = null,
) {
    fun toCompactEntry(): DebugCompactEntry =
        DebugCompactEntry(
            title = title,
            body = if (isTool) "" else body,
            isError = isError,
        )
}

internal fun DebugOverlayToolLine.toOverlayEntry(): DebugOverlayEntry =
    DebugOverlayEntry(
        id = id,
        title = name,
        body = "",
        isError = isError,
        isTool = true,
        toolName = name,
        toolStatus = statusLabel,
        running = running,
        durationLabel = durationLabel,
        argumentsJson = argumentsJson,
        resultJson = resultJson,
    )

internal fun DebugConversationItem.toOverlayEntry(): DebugOverlayEntry =
    when (this) {
        is DebugConversationItem.TurnHeader -> DebugOverlayEntry(
            id = id,
            title = label,
            body = preview.overlayCollapsedLine(),
            isError = false,
        )
        is DebugConversationItem.MessageItem -> DebugOverlayEntry(
            id = id,
            title = role.compactTitle(),
            body = content.overlayCollapsedLine(),
            isError = role == DebugTranscriptRole.ERROR,
        )
        is DebugConversationItem.ToolCallItem -> {
            val durationMs = finishedAt?.let { end -> (end - startedAt).coerceAtLeast(0L) }
            DebugOverlayEntry(
                id = id,
                title = toolName,
                body = "",
                isError = status == DebugToolCallStatus.FAILED,
                isTool = true,
                toolName = toolName,
                toolStatus = status.name.lowercase(),
                running = status == DebugToolCallStatus.RUNNING,
                durationLabel = durationMs?.let { formatWorkedDuration(it) },
                argumentsJson = argumentsJson,
                resultJson = resultJson,
            )
        }
        is DebugConversationItem.ConfirmationItem -> DebugOverlayEntry(
            id = id,
            title = "Confirmation ${status.compactLabel()}",
            body = domain.ifBlank { pendingId }.overlayCollapsedLine(),
            isError = status == DebugConfirmationStatus.DENIED ||
                status == DebugConfirmationStatus.AUTO_DENIED ||
                status == DebugConfirmationStatus.FAILED,
        )
        is DebugConversationItem.RuntimePhaseItem -> DebugOverlayEntry(
            id = id,
            title = title,
            body = body.overlayCollapsedLine(),
            isError = false,
        )
    }

/** @deprecated Prefer [toOverlayEntry]; kept for call sites that only need flat compact text. */
internal fun DebugConversationItem.toOverlayCompact(): DebugCompactEntry =
    toOverlayEntry().toCompactEntry()

private fun String.overlayCollapsedLine(): String {
    val line = lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()
    return if (line.length <= OVERLAY_COMPACT_BODY_CHARS) {
        line
    } else {
        line.take(OVERLAY_COMPACT_BODY_CHARS - 3) + "..."
    }
}

private const val OVERLAY_COMPACT_BODY_CHARS = 96
