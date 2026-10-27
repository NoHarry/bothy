@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.evidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BenchEvidenceTest {
    @Test
    fun promptHashMatchesDeterministicBenchPrompt() {
        assertEquals(
            SAMPLE_DEBUG_CASE_PACK_PROMPT_SHA256,
            sha256HexUtf8(SAMPLE_DEBUG_CASE_PACK_PROMPT),
        )
    }

    @Test
    fun packJsonPromptHashMatchesConstant() {
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
            val cfg = benchEvidenceConfig(filesDir, SAMPLE_DEBUG_CASE_PACK_PROMPT)
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
                benchEvidenceConfig(filesDir, "hello ordinary user")
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
            assertFalse(isBenchPrompt(padded))
            try {
                benchEvidenceConfig(filesDir, padded)
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
    fun onlyExactBenchPromptIsBench() {
        assertTrue(isBenchPrompt(SAMPLE_DEBUG_CASE_PACK_PROMPT))
        assertTrue(productionBenchCaseBoundDecision(SAMPLE_DEBUG_CASE_PACK_PROMPT))
        // Counterexamples: leading/trailing whitespace must NOT match (byte identity).
        assertFalse(isBenchPrompt("  $SAMPLE_DEBUG_CASE_PACK_PROMPT  "))
        assertFalse(isBenchPrompt(" $SAMPLE_DEBUG_CASE_PACK_PROMPT"))
        assertFalse(isBenchPrompt("$SAMPLE_DEBUG_CASE_PACK_PROMPT "))
        assertFalse(isBenchPrompt("\t$SAMPLE_DEBUG_CASE_PACK_PROMPT"))
        assertFalse(isBenchPrompt("finish with done"))
        assertFalse(isBenchPrompt(""))
        assertFalse(productionBenchCaseBoundDecision("  $SAMPLE_DEBUG_CASE_PACK_PROMPT  "))
    }
}
