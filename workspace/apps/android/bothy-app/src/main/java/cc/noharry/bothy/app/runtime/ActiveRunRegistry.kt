@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.runtime
import android.content.Context
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.AgentRuntime
import cc.noharry.agent.kernel.api.SessionEventLogHealthStatus
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.bothy.app.plans.matches
import cc.noharry.bothy.app.session.CurrentRequest
import cc.noharry.bothy.app.session.SessionSummary
import cc.noharry.bothy.app.session.TodoEntry
import cc.noharry.bothy.app.usage.TokenDelta
import cc.noharry.bothy.app.usage.TokenUsageState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job

/** Hard cap on concurrently active Sample session runs. Replacement of the same session does not consume a new slot. */
internal const val DEBUG_MAX_ACTIVE_SESSION_RUNS = 8

internal const val DEBUG_SESSION_LIFECYCLE_IDLE = "IDLE"

internal enum class StartRunAdmission {
    STARTED,
    REPLACED_SAME_SESSION,
    BUSY_MAX_ACTIVE_SESSIONS,
}

internal data class ActiveRunSnapshot(
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
    val todoBoard: List<TodoEntry>? = null,
)

internal class ActiveRunRecord(
    val sessionId: String,
    val runId: String,
    val requestId: String,
    val runtime: AgentRuntime,
    val resources: ActiveRunResources,
    val context: Context?,
    val startedAtEpochMillis: Long,
    job: Job? = null,
    recorders: RuntimeRunRecorders,
    tokenUsage: TokenUsageState = TokenUsageState(),
    currentRequest: CurrentRequest? = null,
    runtimeDemo: RuntimeDemoState = RuntimeDemoState(),
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

    var recorders: RuntimeRunRecorders = recorders
        get() = synchronized(stateLock) { field }
        set(value) = synchronized(stateLock) { field = value }

    var tokenUsage: TokenUsageState = tokenUsage
        get() = synchronized(stateLock) { field }
        set(value) = synchronized(stateLock) { field = value }

    var currentRequest: CurrentRequest? = currentRequest
        get() = synchronized(stateLock) { field }
        set(value) = synchronized(stateLock) { field = value }

    var runtimeDemo: RuntimeDemoState = runtimeDemo
        get() = synchronized(stateLock) { field }
        set(value) = synchronized(stateLock) { field = value }

    /**
     * Latest update_todo board decoded from the public event stream (display state, not storage).
     * Null means no update_todo event was observed for this run; an empty list is an explicit clear.
     */
    var todoBoard: List<TodoEntry>? = null
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

    fun addTokenUsage(delta: TokenDelta): TokenUsageState =
        synchronized(stateLock) {
            tokenUsage = tokenUsage.plus(delta)
            tokenUsage
        }

    fun snapshot(active: Boolean = isJobActive): ActiveRunSnapshot =
        synchronized(stateLock) {
            ActiveRunSnapshot(
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
internal class ActiveRunRegistry(
    val maxActiveSessions: Int = DEBUG_MAX_ACTIVE_SESSION_RUNS,
) {
    private val lock = Any()
    private val runs = LinkedHashMap<String, ActiveRunRecord>()

    init {
        require(maxActiveSessions >= 4) { "Sample multi-session bound must be at least 4" }
    }

    val size: Int get() = synchronized(lock) { runs.size }

    fun get(sessionId: String): ActiveRunRecord? = synchronized(lock) { runs[sessionId] }

    fun getExact(sessionId: String, runId: String): ActiveRunRecord? =
        synchronized(lock) { runs[sessionId]?.takeIf { it.runId == runId } }

    fun canAdmit(sessionId: String): Boolean = synchronized(lock) {
        runs.containsKey(sessionId) || runs.size < maxActiveSessions
    }

    fun admissionFor(sessionId: String): StartRunAdmission = synchronized(lock) {
        when {
            runs.containsKey(sessionId) -> StartRunAdmission.REPLACED_SAME_SESSION
            runs.size >= maxActiveSessions -> StartRunAdmission.BUSY_MAX_ACTIVE_SESSIONS
            else -> StartRunAdmission.STARTED
        }
    }

    fun put(record: ActiveRunRecord): ActiveRunRecord? = synchronized(lock) {
        val previous = runs[record.sessionId]
        runs[record.sessionId] = record
        previous
    }

    fun removeExact(sessionId: String, runId: String): ActiveRunRecord? = synchronized(lock) {
        val current = runs[sessionId] ?: return null
        if (current.runId != runId) return null
        runs.remove(sessionId)
    }

    fun discard(sessionId: String): ActiveRunRecord? = synchronized(lock) { runs.remove(sessionId) }

    fun drain(): List<ActiveRunRecord> = synchronized(lock) {
        val drained = runs.values.toList()
        runs.clear()
        drained
    }

    fun snapshots(): List<ActiveRunSnapshot> = synchronized(lock) {
        runs.values.map { it.snapshot() }
    }

    fun records(): List<ActiveRunRecord> = synchronized(lock) { runs.values.toList() }
}

internal fun SessionSummary.withRunOverlay(snapshot: ActiveRunSnapshot?): SessionSummary {
    if (snapshot == null) return this
    return copy(
        lifecycleStatus = snapshot.lifecycleStatus,
        completionOutcome = snapshot.completionOutcome,
        logHealth = snapshot.logHealth,
    )
}

internal fun SessionSummary.runStatusLine(): String =
    listOf(lifecycleStatus, completionOutcome, logHealth).joinToString(" · ")

internal fun busyActiveSessionStatus(maxActiveSessions: Int = DEBUG_MAX_ACTIVE_SESSION_RUNS): String =
    "Busy: max $maxActiveSessions active sessions"

internal fun busyActiveSessionMessage(maxActiveSessions: Int = DEBUG_MAX_ACTIVE_SESSION_RUNS): String =
    "Run not started: active session limit reached ($maxActiveSessions)."

/** Bounded cancel+join while the Sample admission mutex is held. Cooperative jobs finish immediately. */
internal const val DEBUG_ADMISSION_JOIN_TIMEOUT_MS = 250L
