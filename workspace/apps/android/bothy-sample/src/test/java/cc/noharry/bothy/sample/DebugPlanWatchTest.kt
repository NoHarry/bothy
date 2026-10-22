package cc.noharry.bothy.sample

import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolDescriptor
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Watches on any server's resources or read-only tools, the trackers that fire them, and the actions an automation runs. */
@OptIn(ExperimentalAgentRuntimeApi::class)
class DebugPlanWatchTest {
    private val policy = DebugAutomationPolicy.parse(File("src/main/assets/automation-policy.json").readText())

    @Test
    fun aNumberHoldsBeforeItFiresAndIsReArmedOnlyOnceCleared() {
        val tracker = DebugConditionTracker(cooldownMillis = 60_000L)
        val hot = plan(temperatureWatch(30.0, hold = 5, repeat = true))
        assertFalse(tracker.observe(hot, 32.0, 0))
        assertFalse(tracker.observe(hot, 33.0, 4_000))
        // A reading that could not be taken restarts the hold.
        assertFalse(tracker.observe(hot, null, 4_500))
        assertFalse(tracker.observe(hot, 32.0, 5_000))
        assertTrue(tracker.observe(hot, 32.0, 10_000))
        // Hovering around 30 does not remind again; back under 28.5 (5 %) and a minute later it may.
        assertFalse(tracker.observe(hot, 29.5, 20_000))
        assertFalse(tracker.observe(hot, 32.0, 30_000))
        assertFalse(tracker.observe(hot, 28.0, 40_000))
        assertFalse(tracker.observe(hot, 28.0, 80_000))
        assertFalse(tracker.observe(hot, 31.0, 81_000))
        assertTrue(tracker.observe(hot, 31.0, 86_000))
        val clean = plan(airWatch(50.0, repeat = true)).copy(id = "p2")
        assertTrue(tracker.observe(clean, 0.0, 0))
        // After an app restart a plan that already reminded waits for the reading to clear first.
        val restarted = DebugConditionTracker(cooldownMillis = 60_000L)
        val reminded = clean.copy(lastFiredAtMillis = 0L)
        assertFalse(restarted.observe(reminded, 0.0, 120_000))
        assertFalse(restarted.observe(reminded, 60.0, 130_000))
        assertTrue(restarted.observe(reminded, 10.0, 140_000))
        // Given a new threshold it starts over; a clock set back an hour still re-arms it.
        val below30 = reminded.retimed(airWatch(30.0, repeat = true), 141_000)
        assertTrue(restarted.observe(below30, 0.0, 142_000))
        assertFalse(restarted.observe(below30, 40.0, 142_000 - 3_600_000))
        assertTrue(restarted.observe(below30, 0.0, 142_000 - 3_590_000))
        // 「下雨时」 as rainfall above 0 re-arms when the rain stops at 0 (it never reads below 0).
        val rain = plan(watch(WEATHER_URI, "precipitation", "above", JsonPrimitive(0))).copy(id = "rain")
        assertTrue(restarted.observe(rain, 1.0, 0))
        assertFalse(restarted.observe(rain, 0.0, 120_000))
        assertTrue(restarted.observe(rain, 2.0, 130_000))
    }

