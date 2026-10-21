package cc.noharry.bothy.sample

import cc.noharry.bothy.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.bothy.kernel.api.reliability.RuntimeCompletionSummary
import cc.noharry.bothy.kernel.api.reliability.RuntimeRunStatus

internal data class DebugRunReplayResult(
    val passed: Boolean,
    val summary: String,
    val failures: List<String>,
    val warnings: List<String>,
)

internal object DebugRunReplayVerifier {
    fun verify(evidence: DebugRunEvidence): DebugRunReplayResult {
        val failures = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        addFailures(evidence, failures)
        addWarnings(evidence, warnings)
        val passed = failures.isEmpty()
        val summary = "replay=${if (passed) "PASS" else "FAIL"} " +
            "final=${evidence.finalStatus ?: "missing"} " +
            "public=${evidence.counts.publicFinished}/${evidence.counts.publicStarted} " +
            "business=${evidence.counts.meaningfulBusinessTools} actionable=${evidence.requiresBusinessAction} " +
            "rawGaps=${evidence.counts.rawHookGaps} rawFailures=${evidence.counts.failedRawTools}"
        return DebugRunReplayResult(passed, summary, failures, warnings)
    }

    private fun addFailures(evidence: DebugRunEvidence, failures: MutableList<String>) {
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
        addFinalResponseQualityFailures(evidence, failures)
    }

    private fun addWarnings(evidence: DebugRunEvidence, warnings: MutableList<String>) {
        if (evidence.counts.rawHookGaps > 0) {
            warnings += "raw post hook missing for: ${rawGapNames(evidence)}"
        }
        if (evidence.counts.failedRawTools > 0) {
            warnings += "raw hook tool failures observed: ${failedRawNames(evidence)}"
        }
        if (evidence.counts.rawPre > 0 && evidence.counts.publicStarted == 0) {
            warnings += "raw hooks observed tools but no public NativeToolCallStarted events were captured"
        }
        addFinalResponseQualityWarnings(evidence, warnings)
    }
}

/**
 * A completed run that answered first and left the work to its subagents: the model rightly calls that partial, but
 * the request was handled (owner 2026-10-17: the card said 没有完成 while the three lookups were under way).
 */
internal fun DebugRunEvidence.handedToSubagents(): Boolean =
    finalStatus == RuntimeRunStatus.COMPLETED.name &&
        finalCompletionOutcome == RuntimeCompletionOutcome.PARTIAL.name &&
        tools.any { it.toolName == SPAWN_AGENT_TOOL }

private const val SPAWN_AGENT_TOOL = "spawn_agent"

