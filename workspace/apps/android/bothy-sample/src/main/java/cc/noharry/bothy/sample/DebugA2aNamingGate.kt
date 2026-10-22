package cc.noharry.bothy.sample

import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.HookContext
import cc.noharry.agent.kernel.api.HookDecision
import cc.noharry.agent.kernel.api.RuntimeHook
import cc.noharry.agent.kernel.api.ToolUse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The device hands a task to the user's own agent only when the user names it (owner 2026-10-17): asked to "在后台查…",
 * a fast model sent the same task to its subagent and to Grok at once, whatever the skill and the tool said. A send
 * whose request names no enabled agent is refused, unless it answers a question the agent asked (it carries that
 * task's id). A short "好的" after "要交给 Grok 吗？" counts: its run input quotes the question it answers.
 */
@OptIn(ExperimentalAgentRuntimeApi::class)
internal class DebugA2aNamingGate(private val userInput: String, private val agents: List<String>) : RuntimeHook {
    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision {
        if (!call.toolName.endsWith(A2A_SEND_TOOL)) return HookDecision.Allow()
        if (debugNamesAgent(userInput, agents) || call.argumentsJson.answersAgentQuestion()) return HookDecision.Allow()
        return HookDecision.Deny(
            reason = "Not sent: the user did not name ${agents.joinToString(" or ")} (or say 云端助手 / 个人助手). Do background or parallel " +
                "work yourself, or with spawn_agent when it is offered; for an errand that needs the user's own account, first " +
                "ask whether to hand it to that agent.",
        )
    }
}

/**
 * Whether [text] names one of [agents]: by its label, the label's first distinctive word ("Grok Bot" → grok), or what
 * the user calls any of them, 云端助手 or 个人助手 (owner 2026-10-17).
 */
internal fun debugNamesAgent(text: String, agents: List<String>): Boolean {
    val said = text.lowercase()
    if (agents.isNotEmpty() && CLOUD_ASSISTANT_NAMES.any { it in said }) return true
    return agents.any { label ->
        val name = label.trim().lowercase()
        val word = name.split(' ', '-', '_').firstOrNull { it.length >= MIN_NAME_CHARS && it !in GENERIC_WORDS }
        listOfNotNull(name.takeIf(String::isNotEmpty), word).any { it in said }
    }
}

private fun String.answersAgentQuestion(): Boolean = runCatching {
    Json.parseToJsonElement(this).jsonObject["taskId"]?.jsonPrimitive?.content?.isNotBlank() == true
}.getOrDefault(false)

private const val A2A_SEND_TOOL = "a2a_message_send"
private const val MIN_NAME_CHARS = 2
private val GENERIC_WORDS = setOf("my", "the", "ai", "bot", "agent", "assistant")
/** What the user calls their own cloud agents, whatever their labels. */
internal val CLOUD_ASSISTANT_NAMES = listOf("云端助手", "个人助手")
