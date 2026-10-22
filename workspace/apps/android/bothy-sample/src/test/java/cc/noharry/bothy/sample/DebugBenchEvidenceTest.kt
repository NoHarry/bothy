@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DebugBenchEvidenceTest {
    @Test
    fun promptHashMatchesDeterministicBenchPrompt() {
        assertEquals(
            SAMPLE_DEBUG_CASE_PACK_PROMPT_SHA256,
            sha256HexUtf8(SAMPLE_DEBUG_CASE_PACK_PROMPT),
        )
    }

    @Test
    fun samplePackJsonPromptHashMatchesConstant() {
        val pack = File("src/main/assets/runtime-workspace/bench-cases/sample.debug.v1.json")
        assertTrue(pack.isFile)
        val text = pack.readText(Charsets.UTF_8)
        assertTrue(text.contains(SAMPLE_DEBUG_CASE_PACK_PROMPT_SHA256))
        assertTrue(text.contains("sample.debug.v1"))
    }

    @Test
    fun evidenceConfigHashesThePassedRequestPrompt() {
        val filesDir = Files.createTempDirectory("bench-ev").toFile()
        try {
            val cfg = sampleDebugBenchEvidenceConfig(filesDir, SAMPLE_DEBUG_CASE_PACK_PROMPT)
            assertEquals(SAMPLE_DEBUG_CASE_PACK_ID, cfg.casePackId)
            assertEquals(sha256HexUtf8(SAMPLE_DEBUG_CASE_PACK_PROMPT), cfg.promptSha256)
            assertEquals(SAMPLE_DEBUG_CASE_PACK_PROMPT_SHA256, cfg.promptSha256)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun evidenceConfigRejectsNonBenchPrompt() {
        val filesDir = Files.createTempDirectory("bench-ev-bad").toFile()
        try {
            try {
                sampleDebugBenchEvidenceConfig(filesDir, "hello ordinary user")
                org.junit.Assert.fail("expected reject")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun evidenceConfigRejectsWhitespacePaddedBenchPrompt() {
        val filesDir = Files.createTempDirectory("bench-ev-pad").toFile()
        try {
            val padded = "  $SAMPLE_DEBUG_CASE_PACK_PROMPT  "
            assertFalse(isSampleDebugBenchPrompt(padded))
            try {
                sampleDebugBenchEvidenceConfig(filesDir, padded)
                org.junit.Assert.fail("padded prompt must not bind")
            } catch (_: IllegalArgumentException) {
                // expected — would otherwise store e3ab5758... while input hashes to another value
            }
            assertTrue(sha256HexUtf8(padded) != SAMPLE_DEBUG_CASE_PACK_PROMPT_SHA256)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun onlyExactBenchPromptIsSampleDebugBench() {
        assertTrue(isSampleDebugBenchPrompt(SAMPLE_DEBUG_CASE_PACK_PROMPT))
        assertTrue(productionBenchCaseBoundDecision(SAMPLE_DEBUG_CASE_PACK_PROMPT))
        // Counterexamples: leading/trailing whitespace must NOT match (byte identity).
        assertFalse(isSampleDebugBenchPrompt("  $SAMPLE_DEBUG_CASE_PACK_PROMPT  "))
        assertFalse(isSampleDebugBenchPrompt(" $SAMPLE_DEBUG_CASE_PACK_PROMPT"))
        assertFalse(isSampleDebugBenchPrompt("$SAMPLE_DEBUG_CASE_PACK_PROMPT "))
        assertFalse(isSampleDebugBenchPrompt("\t$SAMPLE_DEBUG_CASE_PACK_PROMPT"))
        assertFalse(isSampleDebugBenchPrompt("finish with done"))
        assertFalse(isSampleDebugBenchPrompt(""))
        assertFalse(productionBenchCaseBoundDecision("  $SAMPLE_DEBUG_CASE_PACK_PROMPT  "))
    }
}
