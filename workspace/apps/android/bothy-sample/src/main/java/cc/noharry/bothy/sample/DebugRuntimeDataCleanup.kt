package cc.noharry.bothy.sample

import android.content.Context
import java.io.File

/**
 * Deletes sample-host **runtime** data only: sessions, generated images, workspace memory/artifacts,
 * and local evidence/logs. Provider and image model API keys and app settings are kept.
 */
internal data class DebugRuntimeDataCleanupResult(
    val deletedPaths: List<String>,
    val failedPaths: List<String>,
) {
    val deletedCount: Int get() = deletedPaths.size
    val failedCount: Int get() = failedPaths.size
    val ok: Boolean get() = failedPaths.isEmpty()

    fun summaryLine(): String =
        when {
            deletedCount == 0 && failedCount == 0 -> "Nothing to clear"
            failedCount == 0 -> "Cleared $deletedCount runtime path${if (deletedCount == 1) "" else "s"}"
            else -> "Cleared $deletedCount path(s); $failedCount failed"
        }
}

/**
 * Wipe targets under [Context.filesDir] plus the no-backup EventLog base root. Safe to call
 * with empty/missing dirs. Does **not** touch provider config, MCP prefs, or SAF tree URI prefs.
 *
 * Callers must stop and join every run and cancel every Trajectory load before calling this;
 * the EventLog roots are deleted as whole host-owned trees, not edited in place.
 */
internal fun clearDebugRuntimeDataOnDisk(context: Context): DebugRuntimeDataCleanupResult {
    val targets = debugRuntimeDataDirs(context.filesDir) +
        debugRuntimeNoBackupDataDirs(context.noBackupFilesDir)
    return clearDebugRuntimeDataDirs(targets)
}

/** Pure helper for tests: delete each existing path and report outcomes. */
internal fun clearDebugRuntimeDataDirs(targets: List<File>): DebugRuntimeDataCleanupResult {
    val deleted = mutableListOf<String>()
    val failed = mutableListOf<String>()
    targets.forEach { target ->
        if (!target.exists()) return@forEach
        val ok = runCatching { target.deleteRecursively() }.getOrDefault(false)
        if (ok || !target.exists()) {
            deleted += target.name
        } else {
            failed += target.name
        }
    }
    return DebugRuntimeDataCleanupResult(deletedPaths = deleted, failedPaths = failed)
}

internal fun debugRuntimeDataDirs(filesDir: File): List<File> =
    listOf(
        File(filesDir, DEBUG_WORKSPACE_DIR),
        File(filesDir, DEBUG_RUN_EVIDENCE_DIR),
        File(filesDir, MCP_PROGRESSIVE_EVIDENCE_DIR),
        File(filesDir, RUNTIME_DEBUG_LOG_DIR),
        File(filesDir, WEB_SEARCH_EVIDENCE_DIR),
        // Q-RPB-008: clearRuntimeData must cover probes/graph/run-meta under reliability root.
        File(filesDir, AGENT_RELIABILITY_DIR),
        // Pictures from the image tool, one folder per session, plus settings/adb test generations.
        File(filesDir, GENERATED_IMAGES_DIR),
    )

/** Runtime data that lives outside `filesDir`: the app-private no-backup EventLog roots. */
internal fun debugRuntimeNoBackupDataDirs(noBackupFilesDir: File): List<File> =
    listOf(File(noBackupFilesDir, DEBUG_SESSION_EVENT_LOG_ROOT_DIR))

// Keep names aligned with the writers that create these directories.
internal const val DEBUG_WORKSPACE_DIR = "runtime-debug-workspace"
/** Reliability root for run.json + probe artifacts (probes.jsonl, run-meta, completeness). */
internal const val AGENT_RELIABILITY_DIR = "agent-reliability"
private const val DEBUG_RUN_EVIDENCE_DIR = "debug-run-evidence"
private const val MCP_PROGRESSIVE_EVIDENCE_DIR = "mcp-progressive-evidence"
private const val RUNTIME_DEBUG_LOG_DIR = "runtime-debug-logs"
private const val WEB_SEARCH_EVIDENCE_DIR = "web-search-evidence"
