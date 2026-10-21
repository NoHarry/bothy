@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import cc.noharry.bothy.kernel.api.*

/** One producer hook feeds every subscriber, including nested tool progress. */
internal class DebugExecutionToolHook : RuntimeHook {
    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision {
        DebugExecutionEvents.publish(context.runId, DebugExecutionMessage.ToolStatus(call.toolName, call.toolCallId, "started"))
        return HookDecision.Allow()
    }

    override suspend fun onPostToolUse(call: ToolUse, result: ToolUseResult, context: HookContext) {
        DebugExecutionEvents.publish(context.runId, DebugExecutionMessage.ToolStatus(
            call.toolName, call.toolCallId, if (result.ok) "completed" else "failed",
        ))
    }
}
