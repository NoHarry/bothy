package cc.noharry.bothy.app.overlay
import cc.noharry.agent.appserver.protocol.USER_STEERING_LABEL
import cc.noharry.agent.appserver.protocol.USER_TURN_LABEL

import android.content.res.Resources
import cc.noharry.agent.appserver.protocol.ConfirmationStatus
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.bothy.app.R
import cc.noharry.bothy.protocol.view.A2aHudTexts

/**
 * Flat single-line projection used by the overlay window, which only shows a compact
 * text strip and does not need the full structured layout.
 */
internal data class CompactEntry(
    val title: String,
    val body: String,
    val isError: Boolean,
)

internal fun ConversationItem.toCompact(): CompactEntry =
    when (this) {
        is ConversationItem.TurnHeader -> CompactEntry(if (label == USER_STEERING_LABEL) USER_TURN_LABEL else label, preview, isError = false)
        is ConversationItem.MessageItem -> CompactEntry(
            title = role.compactTitle(),
            body = content,
            isError = role == TranscriptRole.ERROR,
        )
        is ConversationItem.ToolCallItem -> CompactEntry(
            title = "Tool ${status.compactMarker()} $toolName",
            body = resultJson ?: argumentsJson ?: "",
            isError = status == ToolCallStatus.FAILED,
        )
        is ConversationItem.ConfirmationItem -> CompactEntry(
            title = "Confirmation ${status.compactLabel()}",
            body = domain.ifBlank { pendingId },
            isError = status == ConfirmationStatus.DENIED ||
                status == ConfirmationStatus.AUTO_DENIED ||
                status == ConfirmationStatus.FAILED,
        )
        is ConversationItem.RuntimePhaseItem -> CompactEntry(title, body, isError = false)
    }

internal fun TranscriptRole.compactTitle(): String =
    when (this) {
        TranscriptRole.ASSISTANT -> "Assistant"
        TranscriptRole.THINKING -> "Thinking"
        TranscriptRole.RUNTIME -> "Runtime"
        TranscriptRole.STATUS -> "Status"
        TranscriptRole.ERROR -> "Error"
        TranscriptRole.NOTICE -> "Notice"
    }

internal fun ToolCallStatus.compactMarker(): String =
    when (this) {
        ToolCallStatus.RUNNING -> "⏳"
        ToolCallStatus.DONE -> "✓"
        ToolCallStatus.FAILED -> "✗"
        ToolCallStatus.UNKNOWN -> "?"
    }

internal fun ConfirmationStatus.compactLabel(): String =
    when (this) {
        ConfirmationStatus.PENDING -> "pending"
        ConfirmationStatus.APPROVED -> "approved"
        ConfirmationStatus.DENIED -> "denied"
        ConfirmationStatus.AUTO_APPROVED -> "auto-approved"
        ConfirmationStatus.AUTO_DENIED -> "auto-denied"
        ConfirmationStatus.CONSUMED -> "consumed"
        ConfirmationStatus.EXPIRED -> "expired"
        ConfirmationStatus.FAILED -> "failed"
    }

/** The A2A card's own words in the screen's language. */
internal fun Resources.a2aHudTexts(): A2aHudTexts = object : A2aHudTexts {
    override fun question(text: String): String = getString(R.string.a2a_hud_question, text)

    override fun providerWithoutSpeech(endpoint: String): String = getString(R.string.a2a_hud_provider_no_tts, endpoint)

    override val planEmpty: String get() = getString(R.string.a2a_hud_plan_empty)
}
