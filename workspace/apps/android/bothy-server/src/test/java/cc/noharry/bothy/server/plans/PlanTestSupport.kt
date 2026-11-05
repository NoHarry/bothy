package cc.noharry.bothy.server.plans

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.console.saveFileAtomically
import cc.noharry.agent.appserver.console.setAsideUnreadable
import cc.noharry.agent.appserver.mcp.McpResource
import cc.noharry.agent.appserver.mcp.resourceData
import cc.noharry.agent.appserver.mcp.resourceReadings
import cc.noharry.agent.appserver.mcp.toolReading
import cc.noharry.agent.appserver.plans.PlanWatchAsk
import cc.noharry.agent.appserver.plans.resolvePlanWatch
import cc.noharry.agent.appserver.plans.valueText
import cc.noharry.agent.appserver.protocol.Plan
import cc.noharry.agent.appserver.protocol.PlanAction
import cc.noharry.agent.appserver.protocol.PlanEnd
import cc.noharry.agent.appserver.protocol.PlanFiring
import cc.noharry.agent.appserver.protocol.PlanTrigger
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.protocol.nextDueMillis
import cc.noharry.agent.appserver.protocol.nextRepeat
import cc.noharry.agent.appserver.protocol.text
import cc.noharry.agent.appserver.protocol.view.valueText
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.RuntimeContextRequest
import cc.noharry.agent.kernel.api.RuntimeContextScalar
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolPrefetchRequest
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal const val WEATHER_URI = "weather://shenzhen/now"

internal const val AIR_URI = "weather://shenzhen/air"

internal const val KIWI = "kiwi"

internal const val FARE_ARGUMENTS = """{"flyFrom":"SZX","flyTo":"CTU","departureDate":"07/11/2026","currency":"CNY"}"""

/** Kiwi.com's open flight search as it lists its tools: one read-only query, one that sends feedback. */
internal val KIWI_CATALOG = listOf(
    ToolDescriptor("search-flight", "Search flights", """{"type":"object","properties":{"flyFrom":{"type":"string"}}}""", effectKind = "READ_ONLY"),
    ToolDescriptor("feedback-to-devs", "Send feedback", """{"type":"object","properties":{"text":{"type":"string"}}}"""),
)

internal fun resource(uri: String, name: String, server: String = "weather") = McpResource(server, "Weather", uri, name, "")

/** A watch on a weather server's temperature above [value], as the model would write it. */
internal fun temperatureWatch(value: Double, hold: Int? = null, repeat: Boolean? = null): PlanTrigger = resolvePlanWatch(
    PlanWatchAsk("weather", WEATHER_URI, "temperature", "above", JsonPrimitive(value), "深圳气温超过 ${valueText(JsonPrimitive(value))} 度", holdSeconds = hold, repeat = repeat),
    repeatByDefault = false,
).getOrThrow()

/** A watch on its air quality index below [value]. */
internal fun airWatch(value: Double, repeat: Boolean? = null): PlanTrigger = resolvePlanWatch(
    PlanWatchAsk("weather", AIR_URI, "aqi", "below", JsonPrimitive(value), "空气质量指数低于 ${valueText(JsonPrimitive(value))}", repeat = repeat),
    repeatByDefault = false,
).getOrThrow()

/** A watch on Kiwi.com's cheapest fare from Shenzhen to Chengdu below [value], through its search tool. */
internal fun fareWatch(value: Double): PlanTrigger = resolvePlanWatch(
    PlanWatchAsk(
        KIWI, null, "itineraries.0.price", "below", JsonPrimitive(value), "最低价低于 ${valueText(JsonPrimitive(value))} 元",
        tool = "search-flight", arguments = Json.parseToJsonElement(FARE_ARGUMENTS).jsonObject,
    ),
    repeatByDefault = false,
).getOrThrow()
