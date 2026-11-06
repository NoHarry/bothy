package cc.noharry.agent.appserver.evidence
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus

internal data class RunReplayResult(
    val passed: Boolean,
    val summary: String,
    val failures: List<String>,
    val warnings: List<String>,
)

internal object RunReplayVerifier {
    fun verify(evidence: RunEvidence): RunReplayResult {
        val failures = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        addFailures(evidence, failures)
        addWarnings(evidence, warnings)
        // The host's own checks of an answer that passed otherwise ([RunAddOns.answerProblems]).
        if (evidence.firstPartyPassed()) {
            val problems = AppHost.runs.answerProblems(evidence.inputPreview, evidence.finalResponse.trim())
            failures += problems.failures
            warnings += problems.warnings
        }
        val passed = failures.isEmpty()
        val summary = "replay=${if (passed) "PASS" else "FAIL"} " +
            "final=${evidence.finalStatus ?: "missing"} " +
            "public=${evidence.counts.publicFinished}/${evidence.counts.publicStarted} " +
            "business=${evidence.counts.meaningfulBusinessTools} actionable=${evidence.requiresBusinessAction} " +
            "rawGaps=${evidence.counts.rawHookGaps} rawFailures=${evidence.counts.failedRawTools}"
        return RunReplayResult(passed, summary, failures, warnings)
    }

    private fun addFailures(evidence: RunEvidence, failures: MutableList<String>) {
        if (!evidence.firstPartyPassed() && !evidence.handedToSubagents()) {
            failures += "final result is not a first-party pass " +
                "(status=${evidence.finalStatus ?: "missing"} " +
                "completion=${evidence.finalCompletionOutcome ?: "missing"})"
        }
        if (evidence.counts.pendingPublicTools > 0) {
            failures += "public tool calls still pending: ${pendingPublicNames(evidence)}"
        }
        if (evidence.counts.orphanPublicFinishes > 0) {
            failures += "public tool finishes without starts: ${orphanPublicNames(evidence)}"
        }
        if (evidence.requiresBusinessAction && evidence.counts.meaningfulBusinessTools == 0) {
            failures += "actionable request completed without a meaningful business tool"
        }
    }

    private fun addWarnings(evidence: RunEvidence, warnings: MutableList<String>) {
        if (evidence.counts.rawHookGaps > 0) {
            warnings += "raw post hook missing for: ${rawGapNames(evidence)}"
        }
        if (evidence.counts.failedRawTools > 0) {
            warnings += "raw hook tool failures observed: ${failedRawNames(evidence)}"
        }
        if (evidence.counts.rawPre > 0 && evidence.counts.publicStarted == 0) {
            warnings += "raw hooks observed tools but no public NativeToolCallStarted events were captured"
        }
    }
}

/**
 * A completed run that answered first and left the work to its subagents: the model rightly calls that partial, but
 * the request was handled (owner 2026-10-17: the card said 没有完成 while the three lookups were under way).
 */
internal fun RunEvidence.handedToSubagents(): Boolean =
    finalStatus == RuntimeRunStatus.COMPLETED.name &&
        finalCompletionOutcome == RuntimeCompletionOutcome.PARTIAL.name &&
        tools.any { it.toolName == SPAWN_AGENT_TOOL }

private const val SPAWN_AGENT_TOOL = "spawn_agent"

internal fun RunEvidence.firstPartyPassed(): Boolean {
    val status = runCatching { RuntimeRunStatus.valueOf(finalStatus.orEmpty()) }.getOrNull()
        ?: return false
    val outcome = runCatching {
        RuntimeCompletionOutcome.valueOf(finalCompletionOutcome ?: RuntimeCompletionOutcome.UNKNOWN.name)
    }.getOrDefault(RuntimeCompletionOutcome.UNKNOWN)
    return RuntimeCompletionSummary.firstPartyPass(
        status,
        RuntimeCompletionSummary(outcome = outcome),
    )
}

private fun pendingPublicNames(evidence: RunEvidence): String =
    evidence.tools.filter { it.publicStarted && !it.publicFinished }.toolNames()

private fun orphanPublicNames(evidence: RunEvidence): String =
    evidence.tools.filter { it.publicFinished && !it.publicStarted }.toolNames()

private fun rawGapNames(evidence: RunEvidence): String =
    evidence.tools.filter { it.rawPre && !it.rawPost }.toolNames()

private fun failedRawNames(evidence: RunEvidence): String =
    evidence.tools.filter { it.rawPostStatus == RAW_STATUS_FAILED }.toolNames()

private fun List<RunToolEvidence>.toolNames(): String =
    joinToString("|") { "${it.sequence}:${it.toolName}" }
