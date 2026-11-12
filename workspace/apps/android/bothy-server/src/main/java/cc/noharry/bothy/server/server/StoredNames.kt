package cc.noharry.bothy.server.server

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File

/**
 * What Bothy v0.3.0 (and the releases before it, under the same names) kept under its debug console's names, moved to
 * the names the app server and its plugins read now ([RENAMED_PREFS]; Android Agent Runtime 0.5.0's agent-app-server,
 * agent-app-models and agent-android-use, and the plugins in plugins/), before the server starts ([BothyServer.install])
 * and so before anything reads them: each preferences file to its new name, and the conversations' records from the
 * workspace's `debug-sessions` into its `sessions`, where the server lists them and the model reads them as `/sessions`.
 * Every one of them is read alike under both names (the same keys and values, the same meta.json and turns.jsonl per
 * conversation), so all of it moves and nothing is dropped. Bothy's own stores kept their names; files kept their paths.
 *
 * Where both names hold something (a device put back on v0.3.0 and forward again), what the new name holds wins and the
 * old is dropped: a preferences file's values are kept and the old file's others added; a conversation both directories
 * hold keeps its new records. Once moved, the old file or directory is deleted, so no model key or transcript is left
 * where nothing reads it. A start after all is moved finds nothing. What fails is logged and tried again at the next
 * start; it never stops the app from starting.
 */
internal object StoredNames {
    fun migrate(app: Context) {
        runCatching { movePreferences(app) }.onFailure { failed("preferences", it) }
        runCatching { moveConversations(File(app.filesDir, WORKSPACE_DIR)) }.onFailure { failed(LEGACY_SESSIONS_DIR, it) }
    }

    /** Every file of [RENAMED_PREFS]; a setup finished with model settings moved is checked again ([Onboarding.recheckModel]). */
    private fun movePreferences(app: Context) {
        val modelSettings = prefsFile(app, LEGACY_MODEL_SETTINGS).exists()
        RENAMED_PREFS.forEach { (old, new) -> runCatching { app.movePreferences(old, new) }.onFailure { failed(old, it) } }
        if (modelSettings) Onboarding.recheckModel(app)
    }

    /** [old]'s values that [new] lacks are copied into it; once that lands, [old] is emptied and deleted. */
    private fun Context.movePreferences(old: String, new: String) {
        if (!prefsFile(this, old).exists()) return
        val from = getSharedPreferences(old, Context.MODE_PRIVATE)
        val to = getSharedPreferences(new, Context.MODE_PRIVATE)
        val kept = to.all.keys
        val editor = to.edit()
        from.all.filterKeys { it !in kept }.forEach { (key, value) -> editor.put(key, value) }
        // A copy that does not land keeps the old file for the next start.
        if (!editor.commit()) {
            Log.w(TAG, "Could not move $old; tried again at the next start")
            return
        }
        from.edit().clear().commit()
        if (!deleteSharedPreferences(old)) Log.w(TAG, "Could not delete $old")
    }

    /**
     * The conversations into the directory the server reads: all at once when it has none yet, else each one it lacks,
     * a conversation it holds already keeping its own records. The old index goes: the server writes its own from the
     * conversations whenever its store opens. What could not move stays for the next start.
     */
    private fun moveConversations(workspace: File) {
        val legacy = File(workspace, LEGACY_SESSIONS_DIR)
        if (!legacy.exists()) return
        // Not a directory of conversations: nothing in it to keep.
        if (!legacy.isDirectory) {
            legacy.delete()
            return
        }
        val sessions = File(workspace, SESSIONS_DIR)
        if (!sessions.exists() && legacy.renameTo(sessions)) return
        sessions.mkdirs()
        legacy.listFiles().orEmpty().forEach { entry ->
            val target = File(sessions, entry.name)
            when {
                entry.name == SESSIONS_INDEX || target.exists() -> entry.deleteRecursively()
                !entry.renameTo(target) -> Log.w(TAG, "Could not move conversation ${entry.name}; tried again at the next start")
            }
        }
        if (!legacy.delete()) Log.w(TAG, "$LEGACY_SESSIONS_DIR is not empty yet")
    }

    private fun prefsFile(context: Context, name: String) = File(context.dataDir, "shared_prefs/$name.xml")

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

    /** Never the values: the provider file holds the person's model keys. */
    private fun failed(name: String, error: Throwable) = Log.w(TAG, "Could not move $name: ${error.javaClass.simpleName}")

    /**
     * For installs coming from v0.3.0 (and earlier): each preferences file it kept under its debug console's name, and
     * the name it is read under now. Checked against what Android Agent Runtime 0.5.0 and the plugins read: the same keys
     * and values under the new name, so each moves whole (the plugins' on/off switches, the approval mode, plugin
     * packages and key destinations are new in 0.5.0, with nothing to move into them). Bothy's own stores (debug_voice,
     * debug_tts, debug_wake, debug_onboarding and the others) are read under the names v0.3.0 used.
     */
    internal val RENAMED_PREFS = listOf(
        // agent-app-server.
        "basic_tools", "context_windows", "fetch_fallback", "mcp_provider_config", "mcp_servers", "model_image_input",
        "provider_config", "provider_models", "runtime_skills", "saf_workspace", "search_backend", "sessions",
        "subagent_types", "subagents",
        // agent-app-models (DeepSeek's relay check) and agent-android-use (the screen-operation switch).
        "deepseek_probe", "android_use",
        // The A2A, pictures and memory plugins (the memory switch).
        "a2a_client", "image_model", "user_memory",
    ).map { "debug_$it" to "app_server_$it" }

    /** The model services' settings and keys, which decide whether the setup is checked again. */
    private const val LEGACY_MODEL_SETTINGS = "debug_provider_config"

    /** Where the server's workspace is, and its conversations in it (agent-app-server's store, `/sessions` to the model). */
    private const val WORKSPACE_DIR = "runtime-debug-workspace"
    private const val SESSIONS_DIR = "sessions"
    private const val LEGACY_SESSIONS_DIR = "debug-sessions"
    private const val SESSIONS_INDEX = "index.json"
    private const val TAG = "BothyStores"
}
