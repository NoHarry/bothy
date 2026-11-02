package cc.noharry.bothy.app.usage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.outlinedTextFieldColors
import cc.noharry.bothy.app.console.singleLine
import cc.noharry.bothy.app.model.ProviderConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 模型价格: the dollar rate, the models.dev sync, and every seen model's price, editable. */
@Composable
internal fun UsagePricesTab(usage: UsageDays, book: PriceBook) {
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
            PriceRow(model, book.prices[model])
        }
    }
}

@Composable
private fun UsageSyncRow(book: PriceBook) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var rate by remember(book.usdToCny) { mutableStateOf(book.usdToCny.toString()) }
    var message by remember { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        UsageNumberField(
            stringResource(R.string.usage_rate_label), rate, { rate = it }, Modifier.widthIn(max = RATE_FIELD_WIDTH),
        )
        TextButton(onClick = {
            val value = rate.toDoubleOrNull()
            if (value == null || value <= 0) {
                message = resources.getString(R.string.usage_rate_invalid)
            } else {
                UsageStore.setUsdToCny(value).also { message = resources.getString(R.string.usage_rate_saved) }
            }
        }) { Text(stringResource(R.string.usage_save_rate)) }
        TextButton(enabled = !syncing, onClick = {
            syncing = true
            message = resources.getString(R.string.usage_syncing_message)
            scope.launch {
                val active = withContext(Dispatchers.IO) {
                    runCatching { ProviderConfig.load(context).let { it.modelRef(it.activeProfile).modelId } }.getOrNull()
                }
                val result = suspendCoroutine { done -> UsageStore.syncPrices(listOfNotNull(active)) { done.resume(it) } }
                message = result.fold(
                    { (matched, total) ->
                        context.resources.getQuantityString(R.plurals.usage_synced, total, matched, total)
                    },
                    { resources.getString(R.string.usage_sync_failed, it.message) },
                )
                syncing = false
            }
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

/** One model's price, editable; typing one makes it hand-set, and 恢复同步价 hands it back to the next sync. */
@Composable
private fun PriceRow(model: String, price: ModelPrice?) {
    var input by remember(price) { mutableStateOf(price?.input?.toString().orEmpty()) }
    var cache by remember(price) { mutableStateOf(price?.cacheRead?.toString().orEmpty()) }
    var output by remember(price) { mutableStateOf(price?.output?.toString().orEmpty()) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
        Text(model, style = ConsoleType.label, color = ConsoleColors.text)
        Text(
            price?.let { priceSource(it) } ?: stringResource(R.string.usage_unpriced),
            style = ConsoleType.meta,
            color = if (price == null) ConsoleColors.warning else ConsoleColors.tertiary,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
            UsageNumberField(stringResource(R.string.usage_input), input, { input = it }, Modifier.weight(1f))
            UsageNumberField(stringResource(R.string.usage_cache_hits), cache, { cache = it }, Modifier.weight(1f))
            UsageNumberField(stringResource(R.string.usage_output), output, { output = it }, Modifier.weight(1f))
            TextButton(onClick = {
                val typed = input.toDoubleOrNull()?.let { inPrice ->
                    output.toDoubleOrNull()?.let { outPrice -> ModelPrice(inPrice, outPrice, cache.toDoubleOrNull(), source = MANUAL_SOURCE) }
                }
                if (typed != null) UsageStore.setManualPrice(model, typed)
            }) { Text(stringResource(R.string.common_save)) }
            if (price?.manual == true) {
                TextButton(onClick = { UsageStore.setManualPrice(model, null) }) {
                    Text(stringResource(R.string.usage_restore_synced))
                }
            }
        }
    }
}

/**
 * Where [price] came from, in the screen's language. The stored source is data: 「手填」 ([MANUAL_SOURCE]) or
 * "models.dev · deepseek", with 「（按 deepseek-flash）」 when priced at another model ([matchModelsDevPrice]).
 */
@Composable
internal fun priceSource(price: ModelPrice): String {
    if (price.source == MANUAL_SOURCE) return stringResource(R.string.usage_source_manual)
    val pricedAs = PRICED_AS.matchEntire(price.source) ?: return price.source
    return stringResource(R.string.usage_source_priced_as, pricedAs.groupValues[1], pricedAs.groupValues[2])
}

private val PRICED_AS = Regex("(.*)（按 (.+)）")

@Composable
private fun UsageNumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = outlinedTextFieldColors(),
        modifier = modifier,
    )
}

private val RATE_FIELD_WIDTH = 220.dp
