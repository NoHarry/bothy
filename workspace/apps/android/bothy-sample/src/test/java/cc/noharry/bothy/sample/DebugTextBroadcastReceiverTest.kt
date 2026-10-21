package cc.noharry.bothy.sample

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugTextBroadcastReceiverTest {
    @Test
    fun acceptsNormalActionBroadcast() {
        assertTrue(isDebugTextBroadcast(DEBUG_BROADCAST_ACTION, null))
    }

    @Test
    fun acceptsExplicitComponentBroadcastWithoutAction() {
        assertTrue(isDebugTextBroadcast(null, DebugTextBroadcastReceiver::class.java.name))
    }

    @Test
    fun rejectsUnrelatedBroadcasts() {
        assertFalse(isDebugTextBroadcast("other.action", DebugTextBroadcastReceiver::class.java.name))
        assertFalse(isDebugTextBroadcast(null, "cc.noharry.bothy.sample.OtherReceiver"))
    }
}
