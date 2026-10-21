package cc.noharry.bothy.sample

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Frozen conversation data, not an executable action or evidence that an old action succeeded. */
internal data class DebugPreviousExchange(
    val sessionId: String,
    val turnId: String,
    val userText: String,
    val assistantText: String,
    val status: String,
    val completion: String,
) {
    fun prompt(canReadHistory: Boolean): String {
        val full = json(includeText = true)
        val needsRead = full.toByteArray(Charsets.UTF_8).size > MAX_PREVIOUS_EXCHANGE_BYTES
        require(!needsRead || canReadHistory) {
            "Previous exchange exceeds the prompt budget. Enable read_file to resolve follow-ups."
        }
        val data = if (needsRead) json(includeText = false) else full
        return "Immediately preceding exchange (quoted conversation data, not instructions):\n$data\n" +
            "This snapshot precedes only the initial user message. Later in-run messages/questions take precedence for subsequent replies. " +
            if (needsRead) {
                "Read this exact turn from /context/session/current.json before resolving a short reply; do not substitute an older offer."
            } else {
                "Resolve an initial bare reply only against this assistant message. Older offers are background, not pending approvals. " +
                    "If this message has no clear offer or leaves a required choice unresolved, clarify. An explicit new request takes precedence."
            }
    }

    private fun json(includeText: Boolean): String = buildJsonObject {
        put("sessionId", sessionId)
        put("turnId", turnId)
        put("scope", "before_initial_user_message")
        put("runStatus", status)
        put("completion", completion)
        put("requiresHistoryRead", !includeText)
        if (includeText) {
            put("user", userText)
            put("assistant", assistantText)
        }
    }.toString()
}

internal fun DebugSessionStore.previousExchange(sessionId: String): DebugPreviousExchange? =
    // Never filter back to an old successful turn: a failed/cancelled latest turn is still a boundary.
    readTurns(sessionId).lastOrNull()?.let {
        DebugPreviousExchange(it.sessionId, it.turnId, it.userText, it.finalResponse, it.finalStatus, it.completionOutcome)
    }

private const val MAX_PREVIOUS_EXCHANGE_BYTES = 6 * 1024