    @Test
    fun aValueFiresWhenTheFieldBecomesItNotOnTheSnapshot() {
        val tracker = DebugChangeTracker()
        val rain = plan(watch(WEATHER_URI, "condition", "contains", JsonPrimitive("雨")))
        // The first reading only sets where the field stands.
        assertFalse(tracker.observe(rain, data("""{"condition":"多云"}"""), initial = true))
        assertTrue(tracker.observe(rain, data("""{"condition":"小雨","temperature":22}"""), initial = false))
        // Heavier rain while it rains is not a new start; a dry spell and rain again is.
        assertFalse(tracker.observe(rain, data("""{"condition":"中雨"}"""), initial = false))
        assertFalse(tracker.observe(rain, data("""{"condition":"阴"}"""), initial = false))
        assertTrue(tracker.observe(rain, data("""{"condition":"雷阵雨"}"""), initial = false))
        // Alerts are events: each one fires, but not a cached one on the snapshot.
        val alert = plan(watch("weather://shenzhen/alerts", "level", "equals", JsonPrimitive("橙色"), mode = "each")).copy(id = "p2")
        assertFalse(tracker.observe(alert, data("""{"level":"橙色"}"""), initial = true))
        assertTrue(tracker.observe(alert, data("""{"level":"橙色","type":"暴雨"}"""), initial = false))
        assertTrue(tracker.observe(alert, data("""{"level":"橙色","type":"暴雨"}"""), initial = false))
        assertFalse(tracker.observe(alert, data("""{"level":"黄色"}"""), initial = false))
        // Read, not pushed: the last alert stays readable, so only a new one fires.
        val polled = plan(watch("x://alerts", "level", "equals", JsonPrimitive("橙色"), mode = "each")).copy(id = "p4")
        assertFalse(tracker.observe(polled, data("""{"level":"橙色","type":"暴雨"}"""), initial = false, pushed = false))
        assertFalse(tracker.observe(polled, data("""{"level":"橙色","type":"暴雨"}"""), initial = false, pushed = false))
        assertTrue(tracker.observe(polled, data("""{"level":"橙色","type":"大风"}"""), initial = false, pushed = false))
        // `changes` fires on any new value of the field.
        val level = plan(watch(AIR_URI, "category", "changes", null)).copy(id = "p3")
        assertFalse(tracker.observe(level, data("""{"category":1}"""), initial = false))
        assertFalse(tracker.observe(level, data("""{"category":1.0}"""), initial = false))
        assertTrue(tracker.observe(level, data("""{"category":3}"""), initial = false))
    }

    @Test
    fun aWatchLooksOnlyAtItsOwnPartOfAReading() {
        val station = (watch(AIR_URI, "aqi", "equals", JsonPrimitive(42), where = mapOf("station" to JsonPrimitive(0))) as DebugPlanTrigger.Watch).watch
        assertTrue(station.concerns(data("""{"station":0,"aqi":42}""")))
        assertFalse(station.concerns(data("""{"station":5,"aqi":42}""")))
        assertTrue(station.matches(data("""{"station":0,"aqi":42.0}""")))
        assertEquals("福田", watchReading(data("""{"station":{"name":"福田"}}"""), "station.name")!!.content)
        assertEquals("803", valueText(watchReading(data("""{"itineraries":[{"price":803.0}]}"""), "itineraries.0.price")!!))
        assertNull((temperatureWatch(1.0) as DebugPlanTrigger.Watch).watch.number(data("""{"temperature":-1000}""")))
        fun refusal(op: String, value: JsonPrimitive?, mode: String? = null) =
            resolvePlanWatch(DebugPlanWatchAsk("weather", WEATHER_URI, "temperature", op, value, null, mode = mode), false).exceptionOrNull()?.message
        assertTrue(refusal("bigger", JsonPrimitive(1))!!.startsWith("op 只能是"))
        assertEquals("op=above 的 value 要是数字", refusal("above", JsonPrimitive("hot")))
        assertEquals("op=equals 需要 value", refusal("equals", null))
        assertTrue(refusal("above", JsonPrimitive(1), mode = "each")!!.startsWith("数字比较用 enter"))
        // With no words from the model, the rule still reads.
        assertEquals("$WEATHER_URI 的 temperature 超过 30，一次", resolvePlanWatch(DebugPlanWatchAsk("weather", WEATHER_URI, "temperature", "above", JsonPrimitive(30), null), false).getOrThrow().describe(0, ZoneId.of("Asia/Shanghai")))
        assertEquals("search-flight 的 itineraries.0.price 低于 800，一次", resolvePlanWatch(DebugPlanWatchAsk(KIWI, null, "itineraries.0.price", "below", JsonPrimitive(800), null, tool = "search-flight"), false).getOrThrow().describe(0, ZoneId.of("Asia/Shanghai")))
    }

