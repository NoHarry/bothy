package cc.noharry.bothy.playground.headunit
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.playground.PlaygroundStore
import cc.noharry.bothy.playground.mcp.playgroundEndpoints
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
    fun withTheMapFirstThePanelCoversTheDriversThirdAndTheRouteGoesBesideIt() {
        val width = 1500.dp
        val height = 1000.dp
        for (panel in listOf(false, true)) {
            val slots = realSlots(width, height, panel, routeCards = true)
            assertEquals("the map stays whole", width, slots.map.width)
            assertEquals(slots.map.height, slots.bar.y)
        }
        val up = realSlots(width, height, panel = true, routeCards = true)
        val box = up.assistant!!
        assertTrue("${box.width}", box.width > width * 0.3f && box.width < width * 0.34f)
        assertTrue(up.route.x >= box.x + box.width && up.coverLeft >= up.route.x + up.route.width)
        // Listening has no box over the map: it is the bar's corner.
        val down = realSlots(width, height, panel = false, routeCards = false)
        assertNull(down.assistant)
        assertEquals(0.dp, down.coverLeft)
    }
}
