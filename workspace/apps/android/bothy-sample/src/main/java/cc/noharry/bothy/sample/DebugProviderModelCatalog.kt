package cc.noharry.bothy.sample

import android.content.Context
import java.security.MessageDigest
import org.json.JSONArray

/** Saved model candidates; selecting the active model remains a separate composer action. */
internal fun loadDebugProviderModels(context: Context, profile: DebugProviderEditState): List<String> {
    val raw = context.getSharedPreferences(PROVIDER_MODELS_PREFS, Context.MODE_PRIVATE)
        .getString(providerModelsKey(profile), null) ?: return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        normalizeProviderModels((0 until array.length()).map { array.getString(it) })
    }.getOrDefault(emptyList())
}

internal fun saveDebugProviderModels(context: Context, profile: DebugProviderEditState, models: List<String>) {
    context.getSharedPreferences(PROVIDER_MODELS_PREFS, Context.MODE_PRIVATE).edit()
        .putString(providerModelsKey(profile), JSONArray(normalizeProviderModels(models)).toString()).apply()
}

internal fun deleteDebugProviderModels(context: Context, profileId: String) {
    val prefs = context.getSharedPreferences(PROVIDER_MODELS_PREFS, Context.MODE_PRIVATE)
    val prefix = "${profileId.preferenceKey()}:"
    prefs.edit().apply { prefs.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) } }.apply()
}

internal fun normalizeProviderModels(models: List<String>): List<String> =
    models.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

private fun providerModelsKey(profile: DebugProviderEditState): String {
    val connection = listOf(profile.provider.wireName, profile.endpoint.wireName, profile.baseUrl.trim()).joinToString("\u0000")
    val digest = MessageDigest.getInstance("SHA-256").digest(connection.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return "${profile.profileId.preferenceKey()}:$digest"
}

private const val PROVIDER_MODELS_PREFS = "debug_provider_models"
