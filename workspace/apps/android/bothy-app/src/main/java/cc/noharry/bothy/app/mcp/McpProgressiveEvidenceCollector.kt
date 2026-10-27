package cc.noharry.bothy.app.mcp
import cc.noharry.agent.kernel.api.AgentEvent

/**
 * Bridges runtime events into the progressive-activation evidence recorder.
 *
 * Kept separate from RuntimeSession so the session file stays under the architecture file-size
 * limit. This helper captures schema/catalog browse activity and backend tool calls across both
 * the direct native-call path after search/schema discovery.
 */
internal object McpProgressiveEvidenceCollector {
    private val schemaPathRegex = Regex("""/mcp/tools/([^/"]+)/schema\.json""")

    fun record(event: AgentEvent, recorder: McpProgressiveEvidenceRecorder?) {
        if (recorder == null) return
        when (event) {
            is AgentEvent.Started -> recorder.recordRunId(event.runId)
            is AgentEvent.NativeToolCallStarted -> {
                schemaPathRegex.find(event.arguments)?.let { recorder.recordSchemaBrowse(it.groupValues[1]) }
            }
            is AgentEvent.NativeToolCallFinished -> {
                if (event.toolName.startsWith(MCP_TOOL_PREFIX) || event.toolName.startsWith(COMMAND_TOOL_PREFIX)) {
                    recorder.recordBackendToolCalled(event.toolName)
                }
            }
            is AgentEvent.Finished -> recorder.recordFinished()
            else -> Unit
        }
    }
}

private const val MCP_TOOL_PREFIX = "mcp__"
private const val COMMAND_TOOL_PREFIX = "command_"
