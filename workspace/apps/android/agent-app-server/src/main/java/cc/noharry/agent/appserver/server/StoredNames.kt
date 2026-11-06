package cc.noharry.agent.appserver.server

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import cc.noharry.agent.appserver.runtime.appWorkspaceRoot
import cc.noharry.agent.appserver.session.SESSIONS_DIR
import cc.noharry.agent.appserver.session.TURNS_FILE
import java.io.File

/**
 * What an earlier version stored under its debug console's names, moved to the server's own before anything reads it:
 * each preferences file to its `app_server_` name, and each session's records into the workspace's sessions directory,
 * which the model reads as `/sessions`. It runs at every start and finds nothing once all is moved; what an older build
 * wrote again under the old names (a device put back on one) is merged in the same way, without replacing what the
 * server already keeps under its own. A session both keep with turns is left aside in `debug-sessions-unmerged`.
 */
internal object StoredNames {
    fun migrate(app: Context) {
        RENAMED_PREFS.forEach { (old, new) -> runCatching { app.movePreferences(old, new) }.onFailure { failed(old, it) } }
        runCatching { moveSessions(File(appWorkspaceRoot(app), LEGACY_SESSIONS_DIR), File(appWorkspaceRoot(app), SESSIONS_DIR)) }
            .onFailure { failed(LEGACY_SESSIONS_DIR, it) }
    }

    /** [old]'s values that [new] lacks are copied into it; once that lands, [old] is emptied and deleted. */
    private fun Context.movePreferences(old: String, new: String) {
        if (!File(dataDir, "shared_prefs/$old.xml").exists()) return
        val from = getSharedPreferences(old, Context.MODE_PRIVATE)
        val to = getSharedPreferences(new, Context.MODE_PRIVATE)
        val kept = to.all.keys
        val editor = to.edit()
        from.all.filterKeys { it !in kept }.forEach { (key, value) -> editor.put(key, value) }
        // A copy that does not land keeps the old file for the next start.
        if (!editor.commit()) return
        from.edit().clear().commit()
        deleteSharedPreferences(old)
    }

    /**
     * Each session's folder that [sessions] lacks moves into it, and one it has already is dropped while it holds no turns
     * (a session an older build only opened). What is left is set aside once, so no start looks at it again.
     */
    private fun moveSessions(legacy: File, sessions: File) {
        if (!legacy.isDirectory) return
        if (!sessions.exists()) {
            if (legacy.renameTo(sessions)) return
            sessions.mkdirs()
        }
        legacy.listFiles().orEmpty().forEach { entry ->
            val target = File(sessions, entry.name)
            when {
                // The index is written anew from the sessions whenever the store opens.
                entry.name == SESSIONS_INDEX -> entry.delete()
                target.exists() -> if (!File(entry, TURNS_FILE).exists()) entry.deleteRecursively()
                !entry.renameTo(target) -> Log.w(TAG, "Could not move $entry to $target")
            }
        }
        if (legacy.list().isNullOrEmpty()) {
            legacy.delete()
        } else {
            val aside = generateSequence(1) { it + 1 }
                .map { File(legacy.parentFile, if (it == 1) UNMERGED_SESSIONS_DIR else "$UNMERGED_SESSIONS_DIR-$it") }
                .first { !it.exists() }
            Log.w(TAG, "Sessions both builds kept: ${legacy.list()?.joinToString()}; set aside in $aside")
            if (!legacy.renameTo(aside)) Log.w(TAG, "Could not set $legacy aside")
        }
    }

    private fun SharedPreferences.Editor.put(key: String, value: Any?) {
        when (value) {
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }

    private fun failed(name: String, error: Throwable) = Log.w(TAG, "Could not move $name: ${error.javaClass.simpleName}")

    /** Each preferences file the server kept under its debug console's name, and its own. */
    internal val RENAMED_PREFS = listOf(
        "a2a_client", "android_use", "basic_tools", "context_windows", "deepseek_probe", "fetch_fallback", "image_model",
        "mcp_provider_config", "mcp_servers", "model_image_input", "provider_config", "provider_models", "runtime_skills",
        "saf_workspace", "search_backend", "sessions", "subagent_types", "subagents", "user_memory",
    ).map { "debug_$it" to "app_server_$it" }

    private const val LEGACY_SESSIONS_DIR = "debug-sessions"
    private const val UNMERGED_SESSIONS_DIR = "debug-sessions-unmerged"
    private const val SESSIONS_INDEX = "index.json"
    private const val TAG = "AppServer"
}
