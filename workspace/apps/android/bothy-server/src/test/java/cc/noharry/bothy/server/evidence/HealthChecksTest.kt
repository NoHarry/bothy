package cc.noharry.bothy.server.evidence
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.model.DeepSeekCheck
import cc.noharry.agent.appserver.model.DeepSeekProbe
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.protocol.parts
import cc.noharry.agent.appserver.protocol.text
import cc.noharry.bothy.protocol.BothyFindingCode
import cc.noharry.bothy.protocol.CheckGroup
import cc.noharry.bothy.protocol.CheckId
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings → 检查: failures name what to do about them, and one stuck or broken check never holds up the rest. The page
 * words what each check found (`FindingWordingUiTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HealthChecksTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun failedRequestsSayWhatIsWrong() {
        assertEquals(finding(BothyFindingCode.CHECKS_HTTP_KEY, 401), requestProblem(IllegalStateException("Model catalog request failed: status=401")))
        assertEquals(finding(BothyFindingCode.CHECKS_HTTP_404), requestProblem(IOException("Jev HTTP 404.")))
        assertEquals(finding(BothyFindingCode.CHECKS_CANNOT_CONNECT, "timeout"), requestProblem(IOException("timeout")))
        assertEquals(finding(FindingCode.HTTP_STATUS, 418), statusProblem(418))
    }

    @Test
    fun aBrokenCheckIsReportedAndTheOthersStillFinish() = runBlocking {
        val checks = listOf(
            HealthCheck(CheckId.CHAT, CheckGroup.MODELS) { CheckResult(CheckState.OK, text("fine")) },
            HealthCheck(CheckId.IMAGE, CheckGroup.MODELS) { throw IOException("down") },
        )
        val results = mutableMapOf<CheckId, CheckResult>()
        runHealthChecks(context, checks) { id, result -> results[id] = result }
        assertEquals(CheckState.OK, results.getValue(CheckId.CHAT).state)
        assertEquals(CheckState.FAIL, results.getValue(CheckId.IMAGE).state)
        assertEquals(text("down"), results.getValue(CheckId.IMAGE).finding)
    }

    @Test
    fun aSelectedModelTheServiceNoLongerListsIsCalledStaleWithWhatIsListed() {
        val name = parts(text("deepseek anthropic"), text("deepseek-v4-flash"))
        val stale = chatModelListed(name, "deepseek-v4-flash", listOf("deepseek-flash", "deepseek-v4-pro"))
        val many = chatModelListed(name, "old", (1..7).map { "m$it" })

        assertEquals(CheckState.OK, chatModelListed(name, "m", listOf("m", "n")).state)
        assertEquals(CheckState.OK, chatModelListed(name, "m", emptyList()).state)
        assertEquals(CheckState.WARN, stale.state)
        val said = stale.finding!!
        assertEquals(BothyFindingCode.CHECKS_MODEL_NOT_LISTED.name, said.code)
        assertEquals(listOf(name, text("deepseek-v4-flash")), said.more.take(2))
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), said.more[2].more.map { it.args.single() })
        val listed = many.finding!!.more[2]
        assertEquals(BothyFindingCode.CHECKS_LISTED_MORE.name, listed.code)
        assertEquals(5, listed.more[0].more.size)
        assertEquals(text(7), listed.more[1])
    }

    @Test
    fun aRelaysDeepSeekCheckSaysWhatPassedAndWhyTheRestDidNot() {
        val invalid = finding(FindingCode.MODEL_HTTP_ERROR, 404, "Invalid URL (GET /v1/user/balance)")
        val native = DeepSeekProbe(
            "f",
            DeepSeekCheck(null, invalid),
            DeepSeekCheck(null, finding(FindingCode.HTTP_STATUS, 404)),
            DeepSeekCheck("http://127.0.0.1:18080/v1/messages", finding(FindingCode.MODEL_PROBE_PASSED_THROUGH)),
            1L,
        ).checkResult("local")
        val lost = finding(FindingCode.HTTP_STATUS, 404)
        val nothing = DeepSeekProbe("f", DeepSeekCheck(null, lost), DeepSeekCheck(null, lost), null, 1L).checkResult("gw")

        assertEquals(CheckState.OK, native.state)
        assertEquals(
            parts(
                text("local"),
                Finding(BothyFindingCode.CHECKS_PART_FAILED, more = listOf(finding(BothyFindingCode.CHECKS_DEEPSEEK_BALANCE), invalid)),
                Finding(BothyFindingCode.CHECKS_PART_FAILED, more = listOf(text("Search/Fetch"), finding(FindingCode.HTTP_STATUS, 404))),
                Finding(BothyFindingCode.CHECKS_PART_PASSED, more = listOf(finding(BothyFindingCode.CHECKS_DEEPSEEK_NATIVE_TOOLS))),
            ),
            native.finding,
        )
        assertEquals(CheckState.WARN, nothing.state)
        assertEquals(CheckFix.MODELS, nothing.fix)
        assertEquals(BothyFindingCode.CHECKS_DEEPSEEK_NONE.name, nothing.finding?.code)
        assertTrue(DEBUG_CHECKS.indexOfFirst { it.id == CheckId.DEEPSEEK } == DEBUG_CHECKS.indexOfFirst { it.id == CheckId.CHAT } + 1)
    }

    @Test
    fun everyCheckHasAPlaceOnThePage() {
        assertEquals(CheckId.entries.toList(), DEBUG_CHECKS.map { it.id })
        assertEquals(CheckGroup.entries.toList(), DEBUG_CHECKS.map { it.group }.distinct())
    }

    @Test(timeout = 30_000)
    fun aStuckCheckTimesOut() = runBlocking {
        val results = mutableMapOf<CheckId, CheckResult>()
        val stuck = HealthCheck(CheckId.CHAT, CheckGroup.MODELS) { awaitCancellation() }
        runHealthChecks(context, listOf(stuck)) { id, result -> results[id] = result }
        assertEquals(finding(BothyFindingCode.CHECKS_TIMEOUT, 20), results.getValue(CheckId.CHAT).finding)
    }
}
