package cc.noharry.bothy.app

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.BalanceAmountView
import cc.noharry.agent.appserver.protocol.BalanceView
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.FindingKind
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.protocol.listing
import cc.noharry.agent.appserver.protocol.parts
import cc.noharry.agent.appserver.protocol.text
import cc.noharry.bothy.protocol.BothyFindingCode
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the server finds, in the screen's words: every code has a wording in both languages, and each fills as built. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh", application = Application::class)
class FindingWordingUiTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zh = context.resources
    private val en = context.createConfigurationContext(Configuration(zh.configuration).apply { setLocale(Locale.ENGLISH) }).resources

    @Test
    fun everyCodeIsWordedInBothLanguagesWithTheSameValues() {
        val arranging = setOf(FindingCode.TEXT, FindingCode.PARTS, FindingCode.LIST, FindingCode.ALTERNATIVES, FindingCode.LINES)
        // The app server's codes and Bothy's own.
        val codes: List<FindingKind> = FindingCode.entries.filter { it !in arranging } + BothyFindingCode.entries
        codes.forEach { code ->
            val id = FINDING_STRINGS[code.name] ?: FINDING_PLURALS[code.name]
            assertTrue("$code has no wording", id != null)
            val plural = code.name in FINDING_PLURALS
            val values = { res: Resources ->
                if (plural) res.getQuantityText(id!!, 2).toString() else res.getText(id!!).toString()
            }
            assertEquals("$code takes the same values in English and Chinese", placeholders(values(en)), placeholders(values(zh)))
            // Every value as text, decimals as numbers: what the server sends always fills the wording.
            val filled = Finding(code, placeholders(values(zh)).map { (_, kind) -> if (kind == 'f') "0.5" else "x" }, count = 2)
            zh.words(filled)
            en.words(filled)
        }
    }

    @Test
    fun failedRequestsSayWhatIsWrong() {
        assertTrue(zh.words(finding(BothyFindingCode.CHECKS_HTTP_KEY, 401)).contains("key 不对"))
        assertEquals("HTTP 401: wrong key or no permission", en.words(finding(BothyFindingCode.CHECKS_HTTP_KEY, 401)))
        assertEquals("Can't connect: timeout", en.words(finding(BothyFindingCode.CHECKS_CANNOT_CONNECT, "timeout")))
        assertEquals("HTTP 418", zh.words(finding(FindingCode.HTTP_STATUS, 418)))
    }

    @Test
    fun aStaleModelIsNamedWithWhatTheServiceLists() {
        val name = parts(text("deepseek anthropic"), text("deepseek-v4-flash"))
        val listed = listing(listOf(text("deepseek-flash"), text("deepseek-v4-pro")))
        val stale = Finding(BothyFindingCode.CHECKS_MODEL_NOT_LISTED, more = listOf(name, text("deepseek-v4-flash"), listed))
        val many = Finding(BothyFindingCode.CHECKS_LISTED_MORE, more = listOf(listing((1..5).map { text("m$it") }), text(7)))

        assertEquals(
            "deepseek anthropic · deepseek-v4-flash · 服务可用，但当前选的 deepseek-v4-flash 不在它列出的模型里（可能已下线），请在输入框旁换一个：deepseek-flash、deepseek-v4-pro",
            zh.words(stale),
        )
        assertEquals("m1、m2、m3、m4、m5 等 7 个", zh.words(many))
    }

    @Test
    fun aBalanceReadsAsTotalWithToppedUpAndGrantedPerCurrency() {
        val balance = BalanceView(true, listOf(BalanceAmountView("CNY", "110.00", "100.00", "10.00")))

        assertEquals("¥110.00（充值 100.00，赠送 10.00）", zh.balanceWords(balance))
        assertEquals("¥110.00 (topped up 100.00, granted 10.00)", en.balanceWords(balance))
        assertEquals(
            "$1.00（充值 0.00，赠送 1.00） · 余额不足，暂不可用",
            zh.balanceWords(BalanceView(false, listOf(BalanceAmountView("USD", "1.00", "0.00", "1.00")))),
        )
        assertEquals("余额不足，暂不可用", zh.balanceWords(BalanceView(false)))
        assertEquals("余额：¥110.00（充值 100.00，赠送 10.00）", zh.words(Finding(FindingCode.MODEL_BALANCE, balance = balance)))
    }

    @Test
    fun scoresAndCountsAreWordedAsNumbers() {
        assertEquals("small_talk 0.62 < 0.75", en.words(finding(BothyFindingCode.COMPANION_REASON_BELOW, 0.62, 0.75)))
        assertTrue(zh.words(Finding(BothyFindingCode.TOOLS_COUNT, listOf("5"), count = 5)).contains("5"))
    }

    @Test
    fun aSkillsUnknownFieldNamesTheFieldsItAllows() {
        val fields = listOf("name", "description", "license", "compatibility", "metadata", "allowed-tools", "scenes")
        val found = Finding(FindingCode.SKILLS_CHECK_UNKNOWN_FIELD, more = listOf(text(4), text("version"), listing(fields.map(::text))))

        assertEquals(
            "Line 4: unknown field “version”; use only name, description, license, compatibility, metadata, allowed-tools, scenes.",
            en.words(found),
        )
    }

    /** The values a wording takes, by position, with whether each is formatted as a decimal. */
    private fun placeholders(wording: String): List<Pair<Int, Char>> =
        Regex("""%(\d+)\$[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z])""").findAll(wording)
            .map { it.groupValues[1].toInt() to it.groupValues[2].single() }
            .distinct().sortedBy { it.first }.toList()
}
