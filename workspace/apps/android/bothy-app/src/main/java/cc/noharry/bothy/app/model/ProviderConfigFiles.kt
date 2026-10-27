package cc.noharry.bothy.app.model
import android.content.Context
import cc.noharry.bothy.app.BuildConfig
import cc.noharry.bothy.app.session.ModelProvider
import cc.noharry.bothy.app.voice.tts.MIMO_TTS_API_KEY
import cc.noharry.bothy.app.voice.tts.MIMO_TTS_BASE_URL
import cc.noharry.bothy.app.voice.tts.MIMO_TTS_ENABLED
import cc.noharry.bothy.app.voice.tts.MIMO_TTS_FORMAT
import cc.noharry.bothy.app.voice.tts.MIMO_TTS_MODEL
import cc.noharry.bothy.app.voice.tts.MIMO_TTS_OPTIMIZE_TEXT_PREVIEW
import cc.noharry.bothy.app.voice.tts.MIMO_TTS_STREAM
import cc.noharry.bothy.app.voice.tts.MIMO_TTS_STYLE
import cc.noharry.bothy.app.voice.tts.MIMO_TTS_VOICE
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun buildConfigValues(): Map<String, String> {
    val fullEnv = parseProviderConfigText(BuildConfig.ENV_MODEL_PROVIDER_CONFIG)
    val localOverrides = parseProviderConfigText(BuildConfig.ENV_MODEL_PROVIDER_LOCAL_OVERRIDES)
    val fixedValues = mapOf(
        "AGENT_RUNTIME_MODEL_PROVIDER" to BuildConfig.ENV_AGENT_RUNTIME_MODEL_PROVIDER,
        "AGENT_RUNTIME_LIVE_PROVIDER" to BuildConfig.ENV_AGENT_RUNTIME_LIVE_PROVIDER,
        "AGENT_RUNTIME_MODEL_PROVIDER_STREAM" to BuildConfig.ENV_AGENT_RUNTIME_MODEL_PROVIDER_STREAM,
        "AGENT_RUNTIME_LIVE_PROVIDER_STREAM" to BuildConfig.ENV_AGENT_RUNTIME_LIVE_PROVIDER_STREAM,
        "OPENAI_API_KEY" to BuildConfig.ENV_OPENAI_API_KEY,
        "OPENAI_MODEL" to BuildConfig.ENV_OPENAI_MODEL,
        "OPENAI_BASE_URL" to BuildConfig.ENV_OPENAI_BASE_URL,
        "ANTHROPIC_API_KEY" to BuildConfig.ENV_ANTHROPIC_API_KEY,
        "ANTHROPIC_MODEL" to BuildConfig.ENV_ANTHROPIC_MODEL,
        "ANTHROPIC_BASE_URL" to BuildConfig.ENV_ANTHROPIC_BASE_URL,
    ).filterValues { it.isNotBlank() }
    return withLocalProfiles(fullEnv + fixedValues, localOverrides)
}

/**
 * local.properties may define a whole service, not just its key: `AGENT_RUNTIME_PROVIDER_PROFILE_<ID>_LABEL`,
 * `_PROVIDER` (openai / anthropic), `_ENDPOINT`, `_BASE_URL`, `_MODEL`, `_API_KEY`. One with a base URL and a key joins
 * the listed profiles. `AGENT_RUNTIME_DEFAULT_PROFILE` makes a listed profile the default and is ignored while the one
 * it names is not listed, so a half-filled block never leaves the device without a working default.
 */
internal fun withLocalProfiles(base: Map<String, String>, local: Map<String, String>): Map<String, String> {
    val listed = base[PROFILES_KEY].csvValues()
    val added = if (listed.isEmpty()) emptyList() else local.keys
        .filter { it.startsWith(PROFILE_ENV_PREFIX) && it.endsWith(LOCAL_PROFILE_BASE_URL) }
        .map { it.removePrefix(PROFILE_ENV_PREFIX).removeSuffix(LOCAL_PROFILE_BASE_URL) }
        .filter { envKey -> !local["$PROFILE_ENV_PREFIX${envKey}_API_KEY"].isNullOrBlank() }
        .filter { envKey -> listed.none { it.envProfileKey() == envKey } }
        .map { it.lowercase() }
    val profiles = listed + added
    val requestedDefault = local[DEFAULT_PROFILE_KEY]?.trim()
        ?.takeIf { requested -> profiles.any { it.envProfileKey() == requested.envProfileKey() } }
    return base + local.filterKeys { it != DEFAULT_PROFILE_KEY } +
        (if (added.isEmpty()) emptyMap() else mapOf(PROFILES_KEY to profiles.joinToString(","))) +
        (requestedDefault?.let { mapOf(DEFAULT_PROFILE_KEY to it) } ?: emptyMap())
}

