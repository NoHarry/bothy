package cc.noharry.bothy.playground

import androidx.compose.ui.geometry.Offset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Doors, the trunk, the screen and the volume: by tool, by hand, and in the state a plan watches. */
class CabinBodyTest {
    private val store = PlaygroundStore(clock = { 0L })
    private val cabin = playgroundEndpoints(store).getValue("/cabin/mcp")

    private fun call(tool: String, arguments: String): JsonObject =
        cabin.handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""").jsonObject)!!
            .getValue("result").jsonObject

    private fun JsonObject.text() = getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content

    @Test
    fun aDoorOpensUnlockedButNotOnTheMove() {
        assertEquals("副驾车门已打开（先解了锁）。", call("set_door", """{"door":"副驾","open":true}""").text())
        assertEquals(setOf(Seat.PASSENGER), store.read().cabin.doorsOpen)
        assertFalse(store.read().cabin.doorsLocked)
        call("set_door", """{"door":"all","open":false}""")
        assertTrue(store.read().cabin.doorsOpen.isEmpty())
        store.setDriving(true)
        store.tick(1.0)
        assertEquals("车在行驶，不能开车门", call("set_door", """{"door":"driver","open":true}""").text())
        assertEquals("车在行驶，不能打开后备箱", call("set_trunk", """{"open":true}""").text())
    }

    @Test
    fun theScreenAndTheVolumeTakeALevelOrAStep() {
        assertEquals("中控屏亮度 50%。", call("set_screen", """{"step":-20}""").text())
        assertEquals("中控屏已熄屏。", call("set_screen", """{"brightness":0}""").text())
        assertEquals("音量 15。", call("set_volume", """{"step":3}""").text())
        assertEquals("音量 30。", call("set_volume", """{"step":99}""").text())
        assertEquals("已静音。", call("set_volume", """{"mute":true}""").text())
        val state = call("get_cabin_status", "{}").getValue("structuredContent").jsonObject
        assertEquals(0, state.getValue("screen_brightness").jsonPrimitive.content.toInt())
        assertEquals(0, state.getValue("volume").jsonPrimitive.content.toInt())
    }

    @Test
    fun theTrunkAndTheDoorsAreTheStateAPlanWatches() {
        call("set_trunk", """{"open":true}""")
        store.holdCabin(CabinSpots.windows.getValue(Seat.REAR_LEFT).let { (top, bottom) -> Offset(top.x, (top.y + bottom.y) / 2) })
        val state = store.read().cabin.toJson(userHere = true)
        assertEquals("true", state.getValue("trunk_open").jsonPrimitive.content)
        assertEquals("true", state.getValue("doors_open").jsonObject.getValue("rear_left").jsonPrimitive.content)
        store.tapCabin(CabinSpots.tailgate.center)
        assertFalse(store.read().cabin.trunkOpen)
        assertEquals("后备箱关上", store.calls.value.first().summary)
    }
}
