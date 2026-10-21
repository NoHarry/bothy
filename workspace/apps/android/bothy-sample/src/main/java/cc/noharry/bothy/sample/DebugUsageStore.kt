package cc.noharry.bothy.sample

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Usage by day (ISO date, device time zone) and model. */
internal typealias DebugUsageDays = Map<String, Map<String, DebugModelUsage>>

/** Model prices plus the dollar exchange rate costs are shown at. */
internal data class DebugPriceBook(
    val prices: Map<String, DebugModelPrice> = emptyMap(),
    val usdToCny: Double = DEFAULT_USD_TO_CNY,
    val syncedAtEpochMillis: Long? = null,
)

/**
 * Settings → 用量与费用: every model call's tokens, kept per day and model, and the price book. Stored under
 * `files/model-usage/`, which Clear runtime data does not touch; there is no way to clear it from the UI. All file
 * work runs on one background thread, in order.
 */
internal object DebugUsageStore {
    private val io = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "model-usage") }
    private val mutableUsage = MutableStateFlow<DebugUsageDays>(emptyMap())
    private val mutablePrices = MutableStateFlow(DebugPriceBook())
    val usage: StateFlow<DebugUsageDays> = mutableUsage
    val prices: StateFlow<DebugPriceBook> = mutablePrices

    @Volatile private var dir: File? = null
    private var loaded = false
    private var savePending = false

    fun init(context: Context) {
        dir = File(context.applicationContext.filesDir, USAGE_DIR)
        io.execute(::ensureLoaded)
    }

    /** Adds one call's usage to today's row for [model]. Any thread. */
    fun record(model: String, usage: DebugModelUsage) {
        val name = model.ifBlank { "unknown" }
        val day = LocalDate.now().toString()
        io.execute {
            ensureLoaded()
            val days = mutableUsage.value.toMutableMap()
            val models = days[day].orEmpty().toMutableMap()
            models[name] = (models[name] ?: DebugModelUsage()) + usage
            days[day] = models
            mutableUsage.value = days
            scheduleSave()
        }
    }

    /** Sets or, with null, removes the hand-typed price for [model]; removing lets the next sync fill it. */
    fun setManualPrice(model: String, price: DebugModelPrice?) = io.execute {
        ensureLoaded()
        val prices = mutablePrices.value.prices.toMutableMap()
        if (price == null) prices.remove(model) else prices[model] = price.copy(source = MANUAL_SOURCE, manual = true)
        mutablePrices.value = mutablePrices.value.copy(prices = prices)
        savePrices()
    }

    fun setUsdToCny(rate: Double) = io.execute {
        ensureLoaded()
        mutablePrices.value = mutablePrices.value.copy(usdToCny = rate)
        savePrices()
    }

    /**
     * Fetches models.dev and prices every model seen in the ledger plus [extraModels]; hand-typed prices stay.
     * Calls [done] on the store thread with how many were matched, or the failure.
     */
    fun syncPrices(extraModels: Collection<String>, done: (Result<Pair<Int, Int>>) -> Unit) = io.execute {
        ensureLoaded()
        val result = runCatching {
            val catalog = fetchModelsDev()
            val models = (mutableUsage.value.values.flatMap { it.keys } + extraModels).filter { it != "unknown" }.toSet()
            val prices = mutablePrices.value.prices.toMutableMap()
            var matched = 0
            models.forEach { model ->
                if (prices[model]?.manual == true) return@forEach
                val price = matchModelsDevPrice(catalog, model)
                if (price != null) {
                    prices[model] = price
                    matched++
                }
            }
            mutablePrices.value = mutablePrices.value.copy(prices = prices, syncedAtEpochMillis = System.currentTimeMillis())
            savePrices()
            matched to models.size
        }
        done(result)
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val root = dir ?: return
        runCatching { File(root, LEDGER_FILE).takeIf(File::isFile)?.readText()?.let(::parseUsage) }.getOrNull()?.let { mutableUsage.value = it }
        runCatching { File(root, PRICES_FILE).takeIf(File::isFile)?.readText()?.let(::parsePriceBook) }.getOrNull()?.let { mutablePrices.value = it }
    }

    /** Coalesces a burst of records into one write a second later. */
    private fun scheduleSave() {
        if (savePending) return
        savePending = true
        io.schedule({
            savePending = false
            write(LEDGER_FILE, usageJson(mutableUsage.value))
        }, SAVE_DELAY_MILLIS, TimeUnit.MILLISECONDS)
    }

    private fun savePrices() = write(PRICES_FILE, priceBookJson(mutablePrices.value))

    private fun write(name: String, text: String) {
        val root = dir ?: return
        runCatching {
            root.mkdirs()
            val temp = File(root, "$name.tmp")
            temp.writeText(text)
            if (!temp.renameTo(File(root, name))) throw IOException("rename failed")
        }
    }

    private fun fetchModelsDev(): JsonObject {
        val request = Request.Builder().url(MODELS_DEV_URL).build()
        return MODELS_DEV_HTTP.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("models.dev HTTP ${response.code}")
            Json.parseToJsonElement(response.body?.string() ?: throw IOException("models.dev returned nothing")).jsonObject
        }
    }
}

