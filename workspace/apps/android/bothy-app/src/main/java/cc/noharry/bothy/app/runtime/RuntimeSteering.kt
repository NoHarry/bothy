package cc.noharry.bothy.app.runtime
import cc.noharry.agent.kernel.api.AgentRuntime
import cc.noharry.agent.kernel.api.PostUserInputRequest
import cc.noharry.agent.kernel.api.PostUserInputResult
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.session.TranscriptStore

internal data class SteeringPostUiResult(
    val status: String,
    val transcript: SteeringTranscript,
    /** The run was no longer active or not found: the input did not land. */
    val runEnded: Boolean = false,
)

internal sealed interface SteeringTranscript {
    data class Runtime(val title: String, val body: String) : SteeringTranscript
    data class Status(val message: String) : SteeringTranscript
    data class Error(val title: String, val message: String) : SteeringTranscript
}

internal suspend fun postRuntimeSteering(
    runtime: AgentRuntime,
    runId: String,
    requestId: String,
    text: String,
    title: String = STEERING_TITLE,
    shown: String = text,
): SteeringPostUiResult {
    val result = runCatching {
        runtime.postUserInput(PostUserInputRequest(runId, requestId, text))
    }
    return result.fold(
        onSuccess = { postResult -> postResult.toSteeringUiResult(shown, title) },
        onFailure = { error ->
            SteeringPostUiResult(
                status = "Steering failed",
                transcript = SteeringTranscript.Error(
                    title = "Steering",
                    message = error.message ?: "Unable to post steering input.",
                ),
            )
        },
    )
}

private fun PostUserInputResult.toSteeringUiResult(text: String, title: String): SteeringPostUiResult =
    when (this) {
        PostUserInputResult.Accepted, PostUserInputResult.Duplicate -> SteeringPostUiResult(
            status = "Steering sent",
            transcript = SteeringTranscript.Runtime(
                title = title,
                body = text.take(MAX_STEERING_PREVIEW_CHARS),
            ),
        )
        PostUserInputResult.RunNotActive -> SteeringPostUiResult(
            status = "Steering inactive",
            transcript = SteeringTranscript.Status("Steering ignored: run is no longer active."),
            runEnded = true,
        )
        PostUserInputResult.NotFound -> SteeringPostUiResult(
            status = "Steering missing run",
            transcript = SteeringTranscript.Status("Steering ignored: active run was not found."),
            runEnded = true,
        )
        is PostUserInputResult.Rejected -> SteeringPostUiResult(
            status = "Input rejected",
            transcript = SteeringTranscript.Error("Input", code),
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
internal fun TranscriptStore.append(entry: SteeringTranscript) {
    when (entry) {
        is SteeringTranscript.Runtime -> appendRuntime(entry.title, entry.body)
        is SteeringTranscript.Status -> appendStatus(entry.message)
        is SteeringTranscript.Error -> appendError(entry.title, entry.message)
    }
}
