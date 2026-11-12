package cc.noharry.bothy.app.usage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.CNY
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.ModelPrice
import cc.noharry.agent.appserver.protocol.ModelPriceParams
import cc.noharry.agent.appserver.protocol.PriceBook
import cc.noharry.agent.appserver.protocol.PriceSyncResult
import cc.noharry.agent.appserver.protocol.RateParams
import cc.noharry.agent.appserver.protocol.ServiceView
import cc.noharry.agent.appserver.protocol.UsageDays
import cc.noharry.agent.appserver.protocol.USD
import cc.noharry.agent.appserver.protocol.UsageRequests
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.outlinedTextFieldColors
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.app.model.named
import cc.noharry.bothy.app.model.regionName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 模型价格: the dollar rate, the models.dev sync, and every seen model's price, editable. */
@Composable
internal fun UsagePricesTab(usage: UsageDays, book: PriceBook, services: List<ServiceView> = emptyList()) {
    UsageCard(stringResource(R.string.usage_sync_and_rate)) {
        Text(
            stringResource(R.string.usage_prices_note),
            style = ConsoleType.bodySmall,
            color = ConsoleColors.muted,
        )
        UsageSyncRow(book)
    }
    UsageCard(stringResource(R.string.usage_model_prices_title)) {
        val models = (usage.values.flatMap { it.keys } + book.prices.keys).toSortedSet()
        if (models.isEmpty()) {
            UsageEmptyBox(stringResource(R.string.usage_no_models_title), stringResource(R.string.usage_no_models_body))
        }
        models.forEachIndexed { index, model ->
            if (index > 0) HorizontalDivider(color = ConsoleColors.borderSubtle)
            PriceRow(model, book.prices[model], services)
        }
    }
}

@Composable
private fun UsageSyncRow(book: PriceBook) {
    val server = LocalServer.current
    val resources = LocalResources.current
    var rate by remember(book.usdToCny) { mutableStateOf(book.usdToCny.toString()) }
    var message by remember { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }
    // On a phone the buttons wrap under the rate rather than squeeze into a column of letters.
    WrappingRow(Arrangement.spacedBy(Dimens.spaceSm)) {
        UsageNumberField(
            stringResource(R.string.usage_rate_label), rate, { rate = it }, Modifier.widthIn(max = RATE_FIELD_WIDTH),
        )
        TextButton(onClick = {
            val value = rate.toDoubleOrNull()
            if (value == null || value <= 0) {
                message = resources.getString(R.string.usage_rate_invalid)
            } else {
                server.send(UsageRequests.SET_RATE, RateParams(value))
                message = resources.getString(R.string.usage_rate_saved)
            }
        }) { Text(stringResource(R.string.usage_save_rate)) }
        TextButton(enabled = !syncing, onClick = {
            syncing = true
            message = resources.getString(R.string.usage_syncing_message)
            val synced = { result: PriceSyncResult ->
                message = result.failure?.let { resources.getString(R.string.usage_sync_failed, it) }
                    ?: resources.getQuantityString(R.plurals.usage_synced, result.total, result.matched, result.total)
                syncing = false
            }
            server.send(
                UsageRequests.SYNC_PRICES,
                Empty,
                onRefused = { synced(PriceSyncResult(failure = it.message.orEmpty())) },
                onAnswer = synced,
            )
        }) { Text(stringResource(if (syncing) R.string.usage_syncing else R.string.usage_sync_prices)) }
    }
    val synced = book.syncedAtEpochMillis?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(it)) }
        ?: stringResource(R.string.usage_never)
    val lastSynced = stringResource(R.string.usage_last_synced, synced)
    Text(
        listOf(lastSynced, message).filter(String::isNotEmpty).joinToString(" · "),
        style = ConsoleType.bodySmall,
        color = ConsoleColors.muted,
    )
}

/** A row's prices as typed, in [currency] (the price's own, else US dollars). */
private class PriceInputs(price: ModelPrice?) {
    var input by mutableStateOf(price?.input?.toString().orEmpty())
    var cache by mutableStateOf(price?.cacheRead?.toString().orEmpty())
    var output by mutableStateOf(price?.output?.toString().orEmpty())
    var currency by mutableStateOf(rowCurrency(price))

    fun typed(): ModelPrice? = typedPrice(input, cache, output, currency)
}

/**
 * One model's price, editable, in its currency (the price's own; the switch beside it picks USD or CNY, and each field
 * shows the currency's sign); typing one makes it hand-set in that currency, and 恢复同步价 hands it back to the next
 * sync. On a narrow screen (a phone) the switch and the buttons go under the fields.
 */