    @Test
    fun aRuleFiringInALoopIsCaught() {
        val guard = DebugFiringGuard(minGapMillis = 3_000L, maxFirings = 3, windowMillis = 60_000L)
        assertEquals(DebugFiringGuard.Verdict.FIRE, guard.check("p", 0))
        assertEquals(DebugFiringGuard.Verdict.SKIP, guard.check("p", 1_000))
        assertEquals(DebugFiringGuard.Verdict.FIRE, guard.check("p", 5_000))
        assertEquals(DebugFiringGuard.Verdict.FIRE, guard.check("p", 10_000))
        assertEquals(DebugFiringGuard.Verdict.PAUSE, guard.check("p", 15_000))
        assertEquals(DebugFiringGuard.Verdict.FIRE, guard.check("q", 15_000))
    }

    @Test
    fun anOverrideCanOnlyTightenThePolicy() {
        val loose = DebugAutomationPolicy.parse("""{"deny":[]}""")
        assertEquals(policy.deny, policy.tightenedBy(loose).deny)
        val stricter = policy.tightenedBy(DebugAutomationPolicy.parse("""{"deny":[{"tool":"calendar_*"}]}"""))
        assertTrue(stricter.denied("calendar_event_create") != null)
        assertTrue(policy.denied("send_sms") != null && policy.denied("create_order") != null)
        assertNull(policy.denied("search-flight"))
        // With the shipped policy unreadable, nothing runs on its own.
        assertTrue(DebugAutomationPolicy(emptyList(), blockAll = true).denied("send_notification") != null)
    }

    @Test
    fun anActionIsAnyLoadedToolThePolicyAllows() {
        val notify = resolvePlanAction("tools_json", "mcp__tools_json__send_notification", args("""{"text":"该带伞了"}"""), "发通知", CATALOG, policy)
        assertEquals(DebugPlanAction("tools_json", "send_notification", args("""{"text":"该带伞了"}"""), "发通知"), notify)
        // A dotted id is the same tool; the title stands in for a missing label.
        assertEquals("播放媒体", resolvePlanAction("tools_json", "play.media", args("""{"keyword":"晴天"}"""), null, CATALOG, policy).label)
        fun refusal(tool: String, arguments: String) = runCatching { resolvePlanAction("tools_json", tool, args(arguments), null, CATALOG, policy) }.exceptionOrNull()!!.message!!
        assertEquals("计划里不能自动执行 send_sms（发短信）", refusal("send_sms", "{}"))
        assertTrue(refusal("fly_away", "{}").startsWith("tools_json 现在没有 fly_away"))
        assertTrue(refusal("send_notification", """{"text":42}""").startsWith("send_notification 的参数不对"))
        assertTrue(globMatches("*dial*", "phone_dial_direct"))
        assertFalse(globMatches("*message_send*", "send_notification"))
        // Another server's tool keeps its dotted name; the runtime's 「mcp__server__」 is dropped.
        val dotted = listOf(ToolDescriptor("weather.today", title = "今天天气", inputSchemaJson = """{"type":"object","properties":{}}"""))
        assertEquals("weather.today", resolvePlanAction("weather", "mcp__weather__weather.today", null, null, dotted, policy).tool)
    }

    @Test
    fun actionsRunInOrderAndSayWhatHappened() = runBlocking {
        val notify = DebugPlanAction("tools_json", "send_notification", args("""{"text":"该带伞了"}"""), "发通知")
        val music = DebugPlanAction("tools_json", "play_media", args("""{"keyword":"雨天"}"""), "放雨天的歌")
        val called = mutableListOf<String>()
        val lines = runPlanActions(listOf(notify, music), policy) { action ->
            called += action.label
            if (action == music) "服务拒绝了" else null
        }
        assertEquals(listOf("已发通知", "放雨天的歌没成功（服务拒绝了）"), lines)
        assertEquals(listOf("发通知", "放雨天的歌"), called)
        // A rule saved before the policy denied its tool does not run it.
        val stricter = policy.tightenedBy(DebugAutomationPolicy.parse("""{"deny":[{"tool":"send_*"}]}"""))
        assertEquals(listOf("发通知没执行（现在的策略不允许自动执行）"), runPlanActions(listOf(notify), stricter) { error("must not run") })
    }

