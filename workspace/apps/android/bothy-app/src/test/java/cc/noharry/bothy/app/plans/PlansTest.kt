package cc.noharry.bothy.app.plans
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.RuntimeContextRequest
import cc.noharry.agent.kernel.api.RuntimeContextScalar
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolPrefetchRequest
import cc.noharry.bothy.app.a2a.describe
import cc.noharry.bothy.app.a2ui.valueText
import cc.noharry.bothy.app.console.ConsoleView
import cc.noharry.bothy.app.console.consoleViewFor
import cc.noharry.bothy.app.console.saveFileAtomically
import cc.noharry.bothy.app.console.setAsideUnreadable
import cc.noharry.bothy.app.mcp.McpResource
import cc.noharry.bothy.app.mcp.resourceData
import cc.noharry.bothy.app.mcp.resourceReadings
import cc.noharry.bothy.app.mcp.toolReading
import cc.noharry.bothy.app.model.describe
import cc.noharry.bothy.app.overlay.clientMode
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

@OptIn(ExperimentalAgentRuntimeApi::class)
class PlansTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    /** Thursday 2026-10-15 10:00. */
    private var clock = at("2026-10-15T10:00")
    private val dir: File = Files.createTempDirectory("plans").toFile()
    private val book = PlanBook(File(dir, "plans.json")) { clock }
    private var resources = listOf(resource(WEATHER_URI, "深圳实时天气"), resource(AIR_URI, "深圳空气质量"))
    private val host = object : PlanHost(null) {
        override fun <T> write(change: (PlanBook) -> T): T = change(book)
        override fun plans(): List<Plan> = book.plans()
        override suspend fun resources(): List<McpResource> = resources
        override fun policy(): AutomationPolicy = AutomationPolicy(emptyList())
        override suspend fun tools(server: String): List<ToolDescriptor> = if (server == KIWI) KIWI_CATALOG else emptyList()
        override fun now(): Long = clock
        override fun zone(): ZoneId = zone
    }
    private val tool = PlanToolProvider(host)

    @Test
    fun theHostTurnsWordsIntoTimes() {
        assertEquals(PlanTrigger.Once(at("2026-10-15T12:00")), resolve(PlanWhen(inMinutes = 120)))
        assertEquals(PlanTrigger.Once(at("2026-10-15T16:30")), resolve(PlanWhen(time = "16:30")))
        // A time already past today is tomorrow's.
        assertEquals(PlanTrigger.Once(at("2026-10-16T08:00")), resolve(PlanWhen(time = "8:00")))
        assertEquals(PlanTrigger.Once(at("2026-10-20T08:00")), resolve(PlanWhen(date = "2026-10-20", time = "08:00")))
        assertEquals(PlanTrigger.Repeat(8 * 60, setOf(1, 2, 3, 4, 5)), resolve(PlanWhen(time = "08:00", repeat = "weekdays")))
        assertEquals(PlanTrigger.Repeat(21 * 60 + 30, setOf(1, 3)), resolve(PlanWhen(time = "21:30", repeat = "weekly", days = listOf(1, 3))))
        assertEquals("这个时间已经过了", refusal(PlanWhen(date = "2026-10-15", time = "09:00")))
        assertTrue(refusal(PlanWhen(time = "08:00", repeat = "weekly"))!!.startsWith("weekly 需要 days"))
        assertTrue(refusal(PlanWhen(inMinutes = 30, repeat = "daily"))!!.startsWith("in_minutes 只用于"))
        assertTrue(refusal(PlanWhen(time = "25:00"))!!.startsWith("需要 in_minutes"))
        assertTrue(refusal(PlanWhen(date = "10月20日", time = "08:00"))!!.startsWith("date 要写成"))
    }

    @Test
    fun triggersReadAsAPersonWouldSayThem() {
        assertEquals("今天 16:30", PlanTrigger.Once(at("2026-10-15T16:30")).describe(clock, zone))
        assertEquals("明天 08:00", PlanTrigger.Once(at("2026-10-16T08:00")).describe(clock, zone))
        assertEquals("10月20日 周二 08:00", PlanTrigger.Once(at("2026-10-20T08:00")).describe(clock, zone))
        assertEquals("工作日 08:00", PlanTrigger.Repeat(480, setOf(1, 2, 3, 4, 5)).describe(clock, zone))
        assertEquals("每天 07:05", PlanTrigger.Repeat(425, (1..7).toSet()).describe(clock, zone))
        assertEquals("每周一、周三 21:30", PlanTrigger.Repeat(1290, setOf(3, 1)).describe(clock, zone))
        assertEquals("深圳气温超过 30 度 持续 5 秒，一次", temperatureWatch(30.0, hold = 5).describe(clock, zone))
        assertEquals("空气质量指数低于 50，每次", airWatch(50.0, repeat = true).describe(clock, zone))
        assertEquals("最低价低于 800 元，一次", fareWatch(800.0).describe(clock, zone))
        assertEquals("1 小时 20 分钟后", untilText(80 * 60_000L))
        assertEquals("3 分钟后", untilText(150_000L))
        assertEquals("2 天后", untilText(2 * 24 * 3_600_000L + 1))
    }

    @Test
    fun repeatsFindTheirNextAndLastOccurrence() {
        val weekdays = PlanTrigger.Repeat(480, setOf(1, 2, 3, 4, 5))
        // Friday 20:00 → Monday 08:00; Saturday noon looks back to Friday 08:00.
        assertEquals(at("2026-10-19T08:00"), nextRepeat(weekdays, at("2026-10-16T20:00"), zone))
        assertEquals(at("2026-10-16T08:00"), lastRepeat(weekdays, at("2026-10-17T12:00"), zone))
        assertEquals(at("2026-10-15T08:00"), lastRepeat(weekdays, at("2026-10-15T08:00"), zone))
    }

    @Test
    fun whatIsDueFiresLateOrIsMissed() {
        val once = plan(PlanTrigger.Once(at("2026-10-15T09:59")))
        assertEquals(PlanDue.Fire(at("2026-10-15T09:59"), late = false), once.dueAt(clock, zone))
        assertEquals(PlanDue.Fire(at("2026-10-15T09:59"), late = true), once.dueAt(at("2026-10-15T10:30"), zone))
        assertEquals(PlanDue.Miss(at("2026-10-15T09:59")), once.dueAt(at("2026-10-15T12:30"), zone))
        assertNull(once.copy(enabled = false).dueAt(clock, zone))
        assertNull(plan(PlanTrigger.Once(at("2026-10-15T11:00"))).dueAt(clock, zone))
        val daily = plan(PlanTrigger.Repeat(9 * 60 + 55, (1..7).toSet()), createdAt = at("2026-10-14T00:00"))
        assertEquals(PlanDue.Fire(at("2026-10-15T09:55"), late = true), daily.dueAt(clock, zone))
        assertEquals(PlanDue.Miss(at("2026-10-15T09:55")), daily.dueAt(at("2026-10-15T11:00"), zone))
        // An occurrence handled once is not handled again, nor one before the plan existed.
        assertNull(daily.copy(lastDueAtMillis = at("2026-10-15T09:55")).dueAt(clock, zone))
        assertNull(daily.copy(createdAtMillis = at("2026-10-15T09:58")).dueAt(clock, zone))
        assertEquals(at("2026-10-16T09:55"), daily.nextDueMillis(clock, zone))
        // A watch never comes due by the clock.
        assertNull(plan(temperatureWatch(30.0)).nextDueMillis(clock, zone))
    }

    @Test
    fun aPlanSwitchedBackOnOrGivenANewTimeStartsFromThen() {
        val daily = plan(PlanTrigger.Repeat(9 * 60 + 55, (1..7).toSet()), createdAt = at("2026-10-14T00:00"))
        // Paused over 09:55 and switched on at 10:00: nothing is reminded late, and tomorrow comes as usual.
        assertNull(daily.copy(enabled = false).resumed(clock).dueAt(clock, zone))
        assertEquals(at("2026-10-16T09:55"), daily.resumed(clock).nextDueMillis(clock, zone))
        // A one-off whose time passed while paused is given up as such, not reminded late.
        val once = plan(PlanTrigger.Once(at("2026-10-15T09:59"))).copy(enabled = false)
        assertEquals(PlanDue.Miss(at("2026-10-15T09:59"), paused = true), once.resumed(clock).dueAt(clock, zone))
        // A new time: the occurrence before it is not reported missed, and the plan counts from zero.
        val fired = daily.copy(lastDueAtMillis = at("2026-10-14T09:55"), lastFiredAtMillis = at("2026-10-14T09:55"), firedCount = 3)
        val retimed = fired.retimed(PlanTrigger.Repeat(9 * 60 + 50, (1..7).toSet()), clock)
        assertNull(retimed.dueAt(at("2026-10-15T10:30"), zone))
        assertEquals(null to 0, retimed.lastFiredAtMillis to retimed.firedCount)
        // A mark from a clock that ran ahead (booted at 2027 before the time sync) marks nothing once the clock is right.
        assertEquals(PlanDue.Fire(at("2026-10-15T09:55"), late = true), daily.copy(lastDueAtMillis = at("2027-01-01T09:55")).dueAt(clock, zone))
        // Fired at a clock 3 minutes fast, then set back: yesterday's is not reported missed, and today's does not fire twice.
        val early = plan(PlanTrigger.Repeat(8 * 60, (1..7).toSet()), createdAt = at("2026-10-01T00:00")).copy(lastDueAtMillis = at("2026-10-15T08:00"))
        assertNull(early.dueAt(at("2026-10-15T07:58"), zone))
        assertNull(early.dueAt(at("2026-10-15T08:01"), zone))
        assertTrue(planReminderSteer("提醒你：该喝水了").endsWith("「提醒你：该喝水了」"))
    }

    @Test
    fun plansAreSavedWholeOnOneLineWithoutSecrets() {
        val plan = book.add("拿\n快递", "该去\r\n拿快递了", null, PlanTrigger.Once(at("2026-10-15T16:30")), "提醒我拿快递，柜子密码是 1234").getOrThrow()
        assertEquals("拿 快递" to "该去 拿快递了", plan.title to plan.message)
        assertNull(plan.said)
        assertEquals("拿 快递", book.update(plan.id) { it.copy(title = "拿\n快递") }!!.title)
        val file = File(dir, "x.json")
        saveFileAtomically(file, "{}")
        assertEquals("{}", file.readText())
        assertFalse(File(dir, "x.json.tmp").exists())
        setAsideUnreadable(file)
        assertTrue(!file.exists() && File(dir, "x.json.corrupt").isFile)
    }

    @Test
    fun aOneOffPastItsTimeIsNotResumed() = runBlocking {
        val id = call("""{"action":"create","title":"拿快递","message":"该去拿快递了","when":{"in_minutes":30}}""")["id"]!!.jsonPrimitive.content
        call("""{"action":"pause","id":"$id"}""")
        clock += 31 * 60_000L
        assertTrue(message("""{"action":"resume","id":"$id"}""").startsWith("这个计划的时间在暂停期间已经过了"))
        // A repeat resumed by voice does not report what came due while it was paused.
        val daily = call("""{"action":"create","title":"工牌","message":"带工牌","when":{"time":"10:40","repeat":"daily"}}""")["id"]!!.jsonPrimitive.content
        call("""{"action":"pause","id":"$daily"}""")
        clock += 10 * 60_000L
        call("""{"action":"resume","id":"$daily"}""")
        assertNull(book.plans().first { it.id == daily }.dueAt(clock + 60_000L, zone))
    }

    @Test
    fun theBookKeepsPlansAndTheirFiringsInOneFile() {
        val once = book.add("拿快递", "该去拿快递了", null, PlanTrigger.Once(at("2026-10-15T16:30")), "两小时后提醒我拿快递").getOrThrow()
        val repeat = book.add("工牌", "带工牌", null, PlanTrigger.Repeat(480, setOf(1, 2, 3, 4, 5)), null).getOrThrow()
        val notify = PlanAction("tools_json", "send_notification", Json.parseToJsonElement("""{"text":"空气不错，适合开窗"}""").jsonObject, "发通知")
        val air = book.add("开窗", "空气不错", null, airWatch(50.0, repeat = true), null, listOf(notify)).getOrThrow()
        val fare = book.add("机票", "去成都的机票降价了", null, fareWatch(800.0), null).getOrThrow()
        book.update(repeat.id) { it.copy(enabled = false) }
        book.record(PlanFiring(once.id, once.title, clock, "已提醒"))
        val reread = PlanBook(File(dir, "plans.json"))
        assertEquals(book.plans(), reread.plans())
        assertEquals(listOf("已提醒"), reread.history().map { it.note })
        assertEquals(listOf(true, false, true, true), reread.plans().map { it.enabled })
        assertEquals(air.trigger to listOf(notify), reread.plans()[2].trigger to reread.plans()[2].actions)
        // A watch on a tool keeps the tool and its arguments.
        assertEquals(fare.trigger, reread.plans().last().trigger)
        assertTrue(book.add("", "x", null, once.trigger, null).isFailure)
        assertTrue(book.add("没内容", "", null, once.trigger, null).isFailure)
        // An automation needs no words: its actions are what it does.
        assertTrue(book.add("只做事", "", null, once.trigger, null, listOf(notify)).isSuccess)
        assertEquals(once, book.remove(once.id))
        assertNull(book.remove(once.id))
        val broken = """{"plans":[{"id":"p1","title":"好的","trigger":{"type":"once","at":5}},{"id":"p2","title":"坏的","trigger":{"type":"tide"}}]}"""
        assertEquals(listOf("p1"), decodePlanFile(broken).first.map { it.id })
        // A watch with neither a resource nor a tool, or no server, is dropped.
        val unreadable = """{"plans":[{"id":"p3","title":"空","message":"x","trigger":{"type":"watch","server":"weather","uri":"","field":"t","op":"above","value":1}},""" +
            """{"id":"p4","title":"空","message":"x","trigger":{"type":"watch","uri":"$WEATHER_URI","field":"t","op":"above","value":1}}]}"""
        assertEquals(emptyList<Plan>(), decodePlanFile(unreadable).first)
    }

    @Test
    fun aFullBookMakesRoomOnlyFromEndedPlans() {
        repeat(MAX_PLANS) { book.add("计划 $it", "说点什么", null, PlanTrigger.Once(at("2026-10-16T08:00") + it), null) }
        assertTrue(book.add("再一个", "说点什么", null, PlanTrigger.Once(at("2026-10-17T08:00")), null).isFailure)
        val first = book.plans().first()
        book.update(first.id) { it.copy(end = PlanEnd.DONE) }
        assertTrue(book.add("再一个", "说点什么", null, PlanTrigger.Once(at("2026-10-17T08:00")), null).isSuccess)
        assertFalse(book.plans().any { it.id == first.id })
    }

    @Test
    fun aReadingComesOutOfAResourcesAnswer() {
        // A server that wraps each reading as {"data":…,"ts":…,"uri":…}: the data is the reading.
        val answer = """{"id":11,"jsonrpc":"2.0","result":{"contents":[{"mimeType":"application/json",""" +
            """"text":"{\"data\":{\"temperature\":27.5},\"ts\":1792037417814,\"uri\":\"$WEATHER_URI\"}","uri":"$WEATHER_URI"}]}}"""
        assertEquals("""{"temperature":27.5}""", resourceData(Json.parseToJsonElement(answer).jsonObject).toString())
        // Another MCP server's reading is its text as it is.
        val plain = """{"result":{"contents":[{"uri":"x://y","text":"{\"level\":3}"}]}}"""
        assertEquals("""{"level":3}""", resourceData(Json.parseToJsonElement(plain).jsonObject).toString())
        val unavailable = """{"id":12,"jsonrpc":"2.0","error":{"code":-32006,"message":"unavailable"}}"""
        assertNull(resourceData(Json.parseToJsonElement(unavailable).jsonObject))
        assertEquals(emptyList<JsonObject>(), resourceReadings(Json.parseToJsonElement(unavailable).jsonObject))
    }

    @Test
    fun aResourceMayAnswerOneReadingPerItem() {
        fun content(station: Int) = """{"mimeType":"application/json","text":"{\"data\":{\"aqi\":-1000,\"station\":$station},""" +
            """\"ts\":1792404402111,\"uri\":\"$AIR_URI\"}","uri":"$AIR_URI"}"""
        val answer = """{"id":12,"jsonrpc":"2.0","result":{"contents":[${(0..2).joinToString(",") { content(it) }}]}}"""
        val readings = resourceReadings(Json.parseToJsonElement(answer).jsonObject)

        assertEquals(listOf(0, 1, 2), readings.map { it["station"]!!.jsonPrimitive.int })
        assertEquals(readings.first(), resourceData(Json.parseToJsonElement(answer).jsonObject))
    }

    @Test
    fun aToolsAnswerReadsAsItsStructuredContentElseItsJsonText() {
        // As Kiwi.com's open search-flight answered on 2026-10-21 (trimmed): structuredContent and the same JSON as text.
        val itineraries = """{"currency":"CNY","itineraries":[{"price":803.0,"outbound":{"from":"SZX","to":"TFU","stops":0}}]}"""
        val structured = """{"jsonrpc":"2.0","id":3,"result":{"isError":false,"structuredContent":$itineraries,"content":[{"type":"text","text":"ignored"}]}}"""
        val reading = toolReading(Json.parseToJsonElement(structured).jsonObject)!!
        assertEquals("803", valueText(watchReading(reading, "itineraries.0.price")!!))
        assertEquals("SZX", watchReading(reading, "itineraries.0.outbound.from")!!.content)
        assertNull(watchReading(reading, "itineraries.5.price"))
        val text = """{"result":{"content":[{"type":"text","text":${JsonPrimitive(itineraries)}}]}}"""
        assertEquals(reading, toolReading(Json.parseToJsonElement(text).jsonObject))
        assertNull(toolReading(Json.parseToJsonElement("""{"result":{"isError":true,"content":[{"type":"text","text":"{}"}]}}""").jsonObject))
        assertNull(toolReading(Json.parseToJsonElement("""{"result":{"content":[{"type":"text","text":"晴，27 度"}]}}""").jsonObject))
    }

    @Test
    fun aPlanComingDueSaysItsWordsOrHandsTheTaskOver() {
        val parcel = plan(PlanTrigger.Once(at("2026-10-15T09:00"))).copy(title = "拿快递", message = "该去拿快递了")
        assertEquals(PlanDelivery("计划 · 拿快递", "提醒你：该去拿快递了", null), planDelivery(parcel, parcel.dueAtOnce(), false, null, clock, zone))
        assertEquals("你之前让我 09:00 提醒你：该去拿快递了", planDelivery(parcel, parcel.dueAtOnce(), true, null, clock, zone).reply)
        // A number's words say what was reached, unless the model already did: not twice, and not missing.
        val air = plan(airWatch(50.0)).copy(message = "空气质量指数低于50了，适合开窗通风。")
        assertEquals("空气质量指数低于50了，适合开窗通风。", planDelivery(air, clock, false, "48", clock, zone).reply)
        assertEquals("深圳气温超过 30 度了，注意防暑", planDelivery(plan(temperatureWatch(30.0)).copy(message = "注意防暑"), clock, false, "31", clock, zone).reply)
        val weather = plan(PlanTrigger.Repeat(480, (1..7).toSet())).copy(title = "天气", message = "", instruction = "播报今天的天气")
        val task = planDelivery(weather, clock, false, null, clock, zone)
        assertEquals("到点了，我来办：播报今天的天气", task.reply)
        assertTrue(task.modelText!!, task.modelText!!.contains("现在去办：播报今天的天气") && task.modelText!!.contains("每天 08:00"))
        val outing = planDelivery(air.copy(message = "", instruction = "推荐几个适合户外的去处"), clock, false, "48", clock, zone)
        assertTrue(outing.modelText!!, outing.modelText!!.contains("（现在 aqi 是 48）"))
    }

    @Test
    fun theToolMakesChangesAndListsPlans() = runBlocking {
        val made = call("""{"action":"create","title":"拿快递","message":"该去拿快递了","when":{"in_minutes":120},"said":"两小时后提醒我拿快递"}""")
        assertEquals("今天 12:00", made["when"]!!.jsonPrimitive.content)
        assertEquals("今天 12:00", made["next"]!!.jsonPrimitive.content)
        val id = made["id"]!!.jsonPrimitive.content
        assertEquals("已暂停", call("""{"action":"pause","id":"$id"}""")["status"]!!.jsonPrimitive.content)
        assertEquals("进行中", call("""{"action":"resume","id":"$id"}""")["status"]!!.jsonPrimitive.content)
        book.update(id) { it.copy(end = PlanEnd.DONE) }
        assertTrue(message("""{"action":"resume","id":"$id"}""").startsWith("这个计划已完成了"))
        // A new time revives an ended plan.
        val retimed = call("""{"action":"update","id":"$id","when":{"time":"18:00"}}""")
        assertEquals("进行中" to "今天 18:00", retimed["status"]!!.jsonPrimitive.content to retimed["when"]!!.jsonPrimitive.content)
        assertEquals("需要 when（时间）或 watch（服务的状态）其中一个", message("""{"action":"create","title":"空","message":"x"}"""))
        assertEquals("when 和 watch 只能给一个", message("""{"action":"create","title":"两个","message":"x","when":{"in_minutes":5},"watch":{"server":"weather","uri":"$WEATHER_URI","field":"temperature","op":"above","value":30}}"""))
        // Only what a loaded server lists now can be watched.
        assertTrue(message("""{"action":"create","title":"高温","message":"防暑","watch":{"server":"weather","uri":"weather://nowhere","field":"temperature","op":"above","value":30}}""").contains("用 signals 查"))
        resources = emptyList()
        assertTrue(message("""{"action":"create","title":"高温","message":"防暑","watch":{"server":"weather","uri":"$WEATHER_URI","field":"temperature","op":"above","value":30}}""").contains("现在没有"))
        resources = listOf(resource(WEATHER_URI, "深圳实时天气"))
        call("""{"action":"create","title":"高温","message":"防暑","watch":{"server":"weather","uri":"$WEATHER_URI","field":"temperature","op":"above","value":30,"text":"深圳气温超过 30 度"}}""")
        // A server with tools only: its read-only query tool is called on the plan's pace, never faster than every 5 minutes.
        val fare = """"watch":{"server":"$KIWI","tool":"mcp__${KIWI}__search-flight","arguments":$FARE_ARGUMENTS,"field":"itineraries.0.price","op":"below","value":800,"text":"最低价低于 800 元""""
        assertTrue(message("""{"action":"create","title":"机票","message":"降价了",${fare.replace("元\"", "元\",\"poll_seconds\":60")}}}""").contains("最快 300 秒"))
        assertEquals("最低价低于 800 元，一次", call("""{"action":"create","title":"机票","message":"降价了",$fare}}""")["when"]!!.jsonPrimitive.content)
        val saved = book.plans().last().trigger as PlanTrigger.Watch
        assertEquals(listOf("search-flight", "", "1800000"), listOf(saved.watch.tool, saved.watch.uri, saved.watch.pollMillis.toString()))
        // Not a tool that changes anything, nor one the server does not list.
        assertTrue(message("""{"action":"create","title":"x","message":"x","watch":{"server":"$KIWI","tool":"feedback-to-devs","field":"ok","op":"changes"}}""").contains("不是只读"))
        assertTrue(message("""{"action":"create","title":"x","message":"x","watch":{"server":"$KIWI","tool":"book-flight","field":"ok","op":"changes"}}""").contains("现在没有"))
        assertEquals("watch 的 uri 和 tool 只能写一个", message("""{"action":"create","title":"x","message":"x","watch":{"server":"$KIWI","uri":"$WEATHER_URI","tool":"search-flight","field":"ok","op":"changes"}}"""))
        assertEquals(listOf("拿快递", "高温", "机票"), call("""{"action":"list"}""")["plans"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content })
        call("""{"action":"delete","id":"$id"}""")
        assertEquals(listOf("高温", "机票"), book.plans().map { it.title })
        assertTrue(message("""{"action":"delete","id":"$id"}""").startsWith("没有这个计划"))
    }

    @Test
    fun todayAndThePlansGoingReachTheModel() = runBlocking {
        book.add("工牌", "带工牌", null, PlanTrigger.Repeat(480, setOf(1, 2, 3, 4, 5)), null)
        val soon = book.add("拿快递", "该去拿快递了", null, PlanTrigger.Once(at("2026-10-15T12:00")), null).getOrThrow()
        val done = book.add("旧的", "x", null, PlanTrigger.Once(at("2026-10-15T11:00")), null).getOrThrow()
        book.update(done.id) { it.copy(end = PlanEnd.DONE) }
        val request = RuntimeContextRequest("run", "session", "request", null, "取消拿快递的提醒", clock, null)
        val blocks = PlanContext({ book.plans() }, { zone }).provide(request).blocks
        assertEquals(RuntimeContextScalar.Text("2026-10-15 周四"), blocks.first().data.single().value)
        val listed = blocks.last().data
        assertEquals(listOf(soon.id, book.plans().first().id), listed.map { it.id })
        assertEquals(RuntimeContextScalar.Text("拿快递｜今天 12:00｜进行中"), listed.first().value)
        fun offered(words: String) = tool.localToolNames(ToolPrefetchRequest(words, emptyList(), ToolCallContext("s", "r", "q"), 24))
        assertEquals(listOf(PLAN_TOOL), offered("两小时后提醒我拿快递"))
        assertEquals(listOf(PLAN_TOOL), offered("去成都的机票低于800的时候提醒我"))
        assertEquals(listOf(PLAN_TOOL), offered("下雨的时候帮我发个通知"))
        assertEquals(emptyList<String>(), offered("导航去广州塔"))
        assertEquals(emptyList<String>(), offered("打开日历"))
        assertEquals(emptyList<String>(), offered("今天会下雨吗"))
    }

    @Test
    fun thePlansTabLeavesTheClientModeAlone() {
        assertEquals(ConsoleView.PLANS, consoleViewFor("PLANS", clientMode = true))
        assertEquals(ConsoleView.PLANS, consoleViewFor("PLANS", clientMode = false))
        assertEquals(ConsoleView.A2A, consoleViewFor("DEBUG", clientMode = true))
        assertEquals(ConsoleView.DEBUG, consoleViewFor("A2A", clientMode = false))
    }

    private fun at(local: String): Long = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    private fun resolve(ask: PlanWhen): PlanTrigger = resolvePlanWhen(ask, clock, zone).getOrThrow()

    private fun refusal(ask: PlanWhen): String? = resolvePlanWhen(ask, clock, zone).exceptionOrNull()?.message

    private fun plan(trigger: PlanTrigger, createdAt: Long = at("2026-10-15T08:00")) =
        Plan("p1", "计划", "说点什么", null, trigger, enabled = true, createdAtMillis = createdAt)

    private fun Plan.dueAtOnce(): Long = (trigger as PlanTrigger.Once).atMillis

    private suspend fun call(arguments: String): JsonObject {
        val result = tool.callTool(PLAN_TOOL, arguments, ToolCallContext("s", "r", "q"))
        assertTrue(result.resultJson, result.success)
        return Json.parseToJsonElement(result.resultJson).jsonObject
    }

    private suspend fun message(arguments: String): String {
        val result = tool.callTool(PLAN_TOOL, arguments, ToolCallContext("s", "r", "q"))
        assertFalse(result.resultJson, result.success)
        return Json.parseToJsonElement(result.resultJson).jsonObject["message"]!!.jsonPrimitive.content
    }
}

internal const val WEATHER_URI = "weather://shenzhen/now"
internal const val AIR_URI = "weather://shenzhen/air"
internal const val KIWI = "kiwi"
private const val FARE_ARGUMENTS = """{"flyFrom":"SZX","flyTo":"CTU","departureDate":"07/11/2026","currency":"CNY"}"""

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
