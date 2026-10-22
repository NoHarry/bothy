package cc.noharry.bothy.sample

import cc.noharry.agent.kernel.api.PostUserInputRequest
import cc.noharry.agent.kernel.api.PostUserInputResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Routes control input without acquiring a new execution owner or consulting the selected UI session. */
internal suspend fun postDebugRunInput(
    registry: DebugActiveRunRegistry,
    sessionId: String,
    runId: String,
    requestId: String,
    text: String,
    questionId: String?,
): PostUserInputResult = withContext(Dispatchers.IO) {
    val record = registry.getExact(sessionId, runId) ?: return@withContext PostUserInputResult.NotFound
    if (!record.isJobActive) return@withContext PostUserInputResult.RunNotActive
    record.runtime.postUserInput(PostUserInputRequest(runId, requestId, text, sessionId, questionId))
}
