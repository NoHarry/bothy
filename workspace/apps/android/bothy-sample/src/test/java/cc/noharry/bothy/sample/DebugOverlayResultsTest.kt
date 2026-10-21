package cc.noharry.bothy.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The card's memory of results: fresh cards after a read result, the replaced result's chip, reports held while reading. */
class DebugOverlayResultsTest {
    private var clock = 1_000_000L
    private val reading = OverlayReading { clock }
    private val memory = OverlayResultMemory(reading)

    @Test
    fun aResultOfUnknownAgeOrAlreadyReadOpensTheCardFresh() {
        memory.openToTalk(DebugOverlayPhase.DONE)
        assertTrue("a result from before the overlay ran", memory.fresh)

        memory.folded(DebugOverlayPhase.IDLE)
        memory.phaseChanged(DebugOverlayPhase.LIVE, DebugOverlayPhase.DONE)
        memory.openToTalk(DebugOverlayPhase.DONE)
        assertFalse("a result that just arrived is shown", memory.fresh)

        memory.folded(DebugOverlayPhase.DONE)
        memory.openToTalk(DebugOverlayPhase.DONE)
        assertTrue("its card folded: it was read", memory.fresh)

        memory.folded(DebugOverlayPhase.IDLE)
        memory.phaseChanged(DebugOverlayPhase.LIVE, DebugOverlayPhase.DONE)
        clock += FRESH_AFTER_MS + 1
        memory.openToTalk(DebugOverlayPhase.DONE)
        assertTrue("over a minute old", memory.fresh)
    }

    @Test
    fun aFreshCardShowsOnlyTheChipUntilTappedOrANewRequestStarts() {
        memory.openToTalk(DebugOverlayPhase.DONE)
        val shown = memory.show(result("今天的新闻汇总"), before = null, cardOpen = true)
        assertEquals(DebugOverlayPhase.IDLE, shown.hud.phase)
        assertEquals("今天的新闻汇总", shown.lastResult)

        memory.showLast()
        assertEquals("今天的新闻汇总", memory.show(result("今天的新闻汇总"), shown.hud, cardOpen = true).hud.requestPreview)

        memory.openToTalk(DebugOverlayPhase.DONE)
        val live = memory.show(hud(DebugOverlayPhase.LIVE), before = shown.hud, cardOpen = true)
        assertEquals(DebugOverlayPhase.LIVE, live.hud.phase)
        assertNull(live.lastResult)
        assertFalse(memory.fresh)
    }

    @Test
    fun aNewRequestReplacingAnUnreadResultKeepsItOneTapAway() {
        memory.phaseChanged(DebugOverlayPhase.LIVE, DebugOverlayPhase.DONE)
        val read = result("新闻汇总")
        val replaced = memory.show(hud(DebugOverlayPhase.LIVE), before = read, cardOpen = true)
        assertEquals("新闻汇总", replaced.previousResult)

        memory.togglePrevious()
        val back = memory.show(hud(DebugOverlayPhase.LIVE), before = replaced.hud, cardOpen = true)
        assertSame(read, back.hud)
        assertTrue(back.showingPrevious)

        memory.folded(DebugOverlayPhase.LIVE)
        assertNull(memory.show(hud(DebugOverlayPhase.LIVE), before = null, cardOpen = true).previousResult)
    }

    @Test
    fun aReadResultIsNotKeptWhenANewRequestStarts() {
        memory.phaseChanged(DebugOverlayPhase.LIVE, DebugOverlayPhase.DONE)
        memory.folded(DebugOverlayPhase.DONE)
        assertNull(memory.show(hud(DebugOverlayPhase.LIVE), before = result("天气"), cardOpen = true).previousResult)
    }