internal fun usageJson(days: DebugUsageDays): String = buildJsonObject {
    put("version", 1)
    putJsonObject("days") {
        days.forEach { (day, models) ->
            putJsonObject(day) {
                models.forEach { (model, usage) ->
                    putJsonObject(model) {
                        put("requests", usage.requests)
                        put("input", usage.inputTokens)
                        put("cacheRead", usage.cacheReadTokens)
                        put("cacheWrite", usage.cacheWriteTokens)
                        put("output", usage.outputTokens)
                    }
                }
            }
        }
    }
}.toString()

internal fun parseUsage(text: String): DebugUsageDays {
    val days = Json.parseToJsonElement(text).jsonObject["days"]?.jsonObject ?: return emptyMap()
    return days.mapValues { (_, models) ->
        models.jsonObject.mapValues { (_, value) ->
            val usage = value.jsonObject
            fun long(name: String) = usage[name]?.jsonPrimitive?.longOrNull ?: 0L
            DebugModelUsage(long("requests"), long("input"), long("cacheRead"), long("cacheWrite"), long("output"))
        }
    }
}

internal fun priceBookJson(book: DebugPriceBook): String = buildJsonObject {
    put("version", 1)
    put("usdToCny", book.usdToCny)
    book.syncedAtEpochMillis?.let { put("syncedAt", it) }
    putJsonObject("prices") {
        book.prices.forEach { (model, price) ->
            putJsonObject(model) {
                put("input", price.input)
                put("output", price.output)
                price.cacheRead?.let { put("cacheRead", it) }
                price.cacheWrite?.let { put("cacheWrite", it) }
                put("source", price.source)
                put("manual", price.manual)
            }
        }
    }
}.toString()

internal fun parsePriceBook(text: String): DebugPriceBook {
    val root = Json.parseToJsonElement(text).jsonObject
    val prices = root["prices"]?.jsonObject?.mapNotNull { (model, value) ->
        val entry = value.jsonObject
        fun double(name: String) = entry[name]?.jsonPrimitive?.doubleOrNull
        val input = double("input") ?: return@mapNotNull null
        val output = double("output") ?: return@mapNotNull null
        model to DebugModelPrice(
            input = input,
            output = output,
            cacheRead = double("cacheRead"),
            cacheWrite = double("cacheWrite"),
            source = entry["source"]?.jsonPrimitive?.content ?: MANUAL_SOURCE,
            manual = entry["manual"]?.jsonPrimitive?.content == "true",
        )
    }?.toMap().orEmpty()
    return DebugPriceBook(
        prices = prices,
        usdToCny = root["usdToCny"]?.jsonPrimitive?.doubleOrNull ?: DEFAULT_USD_TO_CNY,
        syncedAtEpochMillis = root["syncedAt"]?.jsonPrimitive?.longOrNull,
    )
}

/** Not one of the Clear runtime data targets, and nothing in the UI clears it. */
internal const val USAGE_DIR = "model-usage"
internal const val MANUAL_SOURCE = "手填"
internal const val DEFAULT_USD_TO_CNY = 7.2
private const val LEDGER_FILE = "usage-ledger.json"
private const val PRICES_FILE = "model-prices.json"
private const val SAVE_DELAY_MILLIS = 1_000L
private const val MODELS_DEV_URL = "https://models.dev/api.json"
private val MODELS_DEV_HTTP = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
