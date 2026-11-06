package cc.noharry.agent.appserver.session
import android.content.Context
import cc.noharry.agent.appserver.protocol.SessionPolicy
import cc.noharry.agent.appserver.protocol.SessionSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** When a wake starts a new conversation ([SessionPolicy]). */
object SessionSettings {
    private val mutablePolicy = MutableStateFlow(SessionPolicy())
    val policy: StateFlow<SessionPolicy> = mutablePolicy

    fun init(context: Context) {
        val prefs = prefs(context)
        mutablePolicy.value = SessionPolicy(
            everyWake = prefs.getBoolean(KEY_EVERY_WAKE, false),
            idleMinutes = prefs.getInt(KEY_IDLE_MINUTES, SessionPolicy.DEFAULT_IDLE_MINUTES),
            maxSessions = prefs.getInt(KEY_MAX_SESSIONS, SessionPolicy.DEFAULT_MAX_SESSIONS),
        ).bounded()
    }

    fun save(context: Context, policy: SessionPolicy) {
        val bounded = policy.bounded()
        prefs(context).edit()
            .putBoolean(KEY_EVERY_WAKE, bounded.everyWake)
            .putInt(KEY_IDLE_MINUTES, bounded.idleMinutes)
            .putInt(KEY_MAX_SESSIONS, bounded.maxSessions)
            .apply()
        mutablePolicy.value = bounded
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "app_server_sessions"
    private const val KEY_EVERY_WAKE = "new_session_every_wake"
    private const val KEY_IDLE_MINUTES = "new_session_idle_minutes"
    private const val KEY_MAX_SESSIONS = "max_sessions"
}

/**
 * The sessions to delete to keep [keep]: the ones past the newest [keep] ([sessions] is newest first), except those in
 * [protected] (the selected one, one running, one with background work), which stay even over the limit.
 */
internal fun sessionsOverLimit(sessions: List<SessionSummary>, keep: Int, protected: Set<String>): List<String> =
    sessions.drop(keep.coerceAtLeast(1)).map { it.sessionId }.filterNot { it in protected }
