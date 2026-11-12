@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.server.evidence
import cc.noharry.agent.appserver.evidence.SAMPLE_DEBUG_CASE_PACK_PROMPT_SHA256
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sample bench case pack Bothy ships names the prompt the server binds a bench run to. Binding a bench run is the
 * server's (`BenchEvidenceTest` in agent-app-server).
 */
class BenchEvidenceTest {
    @Test
    fun packJsonPromptHashMatchesConstant() {
        val pack = File("src/main/assets/runtime-workspace/bench-cases/sample.debug.v1.json")
        assertTrue(pack.isFile)
        val text = pack.readText(Charsets.UTF_8)
        assertTrue(text.contains(SAMPLE_DEBUG_CASE_PACK_PROMPT_SHA256))
        assertTrue(text.contains("sample.debug.v1"))
    }
}
