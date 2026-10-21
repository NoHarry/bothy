package cc.noharry.bothy.sample

import android.content.Context
import cc.noharry.bothy.kernel.api.AgentRequest
import java.io.File

internal fun debugWorkspaceRoot(context: Context): File =
    File(context.filesDir, DEBUG_WORKSPACE_DIR).apply { mkdirs() }

// DEBUG_WORKSPACE_DIR is defined in DebugRuntimeDataCleanup.kt (shared wipe target).

/**
 * Real runtime intake summary derived only from [AgentRequest] and debug config fields.
 *
 * The previous version emitted hardcoded `runtimeRoutingSummary`/`runtimeMemorySummary`
 * preview text that guessed candidate tools/domains by keyword and was unrelated to the
 * actual runtime execution. Those were removed; the transcript now shows real tool calls
 * via [DebugRawToolTranscriptHook] and real runtime events via [AgentEvent].
 */
internal fun runtimeIntakeSummary(
    request: AgentRequest,
    provider: DebugProvider,
    streamLabel: String,
    workspaceRoot: File,
): String =
    """
        stage=intake
        requestId=${request.requestId}
        sessionId=${request.sessionId}
        scene=${request.scene ?: "default"}
        inputLength=${request.input.length}
        inputPreview=${request.input.debugInputPreview()}
        provider=${provider.label}
        mode=$streamLabel
        workspace=${workspaceRoot.name}
    """.trimIndent()

/**
 * Extracts the `tool=` value from a RawToolHook body so pre/post calls can be paired.
 * Falls back to "unknown" if the line is missing (malformed hook body).
 */
internal fun parseRawToolName(body: String): String {
    val line = body.lineSequence().firstOrNull { it.startsWith("tool=") } ?: return "unknown"
    return line.removePrefix("tool=").trim()
}
