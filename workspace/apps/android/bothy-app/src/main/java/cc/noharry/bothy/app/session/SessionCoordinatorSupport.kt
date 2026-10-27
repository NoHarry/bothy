package cc.noharry.bothy.app.session
import android.content.Context
import cc.noharry.bothy.app.model.summary
import cc.noharry.bothy.app.runtime.DEBUG_SESSION_ID
import cc.noharry.bothy.app.runtime.RuntimeDataCleanupResult
import cc.noharry.bothy.app.runtime.clearRuntimeDataOnDisk
import cc.noharry.bothy.app.usage.TokenUsageState

/** Mutable session fields shared by thin coordinator and file-level helpers. */
internal class SessionFields(val transcript: TranscriptStore) {
    @Volatile var activeSessionId: String = DEBUG_SESSION_ID
    @Volatile var sessions: List<SessionSummary> = emptyList()
    @Volatile var store: SessionStore? = null
    var boardSnapshot: SessionBoardState? = null
    var boardStore: SessionStore? = null
    var boardSessionId: String? = null
    val deletedSessionIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    /** Each loaded session's stored turns' usage, by request id; the Board adds the live run (see DebugTokenUsage). */
    val turnUsage = java.util.concurrent.ConcurrentHashMap<String, Map<String, TokenUsageState>>()
}

/** Deletion, tombstone, and test/admin seams for [SessionCoordinator]. */
internal class SessionAdmin(private val fields: SessionFields) {
    fun adoptActive(sessionId: String) {
        fields.activeSessionId = sessionId
    }

    fun attachStore(store: SessionStore) {
        fields.store = store
        fields.sessions = store.listSessions()
    }

    fun markDeleted(sessionId: String) {
        fields.deletedSessionIds.add(sessionId)
    }

    fun isDeleted(sessionId: String): Boolean = sessionId in fields.deletedSessionIds

    fun forgetDeleted(sessionId: String) {
        fields.deletedSessionIds.remove(sessionId)
    }

    fun deletePersisted(sessionId: String) {
        markDeleted(sessionId)
        fields.store?.deleteSession(sessionId)
        fields.sessions = fields.store?.listSessions().orEmpty()
        if (fields.activeSessionId == sessionId) {
            fields.store?.let { sessionSelectFallback(fields, it) }
        }
    }
}

internal fun sessionEnsure(fields: SessionFields, context: Context): SessionStore {
    val current = fields.store ?: sessionStore(context).also { fields.store = it }
    current.ensureSession(fields.activeSessionId)
    fields.sessions = current.listSessions()
    if (fields.transcript.snapshot().isEmpty()) {
        fields.transcript.replaceAll(current.transcriptItems(fields.activeSessionId))
    }
    return current
}

internal fun sessionNew(fields: SessionFields, context: Context) {
    val current = sessionEnsure(fields, context)
    fields.activeSessionId = newSessionId()
    fields.deletedSessionIds.remove(fields.activeSessionId)
    current.ensureSession(fields.activeSessionId)
    fields.sessions = current.listSessions()
    fields.transcript.replaceAll(emptyList())
}

internal fun sessionSelect(fields: SessionFields, context: Context, sessionId: String) {
    val current = sessionEnsure(fields, context)
    fields.deletedSessionIds.remove(sessionId)
    fields.activeSessionId = sessionId
    current.ensureSession(sessionId)
    fields.sessions = current.listSessions()
    fields.transcript.replaceAll(current.transcriptItems(sessionId))
}

internal fun sessionDelete(fields: SessionFields, context: Context, sessionId: String) {
    fields.deletedSessionIds.add(sessionId)
    val current = sessionEnsure(fields, context)
    current.deleteSession(sessionId)
    fields.sessions = current.listSessions()
    if (fields.activeSessionId == sessionId) {
        sessionSelectFallback(fields, current)
    }
}

internal fun sessionClearAll(
    fields: SessionFields,
    context: Context,
): RuntimeDataCleanupResult {
    val app = context.applicationContext
    val result = clearRuntimeDataOnDisk(app)
    fields.store = null
    fields.deletedSessionIds.clear()
    fields.activeSessionId = newSessionId()
    fields.transcript.replaceAll(emptyList())
    fields.sessions = emptyList()
    sessionEnsure(fields, app)
    return result
}

internal fun sessionAppendTurn(fields: SessionFields, turn: SessionTurn) {
    if (turn.sessionId in fields.deletedSessionIds) return
    fields.store?.appendTurn(turn)
    fields.sessions = fields.store?.listSessions().orEmpty()
    fields.turnUsage.computeIfPresent(turn.sessionId) { _, usage -> usage + (turn.requestId to turn.usage) }
}

/** Reads [sessionId]'s stored turns' usage once (IO); later turns are added as they are appended. */
internal fun sessionLoadTurnUsage(fields: SessionFields, sessionId: String) {
    if (fields.turnUsage.containsKey(sessionId)) return
    // No store or an unreadable one counts as loaded with nothing stored, so the Board does not keep retrying.
    val stored = runCatching { fields.store?.readTurns(sessionId).orEmpty().associate { it.requestId to it.usage } }.getOrDefault(emptyMap())
    fields.turnUsage.putIfAbsent(sessionId, stored)
}

internal fun sessionSelectFallback(fields: SessionFields, current: SessionStore) {
    fields.activeSessionId =
        fields.sessions.firstOrNull()?.sessionId ?: newSessionId().also(current::ensureSession)
    fields.sessions = current.listSessions()
    fields.transcript.replaceAll(current.transcriptItems(fields.activeSessionId))
}

internal fun sessionBoard(fields: SessionFields): SessionBoardState = synchronized(fields) {
    val summary = fields.sessions.firstOrNull { it.sessionId == fields.activeSessionId }
    fields.boardSnapshot?.takeIf {
        it.activeSession === summary && fields.boardStore === fields.store &&
            fields.boardSessionId == fields.activeSessionId
    }?.let { return@synchronized it }
    SessionBoardState(
        activeSession = summary,
        recentTurns = fields.store?.readTurns(fields.activeSessionId).orEmpty().takeLast(6),
        lastAction = fields.store?.readContext(fields.activeSessionId)?.lastAction,
    ).also {
        fields.boardSnapshot = it
        fields.boardStore = fields.store
        fields.boardSessionId = fields.activeSessionId
    }
}
