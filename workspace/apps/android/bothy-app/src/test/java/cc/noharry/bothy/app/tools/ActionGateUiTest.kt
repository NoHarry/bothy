package cc.noharry.bothy.app.tools
import cc.noharry.bothy.app.console.uniqueKeys
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionGateUiTest {
    @Test
    fun aRepeatedRowKeyGetsANumberInsteadOfCrashingTheList() {
        val repeats = mutableListOf<List<String>>()

        val keys = listOf("item-1", "item-2", "item-1", "item-1").uniqueKeys { repeats += it }

        assertEquals(listOf("item-1", "item-2", "item-1#2", "item-1#3"), keys)
        assertEquals(listOf(listOf("item-1")), repeats)
        assertEquals(listOf("a", "b"), listOf("a", "b").uniqueKeys { error("no repeats") })
    }
}
