package cc.noharry.bothy.sample

import cc.noharry.bothy.voice.VoiceEndReason
import cc.noharry.bothy.voice.VoiceHandOverMode
import cc.noharry.bothy.voice.VoiceSegment
import cc.noharry.bothy.voice.VoiceSegmentLabel
import cc.noharry.bothy.voice.VoiceSessionState
import cc.noharry.bothy.voice.VoiceSessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugOverlayVoiceTest {
    private val listening = VoiceSessionState(status = VoiceSessionStatus.LISTENING)
    private fun segment(label: VoiceSegmentLabel, handOver: VoiceHandOverMode? = null, endedAt: Long = NOW - 1_000) =
        VoiceSegment(1, "空调调到22度", label, null, VoiceEndReason.JUDGED, endedAt, submitted = handOver != null, handOver = handOver)

    @Test
    fun theSlotShowsWordsAsTheyAreRecognizedThenWhatBecameOfThem() {
        assertEquals(OverlayVoiceLine("空调调到", OverlayVoiceTone.LIVE), overlayVoiceLine(listening.copy(liveText = "空调调到"), null, NOW))
        assertEquals(
            OverlayVoiceLine("空调调到22度", OverlayVoiceTone.SENT),
            overlayVoiceLine(listening.copy(segments = listOf(segment(VoiceSegmentLabel.AGENT, VoiceHandOverMode.NEW_TURN))), null, NOW),
        )
        assertEquals("已追加：空调调到22度", line(segment(VoiceSegmentLabel.AGENT, VoiceHandOverMode.APPEND)).text)
        assertEquals("换成：空调调到22度", line(segment(VoiceSegmentLabel.AGENT, VoiceHandOverMode.REPLACE)).text)
        assertEquals(OverlayVoiceLine("没当成指令：空调调到22度", OverlayVoiceTone.IGNORED), line(segment(VoiceSegmentLabel.SIDE)))
        assertEquals(OverlayVoiceTone.IGNORED, line(segment(VoiceSegmentLabel.UNCERTAIN)).tone)
    }

    @Test
    fun afterAFewSecondsOrWithNothingHeardItPromptsForSpeech() {
        assertEquals("我在听，请说", line(segment(VoiceSegmentLabel.AGENT, VoiceHandOverMode.NEW_TURN, endedAt = NOW - 5_000)).text)
        assertEquals("我在听，请说", overlayVoiceLine(listening, null, NOW).text)
        assertEquals("在听，可以补充或换个要求", overlayVoiceLine(listening.copy(agentWorking = true), null, NOW).text)
        assertEquals("我说完再听你说", overlayVoiceLine(listening.copy(assistantSpeaking = true), null, NOW).text)
        assertEquals("正在打开麦克风…", overlayVoiceLine(VoiceSessionState(status = VoiceSessionStatus.STARTING), null, NOW).text)
    }

    @Test
    fun aWakeThatCouldNotListenSaysWhy() {
        val idle = VoiceSessionState()
        assertEquals(OverlayVoiceLine("需要麦克风权限", OverlayVoiceTone.WARN), overlayVoiceLine(idle, "需要麦克风权限", NOW))
        assertEquals("语音已结束", overlayVoiceLine(idle, null, NOW).text)
        assertEquals(OverlayVoiceTone.WARN, overlayVoiceLine(VoiceSessionState(status = VoiceSessionStatus.ERROR, message = "麦克风被占用"), null, NOW).tone)
    }

    @Test
    fun theRequestLineShowsWhatIsSaidThenTheRequest() {
        assertEquals(OverlayVoiceLine("“空调调到”", OverlayVoiceTone.LIVE), voiceAskedLine(listening.copy(liveText = "空调调到"), null, "讲个笑话", NOW))
        assertEquals(OverlayVoiceLine("“讲个笑话”", OverlayVoiceTone.SENT), voiceAskedLine(listening, null, "讲个笑话", NOW))
        assertEquals("我在听，请说", voiceAskedLine(listening, null, null, NOW).text)
        val side = listening.copy(segments = listOf(segment(VoiceSegmentLabel.SIDE)))
        assertEquals(OverlayVoiceLine("没当成指令：空调调到22度", OverlayVoiceTone.IGNORED), voiceAskedLine(side, null, "讲个笑话", NOW))
        assertEquals(OverlayVoiceTone.WARN, voiceAskedLine(VoiceSessionState(), "需要麦克风权限", "讲个笑话", NOW).tone)
    }

    @Test
    fun theWakeChimeIsShortQuietAndStartsAndEndsSilent() {
        val chime = chimeSamples(listOf(Triple(1318.51, 0.0, 0.16), Triple(1760.0, 0.09, 0.32)), sampleRate = 44_100)
        assertEquals("0.41 s", (0.41 * 44_100).toInt(), chime.size)
        val peak = chime.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue("well below full scale: $peak", peak in 9_000..11_000)
        assertTrue("no click at the start", kotlin.math.abs(chime.first().toInt()) < 200)
        assertTrue("faded out at the end", kotlin.math.abs(chime.last().toInt()) < 200)
    }

    @Test
    fun theAuraFollowsLoudnessAboveTheRoomFloor() {
        assertEquals(0f, levelOf(ShortArray(480)), 0f)
        assertEquals("full scale", 1f, levelOf(ShortArray(480) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }), 0.01f)
        assertEquals("room noise stays dark", 0f, auraCurve(0.2f), 0f)
        assertEquals(0.5f, auraCurve(0.5f), 1e-6f)
        assertEquals("loud speech is full height", 1f, auraCurve(0.9f), 0f)
    }

    @Test
    fun aLaneKeepsTheLoudnessItCarriesAcross() {
        val trace = AuraTrace(size = 10)
        repeat(100) { trace.advance(1f, 0f, false, 0.01f) }
        assertEquals("a second of loud speech: the newest sample is loud", 1f, trace.heard[0], 0.02f)
        assertEquals("小博 said nothing", 0f, trace.spoken[0], 0f)
        repeat(100) { trace.advance(0f, 0f, false, 0.01f) }
        assertTrue("a loud moment has moved on, about 3.5 samples a second in silence", (3..8).maxOf { trace.heard[it] } > 0.9f)
        assertTrue("the present is quiet", trace.heard[0] < 0.05f)
        assertEquals("the band lifts with the voice and settles with it", 0f, trace.loudness, 0.01f)
    }

    @Test
    fun aLaneReadsBetweenItsSamplesFromTheLevelNowToItsOldest() {
        val lane = AuraLane(4)
        repeat(130) { i -> lane.advance(if (i % 40 < 20) 0.9f else 0.2f, 0.01f) }
        val f = lane.fraction
        assertTrue(f in 0f..1f)
        assertEquals("the level now, where the lane enters", lane.level, lane.at(0f), 1e-4f)
        assertEquals(lane[0], lane.at(f), 1e-4f)
        assertEquals((lane[0] + lane[1]) / 2, lane.at(f + 0.5f), 1e-4f)
        assertEquals("past the oldest it stays there", lane[3], lane.at(9f), 1e-4f)
    }

    @Test
    fun aLaneMovesFasterTheFasterTheVoiceSpeaks() {
        fun speaking(syllablesPerSecond: Float): AuraLane {
            val lane = AuraLane(10)
            repeat(300) { i -> lane.advance(if ((i * 0.01f * syllablesPerSecond) % 1f < 0.5f) 0.8f else 0.1f, 0.01f) }
            return lane
        }
        val slow = speaking(3f)
        val quick = speaking(6f)
        assertEquals(3f, slow.syllables.perSecond, 0.7f)
        assertEquals(6f, quick.syllables.perSecond, 0.7f)
        assertTrue("about 1.6 samples a second quicker per syllable a second", quick.speed - slow.speed in 3.5f..6f)
        repeat(300) { quick.advance(0f, 0.01f) }
        assertEquals("silence: back to a slow drift", 0f, quick.syllables.perSecond, 0f)
        assertEquals(3f, quick.speed, 0.1f)
    }

    @Test
    fun theMicLevelsArePlayedBackOnePerTwentyMilliseconds() {
        val mic = MicLevelPlayhead()
        val batch = listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f)
        assertEquals("the newest five, from the first", 0.2f, mic.level(batch, 0L), 0f)
        assertEquals(0.4f, mic.level(batch, 45_000_000L), 0f)
        assertEquals("then holds the last until the next batch", 0.6f, mic.level(batch, 500_000_000L), 0f)
        assertEquals("a new batch starts over", 0.7f, mic.level(listOf(0.7f, 0.8f), 510_000_000L), 0f)
        assertEquals(0f, mic.level(emptyList(), 0L), 0f)
    }

    @Test
    fun theFlowTurnsRightForThePassengerAndLeftForXiaoNiWithoutJumping() {
        val trace = AuraTrace(size = 10)
        assertEquals("小博's way by default", -1f, trace.direction, 0f)
        trace.advance(0.8f, 0f, false, 0.05f)
        assertTrue("turning, not flipped", trace.direction > -1f && trace.direction < 0f)
        repeat(100) { trace.advance(0.8f, 0f, false, 0.05f) }
        assertEquals("the passenger flows right", 1f, trace.direction, 0.01f)
        val before = trace.flowPhase
        repeat(40) { trace.advance(0f, 0f, false, 0.05f) }
        assertTrue("silence keeps the way it went", trace.flowPhase > before && trace.direction > 0.99f)
        repeat(100) { trace.advance(0f, 0.7f, false, 0.05f) }
        assertEquals("the reply flows left", -1f, trace.direction, 0.01f)
    }

    @Test
    fun thinkingEasesInAndQuickensTheFlowWithoutJumping() {
        val trace = AuraTrace(size = 10)
        repeat(100) { trace.advance(0f, 0f, true, 0.01f) }
        assertTrue("mostly in its thinking look after a second", trace.thinking > 0.8f)
        val before = trace.flowPhase
        trace.advance(0f, 0f, true, 0.1f)
        assertEquals("the flow runs about 4 times its silent pace, leftward", -0.1f * 4.07f, trace.flowPhase - before, 0.03f)
    }

    @Test
    fun theAuraColoursCycleThroughSageGlowEmberMist() {
        assertEquals(androidx.compose.ui.graphics.Color(0xFF8CC7A5), auraColor(0f))
        assertEquals(androidx.compose.ui.graphics.Color(0xFFF5C26B), auraColor(0.25f))
        assertEquals("wraps", auraColor(0.25f), auraColor(3.25f))
    }

    @Test
    fun theAuraRisesAndFallsWithEachSyllableAtAnyFrameRate() {
        fun after(seconds: Float, fps: Int, from: Float, to: Float): Float {
            var level = from
            repeat((seconds * fps).toInt()) { level = auraFollow(level, to, 1f / fps) }
            return level
        }
        assertEquals("rise: ~63% in 0.06 s", 0.63f, after(0.067f, 60, 0f, 1f), 0.08f)
        assertEquals("fall: ~37% left after 0.18 s", 0.37f, after(0.18f, 60, 1f, 0f), 0.05f)
        assertEquals("same at 30 fps", after(0.5f, 60, 1f, 0f), after(0.5f, 30, 1f, 0f), 0.01f)
    }

    @Test
    fun theSoftLayerBlurTurnsTheBandEdgeIntoASmoothRamp() {
        // The left half opaque cyan, the right half empty: the hard edge the coarse stretch showed as blocks.
        val side = 40
        val pixels = IntArray(side * side) { if (it % side < side / 2) 0xFF5CE1E6.toInt() else 0 }
        blurPremultiplied(pixels, IntArray(pixels.size), side, side, radius = 3)
        val alpha = List(side) { x -> pixels[side / 2 * side + x] ushr 24 }
        assertEquals("well inside stays opaque", 255, alpha[10])
        assertEquals("well outside stays empty", 0, alpha[30])
        assertEquals("half way at the edge", 128f, (alpha[19] + alpha[20]) / 2f, 2f)
        val steps = (10 until 30).map { alpha[it] - alpha[it + 1] }
        assertTrue("falls steadily: $alpha", steps.all { it >= 0 })
        assertTrue("no step sharper than a Gaussian of σ 3.5: $steps", steps.max() <= 32)
    }

    @Test
    fun theSoftLayerBlurKeepsColourWithinAlphaAndFadesPastTheEdges() {
        val random = java.util.Random(7)
        val side = 24
        val pixels = IntArray(side * side) {
            val a = random.nextInt(256)
            (a shl 24) or (random.nextInt(a + 1) shl 16) or (random.nextInt(a + 1) shl 8) or random.nextInt(a + 1)
        }
        blurPremultiplied(pixels, IntArray(pixels.size), side, side, radius = 3)
        assertTrue("premultiplied", pixels.all { p -> (0..16 step 8).all { (p ushr it) and 0xFF <= p ushr 24 } })

        val opaque = IntArray(side * side) { 0xFF3D7BFF.toInt() }
        blurPremultiplied(opaque, IntArray(opaque.size), side, side, radius = 3)
        assertEquals("the middle stays opaque", 255, opaque[side / 2 * side + side / 2] ushr 24)
        assertTrue("a corner fades, as beyond the edge is clear", opaque[0] ushr 24 < 128)
    }

    private fun line(segment: VoiceSegment) = overlayVoiceLine(listening.copy(segments = listOf(segment)), null, NOW)

    private companion object { const val NOW = 1_000_000L }
}
