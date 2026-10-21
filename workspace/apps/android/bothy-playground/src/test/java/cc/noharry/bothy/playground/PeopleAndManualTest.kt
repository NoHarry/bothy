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

/** Who sits where and who is speaking, and the world changed by hand: the inputs an automation reacts to. */
class PeopleAndManualTest {
    private val store = PlaygroundStore(clock = { 0L })
    private val cabin = playgroundEndpoints(store).getValue("/cabin/mcp")

    private fun call(tool: String, arguments: String): JsonObject =
        cabin.handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""").jsonObject)!!
            .getValue("result").jsonObject

    private fun JsonObject.text() = getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content

    @Test
    fun meIsTheSpeakersSeatAndPassengersAreWhoeverRidesAlong() {
        assertTrue(call("set_seat", """{"seat":"passengers","ventilation":2}""").text().contains("没有乘客"))
        store.holdCabin(CabinSpots.seats.getValue(Seat.REAR_RIGHT))
        assertEquals(Seat.REAR_RIGHT, store.read().cabin.speaker)
        assertEquals(setOf(Seat.DRIVER, Seat.REAR_RIGHT), store.read().cabin.occupants)
        assertEquals("右后座椅通风 2 档。", call("set_seat", """{"seat":"我这边","ventilation":2}""").text())
        call("set_window", """{"window":"passengers","open":30}""")
        assertEquals(mapOf(Seat.DRIVER to 0, Seat.PASSENGER to 0, Seat.REAR_LEFT to 0, Seat.REAR_RIGHT to 30), store.read().cabin.windows)
        assertTrue(call("set_seat", """{"seat":"rear_left","heat":1}""").text().endsWith("（这个座位没人）。"))
        val status = call("get_cabin_status", "{}")
        assertTrue(status.text().startsWith("用户现在在车上。") && status.text().contains("说话的人坐在右后（rear_right）"))
        store.manual("playground", "你现在在家里") { it.copy(userAt = UserPlace.HOME) }
        val away = call("get_cabin_status", "{}").getValue("structuredContent").jsonObject
        assertEquals("false", away.getValue("user_here").jsonPrimitive.content)
        assertEquals("rear_right", status.getValue("structuredContent").jsonObject.getValue("speaker").jsonPrimitive.content)
    }

    @Test
    fun theSpeakerWhoGetsOutHandsTheWordOn() {
        val both = Cabin().withSpeaker(Seat.PASSENGER)
        assertEquals(Seat.DRIVER, both.withOccupant(Seat.PASSENGER, aboard = false).speaker)
        assertNull(Cabin().withOccupant(Seat.DRIVER, aboard = false).speaker)
    }

    @Test
    fun tapsOnThePicturesChangeTheWorldAndAreLoggedAsManual() {
        store.tapCabin(CabinSpots.seats.getValue(Seat.PASSENGER))
        assertTrue(Seat.PASSENGER in store.read().cabin.occupants)
        val window = CabinSpots.windows.getValue(Seat.DRIVER)
        store.tapCabin(androidx.compose.ui.geometry.Offset(window.first.x, (window.first.y + window.second.y) / 2))
        assertEquals(100, store.read().cabin.window(Seat.DRIVER))
        store.tapHome(HomeSpots.door.center)
        assertFalse(store.read().home.doorLocked)
        store.holdHome(HomeSpots.rooms.getValue(Room.BEDROOM).center)
        assertTrue(store.read().home.room(Room.BEDROOM).presence)
        store.tapHome(HomeSpots.rooms.getValue(Room.STUDY).center)
        assertTrue(store.read().home.room(Room.STUDY).light.on)
        val record = store.calls.value.first()
        assertEquals(PlaygroundStore.MANUAL to false, record.tool to record.fromAssistant)
        assertEquals("书房开灯", record.summary)
        assertEquals(5, store.calls.value.count { it.tool == PlaygroundStore.MANUAL })
    }

    @Test
    fun aRoomsPresenceIsInTheStateAPlanWatches() {
        store.changeRoom(Room.BEDROOM, "有人了") { it.copy(presence = true) }
        val rooms = store.read().home.toJson(userHere = false).getValue("rooms").jsonObject
        assertEquals("true", rooms.getValue("bedroom").jsonObject.getValue("presence").jsonPrimitive.content)
        assertEquals("false", rooms.getValue("study").jsonObject.getValue("presence").jsonPrimitive.content)
    }
}
