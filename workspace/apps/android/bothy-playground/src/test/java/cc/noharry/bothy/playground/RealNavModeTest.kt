package cc.noharry.bothy.playground

import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real navigation: only the navigation served, nothing simulated; and where things go in either look. */
class RealNavModeTest {
    private var real = true
    private val endpoints = playgroundEndpoints(PlaygroundStore(clock = { 0L }), realNav = { real })

    private fun send(path: String, method: String, params: String = "{}"): JsonObject =
        endpoints.getValue(path).handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}""").jsonObject)!!

    private fun tools(path: String) =
        send(path, "tools/list").getValue("result").jsonObject.getValue("tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }

    @Test
    fun realNavigationServesTheNavigationAloneAndClosesTheHome() {
        val offered = tools("/cabin/mcp")
        assertTrue(offered.toString(), "start_navigation" in offered && "begin_navigation" in offered && "get_route" in offered)
        assertFalse(offered.toString(), "set_climate" in offered || "get_cabin_status" in offered || "set_window" in offered)
        val server = send("/cabin/mcp", "initialize").getValue("result").jsonObject.getValue("serverInfo").jsonObject
        assertEquals("Bothy Playground 车机导航", server.getValue("title").jsonPrimitive.content)
        // A plan watching navigation.arrived reads the same path; nothing simulated is in the state.
        val read = send("/cabin/mcp", "resources/read", """{"uri":"playground://cabin/state"}""").getValue("result").jsonObject
        val state = Json.parseToJsonElement(read.getValue("contents").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content).jsonObject
        assertEquals(setOf("navigation"), state.keys)
        assertTrue(state.getValue("navigation").jsonObject.containsKey("arrived"))
        val refused = send("/cabin/mcp", "tools/call", """{"name":"set_climate","arguments":{"temperature":22}}""")
        assertTrue(refused.toString(), "error" in refused)
        val home = send("/home/mcp", "initialize")
        assertTrue(home.toString(), "error" in home && "result" !in home)
        assertTrue(home.getValue("error").jsonObject.getValue("message").jsonPrimitive.content.contains("真实导航模式"))
    }

    @Test
    fun theSimulatedHeadUnitServesEverythingAgainWhenRealNavigationIsOff() {
        real = false
        assertTrue("set_climate" in tools("/cabin/mcp") && "start_navigation" in tools("/cabin/mcp"))
        assertTrue("result" in send("/home/mcp", "initialize"))
    }

    @Test
    fun inColumns小博TakesTheFirstTwoColumnsOrRetreatsAndTheMapRunsEdgeToEdge() {
        val width = 1600.dp
        val height = 1000.dp
        val idle = realSlots(HeadUnitTheme.COLUMNS, width, height, AssistantShows.NOTHING, routeCards = false)
        assertNull(idle.assistant)
        assertEquals(0.dp, idle.map.x)
        assertEquals(width, idle.map.width)
        val panel = realSlots(HeadUnitTheme.COLUMNS, width, height, AssistantShows.PANEL, routeCards = true)
        val box = panel.assistant!!
        // The car's column (a quarter) and the next one: about half the screen.
        assertTrue("${box.width}", box.width > width * 0.45f && box.width < width * 0.55f)
        assertTrue(panel.map.x >= box.x + box.width && panel.route.x >= box.x + box.width)
        val listening = realSlots(HeadUnitTheme.COLUMNS, width, height, AssistantShows.BAR, routeCards = true)
        assertEquals(0.dp, listening.map.x)
        assertTrue("the route's cards below the bar", listening.route.top >= listening.assistant!!.y + listening.assistant!!.height)
    }

    @Test
    fun withTheMapFirstThePanelCoversTheDriversThirdAndTheRouteGoesBesideIt() {
        val width = 1500.dp
        val height = 1000.dp
        for (shows in AssistantShows.entries) {
            val slots = realSlots(HeadUnitTheme.FULL_MAP, width, height, shows, routeCards = true)
            assertEquals("the map stays whole", width, slots.map.width)
            assertEquals(slots.map.height, slots.bar.y)
        }
        val panel = realSlots(HeadUnitTheme.FULL_MAP, width, height, AssistantShows.PANEL, routeCards = true)
        val box = panel.assistant!!
        assertTrue("${box.width}", box.width > width * 0.3f && box.width < width * 0.34f)
        assertTrue(panel.route.x >= box.x + box.width && panel.coverLeft >= panel.route.x + panel.route.width)
        val listening = realSlots(HeadUnitTheme.FULL_MAP, width, height, AssistantShows.BAR, routeCards = false)
        assertTrue("bottom left", listening.assistant!!.y > height / 2 && listening.assistant!!.x < width / 4)
        assertEquals(0.dp, listening.coverLeft)
    }
}
