package cc.noharry.bothy.app.console

import cc.noharry.agent.appserver.protocol.client.ServerState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ItemFoldsTest {
    @After
    fun clear() = ItemFolds.clearForTest()

    @Test
    fun whatThePersonOpenedComesBackWithItsThread() {
        ItemFolds.set("a", 3, folded = false)
        // A recreated activity's connection starts with nothing the person opened or folded.
        assertEquals(mapOf(3L to false), ItemFolds.over(ServerState(activeSessionId = "a")).folded)
        // Another thread's items keep their own.
        assertEquals(emptyMap<Long, Boolean>(), ItemFolds.over(ServerState(activeSessionId = "b")).folded)
        // What the connection holds now wins.
        assertEquals(mapOf(3L to true), ItemFolds.over(ServerState(activeSessionId = "a", folded = mapOf(3L to true))).folded)
    }
}
