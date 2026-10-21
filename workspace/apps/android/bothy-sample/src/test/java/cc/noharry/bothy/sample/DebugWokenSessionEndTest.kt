package cc.noharry.bothy.sample

import cc.noharry.bothy.voice.VoiceEndReason
import cc.noharry.bothy.voice.VoiceSegment
import cc.noharry.bothy.voice.VoiceSegmentLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A woken session ends when idle, and when talk around the speaker keeps it busy without the speaker. */
class DebugWokenSessionEndTest {
    @Test
    fun aQuietSessionEndsAfterTheIdleWindow() {
        assertNull(wokenSessionEnd(idleMillis = WAKE_IDLE_END_MILLIS - 1, unengagedMillis = 0))
        assertEquals("idle for 10 s", wokenSessionEnd(idleMillis = WAKE_IDLE_END_MILLIS, unengagedMillis = 0))
    }

    @Test
    fun talkNotForTheAssistantEndsTheSessionEvenThoughWordsKeepComing() {
        // A video next to the tablet: words every second, so never idle, but nothing for the assistant for 30 s.
        assertNull(wokenSessionEnd(idleMillis = 0, unengagedMillis = WAKE_UNENGAGED_END_MILLIS - 1))
        assertTrue(wokenSessionEnd(idleMillis = 0, unengagedMillis = WAKE_UNENGAGED_END_MILLIS)!!.startsWith("heard only talk"))
    }

    @Test
    fun onlyWordsForTheAssistantOrItsNameKeepTheSpeakerEngaged() {
        assertTrue(engagesSpeaker(segment(VoiceSegmentLabel.AGENT, VoiceEndReason.JUDGED)))
        assertTrue(engagesSpeaker(segment(VoiceSegmentLabel.UNCERTAIN, VoiceEndReason.WAKE_PHRASE)))
        assertFalse(engagesSpeaker(segment(VoiceSegmentLabel.SIDE, VoiceEndReason.LENGTH_CAP)))
        assertFalse(engagesSpeaker(segment(VoiceSegmentLabel.UNCERTAIN, VoiceEndReason.LENGTH_CAP)))
    }

    private fun segment(label: VoiceSegmentLabel, reason: VoiceEndReason) =
        VoiceSegment(id = 1, text = "", label = label, judgment = null, reason = reason, endedAtEpochMillis = 0, submitted = false)
}
