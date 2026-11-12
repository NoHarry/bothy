package cc.noharry.bothy.app.console
import android.app.Application
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.RunActivity
import cc.noharry.agent.appserver.protocol.RunOutcome
import cc.noharry.agent.appserver.protocol.RunResult
import cc.noharry.agent.appserver.protocol.view.label
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The runtime's English run summaries keep their words in English and take the screen's language where shown. The
 * summaries are what `formatRunSummary` writes (RunSummaryTest checks them).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RunStatusTextUiTest {
    private val resources: Resources get() = ApplicationProvider.getApplicationContext<Application>().resources

    @Test
    fun englishNamesTheChecksBehindTheLegacySummaryValue() {
        assertEquals(
            listOf(
                "Worked for 1m 05s · partial", "Failed after 12s", "Stopped after 1h 2m 3s", "Worked for 5s · event checks failed",
                "Worked for 9s · not done",
            ),
            SUMMARIES.map { resources.runSummaryText(it) },
        )
    }

    @Test
    @Config(qualifiers = "zh")
    fun chineseRewordsTheSummaryAndItsDuration() {
        assertEquals(
            listOf("用时 1分05秒 · 部分完成", "12秒 后失败", "1小时2分3秒 后停止", "用时 5秒 · 事件检查失败", "用时 9秒 · 没有完成"),
            SUMMARIES.map { resources.runSummaryText(it) },
        )
        // Anything else is shown as it is.
        assertEquals("Session deleted", resources.runSummaryText("Session deleted"))
        assertEquals("—", resources.durationLabelText("—"))
    }

    @Test
    @Config(qualifiers = "zh")
    fun aRunResultReadsAsItsSummaryDoes() {
        assertEquals(
            listOf("用时 1分05秒 · 部分完成", "12秒 后失败", "1小时2分3秒 后停止", "用时 5秒 · 事件检查失败", "用时 9秒 · 没有完成"),
            RESULTS.map { resources.runResultText(it) },
        )
        RESULTS.forEach { assertEquals(resources.runSummaryText(it.label()), resources.runResultText(it)) }
    }

    @Test
    @Config(qualifiers = "zh")
    fun whatTheRunIsDoingReadsInTheScreensLanguage() {
        assertEquals("思考中", resources.statusHeaderText(RunActivity.THINKING, ""))
        assertEquals(resources.statusHeaderText(RunActivity.WORKING, ""), resources.statusHeaderText(RunActivity.STARTING, ""))
        assert(resources.statusHeaderText(RunActivity.RUNNING_TOOL, "navigation_route").contains("navigation_route"))
    }

    private companion object {
        val RESULTS = listOf(
            RunResult(RunOutcome.PARTIAL, 65_000),
            RunResult(RunOutcome.FAILED, 12_000),
            RunResult(RunOutcome.STOPPED, 3_723_000),
            RunResult(RunOutcome.REPLAY_FAILED, 5_000),
            RunResult(RunOutcome.BLOCKED, 9_000),
        )
        val SUMMARIES = listOf(
            "Worked for 1m 05s · partial",
            "Failed after 12s",
            "Stopped after 1h 2m 3s",
            "Worked for 5s · replay failed",
            "Worked for 9s · blocked",
        )
    }
}