    @Test
    fun aToolCallsOutcomeIsReadFromItsAnswer() {
        fun outcome(body: String) = planActionProblem(McpRpcResponse(McpStatus.OK, "", data(body), null))
        assertNull(outcome("""{"result":{"isError":false,"structuredContent":{"status":{"code":0,"message":"OK"}}}}"""))
        assertEquals("参数无效", outcome("""{"result":{"isError":true,"structuredContent":{"status":{"code":1001,"message":"参数无效"}}}}"""))
        assertEquals("resource_unavailable", outcome("""{"error":{"code":-32006,"message":"resource_unavailable"}}"""))
        assertEquals("连不上", planActionProblem(McpRpcResponse(McpStatus.CONNECT_ERROR, "", null, "连不上")))
    }

    @Test
    fun theToolFindsWatchesAndMakesAutomations() = runBlocking {
        val dir: File = Files.createTempDirectory("plans").toFile()
        val book = DebugPlanBook(File(dir, "plans.json")) { 0L }
        val tool = DebugPlanToolProvider(watchHost(book))
        suspend fun call(arguments: String): JsonObject =
            Json.parseToJsonElement(tool.callTool(PLAN_TOOL, arguments, ToolCallContext("s", "r", "q")).resultJson).jsonObject
        val found = call("""{"action":"signals","query":"空气"}""")
        assertEquals(listOf(AIR_URI), found["signals"]!!.jsonArray.map { it.jsonObject["uri"]!!.jsonPrimitive.content })
        assertEquals(3, call("""{"action":"signals"}""")["count"]!!.jsonPrimitive.content.toInt())
        assertEquals("""{"condition":"多云","temperature":27}""", call("""{"action":"inspect","server":"weather","uri":"$WEATHER_URI"}""")["reading"].toString())
        val stations = call("""{"action":"inspect","server":"weather","uri":"$AIR_URI"}""")
        assertEquals("""[{"aqi":42,"station":0},{"aqi":58,"station":1}]""", stations["readings"].toString())
        assertTrue(stations["message"]!!.jsonPrimitive.content.contains("where"))
        // A tool is inspected by calling it once, with the arguments the watch will use.
        val fare = call("""{"action":"inspect","server":"$KIWI","tool":"search-flight","arguments":{"flyFrom":"SZX","flyTo":"CTU"}}""")
        assertEquals("search-flight" to """{"currency":"CNY","itineraries":[{"price":803.0}]}""", fare["tool"]!!.jsonPrimitive.content to fare["reading"].toString())
        assertTrue(call("""{"action":"inspect","server":"$KIWI","tool":"feedback-to-devs"}""")["message"]!!.jsonPrimitive.content.contains("不是只读"))
        val rain = call(
            """{"action":"create","title":"下雨提醒","watch":{"server":"weather","uri":"$WEATHER_URI","field":"condition","op":"contains","value":"雨",""" +
                """"text":"深圳下雨时"},"actions":[{"tool":"mcp__tools_json__send_notification",""" +
                """"arguments":{"text":"下雨了，记得带伞"},"label":"发带伞通知"}]}""",
        )
        assertEquals(listOf("深圳下雨时，每次", "发带伞通知"), listOf(rain["when"]!!.jsonPrimitive.content, rain["actions"]!!.jsonPrimitive.content))
        val saved = book.plans().single()
        assertTrue(saved.trigger.repeats())
        assertEquals("触发时自动：发带伞通知", planDoes(saved))
        val refused = call("""{"action":"create","title":"下雨发短信","watch":{"server":"weather","uri":"$WEATHER_URI","field":"condition","op":"contains","value":"雨"},"actions":[{"tool":"mcp__tools_json__send_sms"}]}""")
        assertEquals("计划里不能自动执行 send_sms（发短信）", refused["message"]!!.jsonPrimitive.content)
        // An update can change the actions alone.
        val updated = call("""{"action":"update","id":"${saved.id}","actions":[{"server":"tools_json","tool":"play_media","arguments":{"keyword":"雨天"},"label":"放雨天的歌"}]}""")
        assertEquals("放雨天的歌", updated["actions"]!!.jsonPrimitive.content)
        assertEquals(1, book.plans().size)
    }

