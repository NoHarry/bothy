package cc.noharry.agent.appserver.plans
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchTrailTest {
    @Test
    fun theWatchTrailHoldsBackRepeatsButNotFirings() {
        var now = 0L
        val lines = mutableListOf<String>()
        val trail = WatchTrail(nowMillis = { now }, log = { lines += it })

        trail.note("signal speed", "push 60")
        now += 1_000
        trail.note("signal speed", "push 61")
        trail.always("「拿蛋糕」 type=2 met: FIRE")
        now += 10_000
        trail.note("signal speed", "push 62")

        assertEquals(listOf("push 60", "「拿蛋糕」 type=2 met: FIRE", "push 62 (+1 since)"), lines)
        assertNull(lines.firstOrNull { it.contains("61") })
        // What reaches the device's log: numbers and switches, never a destination's name.
        val reading = Json.parseToJsonElement("""{"type":2,"poi":{"name":"深圳北站"},"name":"公司","on":true}""").jsonObject
        assertEquals("{type=2 poi=‹…› name=‹text› on=true}", reading.loggable())
        assertEquals("‹text›", "深圳北站".loggable())
        assertEquals("20.5", "20.5".loggable())
    }
}
