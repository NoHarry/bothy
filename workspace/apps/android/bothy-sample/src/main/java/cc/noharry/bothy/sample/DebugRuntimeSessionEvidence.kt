package cc.noharry.bothy.sample

import android.content.Context
import cc.noharry.bothy.kernel.api.AgentRequest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun debugRunEvidenceDir(context: Context): File =
    File(context.filesDir, DEBUG_RUN_EVIDENCE_DIR)

/** Location [DebugRunEvidenceRecorder.write] uses; read-only enrichment resolves the same path. */
internal fun debugRunEvidenceFile(context: Context, requestId: String): File =
    File(debugRunEvidenceDir(context), debugRunEvidenceFileName(requestId))

internal fun readDebugRunEvidence(context: Context, requestId: String): DebugRunEvidence? {
    val file = debugRunEvidenceFile(context, requestId)
    if (!file.isFile) return null
    return runCatching { DebugRunEvidenceJson.read(file) }.getOrNull()
}

internal fun debugRunEvidenceRecorder(
    context: Context,
    request: AgentRequest,
    providerLabel: String,
    streamLabel: String,
    backendLabel: String,
    userText: String = request.input,
): DebugRunEvidenceRecorder =
    DebugRunEvidenceRecorder(
        evidenceDir = debugRunEvidenceDir(context),
        request = request,
        providerLabel = providerLabel,
        streamLabel = streamLabel,
        backendLabel = backendLabel,
        userText = userText,
    )

internal suspend fun DebugTranscriptStore.appendRunEvidence(
    recorder: DebugRunEvidenceRecorder,
    surfaceOnTimeline: Boolean = true,
): DebugRunReplayResult {
    val (replay, fileResult) = withContext(Dispatchers.IO) {
        DebugRunReplayVerifier.verify(recorder.snapshot()) to runCatching { recorder.write() }
    }
    val path = fileResult.getOrNull()?.absolutePath
    val error = fileResult.exceptionOrNull()?.message
    val shouldSurface = surfaceOnTimeline || !replay.passed || error != null
    if (shouldSurface) {
        appendRuntime("Runtime replay evidence", replayEvidenceBody(replay, path, error))
    }
    return replay
}

private fun replayEvidenceBody(
    replay: DebugRunReplayResult,
    path: String?,
    error: String?,
): String = buildString {
    append(replay.summary)
    path?.let { append("\n").append(it) }
    error?.let { append("\nwriteError=").append(it) }
    if (replay.failures.isNotEmpty()) append("\nfailures=").append(replay.failures.joinToString("|"))
    if (replay.warnings.isNotEmpty()) append("\nwarnings=").append(replay.warnings.joinToString("|"))
}

private const val DEBUG_RUN_EVIDENCE_DIR = "debug-run-evidence"
