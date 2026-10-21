@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import android.content.Context
import cc.noharry.bothy.kernel.api.AgentRequest
import cc.noharry.bothy.kernel.api.AgentRuntime
import cc.noharry.bothy.kernel.api.SessionEventLogHealthStatus
import cc.noharry.bothy.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.bothy.kernel.api.reliability.RuntimeRunStatus
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job

/** Hard cap on concurrently active Sample session runs. Replacement of the same session does not consume a new slot. */
internal const val DEBUG_MAX_ACTIVE_SESSION_RUNS = 8

internal const val DEBUG_SESSION_LIFECYCLE_IDLE = "IDLE"

internal enum class DebugStartRunAdmission {
    STARTED,
    REPLACED_SAME_SESSION,
    BUSY_MAX_ACTIVE_SESSIONS,
}

internal data class DebugActiveRunSnapshot(
    val sessionId: String,
    val runId: String,
    val requestId: String,
    val isActive: Boolean,
    val startedAtEpochMillis: Long?,
    val lastRunSummary: String?,
    val lifecycleStatus: String,
    val completionOutcome: String,
    val logHealth: String,
    /** Event-sourced todo projection; null = the run emitted no update_todo event. */
    val todoBoard: List<DebugTodoEntry>? = null,
)

internal class DebugActiveRunRecord(
    val sessionId: String,
    val runId: String,
    val requestId: String,
    val runtime: AgentRuntime,
    val resources: DebugActiveRunResources,
    val context: Context?,
    val startedAtEpochMillis: Long,
    job: Job? = null,
    recorders: DebugRuntimeRunRecorders,
    tokenUsage: DebugTokenUsageState = DebugTokenUsageState(),
    currentRequest: DebugCurrentRequest? = null,
    runtimeDemo: DebugRuntimeDemoState = DebugRuntimeDemoState(),
    lastRunSummary: String? = null,
    lifecycleStatus: String = RuntimeRunStatus.RUNNING.name,
    completionOutcome: String = RuntimeCompletionOutcome.UNKNOWN.name,
    logHealth: String = SessionEventLogHealthStatus.UNKNOWN.name,
    timingClosed: Boolean = false,
    /** Preserve model/variant, scene and runtime options through admission and launch. */
    val request: AgentRequest = AgentRequest(requestId = requestId, sessionId = sessionId, input = currentRequest?.text.orEmpty()),
) {
    private val stateLock = Any()
    private val finalized = AtomicBoolean(false)
    private val persistSuppressed = AtomicBoolean(false)

    @Volatile
    var job: Job? = job

    var recorders: DebugRuntimeRunRecorders = recorders
        get() = synchronized(stateLock) { field }
        set(value) = synchronized(stateLock) { field = value }

    var tokenUsage: DebugTokenUsageState = tokenUsage
        get() = synchronized(stateLock) { field }
        set(value) = synchronized(stateLock) { field = value }

    var currentRequest: DebugCurrentRequest? = currentRequest
        get() = synchronized(stateLock) { field }
        set(value) = synchronized(stateLock) { field = value }

    var runtimeDemo: DebugRuntimeDemoState = runtimeDemo
        get() = synchronized(stateLock) { field }
        set(value) = synchronized(stateLock) { field = value }

    /**
     * Latest update_todo board decoded from the public event stream (display state, not storage).
     * Null means no update_todo event was observed for this run; an empty list is an explicit clear.
     */
    var todoBoard: List<DebugTodoEntry>? = null
        get() = synchronized(stateLock) { field }
        set(value) = synchronized(stateLock) { field = value }

    var lastRunSummary: String? = lastRunSummary
        get() = synchronized(stateLock) { field }
        set(value) = synchronized(stateLock) { field = value }

    var lifecycleStatus: String = lifecycleStatus
        get() = synchronized(stateLock) { field }
        set(value) = synchronized(stateLock) { field = value }

    var completionOutcome: String = completionOutcome
        get() = synchronized(stateLock) { field }
        set(value) = synchronized(stateLock) { field = value }

    var logHealth: String = logHealth
        get() = synchronized(stateLock) { field }
        set(value) = synchronized(stateLock) { field = value }

    var timingClosed: Boolean = timingClosed
        get() = synchronized(stateLock) { field }
        set(value) = synchronized(stateLock) { field = value }

    val isJobActive: Boolean get() = job?.isActive == true

    val isFinalized: Boolean get() = finalized.get()

    fun matches(sessionId: String, runId: String): Boolean =
        this.sessionId == sessionId && this.runId == runId

    /** First caller wins. Terminal confirmation and onFinally share this token. */
    fun tryClaimFinalization(): Boolean = finalized.compareAndSet(false, true)

    fun suppressPersist() {
        persistSuppressed.set(true)
    }

    fun isPersistSuppressed(): Boolean = persistSuppressed.get()

    fun addTokenUsage(delta: DebugTokenUsageDelta): DebugTokenUsageState =
        synchronized(stateLock) {
            tokenUsage = tokenUsage.plus(delta)
            tokenUsage
        }

    fun snapshot(active: Boolean = isJobActive): DebugActiveRunSnapshot =
        synchronized(stateLock) {
            DebugActiveRunSnapshot(
                sessionId = sessionId,
                runId = runId,
                requestId = requestId,
                isActive = active,
                startedAtEpochMillis = startedAtEpochMillis.takeUnless { timingClosed },
                lastRunSummary = lastRunSummary,
                lifecycleStatus = lifecycleStatus,
                completionOutcome = completionOutcome,
                logHealth = logHealth,
                todoBoard = todoBoard,
            )
        }
}

