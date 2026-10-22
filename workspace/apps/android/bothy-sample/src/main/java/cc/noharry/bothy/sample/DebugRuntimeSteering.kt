package cc.noharry.bothy.sample

import cc.noharry.agent.kernel.api.AgentRuntime
import cc.noharry.agent.kernel.api.PostUserInputRequest
import cc.noharry.agent.kernel.api.PostUserInputResult

internal data class DebugSteeringPostUiResult(
    val status: String,
    val transcript: DebugSteeringTranscript,
    /** The run was no longer active or not found: the input did not land. */
    val runEnded: Boolean = false,
)

internal sealed interface DebugSteeringTranscript {
    data class Runtime(val title: String, val body: String) : DebugSteeringTranscript
    data class Status(val message: String) : DebugSteeringTranscript
    data class Error(val title: String, val message: String) : DebugSteeringTranscript
}

internal suspend fun postDebugRuntimeSteering(
    runtime: AgentRuntime,
    runId: String,
    requestId: String,
    text: String,
    title: String = STEERING_TITLE,
    shown: String = text,
): DebugSteeringPostUiResult {
    val result = runCatching {
        runtime.postUserInput(PostUserInputRequest(runId, requestId, text))
    }
    return result.fold(
        onSuccess = { postResult -> postResult.toDebugSteeringUiResult(shown, title) },
        onFailure = { error ->
            DebugSteeringPostUiResult(
                status = "Steering failed",
                transcript = DebugSteeringTranscript.Error(
                    title = "Steering",
                    message = error.message ?: "Unable to post steering input.",
                ),
            )
        },
    )
}

private fun PostUserInputResult.toDebugSteeringUiResult(text: String, title: String): DebugSteeringPostUiResult =
    when (this) {
        PostUserInputResult.Accepted, PostUserInputResult.Duplicate -> DebugSteeringPostUiResult(
            status = "Steering sent",
            transcript = DebugSteeringTranscript.Runtime(
                title = title,
                body = text.take(MAX_STEERING_PREVIEW_CHARS),
            ),
        )
        PostUserInputResult.RunNotActive -> DebugSteeringPostUiResult(
            status = "Steering inactive",
            transcript = DebugSteeringTranscript.Status("Steering ignored: run is no longer active."),
            runEnded = true,
        )
        PostUserInputResult.NotFound -> DebugSteeringPostUiResult(
            status = "Steering missing run",
            transcript = DebugSteeringTranscript.Status("Steering ignored: active run was not found."),
            runEnded = true,
        )
        is PostUserInputResult.Rejected -> DebugSteeringPostUiResult(
            status = "Input rejected",
            transcript = DebugSteeringTranscript.Error("Input", code),
        )
    }

private const val MAX_STEERING_PREVIEW_CHARS = 240
private const val STEERING_TITLE = "Runtime steering input"
/** Transcript title for words the voice session appended to a running task. */
internal const val VOICE_STEERING_TITLE = "语音追加"
internal const val DELEGATION_REPORT_TITLE = "委托回报"
internal const val DELEGATION_REPORT_WAIT_MS = 120_000L
internal const val DELEGATION_REPORT_POLL_MS = 1_000L
/** A report's turn has this long to start before the next report is considered. */
internal const val REPORT_START_WAIT_MS = 3_000L
/** After a run ends, how long the card has to show its result before a waiting report checks again. */
internal const val RESULT_SETTLE_MS = 1_500L

/** Records a steering or confirmation outcome in the transcript. */
internal fun DebugTranscriptStore.append(entry: DebugSteeringTranscript) {
    when (entry) {
        is DebugSteeringTranscript.Runtime -> appendRuntime(entry.title, entry.body)
        is DebugSteeringTranscript.Status -> appendStatus(entry.message)
        is DebugSteeringTranscript.Error -> appendError(entry.title, entry.message)
    }
}
