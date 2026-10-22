@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import android.content.Context
import cc.noharry.agent.kernel.api.RuntimeEvidenceConfig
import java.io.File
import java.security.MessageDigest

/**
 * Explicit sample **bench** evidence wiring (debug/local only).
 *
 * Production path: [DebugRuntimeSessionRunner] sets `benchCaseBound = true` only when the
 * user input is **byte-identical** to [SAMPLE_DEBUG_CASE_PACK_PROMPT] (no trim/normalize),
 * then passes that same string as [AgentRequest.input] and into
 * [sampleDebugBenchEvidenceConfig] so stored hash always matches actual request bytes.
 *
 * Case Pack evaluation remains analyzer-side; host only binds ids + prompt hash.
 */
internal const val SAMPLE_DEBUG_CASE_PACK_ID = "sample.debug.v1"

/**
 * Deterministic prompt text for the sole sample Case Pack.
 * Must match [SAMPLE_DEBUG_CASE_PACK_PROMPT_SHA256] and the pack JSON prompt.sha256.
 */
internal const val SAMPLE_DEBUG_CASE_PACK_PROMPT: String =
    "SAMPLE_DEBUG_BENCH_V1:case=sample.debug.v1;normative=false"

/** SHA-256 (hex lowercase) of UTF-8 [SAMPLE_DEBUG_CASE_PACK_PROMPT]. */
internal const val SAMPLE_DEBUG_CASE_PACK_PROMPT_SHA256: String =
    "e3ab5758c78371c5ea5615fcb582c8eddff7d32f751001d1195624a96e729531"

internal fun sampleAgentReliabilityRoot(filesDir: File): File =
    File(filesDir, AGENT_RELIABILITY_DIR)

internal fun sampleAgentReliabilityRoot(context: Context): File =
    sampleAgentReliabilityRoot(context.filesDir)

internal fun sha256HexUtf8(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { b -> "%02x".format(b) }
}

/**
 * Build evidence only when [requestPrompt] is **byte-identical** to the sample bench prompt.
 * Hash is computed from that same string (must equal [AgentRequest.input] exactly).
 */
internal fun sampleDebugBenchEvidenceConfig(
    filesDir: File,
    requestPrompt: String,
): RuntimeEvidenceConfig {
    require(requestPrompt == SAMPLE_DEBUG_CASE_PACK_PROMPT) {
        "bench case-bound only for exact SAMPLE_DEBUG_CASE_PACK_PROMPT bytes; refuse other inputs"
    }
    val promptHash = sha256HexUtf8(requestPrompt)
    require(promptHash == SAMPLE_DEBUG_CASE_PACK_PROMPT_SHA256) {
        "sample bench prompt hash drift: computed=$promptHash expected=$SAMPLE_DEBUG_CASE_PACK_PROMPT_SHA256"
    }
    return RuntimeEvidenceConfig(
        storageRoot = sampleAgentReliabilityRoot(filesDir),
        enabledProbePackIds = setOf("bench_core"),
        casePackId = SAMPLE_DEBUG_CASE_PACK_ID,
        promptSha256 = promptHash,
    )
}

internal fun sampleDebugBenchEvidenceConfig(
    context: Context,
    requestPrompt: String,
): RuntimeEvidenceConfig =
    sampleDebugBenchEvidenceConfig(context.filesDir, requestPrompt)

/**
 * True only for **exact** sample bench prompt bytes (no trim).
 * Whitespace-padded inputs must not bind — they would hash differently from the pack.
 */
internal fun isSampleDebugBenchPrompt(text: String): Boolean =
    text == SAMPLE_DEBUG_CASE_PACK_PROMPT

/**
 * Production decision used by [DebugRuntimeSessionRunner]: exact prompt → bind.
 * Kept as a named entry so behavioral tests exercise the same predicate as production.
 */
internal fun productionBenchCaseBoundDecision(userInput: String): Boolean =
    isSampleDebugBenchPrompt(userInput)
