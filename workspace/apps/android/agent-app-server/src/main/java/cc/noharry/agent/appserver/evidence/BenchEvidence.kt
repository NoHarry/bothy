@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.evidence
import android.content.Context
import cc.noharry.agent.appserver.InternalAppServerApi
import cc.noharry.agent.appserver.runtime.AGENT_RELIABILITY_DIR
import cc.noharry.agent.kernel.api.RuntimeEvidenceConfig
import java.io.File
import java.security.MessageDigest

/**
 * Explicit sample **bench** evidence wiring (debug/local only).
 *
 * Production path: [RuntimeSessionRunner] sets `benchCaseBound = true` only when the
 * user input is **byte-identical** to [SAMPLE_DEBUG_CASE_PACK_PROMPT] (no trim/normalize),
 * then passes that same string as [AgentRequest.input] and into
 * [benchEvidenceConfig] so stored hash always matches actual request bytes.
 *
 * Case Pack evaluation remains analyzer-side; host only binds ids + prompt hash.
 */
@InternalAppServerApi
const val SAMPLE_DEBUG_CASE_PACK_ID = "sample.debug.v1"

/**
 * Deterministic prompt text for the sole sample Case Pack.
 * Must match [SAMPLE_DEBUG_CASE_PACK_PROMPT_SHA256] and the pack JSON prompt.sha256.
 */
@InternalAppServerApi
const val SAMPLE_DEBUG_CASE_PACK_PROMPT: String =
    "SAMPLE_DEBUG_BENCH_V1:case=sample.debug.v1;normative=false"

/** SHA-256 (hex lowercase) of UTF-8 [SAMPLE_DEBUG_CASE_PACK_PROMPT]. */
@InternalAppServerApi
const val SAMPLE_DEBUG_CASE_PACK_PROMPT_SHA256: String =
    "e3ab5758c78371c5ea5615fcb582c8eddff7d32f751001d1195624a96e729531"

@InternalAppServerApi
fun agentReliabilityRoot(filesDir: File): File =
    File(filesDir, AGENT_RELIABILITY_DIR)

@InternalAppServerApi
fun agentReliabilityRoot(context: Context): File =
    agentReliabilityRoot(context.filesDir)

@InternalAppServerApi
fun sha256HexUtf8(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { b -> "%02x".format(b) }
}

/**
 * Build evidence only when [requestPrompt] is **byte-identical** to the sample bench prompt.
 * Hash is computed from that same string (must equal [AgentRequest.input] exactly).
 */
@InternalAppServerApi
fun benchEvidenceConfig(
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
        storageRoot = agentReliabilityRoot(filesDir),
        enabledProbePackIds = setOf("bench_core"),
        casePackId = SAMPLE_DEBUG_CASE_PACK_ID,
        promptSha256 = promptHash,
    )
}

@InternalAppServerApi
fun benchEvidenceConfig(
    context: Context,
    requestPrompt: String,
): RuntimeEvidenceConfig =
    benchEvidenceConfig(context.filesDir, requestPrompt)

/**
 * True only for **exact** sample bench prompt bytes (no trim).
 * Whitespace-padded inputs must not bind — they would hash differently from the pack.
 */
@InternalAppServerApi
fun isBenchPrompt(text: String): Boolean =
    text == SAMPLE_DEBUG_CASE_PACK_PROMPT

/**
 * Production decision used by [RuntimeSessionRunner]: exact prompt → bind.
 * Kept as a named entry so behavioral tests exercise the same predicate as production.
 */
@InternalAppServerApi
fun productionBenchCaseBoundDecision(userInput: String): Boolean =
    isBenchPrompt(userInput)