@Composable
private fun PriceRow(model: String, price: ModelPrice?, services: List<ServiceView>) {
    val inputs = remember(price) { PriceInputs(price) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
        Text(model, style = ConsoleType.label, color = ConsoleColors.text)
        Text(
            price?.let { priceSource(it, services) } ?: stringResource(R.string.usage_unpriced),
            style = ConsoleType.meta,
            color = if (price == null) ConsoleColors.warning else ConsoleColors.tertiary,
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val oneLine = maxWidth >= PRICE_ROW_ONE_LINE
            if (oneLine) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
                    PriceFields(inputs)
                    PriceActions(model, price, inputs)
                }
            } else {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) { PriceFields(inputs) }
                    PriceActions(model, price, inputs)
                }
            }
        }
    }
}

@Composable
private fun RowScope.PriceFields(inputs: PriceInputs) {
    val sign = currencySign(inputs.currency)
    UsageNumberField(stringResource(R.string.usage_input), inputs.input, { inputs.input = it }, Modifier.weight(1f), sign)
    UsageNumberField(stringResource(R.string.usage_cache_hits), inputs.cache, { inputs.cache = it }, Modifier.weight(1f), sign)
    UsageNumberField(stringResource(R.string.usage_output), inputs.output, { inputs.output = it }, Modifier.weight(1f), sign)
}

/** The row's currency (USD or CNY), 保存, and 恢复同步价 for a price typed in. */
@Composable
private fun PriceActions(model: String, price: ModelPrice?, inputs: PriceInputs) {
    val server = LocalServer.current
    // On a phone 恢复同步价 wraps under the others rather than squeeze them.
    WrappingRow(Arrangement.spacedBy(Dimens.spaceXs, Alignment.End)) {
        UsageSegmented(CURRENCIES, inputs.currency, { it }) { inputs.currency = it }
        TextButton(onClick = { inputs.typed()?.let { server.send(UsageRequests.SET_PRICE, ModelPriceParams(model, it)) } }) {
            Text(stringResource(R.string.common_save))
        }
        if (price?.manual == true) {
            TextButton(onClick = { server.send(UsageRequests.SET_PRICE, ModelPriceParams(model, null)) }) {
                Text(stringResource(R.string.usage_restore_synced))
            }
        }
    }
}

/**
 * Where [price] came from: typed in (with its currency), a service's own list (its name, the day it was read, its
 * currency and, for a service with a list per region, the region), or models.dev under a provider, in US dollars;
 * priced at another model when it had no price of its own.
 */
@Composable
internal fun priceSource(price: ModelPrice, services: List<ServiceView> = emptyList()): String {
    if (price.manual) return "${stringResource(R.string.usage_source_manual)} · ${price.currency}"
    val currency = price.region?.let { "${price.currency} (${regionName(it)})" } ?: price.currency
    val service = services.named(price.source)?.label ?: price.source
    val source = price.asOf?.let { stringResource(R.string.usage_source_service, service, it, currency) }
        ?: "models.dev · ${price.source} · ${price.currency}"
    return price.pricedAs?.let { stringResource(R.string.usage_source_priced_as, source, it) } ?: source
}

/** The sign a price field shows before the number: $ for US dollars, ¥ for yuan, else the currency's code. */
internal fun currencySign(currency: String): String = when (currency) {
    USD -> "$"
    CNY -> "¥"
    else -> currency
}

/** The currency a row's price is typed in: its price's, else US dollars (models.dev's). */
internal fun rowCurrency(price: ModelPrice?): String = price?.currency ?: USD

/** The price typed in a row, in [currency]; null until the input and output are numbers. */
internal fun typedPrice(input: String, cache: String, output: String, currency: String): ModelPrice? =
    input.toDoubleOrNull()?.let { inPrice ->
        output.toDoubleOrNull()?.let { outPrice -> ModelPrice(inPrice, outPrice, cache.toDoubleOrNull(), manual = true, currency = currency) }
    }

@Composable
private fun UsageNumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier, sign: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        prefix = sign?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = outlinedTextFieldColors(),
        modifier = modifier,
    )
}

private val RATE_FIELD_WIDTH = 220.dp

/** Wide enough for the three prices, the currency switch and both buttons on one line. */
private val PRICE_ROW_ONE_LINE = 640.dp
private val CURRENCIES = listOf(USD, CNY)
