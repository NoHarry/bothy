package cc.noharry.bothy.sample

import cc.noharry.agent.kernel.api.AgentRuntime
import cc.noharry.agent.kernel.api.ConfirmationChoice
import cc.noharry.agent.kernel.api.ConfirmationDecision
import cc.noharry.agent.kernel.api.ConfirmationResumeResult
import cc.noharry.agent.kernel.api.PendingConfirmation
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus

internal data class DebugConfirmationPostUiResult(
    val status: String,
    val transcript: DebugSteeringTranscript,
    val terminal: Boolean = false,
    val runtimeResult: RuntimeResult? = null,
    val appliedChoice: ConfirmationChoice? = null,
)

internal data class DebugRecoveredConfirmation(
    val pending: PendingConfirmation,
    val domain: String,
)

internal suspend fun postDebugRuntimeConfirmation(
    runtime: AgentRuntime,
    pendingId: String,
    requestId: String,
    approve: Boolean,
    runId: String? = null,
): DebugConfirmationPostUiResult {
    val choice = if (approve) ConfirmationChoice.APPROVE else ConfirmationChoice.DENY
    val result = runCatching {
        runtime.resumeConfirmation(ConfirmationDecision(pendingId, requestId, choice))
    }
    return result.fold(
        onSuccess = { resumeResult ->
            val appliedChoice = resumeResult.appliedChoice(choice)
            val terminalResult = runId?.let { id -> runCatching { runtime.getRuntimeResult(id) }.getOrNull() }
            if (terminalResult != null) return@fold terminalResult.toDebugConfirmationUiResult(appliedChoice)
            resumeResult.toDebugConfirmationUiResult(pendingId, approve).copy(appliedChoice = appliedChoice)
        },
        onFailure = { error ->
            DebugConfirmationPostUiResult(
                status = "Confirmation failed",
                transcript = DebugSteeringTranscript.Error(
                    title = "Confirmation",
                    message = error.message ?: "Unable to resolve confirmation.",
                ),
            )
        },
    )
}

internal fun DebugConfirmationMode.autoDecision(
    domain: String,
    confirmedDomains: Set<String>,
): Boolean? =
    when (this) {
        DebugConfirmationMode.ASK_EVERY_TIME -> null
        DebugConfirmationMode.ALLOW_CONFIRMED_DOMAINS -> {
            if (domain.isNotBlank() && confirmedDomains.contains(domain)) true else null
        }
        DebugConfirmationMode.ALLOW_ALL_SESSION -> true
        DebugConfirmationMode.DENY_ALL -> false
    }

/**
 * Returns true when the active [mode] will auto-resolve a confirmation for [domain], meaning the
 * sample should suppress the PENDING confirmation card to avoid the "insert then async-resolve"
 * flicker. Only [DebugConfirmationMode.ASK_EVERY_TIME] (and ALLOW_CONFIRMED_DOMAINS for an
 * unconfirmed domain) leave the card visible for a manual tap.
 */
internal fun shouldSuppressConfirmationCard(
    mode: DebugConfirmationMode,
    domain: String,
    confirmedDomains: Set<String>,
): Boolean = mode.autoDecision(domain, confirmedDomains) != null

internal suspend fun recoverDebugPendingConfirmation(
    runtime: AgentRuntime,
    runId: String,
): DebugRecoveredConfirmation? {
    val snapshot = runCatching { runtime.getRunState(runId) }.getOrNull()
    if (snapshot?.status != RuntimeRunStatus.WAITING_CONFIRMATION) return null
    val pendingId = snapshot.pendingConfirmationId.orEmpty()
    if (pendingId.isBlank()) return null
    val pending = runCatching { runtime.getPendingConfirmation(pendingId) }.getOrNull() ?: return null
    val prompt = runCatching { runtime.getRuntimeHandoff(runId)?.nextActions.orEmpty() }
        .getOrDefault(emptyList())
        .joinToString(" ")
    return DebugRecoveredConfirmation(
        pending = pending,
        domain = prompt.extractNetworkConfirmationDomain(),
    )
}

private fun RuntimeResult.toDebugConfirmationUiResult(
    appliedChoice: ConfirmationChoice?,
): DebugConfirmationPostUiResult =
    DebugConfirmationPostUiResult(
        status = status.name,
        transcript = if (cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary.firstPartyPass(status, completion)) {
            DebugSteeringTranscript.Runtime(
                title = "Runtime result after confirmation",
                body = response,
            )
        } else {
            DebugSteeringTranscript.Error(
                title = "Runtime result after confirmation",
                message = response,
            )
        },
        terminal = true,
        runtimeResult = this,
        appliedChoice = appliedChoice,
    )

private fun ConfirmationResumeResult.toDebugConfirmationUiResult(
    pendingId: String,
    approve: Boolean,
): DebugConfirmationPostUiResult =
    when (this) {
        ConfirmationResumeResult.Accepted -> DebugConfirmationPostUiResult(
            status = if (approve) "Confirmation approved" else "Confirmation denied",
            transcript = DebugSteeringTranscript.Runtime(
                title = "Runtime confirmation resolved",
                body = "pendingId=$pendingId choice=${if (approve) "APPROVE" else "DENY"}",
            ),
        )
        ConfirmationResumeResult.AlreadyConsumed -> DebugConfirmationPostUiResult(
            status = "Confirmation consumed",
            transcript = DebugSteeringTranscript.Status("Confirmation was already resolved."),
        )
        ConfirmationResumeResult.Expired -> DebugConfirmationPostUiResult(
            status = "Confirmation expired",
            transcript = DebugSteeringTranscript.Status("Confirmation expired before a decision was applied."),
        )
        ConfirmationResumeResult.NotFound -> DebugConfirmationPostUiResult(
            status = "Confirmation missing",
            transcript = DebugSteeringTranscript.Status("Confirmation was not found."),
        )
        is ConfirmationResumeResult.Rejected -> DebugConfirmationPostUiResult(
            status = "Confirmation rejected",
            transcript = DebugSteeringTranscript.Error("Confirmation", reason),
        )
    }

private fun ConfirmationResumeResult.appliedChoice(choice: ConfirmationChoice): ConfirmationChoice? =
    when (this) {
        ConfirmationResumeResult.Accepted -> choice
        is ConfirmationResumeResult.Rejected -> choice
        ConfirmationResumeResult.AlreadyConsumed,
        ConfirmationResumeResult.Expired,
        ConfirmationResumeResult.NotFound
        -> null
    }

private fun String.extractNetworkConfirmationDomain(): String =
    NETWORK_CONFIRMATION_DOMAIN_REGEX.find(this)
        ?.let { match -> match.groups[1]?.value ?: match.groups[2]?.value }
        ?.substringBefore(":")
        ?.lowercase()
        .orEmpty()

private val NETWORK_CONFIRMATION_DOMAIN_REGEX = Regex(
    """https?://([^/\s)]+)|\b([A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+)\b""",
)
