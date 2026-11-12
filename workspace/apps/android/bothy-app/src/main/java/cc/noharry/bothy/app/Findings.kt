package cc.noharry.bothy.app

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import cc.noharry.agent.appserver.protocol.BalanceView
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import cc.noharry.agent.appserver.protocol.jsonrpc.ProtocolJson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [finding] in the screen's language: its code's string ([FINDING_STRINGS], [FINDING_PLURALS]) with its values, each
 * finding under it worded in turn, then its balance. Values that read as decimals are passed as numbers, so a wording
 * may format them (`%1$.2f`); every other value is text.
 */
internal fun Resources.words(finding: Finding): String {
    val more = finding.more.map { words(it) }
    return when (finding.code) {
        FindingCode.TEXT.name -> finding.args.firstOrNull().orEmpty()
        FindingCode.PARTS.name -> more.joinToString(PARTS_SEPARATOR)
        FindingCode.LIST.name -> more.joinToString(getString(R.string.finding_list_separator))
        FindingCode.ALTERNATIVES.name -> more.joinToString(ALTERNATIVES_SEPARATOR)
        FindingCode.LINES.name -> more.joinToString("\n")
        else -> {
            val values = (finding.args.map(::value) + more + listOfNotNull(finding.balance?.let { balanceWords(it) })).toTypedArray()
            FINDING_PLURALS[finding.code]?.let { getQuantityString(it, finding.count ?: 0, *values) }
                ?: getString(FINDING_STRINGS.getValue(finding.code), *values)
        }
    }
}

@Composable
internal fun words(finding: Finding): String = LocalResources.current.words(finding)

/** The finding a refusal carries in its data (a saved key that would go to another host, say); null when it has none. */
internal fun JsonRpcException.finding(): Finding? =
    data?.let { runCatching { ProtocolJson.decodeFromJsonElement(Finding.serializer(), it) }.getOrNull() }

/** 「¥110.00 (top-up 100.00, granted 10.00)」 per currency, and whether the key is out of credit. */
internal fun Resources.balanceWords(balance: BalanceView): String {
    val totals = balance.amounts.joinToString(getString(R.string.model_balance_separator)) { amount ->
        val sign = CURRENCY_SIGNS[amount.currency] ?: "${amount.currency} "
        getString(R.string.model_balance_amount, "$sign${amount.total}", amount.toppedUp, amount.granted)
    }
    return when {
        totals.isEmpty() -> getString(if (balance.available) R.string.model_deepseek_available else R.string.model_balance_insufficient)
        balance.available -> totals
        else -> getString(R.string.model_balance_amounts_insufficient, totals)
    }
}

/** A time the server sent (epoch milliseconds) as a clock reads it, to the second. */
internal fun clockSeconds(millis: Long): String = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(millis))

private fun value(arg: String): Any = if (DECIMAL.matches(arg)) arg.toDouble() else arg

private val DECIMAL = Regex("-?\\d+\\.\\d+")
private val CURRENCY_SIGNS = mapOf("CNY" to "¥", "USD" to "$")
private const val PARTS_SEPARATOR = " · "
private const val ALTERNATIVES_SEPARATOR = " / "
