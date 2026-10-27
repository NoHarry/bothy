package cc.noharry.bothy.app.console
import cc.noharry.bothy.app.session.BothySessionJson
import cc.noharry.bothy.app.session.SessionTurn
import cc.noharry.bothy.app.usage.TokenUsageState
import cc.noharry.bothy.app.usage.totalTokens
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnUsageTest {
    @Test fun aTurnKeepsItsUsageSoTheBoardCanShowTheWholeSessionAfterARestart() {
        val usage = TokenUsageState(
            requestCount = 3, inputTokens = 28912, outputTokens = 235, totalTokens = 29147,
            cachedInputTokens = 21760, cacheHitTokens = 21760, cacheMissTokens = 7152,
        )
        val turn = SessionTurn(
            turnId = "t", requestId = "r", runId = "run", sessionId = "s", scene = "", userText = "空调调到22度",
            startedAtEpochMillis = 1, completedAtEpochMillis = 2, finalStatus = "COMPLETED", finalResponse = "好了",
            tools = emptyList(), usage = usage,
        )

        assertEquals(usage, BothySessionJson.decodeTurn(BothySessionJson.encodeTurn(turn))?.usage)
        // Turns stored before usage was kept read as none.
        assertEquals(TokenUsageState(), BothySessionJson.decodeTurn(BothySessionJson.encodeTurn(turn.copy(usage = TokenUsageState())))?.usage)
    }

    @Test fun sessionUsageAddsRunsUp() {
        val run = TokenUsageState(requestCount = 2, inputTokens = 100, cachedInputTokens = 90, totalTokens = 110)
        val total = run + run

        assertEquals(4, total.requestCount)
        assertEquals(200, total.inputTokens)
        assertEquals(90, total.cacheHitRate)
    }
}