private const val DEFAULT_PROFILE_KEY = "AGENT_RUNTIME_DEFAULT_PROFILE"
private const val LOCAL_PROFILE_BASE_URL = "_BASE_URL"

internal fun savedProviderValues(context: Context): Map<String, String> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val fixedValues = listOf(ModelProvider.OPENAI, ModelProvider.ANTHROPIC)
        .flatMap { provider ->
            listOf(
                providerKeyName(provider),
                providerModelName(provider),
                providerBaseUrlName(provider),
                providerEndpointName(provider),
                providerVariantName(provider),
                providerServerSideSearchName(provider),
                providerSearchCapabilityName(provider),
            )
        }
        .mapNotNull { name -> prefs.getString(name, null)?.takeIf { it.isNotBlank() }?.let { name to it } }
        .toMap()
    val modelVariantValues = prefs.all.mapNotNull { (name, value) ->
        (value as? String)
            ?.takeIf { it.isNotBlank() && name.contains(MODEL_VARIANT_KEY_SEPARATOR) }
            ?.let { name to it }
    }.toMap()
    return fixedValues + modelVariantValues
}

internal fun savedProfileState(context: Context): SavedProfileState {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val overrides = prefs.all.keys
        .mapNotNull { name -> name.takeIf { it.startsWith(PROFILE_PREF_PREFIX) }?.profileIdFromPref() }
        .distinct()
        .associateWith { id ->
            ProviderProfileOverride(
                label = prefs.getString(profilePrefName(id, PROFILE_FIELD_LABEL), null),
                provider = providerFromOrNull(prefs.getString(profilePrefName(id, PROFILE_FIELD_PROVIDER), null)),
                endpoint = prefs.getString(profilePrefName(id, PROFILE_FIELD_ENDPOINT), null),
                apiKey = prefs.getString(profilePrefName(id, PROFILE_FIELD_API_KEY), null),
                model = prefs.getString(profilePrefName(id, PROFILE_FIELD_MODEL), null),
                baseUrl = prefs.getString(profilePrefName(id, PROFILE_FIELD_BASE_URL), null),
                variant = prefs.getString(profilePrefName(id, PROFILE_FIELD_VARIANT), null),
                serverSideSearch = prefs.getString(profilePrefName(id, PROFILE_FIELD_SERVER_SIDE_SEARCH), null),
                searchCapability = prefs.getString(profilePrefName(id, PROFILE_FIELD_SEARCH_CAPABILITY), null),
            )
        }
    return SavedProfileState(
        selectedProfileId = prefs.getString(PREF_SELECTED_PROFILE_ID, null),
        manualProfileEnabled = prefs.getBoolean(PREF_MANUAL_PROFILE_ENABLED, false),
        overrides = overrides,
    )
}

internal fun configSource(
    buildValues: Map<String, String>,
    file: File?,
    savedValues: Map<String, String>,
    savedProfileState: SavedProfileState,
): String? =
    when {
        savedValues.isNotEmpty() || savedProfileState.hasSavedState ->
            "app settings + ${baseConfigSource(buildValues, file) ?: "defaults"}"
        else -> baseConfigSource(buildValues, file)
    }

private fun baseConfigSource(buildValues: Map<String, String>, file: File?): String? =
    when {
        buildValues.isNotEmpty() && file != null -> "${BuildConfig.PROVIDER_ENV_SOURCE} + ${file.absolutePath}"
        buildValues.isNotEmpty() -> BuildConfig.PROVIDER_ENV_SOURCE
        file != null -> file.absolutePath
        else -> null
    }

internal fun profilePrefName(profileId: String, field: String): String =
    "$PROFILE_PREF_PREFIX${profileId.preferenceKey()}.$field"

internal fun configFiles(context: Context): List<File> {
    val external = context.getExternalFilesDir(null)?.also { it.mkdirs() }
    return listOfNotNull(
        external?.resolve(CONFIG_FILE_NAME),
        external?.resolve(LEGACY_CONFIG_FILE_NAME),
        context.filesDir.resolve(CONFIG_FILE_NAME),
        context.filesDir.resolve(LEGACY_CONFIG_FILE_NAME),
    )
}

internal fun File.readEnvFile(): Map<String, String> =
    parseProviderConfigText(readText(Charsets.UTF_8))

