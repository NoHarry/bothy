package cc.noharry.bothy.server.a2a

import cc.noharry.bothy.server.voice.tts.closesReply
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bothy's voice around a delegation: handing a task to the person's own agent closes the spoken reply. Following and
 * reporting delegations is the A2A plugin's (`A2aDelegationsTest` in agent-app-a2a).
 */
class A2aDelegationsTest {
    @Test
    fun aDelegationCallClosesTheReplyForProgressSpeech() {
        assertTrue(closesReply("mcp__experimental_a2a_user_grok_bot__a2a_message_send"))
        assertTrue(closesReply("finish"))
        // "已经派出三路…" with spawn_agent would be said again by the reply; when the agent waits instead, it is said then.
        assertTrue(closesReply("spawn_agent"))
        assertFalse(closesReply("wait_agent"))
        assertFalse(closesReply("mcp__device__navigation_route_plan"))
    }
}
