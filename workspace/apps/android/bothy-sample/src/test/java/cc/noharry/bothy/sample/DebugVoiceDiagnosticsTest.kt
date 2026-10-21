package cc.noharry.bothy.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The in-app voice log keeps what the voice did, latest last. */
@RunWith(RobolectricTestRunner::class)
class DebugVoiceDiagnosticsTest {
    @Test
    fun theVoiceLogKeepsTheLatestLines() {
        repeat(1_600) { DebugVoiceLog.i("DebugVoice", "line $it") }
        DebugVoiceLog.w("DebugRealtime", "connection lost")

        val kept = DebugVoiceLog.snapshot()
        assertEquals(1_500, kept.size)
        assertTrue(kept.first().endsWith("I DebugVoice: line 101"))
        assertTrue(kept.last().endsWith("W DebugRealtime: connection lost"))
    }
}
