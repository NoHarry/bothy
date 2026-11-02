package cc.noharry.bothy.app.console
import android.app.Application
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import cc.noharry.bothy.app.runtime.RunOutcome
import cc.noharry.bothy.app.runtime.formatRunSummary
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The runtime's English run summaries keep their words in English and take the screen's language where shown. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RunStatusTextTest {
    private val resources: Resources get() = ApplicationProvider.getApplicationContext<Application>().resources

    @Test
    fun englishShowsTheSummaryAsTheRuntimeWroteIt() {
        listOf(
            formatRunSummary(65_000, RunOutcome.PARTIAL),
            formatRunSummary(12_000, RunOutcome.FAILED),
            formatRunSummary(3_723_000, RunOutcome.STOPPED),
            formatRunSummary(5_000, RunOutcome.REPLAY_FAILED),
        ).forEach { assertEquals(it, resources.runSummaryText(it)) }
    }

    @Test
    @Config(qualifiers = "zh")
    fun chineseRewordsTheSummaryAndItsDuration() {
        assertEquals("用时 1分05秒 · 部分完成", shown(65_000, RunOutcome.PARTIAL))
        assertEquals("12秒 后失败", shown(12_000, RunOutcome.FAILED))
        assertEquals("1小时2分3秒 后停止", shown(3_723_000, RunOutcome.STOPPED))
        assertEquals("用时 5秒 · 回放失败", shown(5_000, RunOutcome.REPLAY_FAILED))
        // Anything else is shown as it is.
        assertEquals("Session deleted", resources.runSummaryText("Session deleted"))
        assertEquals("—", resources.durationLabelText("—"))
    }

    private fun shown(durationMs: Long, outcome: RunOutcome): String =
        resources.runSummaryText(formatRunSummary(durationMs, outcome))
}