internal fun parseProviderConfigText(text: String): Map<String, String> {
    val trimmed = text.trimStart()
    return if (trimmed.startsWith(JSON_OBJECT_PREFIX)) parseProviderJsonText(trimmed) else parseEnvText(text)
}

private fun parseProviderJsonText(text: String): Map<String, String> =
    runCatching { Json.parseToJsonElement(text) as? JsonObject }
        .getOrNull()
        ?.toProviderValues()
        .orEmpty()

private fun JsonObject.toProviderValues(): Map<String, String> =
    buildMap {
        stringValue("activeProfile", "active_profile")?.let { put("AGENT_RUNTIME_ACTIVE_PROFILE", it) }
        stringValue("defaultProfile", "default_profile")?.let { put("AGENT_RUNTIME_DEFAULT_PROFILE", it) }
        stringValue("modelProvider", "model_provider")?.let { put("AGENT_RUNTIME_MODEL_PROVIDER", it) }
        stringValue("liveProvider", "live_provider")?.let { put("AGENT_RUNTIME_LIVE_PROVIDER", it) }
        stringValue("stream")?.let { put("AGENT_RUNTIME_LIVE_PROVIDER_STREAM", it) }
        (this@toProviderValues["tts"] as? JsonObject)?.toTtsValues()?.let { putAll(it) }
        val profiles = (this@toProviderValues["profiles"] as? JsonArray)
            .orEmpty()
            .mapNotNull { it as? JsonObject }
        val profileIds = profiles.mapNotNull { it.profileId() }
        if (profileIds.isNotEmpty()) put(PROFILES_KEY, profileIds.joinToString(","))
        profiles.forEach { profile ->
            val id = profile.profileId() ?: return@forEach
            val prefix = "$PROFILE_ENV_PREFIX${id.envProfileKey()}_"
            profile.stringValue("label")?.let { put("${prefix}LABEL", it) }
            profile.stringValue("provider", "kind")?.let { put("${prefix}PROVIDER", it) }
            profile.stringValue("endpoint")?.let { put("${prefix}ENDPOINT", it) }
            profile.stringValue("apiKey", "api_key")?.let { put("${prefix}API_KEY", it) }
            profile.stringValue("model")?.let { put("${prefix}MODEL", it) }
            profile.stringValue("baseUrl", "base_url")?.let { put("${prefix}BASE_URL", it) }
            profile.stringValue("variant")?.let { put("${prefix}VARIANT", it) }
            profile.stringValue("stream")?.let { put("${prefix}STREAM", it) }
            profile.stringValue("serverSideSearch", "server_side_search", "serverSideWebSearch", "server_side_web_search")
                ?.let { put("${prefix}SERVER_SIDE_SEARCH", it) }
            profile.stringValue("searchCapability", "search_capability", "serverSideSearchCapability", "server_side_search_capability")
                ?.let { put("${prefix}SEARCH_CAPABILITY", it) }
        }
    }

private fun JsonObject.toTtsValues(): Map<String, String> =
    buildMap {
        stringValue("enabled")?.let { put(MIMO_TTS_ENABLED, it) }
        stringValue("apiKey", "api_key")?.let { put(MIMO_TTS_API_KEY, it) }
        stringValue("baseUrl", "base_url")?.let { put(MIMO_TTS_BASE_URL, it) }
        stringValue("model")?.let { put(MIMO_TTS_MODEL, it) }
        stringValue("voice")?.let { put(MIMO_TTS_VOICE, it) }
        stringValue("format")?.let { put(MIMO_TTS_FORMAT, it) }
        stringValue("style")?.let { put(MIMO_TTS_STYLE, it) }
        stringValue("optimizeTextPreview", "optimize_text_preview")
            ?.let { put(MIMO_TTS_OPTIMIZE_TEXT_PREVIEW, it) }
        stringValue("stream")?.let { put(MIMO_TTS_STREAM, it) }
    }

private fun JsonObject.profileId(): String? =
    stringValue("id")?.normalizedProfileId()?.takeIf { it.isNotBlank() }

private fun JsonObject.stringValue(vararg names: String): String? =
    names.firstNotNullOfOrNull { name -> this[name]?.scalarStringOrNull() }

private fun JsonElement.scalarStringOrNull(): String? =
    (this as? JsonPrimitive)?.takeUnless { it.toString() == "null" }?.jsonPrimitive?.contentOrNull

private fun parseEnvText(text: String): Map<String, String> =
    text.lineSequence()
        .mapNotNull(::parseEnvLine)
        .toMap()

