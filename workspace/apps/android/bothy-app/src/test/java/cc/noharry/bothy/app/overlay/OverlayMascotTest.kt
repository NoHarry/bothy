package cc.noharry.bothy.app.overlay
import cc.noharry.bothy.app.a2ui.DisplayChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayMascotTest {
    private val ui = OverlayUi()

    @Test
    fun eachStateGetsItsOwnAct() {
        assertEquals(MascotAction.THINKING, hud(OverlayPhase.LIVE).mascotAction(ui))
        assertEquals(MascotAction.DONE, hud(OverlayPhase.DONE).mascotAction(ui))
        assertEquals(MascotAction.FAILED, hud(OverlayPhase.FAIL).mascotAction(ui))
        assertEquals(MascotAction.STOPPED, hud(OverlayPhase.STOP).mascotAction(ui))
        assertEquals(MascotAction.IDLE, hud(OverlayPhase.IDLE).mascotAction(ui))
    }

    @Test
    fun theRunningToolPicksBetweenSearchingAndWorking() {
        val search = hud(OverlayPhase.LIVE, tools = listOf(tool("mcp__device__command_navi_KeyWordSearch", running = true)))
        val other = hud(OverlayPhase.LIVE, tools = listOf(tool("home_ac_set", running = true)))
        val finished = hud(OverlayPhase.LIVE, tools = listOf(tool("home_ac_set", running = false)))

        assertEquals(MascotAction.SEARCHING, search.mascotAction(ui))
        assertEquals(MascotAction.WORKING, other.mascotAction(ui))
        assertEquals(MascotAction.THINKING, finished.mascotAction(ui))
    }

    @Test
    fun drawsOnlyWhileThePictureIsStillComing() {
        val drawing = hud(OverlayPhase.LIVE, picture = OverlayPicture(null, "一只猫"))
        val drawn = hud(OverlayPhase.LIVE, picture = OverlayPicture("generated-images/s/a.png", "一只猫"))

        assertEquals(MascotAction.DRAWING, drawing.mascotAction(ui))
        assertEquals(MascotAction.THINKING, drawn.mascotAction(ui))
        assertEquals(MascotAction.DONE, drawn.copy(phase = OverlayPhase.DONE).mascotAction(ui))
    }

    @Test
    fun asksWhileWaitingForThePassenger() {
        assertEquals(MascotAction.ASKING, hud(OverlayPhase.LIVE, headline = "等待回答：去哪个充电站").mascotAction(ui))
        assertEquals(MascotAction.ASKING, hud(OverlayPhase.DONE, headline = "等待你的回复").mascotAction(ui))
    }

    @Test
    fun picksOutrankTheEndStateUntilAnswered() {
        assertEquals(MascotAction.PICKING, hud(OverlayPhase.DONE, choices = PICKS).mascotAction(ui))
        assertEquals(MascotAction.PICKING, hud(OverlayPhase.FAIL, choices = PICKS).mascotAction(ui))
        assertEquals(MascotAction.DONE, hud(OverlayPhase.DONE, choices = PICKS).mascotAction(ui.copy(choicesAnswered = true)))
        assertEquals(MascotAction.THINKING, hud(OverlayPhase.LIVE, choices = PICKS).mascotAction(ui.copy(choicesAnswered = true)))
    }

    @Test
    fun aNewRequestOrAnArrivingReplyOpensTheCard() {
        assertTrue(overlayAnnounces(OverlayPhase.IDLE, OverlayPhase.LIVE))
        assertTrue(overlayAnnounces(OverlayPhase.DONE, OverlayPhase.LIVE))
        assertTrue(overlayAnnounces(OverlayPhase.LIVE, OverlayPhase.DONE))
        assertTrue(overlayAnnounces(OverlayPhase.LIVE, OverlayPhase.FAIL))
        assertFalse(overlayAnnounces(OverlayPhase.LIVE, OverlayPhase.LIVE))
        assertFalse(overlayAnnounces(OverlayPhase.LIVE, OverlayPhase.STOP))
        assertFalse(overlayAnnounces(OverlayPhase.DONE, OverlayPhase.DONE))
        assertFalse(overlayAnnounces(OverlayPhase.DONE, OverlayPhase.IDLE))
    }

    @Test
    fun aQuietCardFoldsBackButNotWhileThePassengerIsNeeded() {
        assertEquals(QUIET_COLLAPSE_MS, hud(OverlayPhase.DONE).quietCollapseMillis(ui))
        assertEquals(QUIET_COLLAPSE_MS, hud(OverlayPhase.FAIL).quietCollapseMillis(ui))
        assertEquals(IDLE_COLLAPSE_MS, hud(OverlayPhase.IDLE).quietCollapseMillis(ui))

        assertNull(hud(OverlayPhase.LIVE).quietCollapseMillis(ui))
        assertNull(hud(OverlayPhase.DONE, choices = PICKS).quietCollapseMillis(ui))
        assertNull(hud(OverlayPhase.DONE, headline = "等待你的回复").quietCollapseMillis(ui))
        assertNull(hud(OverlayPhase.DONE).quietCollapseMillis(ui.copy(showInput = true)))
        assertNull(hud(OverlayPhase.DONE).quietCollapseMillis(ui.copy(showDetails = true)))
        assertNull(hud(OverlayPhase.DONE).quietCollapseMillis(ui.copy(pictureExpanded = true)))
        assertNull(hud(OverlayPhase.DONE).quietCollapseMillis(ui.copy(pendingQuestion = true)))
        // Touched while it counted down: it stays until closed.
        assertNull(hud(OverlayPhase.DONE).quietCollapseMillis(ui.copy(held = true)))
    }

    private fun tool(name: String, running: Boolean) = OverlayToolLine(
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
        assertEquals(QUIET_COLLAPSE_MS, hud(OverlayPhase.DONE).readingMillis())
        assertEquals(10_000L + 200 * 200, hud(OverlayPhase.DONE).copy(resultText = "字".repeat(200)).readingMillis())
        assertEquals(MAX_READING_MS, hud(OverlayPhase.DONE).copy(resultText = "字".repeat(5_000)).readingMillis())
        assertEquals(10_000L + 200 * 200, hud(OverlayPhase.DONE).copy(resultText = "字".repeat(200)).quietCollapseMillis(ui))
    }

    @Test
    fun aFreshCardKeepsOnlyAChipOfTheLastResult() {
        val read = hud(OverlayPhase.DONE, choices = PICKS).copy(requestPreview = "明天上海天气怎么样，顺便看看周末会不会下雨，要不要带伞", resultText = "多云")

        val fresh = read.freshView()

        assertEquals(OverlayPhase.IDLE, fresh.phase)
        assertEquals("", fresh.resultText)
        assertTrue(fresh.choices.isEmpty())
        assertNull(fresh.requestPreview)
        assertEquals("明天上海天气怎么样，顺便看看周末会不会下雨，要不要带伞".take(24) + "…", read.resultLabel())
        assertEquals("多云", read.copy(requestPreview = null).resultLabel())
        assertEquals("Grok Bot：明天杭州多云转晴", read.copy(requestPreview = "【委托回报】Grok Bot：明天杭州多云转晴").resultLabel())
    }

    private fun hud(
        phase: OverlayPhase,
        headline: String = "",
        choices: List<DisplayChoice> = emptyList(),
        picture: OverlayPicture? = null,
        tools: List<OverlayToolLine> = emptyList(),
    ) = OverlayHud(
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
        isRunning = phase == OverlayPhase.LIVE,
        headline = headline,
        choices = choices,
        picture = picture,
    )

    private companion object {
        val PICKS = listOf(DisplayChoice(1, "近的", recommended = true), DisplayChoice(2, "便宜的", recommended = false))
    }
}
