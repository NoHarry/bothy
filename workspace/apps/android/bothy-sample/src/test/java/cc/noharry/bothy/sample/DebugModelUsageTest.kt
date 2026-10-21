package cc.noharry.bothy.sample

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DebugModelUsageTest {
    private val catalog = Json.parseToJsonElement(
        """
        {
          "deepseek": {"models": {
            "deepseek-v4-flash": {"id": "deepseek-v4-flash", "cost": {"input": 0.15, "output": 0.6, "cache_read": 0.003}},
            "deepseek-flash": {"id": "deepseek-flash", "cost": {"input": 0.14, "output": 0.56, "cache_read": 0.002}},
            "deepseek-v4-pro": {"id": "deepseek-v4-pro", "cost": {"input": 0.66, "output": 1.98, "cache_read": 0.022}}
          }},
          "alibaba-token-plan": {"models": {"deepseek-v4.1-flash": {"cost": {"input": 0, "output": 0}}}},
          "resale-a": {"models": {"deepseek-v4-1-flash": {"cost": {"input": 0.3, "output": 1.2}}}},
          "resale-b": {"models": {"deepseek/deepseek-v4-flash": {"cost": {"input": 0.9, "output": 2.0}}}},
          "resale-c": {"models": {"other/deepseek-v4-1-flash": {"cost": {"input": 0.5, "output": 1.5}}}},
          "resale-d": {"models": {"deepseek-v4-1-flash": {"cost": {"input": 0.1, "output": 0.4}}}},
          "alibaba": {"models": {"qwen3-max": {"cost": {"input": 1.2, "output": 6.0}}}},
          "resale-e": {"models": {"qwen3-max": {"cost": {"input": 2.0, "output": 9.0}}, "glm-x": {"cost": {"input": 0.1, "output": 0.2}}},
                       "resale-f": {"cost": {}}},
          "resale-g": {"models": {"glm-x": {"cost": {"input": 0.3, "output": 0.6}}}},
          "resale-h": {"models": {"glm-x": {"cost": {"input": 0.5, "output": 1.0}}}}
        }
        """,
    ).jsonObject

    @Test
    fun cacheHitsAreBilledAtTheCachePrice() {
        val usage = DebugModelUsage(requests = 2, inputTokens = 1_000_000, cacheReadTokens = 600_000, outputTokens = 100_000)

        val cost = usage.costUsd(DebugModelPrice(input = 0.15, output = 0.6, cacheRead = 0.003, source = "t"))

        assertEquals(400_000 * 0.15 / 1e6 + 600_000 * 0.003 / 1e6 + 100_000 * 0.6 / 1e6, cost, 1e-12)
        assertEquals("no cache price: input price", 0.15 + 0.06, usage.costUsd(DebugModelPrice(0.15, 0.6, source = "t")), 1e-12)
    }

    @Test
    fun everyDeepSeekFlashModelIsPricedAtTheOfficialDeepseekFlash() {
        listOf("cline-pass/deepseek-v4-flash", "cline-pass/deepseek-v4.1-flash", "deepseek-flash").forEach { model ->
            val price = matchModelsDevPrice(catalog, model)!!
            assertEquals(model, 0.14, price.input, 0.0)
            assertEquals(model, 0.002, price.cacheRead!!, 0.0)
            assertEquals(model, "models.dev · deepseek（按 deepseek-flash）", price.source)
        }
    }

    @Test
    fun otherDeepSeekTiersTakeTheClosestOfficialModelNeverAReseller() {
        val price = matchModelsDevPrice(catalog, "cline-pass/deepseek-v4.2-pro")!!

        assertEquals(0.66, price.input, 0.0)
        assertEquals("models.dev · deepseek（按 deepseek-v4-pro）", price.source)
    }

    @Test
    fun theFirstPartyPriceWinsAndResellersGiveTheirMedian() {
        assertEquals("models.dev · alibaba", matchModelsDevPrice(catalog, "proxy/qwen3-max")!!.source)
        // No first party for glm-x here: three resellers at 0.1, 0.3 and 0.5.
        assertEquals(0.3, matchModelsDevPrice(catalog, "glm-x")!!.input, 0.0)
        assertNull(matchModelsDevPrice(catalog, "jev-1.13.0"))
    }

    @Test
    fun theLedgerAndPriceBookRoundTrip() {
        val days = mapOf("2026-10-11" to mapOf("m" to DebugModelUsage(3, 100, 40, 5, 20)))
        val book = DebugPriceBook(mapOf("m" to DebugModelPrice(0.1, 0.2, 0.01, null, MANUAL_SOURCE, manual = true)), 7.1, 42L)

        assertEquals(days, parseUsage(usageJson(days)))
        assertEquals(book, parsePriceBook(priceBookJson(book)))
    }

    @Test
    fun clearingRuntimeDataKeepsTheUsage() {
        val targets = debugRuntimeDataDirs(File("/files")).map(File::getName)

        assertFalse(USAGE_DIR in targets)
        assertTrue(targets.isNotEmpty())
    }

    @Test
    fun costsFormatInYuan() {
        val book = DebugPriceBook(mapOf("m" to DebugModelPrice(1.0, 2.0, source = "t")), usdToCny = 7.0)
        val days = mapOf("d" to mapOf("m" to DebugModelUsage(1, 1_000_000, outputTokens = 1_000_000), "unpriced" to DebugModelUsage(1, 5)))

        assertEquals(21.0, cost(days, book), 1e-9)
        assertEquals("¥21.00", yuan(21.0))
        assertEquals("¥0.0123", yuan(0.01234))
        assertEquals("¥0", yuan(0.0))
    }
}
