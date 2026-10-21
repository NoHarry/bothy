package cc.noharry.bothy.sample

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugDeepSeekBalanceTest {
    @Test
    fun theBalanceReadsAsTotalWithToppedUpAndGrantedPerCurrency() = runBlocking {
        val asked = mutableListOf<String>()
        val client = answering(200, """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"110.00","granted_balance":"10.00","topped_up_balance":"100.00"}]}""") {
            asked += "${it.url} ${it.header("Authorization")}"
        }

        val balance = fetchDeepSeekBalance(" sk-test ", client, "https://api.deepseek.com/user/balance").getOrThrow()

        assertEquals(listOf("https://api.deepseek.com/user/balance Bearer sk-test"), asked)
        assertEquals("¥110.00（充值 100.00，赠送 10.00）", balance.summary())
        assertEquals("$1.00（充值 0.00，赠送 1.00） · 余额不足，暂不可用", DeepSeekBalance(false, listOf(DeepSeekAmount("USD", "1.00", "0.00", "1.00"))).summary())
        assertEquals("余额不足，暂不可用", parseDeepSeekBalance("""{"is_available":false,"balance_infos":[]}""").summary())
    }

    @Test
    fun aRejectedKeySaysSoWithoutEchoingTheAnswer() = runBlocking {
        val rejected = fetchDeepSeekBalance("sk-bad", answering(401, """{"error":{"message":"Authentication Fails, Your api key: ****-bad is invalid"}}"""), URL)
        val busy = fetchDeepSeekBalance("sk-test", answering(503, """{"error":{"message":"Service busy"}}"""), URL)

        assertEquals("API key 无效或已停用", rejected.exceptionOrNull()?.message)
        assertFalse(rejected.exceptionOrNull()!!.message!!.contains("bad"))
        assertTrue(busy.exceptionOrNull()?.message == "HTTP 503：Service busy")
    }

    @Test
    fun onlyDeepSeeksOwnHostCounts() {
        assertTrue("https://api.deepseek.com/anthropic".isDeepSeekUrl())
        assertTrue(" https://api.deepseek.com ".isDeepSeekUrl())
        assertFalse("https://api.deepseek.com.example.net/v1".isDeepSeekUrl())
        assertFalse("https://openrouter.ai/api/v1".isDeepSeekUrl())
    }

    private fun answering(code: Int, body: String, seen: (okhttp3.Request) -> Unit = {}): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            seen(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()

    private companion object {
        const val URL = "https://api.deepseek.com/user/balance"
    }
}