internal fun DebugRunEvidence.firstPartyPassed(): Boolean {
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

private fun addFinalResponseQualityFailures(evidence: DebugRunEvidence, failures: MutableList<String>) {
    if (!evidence.requiresComplexNameRouteFinalResponse()) return
    if (!evidence.firstPartyPassed()) return
    val response = evidence.finalResponse.trim()
    if (response.isBlank()) {
        failures += "final response is missing for complex name-route request"
        return
    }
    if (response.isPureNarrationFinalResponse()) {
        failures += "final response is process narration, not a direct user-visible answer"
    }
}

private fun addFinalResponseQualityWarnings(evidence: DebugRunEvidence, warnings: MutableList<String>) {
    if (!evidence.requiresComplexNameRouteFinalResponse()) return
    if (!evidence.firstPartyPassed()) return
    val response = evidence.finalResponse.trim()
    if (response.isBlank() || response.isPureNarrationFinalResponse()) return
    if (!response.hasNameExplanation()) {
        warnings += "final response is missing name explanation"
    }
    if (!response.hasRouteKeyInfo()) {
        warnings += "final response is missing route key info"
    }
}

private fun DebugRunEvidence.requiresComplexNameRouteFinalResponse(): Boolean {
    val input = inputPreview
    val asksName = input.containsAny("介绍下你自己", "介绍一下你自己", "为什么叫这个名字", "名字")
    val asksRoute = input.contains("广州塔") &&
        input.containsAny("深圳北站", "肯德基") &&
        input.containsAny("停车", "厕所", "洗手间", "周杰伦", "不走高速")
    return asksName && asksRoute
}

private fun String.hasProspectiveFinishNarration(): Boolean =
    PROSPECTIVE_FINISH_NARRATION.any { pattern -> pattern.containsMatchIn(this) }

private fun String.isPureNarrationFinalResponse(): Boolean {
    val normalized = trim().replace(Regex("""\s+"""), " ")
    if (normalized.isBlank()) return false
    if (normalized.hasConcreteFinalResultContent()) return false
    return normalized.hasProspectiveFinishNarration() ||
        (normalized.length < SHORT_NARRATION_RESPONSE_LIMIT && normalized.hasGenericNonAnswerNarration())
}

private fun String.hasConcreteFinalResultContent(): Boolean =
    hasNameExplanation() ||
        hasRouteKeyInfo() ||
        containsAny(
            "广州塔",
            "深圳北站",
            "肯德基",
            "停车",
            "厕所",
            "洗手间",
            "卫生间",
            "导航",
            "路线",
            "周杰伦",
            "KFC",
            "parking",
            "route",
            "navigation",
        )

private fun String.hasGenericNonAnswerNarration(): Boolean =
    GENERIC_NON_ANSWER_NARRATION.any { pattern -> pattern.containsMatchIn(this) }

private fun String.hasNameExplanation(): Boolean =
    containsAny("我叫", "我是", "名字", "叫这个名字", "叫做", "称为") &&
        containsAny("因为", "代表", "寓意", "含义", "意思", "来源", "取自")

private fun String.hasRouteKeyInfo(): Boolean {
    val signals = listOf(
        contains("广州塔"),
        contains("停车"),
        containsAny("深圳北站", "肯德基"),
        containsAny("不走高速", "避开高速", "高速"),
        containsAny("厕所", "洗手间", "卫生间"),
        containsAny("周杰伦", "音乐", "歌"),
        containsAny("导航", "路线"),
    )
    return signals.count { it } >= 4
}

private fun String.containsAny(vararg values: String): Boolean =
    values.any { contains(it, ignoreCase = true) }

private val PROSPECTIVE_FINISH_NARRATION = listOf(
    Regex("""\blet me (?:now )?(?:update|finish|call|provide)\b""", RegexOption.IGNORE_CASE),
    Regex("""\b(?:call|use)\s+finish\b""", RegexOption.IGNORE_CASE),
    Regex("""\b(?:provide|write|compose) (?:the )?final response\b""", RegexOption.IGNORE_CASE),
    Regex("""\bupdate (?:the )?todo\b""", RegexOption.IGNORE_CASE),
    Regex("""\bfinish with (?:a |the )?.*response\b""", RegexOption.IGNORE_CASE),
    Regex("""(我来|我会|我将|现在|准备).{0,8}(更新|调用).{0,8}(todo|finish|最终答复|最终回答)"""),
)

private const val SHORT_NARRATION_RESPONSE_LIMIT = 120
private val GENERIC_NON_ANSWER_NARRATION = listOf(
    Regex("""\ball (?:tasks|actions|steps|operations) (?:are )?(?:complete|completed|done)\b""", RegexOption.IGNORE_CASE),
    Regex("""\bcompleted successfully\b""", RegexOption.IGNORE_CASE),
    Regex("""\boperations are complete\b""", RegexOption.IGNORE_CASE),
    Regex("""^\(?same reasoning as previous tool call\)?$""", RegexOption.IGNORE_CASE),
)

private fun pendingPublicNames(evidence: DebugRunEvidence): String =
    evidence.tools.filter { it.publicStarted && !it.publicFinished }.toolNames()

private fun orphanPublicNames(evidence: DebugRunEvidence): String =
    evidence.tools.filter { it.publicFinished && !it.publicStarted }.toolNames()

private fun rawGapNames(evidence: DebugRunEvidence): String =
    evidence.tools.filter { it.rawPre && !it.rawPost }.toolNames()

private fun failedRawNames(evidence: DebugRunEvidence): String =
    evidence.tools.filter { it.rawPostStatus == RAW_STATUS_FAILED }.toolNames()

private fun List<DebugRunToolEvidence>.toolNames(): String =
    joinToString("|") { "${it.sequence}:${it.toolName}" }
