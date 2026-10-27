package cc.noharry.bothy.app.session
import android.content.Context
import cc.noharry.bothy.app.usage.TokenUsageState

internal class SessionCoordinator(transcript: TranscriptStore) {
    private val fields = SessionFields(transcript)
    private val admin = SessionAdmin(fields)

    var activeSessionId: String
        get() = fields.activeSessionId
        private set(value) {
            fields.activeSessionId = value
        }

    var sessions: List<SessionSummary>
        get() = fields.sessions
        private set(value) {
            fields.sessions = value
        }

    fun ensure(context: Context): SessionStore = sessionEnsure(fields, context)
    fun newSession(context: Context) = sessionNew(fields, context)
    fun selectSession(context: Context, sessionId: String) = sessionSelect(fields, context, sessionId)
    fun deleteSession(context: Context, sessionId: String) = sessionDelete(fields, context, sessionId)
    fun clearAllRuntimeData(context: Context) = sessionClearAll(fields, context)
    fun adoptActiveSessionId(sessionId: String) = admin.adoptActive(sessionId)
    fun reviveDeletedSession(sessionId: String) = admin.forgetDeleted(sessionId)
    fun attachStoreForTest(store: SessionStore) = admin.attachStore(store)
    fun markSessionDeleted(sessionId: String) = admin.markDeleted(sessionId)
    fun isSessionDeleted(sessionId: String): Boolean = admin.isDeleted(sessionId)
    fun forgetDeletedSession(sessionId: String) = admin.forgetDeleted(sessionId)
    fun deletePersistedSession(sessionId: String) = admin.deletePersisted(sessionId)
    fun appendTurn(turn: SessionTurn) = sessionAppendTurn(fields, turn)
    /** [sessionId]'s stored turns' usage by request id, or null until [loadTurnUsage] has read it. */
    fun storedTurnUsage(sessionId: String): Map<String, TokenUsageState>? = fields.turnUsage[sessionId]
    fun loadTurnUsage(sessionId: String) = sessionLoadTurnUsage(fields, sessionId)
    fun board(): SessionBoardState = sessionBoard(fields)
}
