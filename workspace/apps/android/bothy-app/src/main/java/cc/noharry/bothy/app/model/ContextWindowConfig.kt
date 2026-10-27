package cc.noharry.bothy.app.model
import android.content.Context
import cc.noharry.bothy.app.plans.number
import cc.noharry.bothy.app.usage.tokens
import java.security.MessageDigest

/** Shared model capacity for usage display and next-run automatic compaction at80percent. */
internal fun loadContextWindow(context: Context, profile: ProviderEditState): Long? {
    val stored = context.getSharedPreferences(CONTEXT_WINDOW_PREFS, Context.MODE_PRIVATE)
        .getLong(modelIdentityKey(profile), 0L)
    return stored.takeIf { it > 0 } ?: defaultContextWindow(profile.model)
}

internal fun saveContextWindow(context: Context, profile: ProviderEditState, tokens: Long?) {
    require(tokens == null || tokens > 0) { "Context window must be positive." }
    context.getSharedPreferences(CONTEXT_WINDOW_PREFS, Context.MODE_PRIVATE)
        .edit().apply {
            if (tokens == null) remove(modelIdentityKey(profile)) else putLong(modelIdentityKey(profile), tokens)
        }.apply()
}

internal fun defaultContextWindow(model: String): Long =
    // These are the current deployment aliases whose 1M window the owner confirmed.
    // Do not infer the same capacity for older DeepSeek models or other providers.
    if (model.substringAfterLast('/').lowercase() in OWNER_CONFIRMED_DEEPSEEK_MODELS) DEEPSEEK_CONTEXT_TOKENS else DEFAULT_CONTEXT_TOKENS

/** One saved model: profile, provider, endpoint, base URL and model ID. Keys every per-model setting. */
internal fun modelIdentityKey(profile: ProviderEditState): String {
    val identity = listOf(profile.profileId, profile.provider.wireName, profile.endpoint.wireName, profile.baseUrl, profile.model)
        .joinToString(separator = "\u0000")
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal const val CONTEXT_WINDOW_PREFS = "debug_context_windows"
private const val DEFAULT_CONTEXT_TOKENS = 128_000L
private const val DEEPSEEK_CONTEXT_TOKENS = 1_000_000L
private val OWNER_CONFIRMED_DEEPSEEK_MODELS = setOf("deepseek-flash", "deepseek-v4.1-flash")

internal fun contextWindowOverride(context: Context, profile: ProviderEditState): Long? =
    context.getSharedPreferences(CONTEXT_WINDOW_PREFS, Context.MODE_PRIVATE)
        .getLong(modelIdentityKey(profile), 0L).takeIf { it > 0 }

internal fun parseContextWindow(text: String): Long? {
    val value = text.trim().uppercase()
    val multiplier = when (value.lastOrNull()) { 'K' -> 1_000L; 'M' -> 1_000_000L; else -> 1L }
    val number = if (multiplier == 1L) value else value.dropLast(1)
    return runCatching { number.toBigDecimal().multiply(multiplier.toBigDecimal()).longValueExact() }
        .getOrNull()?.takeIf { it > 0 }
}
