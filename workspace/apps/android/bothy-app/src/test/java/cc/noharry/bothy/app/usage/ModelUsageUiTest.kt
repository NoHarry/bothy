package cc.noharry.bothy.app.usage
import cc.noharry.agent.appserver.protocol.ModelPrice
import cc.noharry.agent.appserver.protocol.ModelUsage
import cc.noharry.agent.appserver.protocol.PriceBook
import cc.noharry.agent.appserver.protocol.cost
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelUsageUiTest {
    @Test
    fun cacheHitsAreBilledAtTheCachePrice() {
        val usage = ModelUsage(requests = 2, inputTokens = 1_000_000, cacheReadTokens = 600_000, outputTokens = 100_000)

        val cost = usage.cost(ModelPrice(input = 0.15, output = 0.6, cacheRead = 0.003, source = "t"))

        assertEquals(400_000 * 0.15 / 1e6 + 600_000 * 0.003 / 1e6 + 100_000 * 0.6 / 1e6, cost, 1e-12)
        assertEquals("no cache price: input price", 0.15 + 0.06, usage.cost(ModelPrice(0.15, 0.6, source = "t")), 1e-12)
    }

    @Test
    fun costsFormatInYuan() {
        val book = PriceBook(mapOf("m" to ModelPrice(1.0, 2.0, source = "t")), usdToCny = 7.0)
        val days = mapOf("d" to mapOf("m" to ModelUsage(1, 1_000_000, outputTokens = 1_000_000), "unpriced" to ModelUsage(1, 5)))

        assertEquals(21.0, cost(days, book), 1e-9)
        assertEquals("¥21.00", yuan(21.0))
        assertEquals("¥0.0123", yuan(0.01234))
        assertEquals("¥0", yuan(0.0))
    }

    @Test
    fun aPriceTypedOverAServicesYuanPriceIsSavedInYuanAndCostsAsYuan() {
        // DeepSeek's list, in yuan, pre-fills the row; the person changes the input price and saves.
        val listed = ModelPrice(2.0, 8.0, 0.04, source = "deepseek", currency = "CNY", asOf = "2026-11-11")
        val typed = typedPrice("1.5", "0.04", "8", rowCurrency(listed))!!
        assertEquals("CNY", typed.currency)
        assertEquals(true, typed.manual)
        val book = PriceBook(mapOf("deepseek-flash" to typed), usdToCny = 7.0)
        // A million input tokens at ¥1.5 is ¥1.5, not $1.5 (¥10.5).
        assertEquals(1.5, cost(mapOf("d" to mapOf("deepseek-flash" to ModelUsage(1, 1_000_000))), book), 1e-9)
        assertEquals("USD", rowCurrency(null))
        assertEquals(null, typedPrice("x", "", "8", "USD"))
    }
}