    @Test
    fun reportsWaitForTheReadingTimeAndForAFreshTouch() {
        memory.phaseChanged(DebugOverlayPhase.LIVE, DebugOverlayPhase.DONE)
        val shown = memory.show(result("天气"), before = null, cardOpen = true)
        assertEquals(clock + shown.hud.readingMillis(), reading.holdUntil(shown, cardOpen = true))
        assertEquals(0L, reading.holdUntil(shown, cardOpen = false))

        clock += shown.hud.readingMillis() + 1
        assertEquals(0L, reading.holdUntil(shown, cardOpen = true))
        reading.touched()
        assertEquals(clock + INTERACTION_HOLD_MS, reading.holdUntil(shown, cardOpen = true))

        // Folded, then woken: the card opens fresh, and a fresh card holds nothing back even right after a touch.
        memory.folded(DebugOverlayPhase.DONE)
        memory.openToTalk(DebugOverlayPhase.DONE)
        reading.touched()
        assertEquals(0L, reading.holdUntil(memory.show(result("天气"), null, cardOpen = true), cardOpen = true))
    }

    @Test
    fun aWakeOpensTheCardFreshEvenOverANewResultOnTheOpenCard() {
        memory.phaseChanged(DebugOverlayPhase.LIVE, DebugOverlayPhase.DONE)
        val read = result("新闻汇总")
        assertFalse(memory.show(read, before = null, cardOpen = true).fresh)

        memory.woken(DebugOverlayPhase.DONE)
        val woken = memory.show(read, before = read, cardOpen = true)
        assertTrue(woken.fresh)
        assertEquals(DebugOverlayPhase.IDLE, woken.hud.phase)
        assertEquals("新闻汇总", woken.lastResult)

        // Not read yet: once the new request starts, it is one tap away behind 上一个结果.
        val asked = memory.show(hud(DebugOverlayPhase.LIVE), before = woken.hud, cardOpen = true)
        assertEquals(DebugOverlayPhase.LIVE, asked.hud.phase)
        assertEquals("新闻汇总", asked.previousResult)
    }

    @Test
    fun aWakeThatStopsARunningRequestStaysFreshUntilTheNextOne() {
        memory.woken(DebugOverlayPhase.LIVE)
        val stopping = memory.show(hud(DebugOverlayPhase.LIVE).copy(requestPreview = "查天气"), before = null, cardOpen = true)
        assertTrue("the run is being stopped, not shown", stopping.fresh)
        assertNull(stopping.lastResult)

        memory.phaseChanged(DebugOverlayPhase.LIVE, DebugOverlayPhase.STOP)
        val stopped = memory.show(hud(DebugOverlayPhase.STOP).copy(requestPreview = "查天气"), before = stopping.hud, cardOpen = true)
        assertTrue(stopped.fresh)
        assertEquals("查天气", stopped.lastResult)

        memory.phaseChanged(DebugOverlayPhase.STOP, DebugOverlayPhase.LIVE)
        val next = memory.show(hud(DebugOverlayPhase.LIVE).copy(requestPreview = "放首歌"), before = stopped.hud, cardOpen = true)
        assertFalse(next.fresh)
        assertEquals("放首歌", next.hud.requestPreview)
        assertNull("the stopped request is not offered back", next.previousResult)
    }

    @Test
    fun aWakeWithNothingFinishedShowsNoChip() {
        memory.woken(DebugOverlayPhase.IDLE)
        assertNull(memory.show(hud(DebugOverlayPhase.IDLE).copy(requestPreview = "旧的请求"), before = null, cardOpen = false).lastResult)
    }

    private fun result(request: String) = hud(DebugOverlayPhase.DONE).copy(requestPreview = request, resultText = "结果")

    private fun hud(phase: DebugOverlayPhase) = DebugOverlayHud(
        phase = phase, phaseLabel = phase.name, elapsedLabel = "", providerLabel = "", requestPreview = null, progressDone = 0,
        progressTotal = 0, progressLabel = "", currentStep = null, planRows = emptyList(), resultText = "", timingLine = "",
        toolLines = emptyList(), isRunning = phase == DebugOverlayPhase.LIVE, headline = "",
    )
}
