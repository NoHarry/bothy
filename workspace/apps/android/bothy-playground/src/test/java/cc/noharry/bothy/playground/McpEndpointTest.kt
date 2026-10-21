package cc.noharry.bothy.playground

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

/** The two servers as an MCP client sees them: the handshake, the tool list, calls that change the world, resources. */
class McpEndpointTest {
    private val store = PlaygroundStore(clock = { 0L })
    private val endpoints = playgroundEndpoints(store)
    private val cabin = endpoints.getValue("/cabin/mcp")
    private val home = endpoints.getValue("/home/mcp")

    private fun rpc(endpoint: McpEndpoint, method: String, params: String = "{}"): JsonObject =
        endpoint.handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":7,"method":"$method","params":$params}""").jsonObject)!!

    private fun call(endpoint: McpEndpoint, tool: String, arguments: String): JsonObject =
        rpc(endpoint, "tools/call", """{"name":"$tool","arguments":$arguments}""").getValue("result").jsonObject

    private fun JsonObject.text() = getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content

    private fun JsonObject.isError() = getValue("isError").jsonPrimitive.content == "true"

    @Test
    fun theHandshakeAnswersTheClientsVersionAndANotificationGetsNoAnswer() {
        val result = rpc(cabin, "initialize", """{"protocolVersion":"2025-03-26","capabilities":{}}""").getValue("result").jsonObject
        assertEquals("2025-03-26", result.getValue("protocolVersion").jsonPrimitive.content)
        assertEquals("bothy-playground-cabin", result.getValue("serverInfo").jsonObject.getValue("name").jsonPrimitive.content)
        val unknown = rpc(cabin, "initialize", """{"protocolVersion":"1999-01-01"}""").getValue("result").jsonObject
        assertEquals(McpEndpoint.SUPPORTED_VERSIONS.first(), unknown.getValue("protocolVersion").jsonPrimitive.content)
        assertNull(cabin.handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").jsonObject))
        assertEquals(McpEndpoint.METHOD_NOT_FOUND, rpc(cabin, "sampling/createMessage").errorCode())
    }

    @Test
    fun theStatusToolsAreReadOnlyAndOnlyTheFrontDoorIsDestructive() {
        val tools = listOf(cabin, home).flatMap { rpc(it, "tools/list").getValue("result").jsonObject.getValue("tools").jsonArray }.map { it.jsonObject }
        fun hint(name: String, hint: String) = tools.filter { it.name() == name }.map { it.getValue("annotations").jsonObject.getValue(hint).jsonPrimitive.content }
        assertEquals(listOf("true"), hint("get_cabin_status", "readOnlyHint"))
        assertEquals(listOf("true"), hint("get_home_status", "readOnlyHint"))
        assertEquals(listOf("false"), hint("set_seat", "readOnlyHint"))
        assertEquals(tools.filter { it.getValue("annotations").jsonObject.getValue("destructiveHint").jsonPrimitive.content == "true" }.map { it.name() }, listOf("set_door_lock"))
        tools.forEach { assertEquals(it.name(), "object", it.getValue("inputSchema").jsonObject.getValue("type").jsonPrimitive.content) }
    }

    @Test
    fun aCallChangesTheWorldReadsChineseNamesAndIsLogged() {
        val seat = call(cabin, "set_seat", """{"seat":"主驾","heat":"2"}""")
        assertFalse(seat.isError())
        assertEquals("主驾座椅加热 2 档。", seat.text())
        assertEquals(2, store.read().cabin.seat(Seat.DRIVER).heat)
        assertEquals(2, seat.getValue("structuredContent").jsonObject.getValue("seats").jsonObject.getValue("driver").jsonObject.getValue("heat").jsonPrimitive.content.toInt())
        // Ventilation on the same seat turns heating off.
        call(cabin, "set_seat", """{"seat":"driver","ventilation":1}""")
        assertEquals(SeatComfort(heat = 0, ventilation = 1), store.read().cabin.seat(Seat.DRIVER))
        val record = store.calls.value.first()
        assertEquals("cabin" to "set_seat", record.server to record.tool)
        assertEquals(setOf(Highlight.seat(Seat.DRIVER)), record.highlights)
    }

    @Test
    fun aRefusedCallIsAToolErrorThatChangesNothing() {
        val kitchen = call(home, "set_climate", """{"room":"厨房","power":true}""")
        assertTrue(kitchen.isError())
        assertEquals("厨房没有空调", kitchen.text())
        val colour = call(cabin, "set_ambient_light", """{"color":"彩虹"}""")
        assertTrue(colour.isError() && colour.text().contains("森林绿"))
        assertEquals(World(), store.read())
        assertTrue(store.calls.value.isEmpty())
        assertEquals(McpEndpoint.INVALID_PARAMS, rpc(home, "tools/call", """{"name":"fly"}""").errorCode())
    }

    @Test
    fun theStateResourceIsJsonAPlanCanWatch() {
        call(home, "run_scene", """{"scene":"我要睡了"}""")
        val listed = rpc(home, "resources/list").getValue("result").jsonObject.getValue("resources").jsonArray.single().jsonObject
        assertEquals("playground://home/state", listed.getValue("uri").jsonPrimitive.content)
        val read = rpc(home, "resources/read", """{"uri":"playground://home/state"}""").getValue("result").jsonObject
        val state = Json.parseToJsonElement(read.getValue("contents").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content).jsonObject
        val bedroom = state.getValue("rooms").jsonObject.getValue("bedroom").jsonObject
        assertEquals("true", bedroom.getValue("light").jsonObject.getValue("on").jsonPrimitive.content)
        assertEquals(10, bedroom.getValue("light").jsonObject.getValue("brightness").jsonPrimitive.content.toInt())
        assertEquals(0, bedroom.getValue("curtain_open_pct").jsonPrimitive.content.toInt())
        assertEquals("睡觉", state.getValue("scene").jsonPrimitive.content)
        assertEquals(RESOURCE_MISSING, rpc(home, "resources/read", """{"uri":"playground://nowhere"}""").errorCode())
    }

    @Test
    fun theVacuumWorksThroughEveryRoomThenGoesHome() {
        call(home, "control_vacuum", """{"action":"start","room":"all"}""")
        repeat(Room.entries.size) { store.tick(46.0) }
        assertEquals(VacuumStatus.RETURNING, store.read().home.vacuum.status)
        store.tick(7.0)
        assertEquals(VacuumStatus.DOCKED, store.read().home.vacuum.status)
        assertTrue(store.calls.value.any { it.summary == "扫地机扫完了书房" })
        assertTrue(call(home, "control_vacuum", """{"action":"pause"}""").isError())
    }

    private fun JsonObject.name() = getValue("name").jsonPrimitive.content

    private fun JsonObject.errorCode(): Int = getValue("error").jsonObject.getValue("code").jsonPrimitive.content.toInt()

    private companion object {
        const val RESOURCE_MISSING = McpEndpoint.RESOURCE_NOT_FOUND
    }
}
