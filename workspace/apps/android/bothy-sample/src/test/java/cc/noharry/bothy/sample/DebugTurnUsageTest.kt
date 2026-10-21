package cc.noharry.bothy.sample

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugTurnUsageTest {
    @Test fun aTurnKeepsItsUsageSoTheBoardCanShowTheWholeSessionAfterARestart() {
        val usage = DebugTokenUsageState(
            requestCount = 3, inputTokens = 28912, outputTokens = 235, totalTokens = 29147,
            cachedInputTokens = 21760, cacheHitTokens = 21760, cacheMissTokens = 7152,
        )
        val turn = DebugSessionTurn(
            turnId = "t", requestId = "r", runId = "run", sessionId = "s", scene = "", userText = "空调调到22度",
            startedAtEpochMillis = 1, completedAtEpochMillis = 2, finalStatus = "COMPLETED", finalResponse = "好了",
            tools = emptyList(), usage = usage,
        )

        assertEquals(usage, DebugSessionJson.decodeTurn(DebugSessionJson.encodeTurn(turn))?.usage)
        // Turns stored before usage was kept read as none.
        assertEquals(DebugTokenUsageState(), DebugSessionJson.decodeTurn(DebugSessionJson.encodeTurn(turn.copy(usage = DebugTokenUsageState())))?.usage)
    }

    @Test fun sessionUsageAddsRunsUp() {
        val run = DebugTokenUsageState(requestCount = 2, inputTokens = 100, cachedInputTokens = 90, totalTokens = 110)
        val total = run + run

        assertEquals(4, total.requestCount)
        assertEquals(200, total.inputTokens)
        assertEquals(90, total.cacheHitRate)
    }
}
