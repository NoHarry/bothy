package cc.noharry.bothy.sample

import android.content.Context

internal class DebugSessionCoordinator(transcript: DebugTranscriptStore) {
    private val fields = DebugSessionFields(transcript)
    private val admin = DebugSessionAdmin(fields)

    var activeSessionId: String
        get() = fields.activeSessionId
        private set(value) {
            fields.activeSessionId = value
        }

    var sessions: List<DebugSessionSummary>
        get() = fields.sessions
        private set(value) {
            fields.sessions = value
        }

    fun ensure(context: Context): DebugSessionStore = sessionEnsure(fields, context)
    fun newSession(context: Context) = sessionNew(fields, context)
    fun selectSession(context: Context, sessionId: String) = sessionSelect(fields, context, sessionId)
    fun deleteSession(context: Context, sessionId: String) = sessionDelete(fields, context, sessionId)
    fun clearAllRuntimeData(context: Context) = sessionClearAll(fields, context)
    fun adoptActiveSessionId(sessionId: String) = admin.adoptActive(sessionId)
    fun reviveDeletedSession(sessionId: String) = admin.forgetDeleted(sessionId)
    fun attachStoreForTest(store: DebugSessionStore) = admin.attachStore(store)
    fun markSessionDeleted(sessionId: String) = admin.markDeleted(sessionId)
    fun isSessionDeleted(sessionId: String): Boolean = admin.isDeleted(sessionId)
    fun forgetDeletedSession(sessionId: String) = admin.forgetDeleted(sessionId)
    fun deletePersistedSession(sessionId: String) = admin.deletePersisted(sessionId)
    fun appendTurn(turn: DebugSessionTurn) = sessionAppendTurn(fields, turn)
    /** [sessionId]'s stored turns' usage by request id, or null until [loadTurnUsage] has read it. */
    fun storedTurnUsage(sessionId: String): Map<String, DebugTokenUsageState>? = fields.turnUsage[sessionId]
    fun loadTurnUsage(sessionId: String) = sessionLoadTurnUsage(fields, sessionId)
    fun board(): DebugSessionBoardState = debugSessionBoard(fields)
}
