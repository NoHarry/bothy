package cc.noharry.bothy.sample

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** 模型价格: the dollar rate, the models.dev sync, and every seen model's price, editable. */
@Composable
internal fun DebugUsagePricesTab(usage: DebugUsageDays, book: DebugPriceBook) {
    UsageCard("同步与汇率") {
        Text(
            "价格从 models.dev 同步，单位是美元/百万 token，按汇率折算成人民币。DeepSeek 按官方价：flash 系列都按 deepseek-flash；" +
                "缓存命中按缓存价计，没有缓存价的按输入价。手填的价格同步时不会被覆盖。",
            style = DebugType.bodySmall,
            color = DebugColors.muted,
        )
        UsageSyncRow(book)
    }
    UsageCard("模型价格（美元/百万 token）") {
        val models = (usage.values.flatMap { it.keys } + book.prices.keys).toSortedSet()
        if (models.isEmpty()) UsageEmptyBox("还没有模型", "用过的模型会出现在这里，同步后自动带上价格。")
        models.forEachIndexed { index, model ->
            if (index > 0) HorizontalDivider(color = DebugColors.borderSubtle)
            PriceRow(model, book.prices[model])
        }
    }
}

@Composable
private fun UsageSyncRow(book: DebugPriceBook) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rate by remember(book.usdToCny) { mutableStateOf(book.usdToCny.toString()) }
    var message by remember { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm)) {
        UsageNumberField("美元兑人民币汇率", rate, { rate = it }, Modifier.widthIn(max = RATE_FIELD_WIDTH))
        TextButton(onClick = {
            val value = rate.toDoubleOrNull()
            if (value == null || value <= 0) message = "汇率需为正数" else DebugUsageStore.setUsdToCny(value).also { message = "汇率已保存" }
        }) { Text("保存汇率") }
        TextButton(enabled = !syncing, onClick = {
            syncing = true
            message = "正在从 models.dev 同步…"
            scope.launch {
                val active = withContext(Dispatchers.IO) {
                    runCatching { DebugProviderConfig.load(context).let { it.modelRef(it.activeProfile).modelId } }.getOrNull()
                }
                val result = suspendCoroutine { done -> DebugUsageStore.syncPrices(listOfNotNull(active)) { done.resume(it) } }
                message = result.fold({ (matched, total) -> "已同步：$matched / $total 个模型匹配到价格" }, { "同步失败：${it.message}" })
                syncing = false
            }
        }) { Text(if (syncing) "同步中…" else "同步价格（models.dev）") }
    }
    val synced = book.syncedAtEpochMillis?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(it)) } ?: "从未"
    Text(listOf("上次同步：$synced", message).filter(String::isNotEmpty).joinToString(" · "), style = DebugType.bodySmall, color = DebugColors.muted)
}

/** One model's price, editable; typing one makes it hand-set, and 恢复同步价 hands it back to the next sync. */
@Composable
private fun PriceRow(model: String, price: DebugModelPrice?) {
    var input by remember(price) { mutableStateOf(price?.input?.toString().orEmpty()) }
    var cache by remember(price) { mutableStateOf(price?.cacheRead?.toString().orEmpty()) }
    var output by remember(price) { mutableStateOf(price?.output?.toString().orEmpty()) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceXs)) {
        Text(model, style = DebugType.label, color = DebugColors.text)
        Text(price?.source ?: "未定价", style = DebugType.meta, color = if (price == null) DebugColors.warning else DebugColors.tertiary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm)) {
            UsageNumberField("输入", input, { input = it }, Modifier.weight(1f))
            UsageNumberField("缓存命中", cache, { cache = it }, Modifier.weight(1f))
            UsageNumberField("输出", output, { output = it }, Modifier.weight(1f))
            TextButton(onClick = {
                val typed = input.toDoubleOrNull()?.let { inPrice ->
                    output.toDoubleOrNull()?.let { outPrice -> DebugModelPrice(inPrice, outPrice, cache.toDoubleOrNull(), source = MANUAL_SOURCE) }
                }
                if (typed != null) DebugUsageStore.setManualPrice(model, typed)
            }) { Text("保存") }
            if (price?.manual == true) TextButton(onClick = { DebugUsageStore.setManualPrice(model, null) }) { Text("恢复同步价") }
        }
    }
}

@Composable
private fun UsageNumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = debugOutlinedTextFieldColors(),
        modifier = modifier,
    )
}

private val RATE_FIELD_WIDTH = 220.dp
