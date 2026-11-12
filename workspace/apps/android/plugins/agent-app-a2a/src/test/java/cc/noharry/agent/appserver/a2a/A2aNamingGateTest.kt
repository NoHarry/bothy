@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.a2a
import cc.noharry.agent.spi.HookContext
import cc.noharry.agent.spi.HookDecision
import cc.noharry.agent.spi.ToolSource
import cc.noharry.agent.spi.ToolUse
import cc.noharry.agent.appserver.runtime.PreviousExchange
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class A2aNamingGateTest {
    private val context = HookContext(sessionId = "s", runId = "r", requestId = "q")
    private val agents = listOf("Grok Bot")
    private val nicknames = listOf("云端助手", "个人助手")

    @Test
    fun backgroundWorkTheUserDidNotHandToTheAgentIsNotSent() = runBlocking {
        val denied = send("在后台分别帮我查一下成都、重庆、西安这周末有什么好看的展览", """{"text":"查展览"}""")

        assertTrue(denied is HookDecision.Deny)
        assertTrue((denied as HookDecision.Deny).reason.contains("spawn_agent"))
        // Other tools are none of its business.
        assertTrue(gate("随便").onPreToolUse(ToolUse("spawn_agent", "{}", ToolSource.BACKEND), context) is HookDecision.Allow)
    }

    @Test
    fun namingTheAgentAgreeingToItOrAnsweringItsQuestionIsSent() = runBlocking {
        assertTrue(send("让 grok 帮我查一下明天的会议", """{"text":"查会议"}""") is HookDecision.Allow)
        assertTrue(send("让云端助手帮我查一下明天的会议", """{"text":"查会议"}""") is HookDecision.Allow)
        assertTrue(send("交给我的个人助手去订", """{"text":"订会议室"}""") is HookDecision.Allow)
        // 「好的」 to the trip's closing question quotes it, and the question names the personal assistant.
        assertTrue(namesAgent("好的\n（回答你刚才的问题：要不要设成提醒，或者把行程发给你的个人助手备份？）", listOf("Grok Bot"), nicknames))
        val agreed = A2aNamingGate("好的", agents, nicknames, previousQuestion = "这个要交给 Grok Bot 去发吗？")
        assertTrue(agreed.onPreToolUse(ToolUse("a2a_message_send", "{}", ToolSource.BACKEND), context) is HookDecision.Allow)
        assertTrue(send("发到 zhang@example.com", """{"text":"zhang@example.com","taskId":"task-7"}""") is HookDecision.Allow)
    }

    @Test
    fun aNicknameCountsOnlyWhenTheHostHasIt() = runBlocking {
        assertFalse(namesAgent("让云端助手帮我查一下明天的会议", agents, nicknames = emptyList()))
        val denied = A2aNamingGate("让云端助手帮我查", agents, nicknames = emptyList())
            .onPreToolUse(ToolUse("mcp__experimental_a2a_user_grok_bot__a2a_message_send", "{}", ToolSource.BACKEND), context)
        assertTrue(denied is HookDecision.Deny && !(denied as HookDecision.Deny).reason.contains("(or say"))
        assertTrue(namesAgent("Ask My Cloud Assistant to book it", agents, nicknames = listOf("my cloud assistant")))
    }

    @Test
    fun genericWordsInALabelDoNotCountAsItsName() {
        assertFalse(namesAgent("帮我问问 AI 助手", listOf("AI Agent")))
        assertTrue(namesAgent("让小秘书订个会议室", listOf("小秘书")))
    }

    private fun gate(input: String) = A2aNamingGate(input, agents, nicknames)

    private suspend fun send(input: String, arguments: String) =
        gate(input).onPreToolUse(ToolUse("mcp__experimental_a2a_user_grok_bot__a2a_message_send", arguments, ToolSource.BACKEND), context)
}