private fun parseEnvLine(line: String): Pair<String, String>? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
    val split = trimmed.indexOf("=")
    if (split <= 0) return null
    val key = trimmed.substring(0, split).trim()
    val value = trimmed.substring(split + 1).trim().trimMatchingQuotes()
    return key to value
}

internal fun String?.orDefault(default: String): String =
    this?.takeIf { it.isNotBlank() } ?: default

internal fun String?.toStreamOverride(): Boolean? =
    toBooleanOverride()

internal fun String?.toBooleanOverride(): Boolean? =
    when (this?.trim()?.lowercase()) {
        "1", "true", "on", "yes" -> true
        "0", "false", "off", "no" -> false
        else -> null
    }

internal fun String?.csvValues(): List<String> =
    this?.split(",").orEmpty().map { it.trim() }.filter { it.isNotBlank() }

internal fun String.normalizedProfileId(): String =
    trim().lowercase().map { char ->
        if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_'
    }.joinToString("")

internal fun String.envProfileKey(): String =
    uppercase().map { char -> if (char.isLetterOrDigit()) char else '_' }.joinToString("")

internal fun String.preferenceKey(): String =
    trim().lowercase().map { char ->
        if (char.isLetterOrDigit()) char else '_'
    }.joinToString("")

private fun String.profileIdFromPref(): String? =
    removePrefix(PROFILE_PREF_PREFIX).substringBefore(".").takeIf { it.isNotBlank() }

internal fun String.isManualProfileId(): Boolean =
    preferenceKey().startsWith("${MANUAL_PROFILE_ID_PREFIX}_")

private fun String.trimMatchingQuotes(): String =
    if (length >= 2 && first() == last() && first() in setOf('"', '\'')) {
        substring(1, length - 1)
    } else {
        this
    }

internal fun String.isLocalBaseUrl(): Boolean {
    val lower = trim().lowercase()
    return lower.startsWith("http://localhost") || lower.startsWith("http://127.0.0.1")
}

internal const val CONFIG_FILE_NAME = "model-provider-profiles.json"
private const val LEGACY_CONFIG_FILE_NAME = "model-provider-profiles.properties"
private const val JSON_OBJECT_PREFIX = "{"
internal const val PREFS_NAME = "debug_provider_config"
internal const val PREF_SELECTED_PROFILE_ID = "selected_profile_id"
internal const val PREF_MANUAL_PROFILE_ENABLED = "manual_profile_enabled"
private const val PROFILE_PREF_PREFIX = "profile."
internal const val PROFILE_FIELD_LABEL = "label"
internal const val PROFILE_FIELD_PROVIDER = "provider"
internal const val PROFILE_FIELD_API_KEY = "api_key"
internal const val PROFILE_FIELD_MODEL = "model"
internal const val PROFILE_FIELD_BASE_URL = "base_url"
internal const val PROFILE_FIELD_ENDPOINT = "endpoint"
internal const val PROFILE_FIELD_VARIANT = "variant"
internal const val PROFILE_FIELD_SERVER_SIDE_SEARCH = "server_side_search"
internal const val PROFILE_FIELD_SEARCH_CAPABILITY = "search_capability"
internal val PROFILE_FIELDS = listOf(
    PROFILE_FIELD_LABEL,
    PROFILE_FIELD_PROVIDER,
    PROFILE_FIELD_API_KEY,
    PROFILE_FIELD_MODEL,
    PROFILE_FIELD_BASE_URL,
    PROFILE_FIELD_ENDPOINT,
    PROFILE_FIELD_VARIANT,
    PROFILE_FIELD_SERVER_SIDE_SEARCH,
    PROFILE_FIELD_SEARCH_CAPABILITY,
)
internal const val PROFILE_ENV_PREFIX = "AGENT_RUNTIME_PROVIDER_PROFILE_"
internal const val PROFILES_KEY = "AGENT_RUNTIME_PROVIDER_PROFILES"
internal const val MODEL_VARIANT_KEY_SEPARATOR = "__"
internal const val MANUAL_PROFILE_ID_PREFIX = "manual"
internal const val BUILD_CONFIG_EXPECTED_SOURCE =
    "APK BuildConfig generated from bothy-app/src/main/assets/model-provider-profiles.json"
internal const val DEFAULT_LOCAL_API_KEY = "local"
internal const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
internal const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
internal const val DEFAULT_ANTHROPIC_MODEL = "claude-3-5-haiku-latest"
internal const val DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com/v1"
