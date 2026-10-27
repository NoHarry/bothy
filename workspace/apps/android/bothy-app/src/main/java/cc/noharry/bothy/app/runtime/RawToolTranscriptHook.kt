@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.runtime
import cc.noharry.agent.kernel.api.HookDecision
import cc.noharry.agent.kernel.api.HookContext
import cc.noharry.agent.kernel.api.RuntimeHook
import cc.noharry.agent.kernel.api.ToolUse
import cc.noharry.agent.kernel.api.ToolUseResult

internal class RawToolTranscriptHook(
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    private val appendRawToolEvent: (title: String, body: String) -> Unit,
) : RuntimeHook {
    constructor(appendRawToolEvent: (title: String, body: String) -> Unit) : this(
        nowEpochMillis = { System.currentTimeMillis() },
        appendRawToolEvent = appendRawToolEvent,
    )

    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision {
        appendRawToolEvent(
            "Raw tool arguments",
            call.rawArgumentsBody(context, nowEpochMillis()),
        )
        return HookDecision.Allow()
    }

    override suspend fun onPostToolUse(call: ToolUse, result: ToolUseResult, context: HookContext) {
        appendRawToolEvent(
            "Raw tool result",
            call.rawResultBody(result, context, nowEpochMillis()),
        )
    }
}

private fun ToolUse.rawArgumentsBody(context: HookContext, timestampMs: Long): String =
    """
        runId=${context.runId}
        timestampMs=$timestampMs
        toolCallId=${toolCallId}
        tool=$toolName
        source=$source
        arguments=${redactToolPayload(argumentsJson)}
    """.trimIndent()

private fun ToolUse.rawResultBody(result: ToolUseResult, context: HookContext, timestampMs: Long): String =
    """
        runId=${context.runId}
        timestampMs=$timestampMs
        toolCallId=${toolCallId}
        tool=$toolName
        source=$source
        ok=${result.ok}
        retryable=${result.retryable}
        code=${result.code.orEmpty()}
        result=${redactToolPayload(result.resultJson)}
    """.trimIndent()
