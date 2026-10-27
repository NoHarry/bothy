package cc.noharry.bothy.app
import cc.noharry.bothy.app.session.DEBUG_BROADCAST_ACTION
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextBroadcastReceiverTest {
    @Test
    fun acceptsNormalActionBroadcast() {
        assertTrue(isTextBroadcast(DEBUG_BROADCAST_ACTION, null))
    }

    @Test
    fun acceptsExplicitComponentBroadcastWithoutAction() {
        assertTrue(isTextBroadcast(null, TextBroadcastReceiver::class.java.name))
    }

    @Test
    fun rejectsUnrelatedBroadcasts() {
        assertFalse(isTextBroadcast("other.action", TextBroadcastReceiver::class.java.name))
        assertFalse(isTextBroadcast(null, "cc.noharry.bothy.OtherReceiver"))
    }
}
