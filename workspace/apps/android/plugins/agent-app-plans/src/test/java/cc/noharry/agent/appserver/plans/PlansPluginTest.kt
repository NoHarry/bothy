package cc.noharry.agent.appserver.plans

import cc.noharry.agent.appserver.server.AppServerTesting
import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.FeatureRequests
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.PlanAction
import cc.noharry.agent.appserver.protocol.PlanDraft
import cc.noharry.agent.appserver.protocol.PlanTrigger
import cc.noharry.agent.appserver.protocol.ServerFeatures
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcNotification
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcRequest
import cc.noharry.agent.appserver.protocol.jsonrpc.ProtocolJson
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import cc.noharry.agent.appserver.runtime.RunFacts
import cc.noharry.agent.appserver.server.Delivery
import cc.noharry.agent.appserver.server.PluginAdditions
import cc.noharry.agent.appserver.server.PluginHost
import cc.noharry.agent.appserver.server.PluginSwitches
import cc.noharry.agent.appserver.server.PublishedState
import cc.noharry.agent.appserver.tools.isServiceTool
import cc.noharry.agent.spi.ToolCallContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Plans come only with their plugin, and go with it: the tool and its asking rule, the facts each round, the requests,
 * the sections, the alarm, the start after boot. Turned off, the alarm and the watches are let go and nothing is
 * published; turned on, the book sets them again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PlansPluginTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val delivered = mutableListOf<Delivery>()
    private val host = PluginHost(app, listOf(PlansPlugin()), AllOn(), conversation = { _, delivery -> delivered += delivery })
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms

    @Before
    fun freshBook() = PlanRunner.forgetBook()

    @After
    fun stop() {
        host.setEnabled("plans", false)
        AppServerTesting.reset()
    }

    @Test
    fun withoutThePluginNothingDueFiresOrSetsAnAlarm() {
        PlanRunner.write(app) { it.add("拿快递", "去拿快递", null, PlanTrigger.Once(System.currentTimeMillis() + HOUR), said = null) }
        PlanRunner.check(app)
        assertTrue(alarms.isEmpty())
        assertFalse(PlanRunner.running)
        host.startAll()
        idle()
        assertEquals(1, alarms.size)
        // Turned off: the alarm left is let go, and the alarm's receiver going off meanwhile sets none.
        host.setEnabled("plans", false)
        assertTrue(alarms.isEmpty())
        PlanRunner.check(app)
        assertTrue(alarms.isEmpty())
        // On again: the book sets it again.
        host.setEnabled("plans", true)
        idle()
        assertEquals(1, alarms.size)
    }

    @Test
    fun runningItBringsTheToolTheFactsTheRequestsTheSectionsAndTheStartAfterBoot() {
        host.startAll()
        idle()
        val additions = host.additions
        assertTrue(ServerFeatures.PLANS in additions.offers)
        val requests = listOf(FeatureRequests.PLAN_SET_ENABLED, FeatureRequests.PLAN_DELETE, FeatureRequests.PLAN_CREATE)
        assertEquals(requests.map { it.name }.toSet(), additions.routes.keys)
        assertEquals(listOf(PLAN_TOOL_SERVER), additions.runTools.flatMap { it(facts()) }.map { it.providerId })
        // A subagent's run mounts it too, as before; the subagent's calls are refused as the main agent's alone.
        assertEquals(listOf(PLAN_TOOL_SERVER), additions.runTools.flatMap { it(facts(child = true)) }.map { it.providerId })
        assertTrue(additions.mainAgentOnly(PLAN_TOOL_SERVER))
        assertEquals(1, additions.contextProviders.flatMap { it(facts()) }.size)
        assertTrue(additions.startsOnBoot)
        AppServerTesting.running(additions)
        assertFalse(isServiceTool("mcp__plans__plan_manage"))
        val state = RecordingState()
        host.publishInto(state)
        PlanRunner.write(app) { it.add("带伞", "记得带伞", null, PlanTrigger.Once(System.currentTimeMillis() + HOUR), said = null) }
        idle()
        assertEquals(listOf("带伞"), state.current.plans.map { it.title })
        // Turned off, the plans leave the screens and every addition goes.
        host.setEnabled("plans", false)
        assertTrue(state.current.plans.isEmpty() && state.current.planHistory.isEmpty() && state.current.planFeeds.isEmpty())
        with(host.additions) {
            assertTrue(offers.isEmpty() && routes.isEmpty() && runTools.isEmpty() && contextProviders.isEmpty() && !startsOnBoot)
            assertFalse(mainAgentOnly(PLAN_TOOL_SERVER) || ownsToolServer(PLAN_TOOL_SERVER))
        }
    }

    @Test
    fun makingOrChangingAPlanAsksAndListingDoesNot() {
        host.startAll()
        val acts = { arguments: String -> host.additions.acts(PLAN_TOOL_SERVER, PLAN_TOOL, arguments) }
        listOf("""{"action":"list"}""", """{"action":"signals"}""", """{"action":"inspect","id":"p1"}""", "{}").forEach {
            assertEquals(it, false, acts(it))
        }
        listOf("create", "update", "pause", "resume", "delete").forEach { assertEquals(it, true, acts("""{"action":"$it"}""")) }
    }

    @Test
    fun denyAllKeepsAPlansActionsFromRunning() = runBlocking {
        val light = PlanAction("home", "set_light", JsonObject(emptyMap()), "Light")
        val ran = mutableListOf<String>()
        val lines = runPlanActions(listOf(light), AutomationPolicy(emptyList()), ConfirmationMode.DENY_ALL) { ran += it.tool; null }
        assertEquals(listOf(finding(FindingCode.PLANS_ACTION_DENIED, "Light")), lines)
        assertEquals(emptyList<String>(), ran)
        // Asked about when the plan was made, they run unasked in every other mode.
        listOf(ConfirmationMode.ASK_EVERY_TIME, ConfirmationMode.ALLOW_CONFIRMED_DOMAINS, ConfirmationMode.ALLOW_ALL_SESSION).forEach { mode ->
            runPlanActions(listOf(light), AutomationPolicy(emptyList()), mode) { ran += it.tool; null }
        }
        assertEquals(listOf("set_light", "set_light", "set_light"), ran)
    }

    @Test
    fun aPlanMadeOnThePageIsKeptOrRefusedWithWhy() = runBlocking {
        host.startAll()
        val trigger = PlanTrigger.Repeat(minuteOfDay = 8 * 60, days = setOf(1, 2, 3, 4, 5))
        assertNull(call(FeatureRequests.PLAN_CREATE, PlanDraft("带伞", message = "记得带伞", trigger = trigger)).error)
        val refused = call(FeatureRequests.PLAN_CREATE, PlanDraft(" ", message = "记得带伞", trigger = trigger)).error
        assertEquals(FindingCode.PLANS_PROBLEM_NOT_SAVED.name, refused?.code)
        assertEquals(listOf("带伞"), PlanRunner.book(app).plans().map { it.title })
    }

    @Test
    fun aPlanComingDueIsHandedToTheConversation() {
        host.startAll()
        idle()
        val now = System.currentTimeMillis()
        PlanRunner.write(app) { it.add("拿快递", "去拿快递", null, PlanTrigger.Once(now + DUE_SOON), said = null) }
        PlanRunner.write(app) { it.add("咖啡", "", "点一杯咖啡", PlanTrigger.Once(now + DUE_SOON), said = null) }
        Thread.sleep(DUE_SOON + LOAD_MILLIS)
        PlanRunner.check(app)
        // A reminder is said as written; the running turn, if any, is asked to pass it on.
        val reminder = delivered.single { it.said != null }
        assertEquals("Reminder: 去拿快递", reminder.said)
        assertTrue(reminder.forModel.contains("去拿快递") && !reminder.showCard)
        // A task is a turn the agent runs, on the card.
        val task = delivered.single { it.said == null }
        assertTrue(task.showCard && task.forModel.contains("点一杯咖啡"))
        assertEquals(app.getString(R.string.plans_label), task.title)
    }

    @Test
    fun aRunStillHoldingTheToolAfterItStoppedKeepsNothing() = runBlocking {
        host.startAll()
        idle()
        val tool = host.additions.runTools.flatMap { it(facts()) }.single() as PlanToolProvider
        host.setEnabled("plans", false)
        val made = tool.callTool(PLAN_TOOL, """{"action":"create","title":"晚点","message":"喝水","when":{"in_minutes":30}}""", ToolCallContext("s", "r", "q"))
        assertFalse(made.success)
        assertTrue(PlanRunner.book(app).plans().none { it.title == "晚点" })
    }

    @Test
    fun theClockChangingChecksThePlansAgain() {
        host.startAll()
        idle()
        assertEquals(1, host.additions.clockChanged.size)
        PlanRunner.write(app) { it.add("拿快递", "去拿快递", null, PlanTrigger.Once(System.currentTimeMillis() + HOUR), said = null) }
        host.additions.clockChanged.single().invoke()
        idle()
        assertEquals(1, alarms.size)
    }

    private suspend fun <P, R> call(method: RequestMethod<P, R>, params: P): R {
        val route = checkNotNull(host.additions.routes[method.name]) { "no route ${method.name}" }
        val request = JsonRpcRequest(JsonPrimitive(1), method.name, ProtocolJson.encodeToJsonElement(method.params, params))
        return ProtocolJson.decodeFromJsonElement(method.result, route(request))
    }

    /** The plugin checks the book on a thread of its own, and its sections follow on the main looper. */
    private fun idle() {
        Thread.sleep(LOAD_MILLIS)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun facts(child: Boolean = false) = RunFacts(app as Context, "", child = child, webSearch = false, subagentTypes = emptySet())

    private class RecordingState : PublishedState {
        override var current = ServerState()
        override fun update(change: ServerState.() -> ServerState) { current = current.change() }
        override fun <T> follow(flow: Flow<T>, into: ServerState.(T) -> ServerState) = error("a plugin follows through its own scope")
        override fun broadcast(notification: JsonRpcNotification) = Unit
    }

    private class AllOn : PluginSwitches {
        private val off = mutableSetOf<String>()

        override fun isOn(id: String): Boolean = id !in off

        override fun set(id: String, on: Boolean) {
            if (on) off -= id else off += id
        }
    }

    private companion object {
        const val LOAD_MILLIS = 300L
        const val DUE_SOON = 1_500L
        const val HOUR = 3_600_000L
    }
}
