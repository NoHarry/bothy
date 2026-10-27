@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.overlay
import cc.noharry.agent.runtime.SubagentState
import cc.noharry.agent.runtime.SubagentStatus
import cc.noharry.bothy.app.skills.REPORT_QUEUED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlaySubagentsTest {
    @Test
    fun theCardShowsTheWholeBatchUntilItHasSettledForAWhile() {
        val old = child("sub-1", started = 0, finished = 5_000)
        val a = child("sub-2", started = 100_000, finished = 103_000)
        val b = child("sub-3", started = 100_500)
        val board = mapOf("s" to listOf(old, a, b))

        // A sibling finished long ago still belongs to the batch while another one works.
        assertEquals(listOf("sub-2", "sub-3"), subagentsOnCard(board, now = 140_000).map { it.id })
        val settled = mapOf("s" to listOf(old, a, b.copy(state = SubagentState.COMPLETED, finishedAtEpochMillis = 150_000)))
        assertEquals(2, subagentsOnCard(settled, now = 160_000).size)
        assertTrue(subagentsOnCard(settled, now = 171_000).isEmpty())

        // A result still waiting for its turn keeps the batch up; once told, it lingers as long as after finishing.
        val queued = mapOf("s/sub-2" to REPORT_QUEUED, "s/sub-3" to REPORT_QUEUED)
        assertEquals(2, subagentsOnCard(settled, now = 300_000, queued).size)
        val told = mapOf("s/sub-2" to 290_000L, "s/sub-3" to 290_000L)
        assertEquals(2, subagentsOnCard(settled, now = 300_000, told).size)
        assertTrue(subagentsOnCard(settled, now = 311_000, told).isEmpty())
    }

    @Test
    fun aLaneSaysWhatTheChildIsDoingInPlainWords() {
        val running = child("sub-1", started = 0)
        assertEquals("思考中", running.cardActivity(0))
        assertEquals("搜索「上海 92 号油价」", running.copy(latest = """web_search {"query":"上海 92 号油价"}""").cardActivity(0))
        assertEquals("读取 sh.gov.cn", running.copy(latest = """web_fetch {"url":"https://www.sh.gov.cn/fuel/today?x=1""").cardActivity(0))
        assertEquals("调用 command_cmd_QueryWeather", running.copy(latest = "mcp__tools_json__command_cmd_QueryWeather {}").cardActivity(0))
        assertEquals("搜索「杭州」", running.copy(latest = """call_tool {"name":"web_search","arguments":{"query":"杭州"}}""").cardActivity(0))
        assertEquals("95 秒没有进展", running.copy(state = SubagentState.STALLED, lastActivityEpochMillis = 5_000).cardActivity(100_000))
        val done = running.copy(state = SubagentState.COMPLETED, finishedAtEpochMillis = 9_000, result = "\n## 上海今日油价\n92 号 7.9 元")
        assertEquals("上海今日油价", done.cardActivity(10_000))
        // A lead-in such as "…值得看的展如下：" says nothing yet; the first item does.
        val listed = done.copy(result = "成都本周末值得看的展如下：\n\n1. **维也纳花卉名作展**，市美术馆，69 元")
        assertEquals("维也纳花卉名作展，市美术馆，69 元", listed.cardActivity(10_000))
        assertEquals("凤凰传奇演唱会", done.copy(result = "南京这个周末确认的演出如下。\n- 凤凰传奇演唱会").cardActivity(10_000))
        assertEquals("超时停止：Timed out.", running.copy(state = SubagentState.TIMED_OUT, error = "Timed out.").cardActivity(0))
    }

    @Test
    fun foldedMascotCountsFinishedChildrenAndTurnsAmberOnAStall() {
        val a = child("sub-1", started = 0, finished = 3_000)
        val b = child("sub-2", started = 100, latest = """web_search {"query":"杭州"}""")
        val c = child("sub-3", started = 200)

        val signal = overlaySubagentSignal(mapOf("s" to listOf(a, b, c)), now = 5_000)!!
        assertEquals(OverlayTaskTone.WORKING, signal.tone)
        assertEquals(1, signal.stepsDone)
        assertEquals(3, signal.stepsTotal)
        assertEquals("子任务 · 1/3 完成", signal.title)

        val stalled = overlaySubagentSignal(mapOf("s" to listOf(a, b.copy(state = SubagentState.STALLED), c)), now = 95_000)!!
        assertEquals(OverlayTaskTone.STALLED, stalled.tone)
        assertTrue(stalled.detail, stalled.detail.startsWith("「sub-2 标签」"))
        assertNull(overlaySubagentSignal(mapOf("s" to listOf(a)), now = 5_000))
        // Done but not told yet: the ring stays, all lit.
        val waiting = overlaySubagentSignal(mapOf("s" to listOf(a)), now = 5_000, deliveries = mapOf("s/sub-1" to REPORT_QUEUED))!!
        assertEquals("子任务都完成了", waiting.title)
        assertEquals("27.4k", compactTokens(27_376))
    }

    private fun child(id: String, started: Long, finished: Long = 0, latest: String = "") = SubagentStatus(
        id = id, label = "$id 标签", state = if (finished > 0) SubagentState.COMPLETED else SubagentState.RUNNING, parentSessionId = "s",
        startedAtEpochMillis = started, lastActivityEpochMillis = started, finishedAtEpochMillis = finished,
        toolCalls = 0, latest = latest, result = "", error = "",
    )
}
