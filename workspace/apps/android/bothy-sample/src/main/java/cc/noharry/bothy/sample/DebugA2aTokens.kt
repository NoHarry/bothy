package cc.noharry.bothy.sample

import android.content.Context
import cc.noharry.bothy.a2a.A2aAuthProvider
import cc.noharry.bothy.a2a.A2aProtectedHeaders
import cc.noharry.bothy.a2a.ExperimentalA2aClientApi
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bearer tokens for A2A profiles (a user's agent behind a relay needs one). One file per profile in no-backup storage,
 * never in preferences, the profile JSON, logs or tracked files. Clients read it before every request, so a changed
 * token applies without rebuilding the client.
 */
internal object DebugA2aTokens {
    fun has(context: Context, profileId: String): Boolean = read(context, profileId).isNotEmpty()

    /** Stores [token]; returns a message when it is unusable, null when saved. */
    fun save(context: Context, profileId: String, token: String): String? {
        val value = token.trim()
        if (value.isEmpty() || value.any(Char::isWhitespace)) return "Access token must be one word without spaces."
        return try {
            file(context, profileId).apply { parentFile?.mkdirs() }.writeText(value, Charsets.UTF_8)
            null
        } catch (_: IOException) {
            "Could not save the access token."
        }
    }

    fun clear(context: Context, profileId: String) {
        file(context, profileId).delete()
    }

    fun read(context: Context, profileId: String): String =
        runCatching { file(context, profileId).readText(Charsets.UTF_8).trim() }.getOrDefault("")

    /** `Authorization: Bearer …` while a token is stored for [profileId]; no headers otherwise. */
    @OptIn(ExperimentalA2aClientApi::class)
    fun authProvider(context: Context, profileId: String): A2aAuthProvider = A2aAuthProvider {
        val token = withContext(Dispatchers.IO) { read(context, profileId) }
        if (token.isEmpty()) A2aProtectedHeaders.empty() else A2aProtectedHeaders.of(mapOf(AUTHORIZATION to "Bearer $token"))
    }

    private fun file(context: Context, profileId: String): File =
        File(File(context.noBackupFilesDir, TOKEN_DIR), fileName(profileId))

    /** Profile ids are user-derived; a digest keeps them out of the path. */
    internal fun fileName(profileId: String): String =
        MessageDigest.getInstance("SHA-256").digest(profileId.toByteArray(Charsets.UTF_8))
            .take(FILE_NAME_BYTES).joinToString("") { "%02x".format(it) }

    internal const val TOKEN_DIR = "a2a-tokens"
    private const val FILE_NAME_BYTES = 16
    private const val AUTHORIZATION = "Authorization"
}
