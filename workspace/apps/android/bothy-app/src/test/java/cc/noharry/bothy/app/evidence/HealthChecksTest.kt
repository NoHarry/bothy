package cc.noharry.bothy.app.evidence
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.bothy.app.model.DeepSeekCheck
import cc.noharry.bothy.app.model.DeepSeekProbe
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Settings → 检查: failures read as what to do about them, and one stuck or broken check never holds up the rest. */
@RunWith(RobolectricTestRunner::class)
class HealthChecksTest {
    @Test
    fun failedRequestsSayWhatIsWrong() {
        assertTrue(requestProblem(IllegalStateException("Model catalog request failed: status=401")).contains("key 不对"))
        assertTrue(requestProblem(IOException("Jev HTTP 404.")).contains("地址不对"))
        assertTrue(requestProblem(IOException("timeout")).startsWith("连不上"))
        assertEquals("HTTP 418", statusProblem(418))
    }

    @Test
    fun aBrokenCheckIsReportedAndTheOthersStillFinish() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val checks = listOf(
            HealthCheck("good", "g", "good") { CheckResult(CheckState.OK, "fine") },
            HealthCheck("broken", "g", "broken") { throw IOException("down") },
        )
        val results = mutableMapOf<String, CheckResult>()
        runHealthChecks(context, checks) { id, result -> results[id] = result }
        assertEquals(CheckState.OK, results.getValue("good").state)
        assertEquals(CheckState.FAIL, results.getValue("broken").state)
        assertEquals("down", results.getValue("broken").detail)
    }

    @Test
    fun aSelectedModelTheServiceNoLongerListsIsCalledStaleWithWhatIsListed() {
        val stale = chatModelListed("deepseek anthropic · deepseek-v4-flash", "deepseek-v4-flash", listOf("deepseek-flash", "deepseek-v4-pro"))
        val many = chatModelListed("gw · old", "old", (1..7).map { "m$it" })

        assertEquals(CheckState.OK, chatModelListed("p · m", "m", listOf("m", "n")).state)
        assertEquals(CheckState.OK, chatModelListed("p · m", "m", emptyList()).state)
        assertEquals(CheckState.WARN, stale.state)
        assertEquals(
            "deepseek anthropic · deepseek-v4-flash · 服务可用，但当前选的 deepseek-v4-flash 不在它列出的模型里（可能已下线），请在输入框旁换一个：deepseek-flash、deepseek-v4-pro",
            stale.detail,
        )
        assertTrue(many.detail, many.detail.endsWith("m1、m2、m3、m4、m5 等 7 个"))
    }

    @Test
    fun aRelaysDeepSeekCheckSaysWhatPassedAndWhyTheRestDidNot() {
        val native = DeepSeekProbe(
            "f",
            DeepSeekCheck(null, "HTTP 404：Invalid URL (GET /v1/user/balance)"),
            DeepSeekCheck(null, "HTTP 404"),
            DeepSeekCheck("http://127.0.0.1:18080/v1/messages", "原样透传"),
            1L,
        ).checkResult("local")
        val nothing = DeepSeekProbe("f", DeepSeekCheck(null, "HTTP 404"), DeepSeekCheck(null, "HTTP 404"), null, 1L).checkResult("gw")

        assertEquals(CheckState.OK, native.state)
        assertEquals("local · 余额 ✗ HTTP 404：Invalid URL (GET /v1/user/balance) · Search/Fetch ✗ HTTP 404 · 原生延迟工具 ✓", native.detail)
        assertEquals(CheckState.WARN, nothing.state)
        assertEquals(CheckFix.MODELS, nothing.fix)
        assertTrue(nothing.detail, nothing.detail.endsWith("照常能用，只是没有 DeepSeek 专属能力"))
        assertTrue(DEBUG_CHECKS.indexOfFirst { it.id == "deepseek" } == DEBUG_CHECKS.indexOfFirst { it.id == "chat" } + 1)
    }

    @Test
    fun everyCheckHasAPlaceOnThePage() {
        assertEquals(DEBUG_CHECKS.size, DEBUG_CHECKS.map { it.id }.toSet().size)
        assertEquals(listOf("模型", "语音", "权限与服务"), DEBUG_CHECKS.map { it.group }.distinct())
    }

    @Test(timeout = 30_000)
    fun aStuckCheckTimesOut() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val results = mutableMapOf<String, CheckResult>()
        runHealthChecks(context, listOf(HealthCheck("stuck", "g", "stuck") { awaitCancellation() })) { id, result -> results[id] = result }
        assertTrue(results.getValue("stuck").detail.startsWith("超时"))
    }
}