/**
 * Bounded one-active-owner-per-session registry. Keyed by sessionId; mutations are runId-safe
 * so a replaced predecessor cannot clobber the successor. Map mutations and snapshots share
 * [lock] so Dispatchers.Default tests never observe an unsynchronized LinkedHashMap.
 */
internal class DebugActiveRunRegistry(
    val maxActiveSessions: Int = DEBUG_MAX_ACTIVE_SESSION_RUNS,
) {
    private val lock = Any()
    private val runs = LinkedHashMap<String, DebugActiveRunRecord>()

    init {
        require(maxActiveSessions >= 4) { "Sample multi-session bound must be at least 4" }
    }

    val size: Int get() = synchronized(lock) { runs.size }

    fun get(sessionId: String): DebugActiveRunRecord? = synchronized(lock) { runs[sessionId] }

    fun getExact(sessionId: String, runId: String): DebugActiveRunRecord? =
        synchronized(lock) { runs[sessionId]?.takeIf { it.runId == runId } }

    fun canAdmit(sessionId: String): Boolean = synchronized(lock) {
        runs.containsKey(sessionId) || runs.size < maxActiveSessions
    }

    fun admissionFor(sessionId: String): DebugStartRunAdmission = synchronized(lock) {
        when {
            runs.containsKey(sessionId) -> DebugStartRunAdmission.REPLACED_SAME_SESSION
            runs.size >= maxActiveSessions -> DebugStartRunAdmission.BUSY_MAX_ACTIVE_SESSIONS
            else -> DebugStartRunAdmission.STARTED
        }
    }

    fun put(record: DebugActiveRunRecord): DebugActiveRunRecord? = synchronized(lock) {
        val previous = runs[record.sessionId]
        runs[record.sessionId] = record
        previous
    }

    fun removeExact(sessionId: String, runId: String): DebugActiveRunRecord? = synchronized(lock) {
        val current = runs[sessionId] ?: return null
        if (current.runId != runId) return null
        runs.remove(sessionId)
    }

    fun discard(sessionId: String): DebugActiveRunRecord? = synchronized(lock) { runs.remove(sessionId) }

    fun drain(): List<DebugActiveRunRecord> = synchronized(lock) {
        val drained = runs.values.toList()
        runs.clear()
        drained
    }

    fun snapshots(): List<DebugActiveRunSnapshot> = synchronized(lock) {
        runs.values.map { it.snapshot() }
    }

    fun records(): List<DebugActiveRunRecord> = synchronized(lock) { runs.values.toList() }
}

internal fun DebugSessionSummary.withRunOverlay(snapshot: DebugActiveRunSnapshot?): DebugSessionSummary {
    if (snapshot == null) return this
    return copy(
        lifecycleStatus = snapshot.lifecycleStatus,
        completionOutcome = snapshot.completionOutcome,
        logHealth = snapshot.logHealth,
    )
}

internal fun DebugSessionSummary.runStatusLine(): String =
    listOf(lifecycleStatus, completionOutcome, logHealth).joinToString(" · ")

internal fun debugBusyActiveSessionStatus(maxActiveSessions: Int = DEBUG_MAX_ACTIVE_SESSION_RUNS): String =
    "Busy: max $maxActiveSessions active sessions"

internal fun debugBusyActiveSessionMessage(maxActiveSessions: Int = DEBUG_MAX_ACTIVE_SESSION_RUNS): String =
    "Run not started: active session limit reached ($maxActiveSessions)."

/** Bounded cancel+join while the Sample admission mutex is held. Cooperative jobs finish immediately. */
internal const val DEBUG_ADMISSION_JOIN_TIMEOUT_MS = 250L
