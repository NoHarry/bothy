package cc.noharry.bothy.app.overlay

import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.runtime.formatWorkedDuration
import cc.noharry.bothy.app.session.CompactEntry
import cc.noharry.bothy.app.session.ConfirmationStatus
import cc.noharry.bothy.app.session.ConversationItem
import cc.noharry.bothy.app.session.ToolCallStatus
import cc.noharry.bothy.app.session.TranscriptRole
import cc.noharry.bothy.app.session.compactLabel
import cc.noharry.bothy.app.session.compactTitle
/**
 * Overlay projection of a conversation item.
 * Tools keep Arguments/Result for expand-in-place (same priority as fullscreen ToolRow).
 */
internal data class OverlayEntry(
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
    fun toCompactEntry(): CompactEntry =
        CompactEntry(
            title = title,
            body = if (isTool) "" else body,
            isError = isError,
        )
}

internal fun OverlayToolLine.toOverlayEntry(): OverlayEntry =
    OverlayEntry(
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

internal fun ConversationItem.toOverlayEntry(): OverlayEntry =
    when (this) {
        is ConversationItem.TurnHeader -> OverlayEntry(
            id = id,
            title = label,
            body = preview.overlayCollapsedLine(),
            isError = false,
        )
        is ConversationItem.MessageItem -> OverlayEntry(
            id = id,
            title = role.compactTitle(),
            body = content.overlayCollapsedLine(),
            isError = role == TranscriptRole.ERROR,
        )
        is ConversationItem.ToolCallItem -> {
            val durationMs = finishedAt?.let { end -> (end - startedAt).coerceAtLeast(0L) }
            OverlayEntry(
                id = id,
                title = toolName,
                body = "",
                isError = status == ToolCallStatus.FAILED,
                isTool = true,
                toolName = toolName,
                toolStatus = status.name.lowercase(),
                running = status == ToolCallStatus.RUNNING,
                durationLabel = durationMs?.let { formatWorkedDuration(it) },
                argumentsJson = argumentsJson,
                resultJson = resultJson,
            )
        }
        is ConversationItem.ConfirmationItem -> OverlayEntry(
            id = id,
            title = "Confirmation ${status.compactLabel()}",
            body = domain.ifBlank { pendingId }.overlayCollapsedLine(),
            isError = status == ConfirmationStatus.DENIED ||
                status == ConfirmationStatus.AUTO_DENIED ||
                status == ConfirmationStatus.FAILED,
        )
        is ConversationItem.RuntimePhaseItem -> OverlayEntry(
            id = id,
            title = title,
            body = body.overlayCollapsedLine(),
            isError = false,
        )
    }

/** @deprecated Prefer [toOverlayEntry]; kept for call sites that only need flat compact text. */
internal fun ConversationItem.toOverlayCompact(): CompactEntry =
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