    /** Weather resources, Kiwi.com's search tool and the example tools, as loaded servers would offer them. */
    private fun watchHost(book: DebugPlanBook) = object : DebugPlanHost(null) {
        override fun <T> write(change: (DebugPlanBook) -> T): T = change(book)
        override fun plans(): List<DebugPlan> = book.plans()
        override suspend fun resources(): List<DebugMcpResource> =
            listOf(resource(WEATHER_URI, "深圳实时天气"), resource(AIR_URI, "深圳空气质量"), resource("hf://trending", "热门模型", server = "huggingface"))
        override suspend fun readings(server: String, uri: String): List<JsonObject>? = when (uri) {
            WEATHER_URI -> listOf(data("""{"condition":"多云","temperature":27}"""))
            AIR_URI -> listOf(data("""{"aqi":42,"station":0}"""), data("""{"aqi":58,"station":1}"""))
            else -> null
        }
        override suspend fun toolReading(server: String, tool: String, arguments: JsonObject): JsonObject? =
            if (server == KIWI && tool == "search-flight") data("""{"currency":"CNY","itineraries":[{"price":803.0}]}""") else null
        override suspend fun tools(server: String): List<ToolDescriptor> = when (server) {
            "tools_json" -> CATALOG
            KIWI -> KIWI_CATALOG
            else -> emptyList()
        }
        override fun policy(): DebugAutomationPolicy = policy
        override fun serverId(raw: String): String = raw
        override fun now(): Long = 0L
        override fun zone(): ZoneId = ZoneId.of("Asia/Shanghai")
    }

    private fun plan(trigger: DebugPlanTrigger) = DebugPlan("p1", "计划", "说点什么", null, trigger, enabled = true, createdAtMillis = 0L)

    private fun watch(uri: String, field: String, op: String, value: JsonPrimitive?, mode: String? = null, where: Map<String, JsonPrimitive> = emptyMap()) =
        resolvePlanWatch(DebugPlanWatchAsk("weather", uri, field, op, value, null, where = where, mode = mode), repeatByDefault = true).getOrThrow()

    private fun data(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun args(json: String): JsonObject = data(json)
}

/** The demo's offline example tools (assets/mcp/tools.json) as a plan sees them, plus one the policy denies. */
private val CATALOG = listOf(
    ToolDescriptor(
        name = "send_notification",
        title = "发通知",
        inputSchemaJson = """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""",
    ),
    ToolDescriptor("play_media", title = "播放媒体", inputSchemaJson = """{"type":"object","properties":{"keyword":{"type":"string"}}}"""),
    ToolDescriptor("send_sms", title = "发短信", inputSchemaJson = """{"type":"object","properties":{}}"""),
)

/** A server on this device is known by its address, so its watches can be read often. */
class DebugLoopbackServerTest {
    @Test
    fun aServerOnThisDeviceIsKnownByItsAddress() {
        assertTrue(isLoopbackUrl("http://127.0.0.1:8790/cabin/mcp"))
        assertTrue(isLoopbackUrl("http://localhost:3000/mcp"))
        assertTrue(isLoopbackUrl("http://[::1]:8790/mcp"))
        assertFalse(isLoopbackUrl("https://127.0.0.1.example.com/mcp"))
        assertFalse(isLoopbackUrl("https://huggingface.co/mcp"))
    }
}
