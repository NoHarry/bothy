@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.runtime
import cc.noharry.agent.kernel.api.*

/** One producer hook feeds every subscriber, including nested tool progress. */
internal class ExecutionToolHook : RuntimeHook {
    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision {
        ExecutionEvents.publish(context.runId, ExecutionMessage.ToolStatus(call.toolName, call.toolCallId, "started"))
        return HookDecision.Allow()
    }

    override suspend fun onPostToolUse(call: ToolUse, result: ToolUseResult, context: HookContext) {
        ExecutionEvents.publish(context.runId, ExecutionMessage.ToolStatus(
            call.toolName, call.toolCallId, if (result.ok) "completed" else "failed",
        ))
    }
}
