package cc.noharry.bothy.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugOverlayMascotTest {
    private val ui = DebugOverlayUi()

    @Test
    fun eachStateGetsItsOwnAct() {
        assertEquals(MascotAction.THINKING, hud(DebugOverlayPhase.LIVE).mascotAction(ui))
        assertEquals(MascotAction.DONE, hud(DebugOverlayPhase.DONE).mascotAction(ui))
        assertEquals(MascotAction.FAILED, hud(DebugOverlayPhase.FAIL).mascotAction(ui))
        assertEquals(MascotAction.STOPPED, hud(DebugOverlayPhase.STOP).mascotAction(ui))
        assertEquals(MascotAction.IDLE, hud(DebugOverlayPhase.IDLE).mascotAction(ui))
    }

    @Test
    fun theRunningToolPicksBetweenSearchingAndWorking() {
        val search = hud(DebugOverlayPhase.LIVE, tools = listOf(tool("mcp__device__command_navi_KeyWordSearch", running = true)))
        val other = hud(DebugOverlayPhase.LIVE, tools = listOf(tool("home_ac_set", running = true)))
        val finished = hud(DebugOverlayPhase.LIVE, tools = listOf(tool("home_ac_set", running = false)))

        assertEquals(MascotAction.SEARCHING, search.mascotAction(ui))
        assertEquals(MascotAction.WORKING, other.mascotAction(ui))
        assertEquals(MascotAction.THINKING, finished.mascotAction(ui))
    }

    @Test
    fun drawsOnlyWhileThePictureIsStillComing() {
        val drawing = hud(DebugOverlayPhase.LIVE, picture = DebugOverlayPicture(null, "一只猫"))
        val drawn = hud(DebugOverlayPhase.LIVE, picture = DebugOverlayPicture("generated-images/s/a.png", "一只猫"))

        assertEquals(MascotAction.DRAWING, drawing.mascotAction(ui))
        assertEquals(MascotAction.THINKING, drawn.mascotAction(ui))
        assertEquals(MascotAction.DONE, drawn.copy(phase = DebugOverlayPhase.DONE).mascotAction(ui))
    }

    @Test
    fun asksWhileWaitingForThePassenger() {
        assertEquals(MascotAction.ASKING, hud(DebugOverlayPhase.LIVE, headline = "等待回答：去哪个充电站").mascotAction(ui))
        assertEquals(MascotAction.ASKING, hud(DebugOverlayPhase.DONE, headline = "等待你的回复").mascotAction(ui))
    }

    @Test
    fun picksOutrankTheEndStateUntilAnswered() {
        assertEquals(MascotAction.PICKING, hud(DebugOverlayPhase.DONE, choices = PICKS).mascotAction(ui))
        assertEquals(MascotAction.PICKING, hud(DebugOverlayPhase.FAIL, choices = PICKS).mascotAction(ui))
        assertEquals(MascotAction.DONE, hud(DebugOverlayPhase.DONE, choices = PICKS).mascotAction(ui.copy(choicesAnswered = true)))
        assertEquals(MascotAction.THINKING, hud(DebugOverlayPhase.LIVE, choices = PICKS).mascotAction(ui.copy(choicesAnswered = true)))
    }

    @Test
    fun aNewRequestOrAnArrivingReplyOpensTheCard() {
        assertTrue(overlayAnnounces(DebugOverlayPhase.IDLE, DebugOverlayPhase.LIVE))
        assertTrue(overlayAnnounces(DebugOverlayPhase.DONE, DebugOverlayPhase.LIVE))
        assertTrue(overlayAnnounces(DebugOverlayPhase.LIVE, DebugOverlayPhase.DONE))
        assertTrue(overlayAnnounces(DebugOverlayPhase.LIVE, DebugOverlayPhase.FAIL))
        assertFalse(overlayAnnounces(DebugOverlayPhase.LIVE, DebugOverlayPhase.LIVE))
        assertFalse(overlayAnnounces(DebugOverlayPhase.LIVE, DebugOverlayPhase.STOP))
        assertFalse(overlayAnnounces(DebugOverlayPhase.DONE, DebugOverlayPhase.DONE))
        assertFalse(overlayAnnounces(DebugOverlayPhase.DONE, DebugOverlayPhase.IDLE))
    }

    @Test
    fun aQuietCardFoldsBackButNotWhileThePassengerIsNeeded() {
        assertEquals(QUIET_COLLAPSE_MS, hud(DebugOverlayPhase.DONE).quietCollapseMillis(ui))
        assertEquals(QUIET_COLLAPSE_MS, hud(DebugOverlayPhase.FAIL).quietCollapseMillis(ui))
        assertEquals(IDLE_COLLAPSE_MS, hud(DebugOverlayPhase.IDLE).quietCollapseMillis(ui))

        assertNull(hud(DebugOverlayPhase.LIVE).quietCollapseMillis(ui))
        assertNull(hud(DebugOverlayPhase.DONE, choices = PICKS).quietCollapseMillis(ui))
        assertNull(hud(DebugOverlayPhase.DONE, headline = "等待你的回复").quietCollapseMillis(ui))
        assertNull(hud(DebugOverlayPhase.DONE).quietCollapseMillis(ui.copy(showInput = true)))
        assertNull(hud(DebugOverlayPhase.DONE).quietCollapseMillis(ui.copy(showDetails = true)))
        assertNull(hud(DebugOverlayPhase.DONE).quietCollapseMillis(ui.copy(pictureExpanded = true)))
        assertNull(hud(DebugOverlayPhase.DONE).quietCollapseMillis(ui.copy(pendingQuestion = true)))
    }

    private fun tool(name: String, running: Boolean) = DebugOverlayToolLine(
        id = 1,
        name = name,
        statusLabel = if (running) "running" else "done",
        durationLabel = null,
        isError = false,
        running = running,
        argumentsJson = null,
        resultJson = null,
    )

    @Test
    fun aLongerResultStaysUpLongerToBeRead() {
        assertEquals(QUIET_COLLAPSE_MS, hud(DebugOverlayPhase.DONE).readingMillis())
        assertEquals(10_000L + 200 * 200, hud(DebugOverlayPhase.DONE).copy(resultText = "字".repeat(200)).readingMillis())
        assertEquals(MAX_READING_MS, hud(DebugOverlayPhase.DONE).copy(resultText = "字".repeat(5_000)).readingMillis())
        assertEquals(10_000L + 200 * 200, hud(DebugOverlayPhase.DONE).copy(resultText = "字".repeat(200)).quietCollapseMillis(ui))
    }

    @Test
    fun aFreshCardKeepsOnlyAChipOfTheLastResult() {
        val read = hud(DebugOverlayPhase.DONE, choices = PICKS).copy(requestPreview = "明天上海天气怎么样，顺便看看周末会不会下雨，要不要带伞", resultText = "多云")

        val fresh = read.freshView()

        assertEquals(DebugOverlayPhase.IDLE, fresh.phase)
        assertEquals("", fresh.resultText)
        assertTrue(fresh.choices.isEmpty())
        assertNull(fresh.requestPreview)
        assertEquals("明天上海天气怎么样，顺便看看周末会不会下雨，要不要带伞".take(24) + "…", read.resultLabel())
        assertEquals("多云", read.copy(requestPreview = null).resultLabel())
        assertEquals("Grok Bot：明天杭州多云转晴", read.copy(requestPreview = "【委托回报】Grok Bot：明天杭州多云转晴").resultLabel())
    }

    private fun hud(
        phase: DebugOverlayPhase,
        headline: String = "",
        choices: List<DebugDisplayChoice> = emptyList(),
        picture: DebugOverlayPicture? = null,
        tools: List<DebugOverlayToolLine> = emptyList(),
    ) = DebugOverlayHud(
        phase = phase,
        phaseLabel = phase.name,
        elapsedLabel = "",
        providerLabel = "",
        requestPreview = null,
        progressDone = 0,
        progressTotal = 0,
        progressLabel = "",
        currentStep = null,
        planRows = emptyList(),
        resultText = "",
        timingLine = "",
        toolLines = tools,
        isRunning = phase == DebugOverlayPhase.LIVE,
        headline = headline,
        choices = choices,
        picture = picture,
    )

    private companion object {
        val PICKS = listOf(DebugDisplayChoice(1, "近的", recommended = true), DebugDisplayChoice(2, "便宜的", recommended = false))
    }
}
