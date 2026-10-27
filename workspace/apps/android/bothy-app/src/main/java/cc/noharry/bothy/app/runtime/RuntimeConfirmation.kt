package cc.noharry.bothy.app.runtime
import cc.noharry.agent.kernel.api.AgentRuntime
import cc.noharry.agent.kernel.api.ConfirmationChoice
import cc.noharry.agent.kernel.api.ConfirmationDecision
import cc.noharry.agent.kernel.api.ConfirmationResumeResult
import cc.noharry.agent.kernel.api.PendingConfirmation
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.session.ConfirmationMode

internal data class ConfirmationPostUiResult(
    val status: String,
    val transcript: SteeringTranscript,
    val terminal: Boolean = false,
    val runtimeResult: RuntimeResult? = null,
    val appliedChoice: ConfirmationChoice? = null,
)

internal data class RecoveredConfirmation(
    val pending: PendingConfirmation,
    val domain: String,
)

internal suspend fun postRuntimeConfirmation(
    runtime: AgentRuntime,
    pendingId: String,
    requestId: String,
    approve: Boolean,
    runId: String? = null,
): ConfirmationPostUiResult {
    val choice = if (approve) ConfirmationChoice.APPROVE else ConfirmationChoice.DENY
    val result = runCatching {
        runtime.resumeConfirmation(ConfirmationDecision(pendingId, requestId, choice))
    }
    return result.fold(
        onSuccess = { resumeResult ->
            val appliedChoice = resumeResult.appliedChoice(choice)
            val terminalResult = runId?.let { id -> runCatching { runtime.getRuntimeResult(id) }.getOrNull() }
            if (terminalResult != null) return@fold terminalResult.toConfirmationUiResult(appliedChoice)
            resumeResult.toConfirmationUiResult(pendingId, approve).copy(appliedChoice = appliedChoice)
        },
        onFailure = { error ->
            ConfirmationPostUiResult(
                status = "Confirmation failed",
                transcript = SteeringTranscript.Error(
                    title = "Confirmation",
                    message = error.message ?: "Unable to resolve confirmation.",
                ),
            )
        },
    )
}

internal fun ConfirmationMode.autoDecision(
    domain: String,
    confirmedDomains: Set<String>,
): Boolean? =
    when (this) {
        ConfirmationMode.ASK_EVERY_TIME -> null
        ConfirmationMode.ALLOW_CONFIRMED_DOMAINS -> {
            if (domain.isNotBlank() && confirmedDomains.contains(domain)) true else null
        }
        ConfirmationMode.ALLOW_ALL_SESSION -> true
        ConfirmationMode.DENY_ALL -> false
    }

/**
 * Returns true when the active [mode] will auto-resolve a confirmation for [domain], meaning the
 * sample should suppress the PENDING confirmation card to avoid the "insert then async-resolve"
 * flicker. Only [ConfirmationMode.ASK_EVERY_TIME] (and ALLOW_CONFIRMED_DOMAINS for an
 * unconfirmed domain) leave the card visible for a manual tap.
 */
internal fun shouldSuppressConfirmationCard(
    mode: ConfirmationMode,
    domain: String,
    confirmedDomains: Set<String>,
): Boolean = mode.autoDecision(domain, confirmedDomains) != null

internal suspend fun recoverPendingConfirmation(
    runtime: AgentRuntime,
    runId: String,
): RecoveredConfirmation? {
    val snapshot = runCatching { runtime.getRunState(runId) }.getOrNull()
    if (snapshot?.status != RuntimeRunStatus.WAITING_CONFIRMATION) return null
    val pendingId = snapshot.pendingConfirmationId.orEmpty()
    if (pendingId.isBlank()) return null
    val pending = runCatching { runtime.getPendingConfirmation(pendingId) }.getOrNull() ?: return null
    val prompt = runCatching { runtime.getRuntimeHandoff(runId)?.nextActions.orEmpty() }
        .getOrDefault(emptyList())
        .joinToString(" ")
    return RecoveredConfirmation(
        pending = pending,
        domain = prompt.extractNetworkConfirmationDomain(),
    )
}

private fun RuntimeResult.toConfirmationUiResult(
    appliedChoice: ConfirmationChoice?,
): ConfirmationPostUiResult =
    ConfirmationPostUiResult(
        status = status.name,
        transcript = if (cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary.firstPartyPass(status, completion)) {
            SteeringTranscript.Runtime(
                title = "Runtime result after confirmation",
                body = response,
            )
        } else {
            SteeringTranscript.Error(
                title = "Runtime result after confirmation",
                message = response,
            )
        },
        terminal = true,
        runtimeResult = this,
        appliedChoice = appliedChoice,
    )

private fun ConfirmationResumeResult.toConfirmationUiResult(
    pendingId: String,
    approve: Boolean,
): ConfirmationPostUiResult =
    when (this) {
        ConfirmationResumeResult.Accepted -> ConfirmationPostUiResult(
            status = if (approve) "Confirmation approved" else "Confirmation denied",
            transcript = SteeringTranscript.Runtime(
                title = "Runtime confirmation resolved",
                body = "pendingId=$pendingId choice=${if (approve) "APPROVE" else "DENY"}",
            ),
        )
        ConfirmationResumeResult.AlreadyConsumed -> ConfirmationPostUiResult(
            status = "Confirmation consumed",
            transcript = SteeringTranscript.Status("Confirmation was already resolved."),
        )
        ConfirmationResumeResult.Expired -> ConfirmationPostUiResult(
            status = "Confirmation expired",
            transcript = SteeringTranscript.Status("Confirmation expired before a decision was applied."),
        )
        ConfirmationResumeResult.NotFound -> ConfirmationPostUiResult(
            status = "Confirmation missing",
            transcript = SteeringTranscript.Status("Confirmation was not found."),
        )
        is ConfirmationResumeResult.Rejected -> ConfirmationPostUiResult(
            status = "Confirmation rejected",
            transcript = SteeringTranscript.Error("Confirmation", reason),
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
