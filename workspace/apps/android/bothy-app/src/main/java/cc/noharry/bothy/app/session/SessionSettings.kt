package cc.noharry.bothy.app.session
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * When a wake starts a new conversation, and how many conversations are kept (owner 2026-10-25: 「xx分钟后再唤醒才开新
 * session,这个改成可以配置吧，时间可以配置，然后也可以配置每次唤醒都开新session,还有session数量上限也配置，默认先用20个
 * 吧，超过就清掉前面的」).
 */
internal data class SessionPolicy(
    /** Every wake starts a new conversation, however recent the last turn. */
    val everyWake: Boolean = false,
    /** Otherwise, how long without a turn before a wake does. */
    val idleMinutes: Int = DEFAULT_IDLE_MINUTES,
    /** Conversations kept; past it the ones touched longest ago are deleted, with all they hold. */
    val maxSessions: Int = DEFAULT_MAX_SESSIONS,
) {
    val idleMillis: Long get() = idleMinutes * 60_000L

    fun bounded(): SessionPolicy = copy(
        idleMinutes = idleMinutes.coerceIn(MIN_IDLE_MINUTES, MAX_IDLE_MINUTES),
        maxSessions = maxSessions.coerceIn(MIN_SESSIONS, MAX_SESSIONS),
    )

    companion object {
        const val DEFAULT_IDLE_MINUTES = 5
        const val DEFAULT_MAX_SESSIONS = 20
        const val MIN_IDLE_MINUTES = 1
        const val MAX_IDLE_MINUTES = 240
        const val MIN_SESSIONS = 1
        const val MAX_SESSIONS = 500
    }
}

/** 设置 → 通用 → 会话. */
internal object SessionSettings {
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

    private const val PREFS = "debug_sessions"
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
