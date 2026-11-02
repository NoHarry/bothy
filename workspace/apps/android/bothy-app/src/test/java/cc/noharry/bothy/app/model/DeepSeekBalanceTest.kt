package cc.noharry.bothy.app.model
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh", application = Application::class)
class DeepSeekBalanceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theBalanceReadsAsTotalWithToppedUpAndGrantedPerCurrency() = runBlocking {
        val asked = mutableListOf<String>()
        val client = answering(200, """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"110.00","granted_balance":"10.00","topped_up_balance":"100.00"}]}""") {
            asked += "${it.url} ${it.header("Authorization")}"
        }

        val balance = fetchDeepSeekBalance(context, " sk-test ", client, "https://api.deepseek.com/user/balance").getOrThrow()

        assertEquals(listOf("https://api.deepseek.com/user/balance Bearer sk-test"), asked)
        assertEquals("¥110.00（充值 100.00，赠送 10.00）", balance.summary(context))
        assertEquals("$1.00（充值 0.00，赠送 1.00） · 余额不足，暂不可用", DeepSeekBalance(false, listOf(DeepSeekAmount("USD", "1.00", "0.00", "1.00"))).summary(context))
        assertEquals("余额不足，暂不可用", parseDeepSeekBalance("""{"is_available":false,"balance_infos":[]}""").summary(context))
        val english = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) },
        )
        assertEquals("¥110.00 (topped up 100.00, granted 10.00)", balance.summary(english))
    }

    @Test
    fun aRejectedKeySaysSoWithoutEchoingTheAnswer() = runBlocking {
        val rejected = fetchDeepSeekBalance(context, "sk-bad", answering(401, """{"error":{"message":"Authentication Fails, Your api key: ****-bad is invalid"}}"""), URL)
        val busy = fetchDeepSeekBalance(context, "sk-test", answering(503, """{"error":{"message":"Service busy"}}"""), URL)

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
