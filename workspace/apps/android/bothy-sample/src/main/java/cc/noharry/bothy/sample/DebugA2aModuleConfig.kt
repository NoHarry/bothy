package cc.noharry.bothy.sample

import android.content.Context
import android.content.SharedPreferences
import cc.noharry.bothy.a2a.A2aAuthProvider
import cc.noharry.bothy.a2a.A2aClient
import cc.noharry.bothy.a2a.A2aClientConfig
import cc.noharry.bothy.a2a.A2aClientModule
import cc.noharry.bothy.a2a.ExperimentalA2aClientApi
import cc.noharry.bothy.a2a.direct.DirectA2aClientModule
import cc.noharry.bothy.a2a.official.OfficialA2aClientModule
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class DebugA2aProfile(
    val id: String,
    val label: String,
    val moduleId: String,
    val endpoint: URI,
    val enabled: Boolean,
    val allowLoopbackHttpForDebug: Boolean,
    val userDefined: Boolean = false,
    /** What the remote agent can do, in the user's words; the delegation tool's description carries it. */
    val description: String = "",
)

/** What the Add form submits; [token] is kept only until it is stored by [DebugA2aTokens]. */
internal class DebugA2aProfileDraft(
    val label: String,
    val moduleId: String,
    val endpoint: String,
    val allowLoopback: Boolean,
    val description: String = "",
    val token: String = "",
)

/** Outcome of a user-profile save attempt: either a reloaded config or a validation reason. */
internal sealed interface DebugA2aSaveResult {
    data class Saved(val config: DebugA2aModuleConfig) : DebugA2aSaveResult

    data class Rejected(val reason: String) : DebugA2aSaveResult
}

internal data class DebugA2aModuleState(
    val profiles: List<DebugA2aProfile> = emptyList(),
    val activeProfileId: String? = null,
    val loadedModuleId: String? = null,
    val error: String? = null,
    val enabledProfileIds: Set<String> = emptySet(),
    val loadedModuleIds: Map<String, String> = emptyMap(),
    val errorsByProfileId: Map<String, String> = emptyMap(),
    /** Profiles with a stored access token ([DebugA2aTokens]); the token itself never enters state. */
    val tokenProfileIds: Set<String> = emptySet(),
) {
    val activeProfile: DebugA2aProfile?
        get() = profiles.firstOrNull { it.id == activeProfileId }

    val enabledProfiles: List<DebugA2aProfile>
        get() = profiles.filter { it.id in enabledProfileIds }

    val loadedProfiles: List<DebugA2aProfile>
        get() = profiles.filter { it.id in loadedModuleIds }

    val summary: String
        get() = when {
            profiles.isEmpty() -> error ?: "No A2A client profiles"
            enabledProfileIds.isEmpty() -> "A2A clients disabled"
            else -> "${enabledProfileIds.size} enabled · ${loadedModuleIds.size} loaded · ${errorsByProfileId.size} errors"
        }
}

@OptIn(ExperimentalA2aClientApi::class)
internal fun interface DebugA2aModuleFactory {
    fun create(): A2aClientModule
}

@OptIn(ExperimentalA2aClientApi::class)
internal class DebugA2aModuleRegistry(
    private val factories: Map<String, DebugA2aModuleFactory>,
) {
    fun moduleIds(): Set<String> = factories.keys

    fun create(moduleId: String): A2aClientModule =
        factories[moduleId]?.create() ?: error("Unknown packaged A2A module: $moduleId")

    companion object {
        fun packaged(): DebugA2aModuleRegistry = DebugA2aModuleRegistry(
            mapOf(
                DIRECT_MODULE_ID to DebugA2aModuleFactory { DirectA2aClientModule() },
                OFFICIAL_MODULE_ID to DebugA2aModuleFactory { OfficialA2aClientModule() },
            ),
        )
    }
}

@OptIn(ExperimentalA2aClientApi::class)
internal data class DebugA2aModuleConfig(
    val state: DebugA2aModuleState,
    val modules: Map<String, A2aClientModule> = emptyMap(),
    /** Per-profile request authentication; [load] wires stored access tokens, text-only loads send none. */
    val auth: (String) -> A2aAuthProvider = { A2aAuthProvider.None },
) {
    val module: A2aClientModule?
        get() = state.activeProfileId?.let(modules::get)

    fun clientConfig(profileId: String): A2aClientConfig? =
        state.profiles.firstOrNull { it.id == profileId && it.enabled }?.let { profile ->
            A2aClientConfig(allowLoopbackHttpForDebug = profile.allowLoopbackHttpForDebug, authProvider = auth(profile.id))
        }

    fun clientConfig(): A2aClientConfig? = state.activeProfileId?.let(::clientConfig)

    fun createClient(profileId: String): A2aClient? {
        val config = clientConfig(profileId) ?: return null
        return modules[profileId]?.factory()?.create(config)
    }

    fun createClient(): A2aClient? = state.activeProfileId?.let(::createClient)

    companion object {
        fun load(
            context: Context,
            registry: DebugA2aModuleRegistry = DebugA2aModuleRegistry.packaged(),
        ): DebugA2aModuleConfig {
            val preferences = context.getSharedPreferences(A2A_PREFS_NAME, Context.MODE_PRIVATE)
            val enabledIds = when {
                preferences.contains(A2A_ENABLED_PROFILES) ->
                    preferences.getStringSet(A2A_ENABLED_PROFILES, emptySet()).orEmpty().toSet()
                preferences.contains(A2A_SELECTED_PROFILE) -> migrateLegacySelection(preferences)
                else -> null
            }
            return loadMerged(
                text = context.assets.open(A2A_PROFILE_ASSET).bufferedReader().use { it.readText() },
                userProfilesJson = preferences.getString(A2A_USER_PROFILES, null),
                enabledProfileIds = enabledIds,
                registry = registry,
            ).withTokens(context.applicationContext)
        }

        /** True while any profile is enabled; preferences only, cheap enough for every run's skill selection. */
        fun hasEnabledProfiles(context: Context): Boolean =
            context.getSharedPreferences(A2A_PREFS_NAME, Context.MODE_PRIVATE)
                .getStringSet(A2A_ENABLED_PROFILES, emptySet()).orEmpty().isNotEmpty()

        fun setProfileEnabled(
            context: Context,
            profileId: String,
            enabled: Boolean,
            registry: DebugA2aModuleRegistry = DebugA2aModuleRegistry.packaged(),
        ): DebugA2aModuleConfig {
            val current = load(context, registry)
            val enabledIds = current.state.enabledProfileIds.toMutableSet()
            if (enabled) enabledIds += profileId else enabledIds -= profileId
            return setEnabledProfiles(context, enabledIds, registry)
        }

        fun setEnabledProfiles(
            context: Context,
            profileIds: Set<String>,
            registry: DebugA2aModuleRegistry = DebugA2aModuleRegistry.packaged(),
        ): DebugA2aModuleConfig {
            context.getSharedPreferences(A2A_PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putStringSet(A2A_ENABLED_PROFILES, profileIds.toSet())
                .remove(A2A_SELECTED_PROFILE)
                .apply()
            return load(context, registry)
        }

        /** Compatibility entry point: selecting now enables the profile without disabling its peers. */
        fun select(
            context: Context,
            profileId: String,
            registry: DebugA2aModuleRegistry = DebugA2aModuleRegistry.packaged(),
        ): DebugA2aModuleConfig = setProfileEnabled(context, profileId, enabled = true, registry)

        /**
         * Validates and persists a user-defined profile (a new one is stored disabled; enablement stays in
         * [A2A_ENABLED_PROFILES]). Asset profiles keep precedence: generated ids never collide with packaged or
         * existing user ids. With [replaceSameLabel] a user profile of the same label is updated in place (adb setup).
         * A non-blank [token] is stored for the profile; a blank one leaves any stored token as it is.
         */
        fun saveUserProfile(
            context: Context,
            label: String,
            moduleId: String,
            endpoint: String,
            allowLoopbackHttpForDebug: Boolean,
            registry: DebugA2aModuleRegistry = DebugA2aModuleRegistry.packaged(),
            description: String = "",
            token: String = "",
            replaceSameLabel: Boolean = false,
        ): DebugA2aSaveResult {
            val trimmedLabel = label.trim()
            val trimmedEndpoint = endpoint.trim()
            validateUserProfile(trimmedLabel, moduleId, trimmedEndpoint, allowLoopbackHttpForDebug, registry)
                ?.let { return DebugA2aSaveResult.Rejected(it) }
            if (token.trim().any(Char::isWhitespace)) return DebugA2aSaveResult.Rejected("Access token must be one word without spaces.")
            val preferences = context.getSharedPreferences(A2A_PREFS_NAME, Context.MODE_PRIVATE)
            val stored = readUserProfiles(preferences)
            val replaced = stored.firstOrNull { replaceSameLabel && it.label == trimmedLabel }
            val takenIds = load(context, registry).state.profiles.mapTo(hashSetOf()) { it.id } + stored.map { it.id }
            val profile = DebugA2aProfile(
                id = replaced?.id ?: generateUserProfileId(trimmedLabel, takenIds),
                label = trimmedLabel,
                moduleId = moduleId,
                endpoint = URI(trimmedEndpoint),
                enabled = false,
                allowLoopbackHttpForDebug = allowLoopbackHttpForDebug,
                userDefined = true,
                description = description.trim(),
            )
            if (token.isNotBlank()) DebugA2aTokens.save(context, profile.id, token)?.let { return DebugA2aSaveResult.Rejected(it) }
            writeUserProfiles(preferences, stored.filterNot { it.id == profile.id } + profile)
            return DebugA2aSaveResult.Saved(load(context, registry))
        }

        /**
         * Removes a user-defined profile and its enabled flag; returns null (refused) when
         * [profileId] is a packaged asset profile or does not exist.
         */
        fun deleteUserProfile(
            context: Context,
            profileId: String,
            registry: DebugA2aModuleRegistry = DebugA2aModuleRegistry.packaged(),
        ): DebugA2aModuleConfig? {
            val preferences = context.getSharedPreferences(A2A_PREFS_NAME, Context.MODE_PRIVATE)
            val stored = readUserProfiles(preferences)
            if (stored.none { it.id == profileId }) return null
            writeUserProfiles(preferences, stored.filterNot { it.id == profileId })
            DebugA2aTokens.clear(context, profileId)
            val remainingEnabled = load(context, registry).state.enabledProfileIds - profileId
            return setEnabledProfiles(context, remainingEnabled, registry)
        }

        fun loadText(
            text: String,
            enabledProfileIds: Set<String>?,
            registry: DebugA2aModuleRegistry,
        ): DebugA2aModuleConfig = loadMerged(text, userProfilesJson = null, enabledProfileIds, registry)

        fun loadText(
            text: String,
            selectedProfileId: String?,
            registry: DebugA2aModuleRegistry,
        ): DebugA2aModuleConfig = loadText(text, selectedProfileId?.let(::setOf), registry)
    }
}

@OptIn(ExperimentalA2aClientApi::class)
private fun createConfig(
    profiles: List<DebugA2aProfile>,
    enabledIds: Set<String>,
    registry: DebugA2aModuleRegistry,
): DebugA2aModuleConfig {
    val modules = linkedMapOf<String, A2aClientModule>()
    val errors = linkedMapOf<String, String>()
    profiles.filter { it.enabled }.forEach { profile ->
        runCatching { registry.create(profile.moduleId) }
            .onSuccess { modules[profile.id] = it }
            .onFailure { errors[profile.id] = it.message ?: "Unable to load packaged A2A module" }
    }
    val activeId = profiles.firstOrNull { it.id in modules }?.id ?: profiles.firstOrNull { it.enabled }?.id
    return DebugA2aModuleConfig(
        state = DebugA2aModuleState(
            profiles = profiles,
            activeProfileId = activeId,
            loadedModuleId = activeId?.let { modules[it] }?.let { profiles.first { profile -> profile.id == activeId }.moduleId },
            error = errors.values.firstOrNull(),
            enabledProfileIds = enabledIds.toSet(),
            loadedModuleIds = modules.keys.associateWith { id -> profiles.first { it.id == id }.moduleId },
            errorsByProfileId = errors,
        ),
        modules = modules,
    )
}

private fun DebugA2aModuleConfig.withTokens(context: Context): DebugA2aModuleConfig = copy(
    state = state.copy(tokenProfileIds = state.profiles.filter { DebugA2aTokens.has(context, it.id) }.mapTo(linkedSetOf()) { it.id }),
    auth = { profileId -> DebugA2aTokens.authProvider(context, profileId) },
)

private fun migrateLegacySelection(preferences: SharedPreferences): Set<String> {
    val enabledIds = preferences.getString(A2A_SELECTED_PROFILE, null)?.let(::setOf).orEmpty()
    preferences.edit()
        .putStringSet(A2A_ENABLED_PROFILES, enabledIds)
        .remove(A2A_SELECTED_PROFILE)
        .apply()
    return enabledIds
}

@OptIn(ExperimentalA2aClientApi::class)
private fun loadMerged(
    text: String,
    userProfilesJson: String?,
    enabledProfileIds: Set<String>?,
    registry: DebugA2aModuleRegistry,
): DebugA2aModuleConfig = runCatching {
    val root = Json.parseToJsonElement(text).jsonObject
    val assetProfiles = root.requiredArray("profiles").map { parseProfile(it.jsonObject) }
    require(assetProfiles.map { it.id }.distinct().size == assetProfiles.size) { "Duplicate A2A profile id" }
    val assetIds = assetProfiles.mapTo(hashSetOf()) { it.id }
    val userProfiles = parseStoredUserProfiles(userProfilesJson).filter { it.id !in assetIds }
    val parsedProfiles = assetProfiles + userProfiles
    val requestedIds = enabledProfileIds ?: parsedProfiles.filter { it.enabled }.mapTo(linkedSetOf()) { it.id }
    val configuredIds = parsedProfiles.mapTo(hashSetOf()) { it.id }
    val enabledIds = requestedIds.filterTo(linkedSetOf()) { it in configuredIds }
    val profiles = parsedProfiles.map { it.copy(enabled = it.id in enabledIds) }
    createConfig(profiles, enabledIds, registry)
}.getOrElse { failure ->
    DebugA2aModuleConfig(
        state = DebugA2aModuleState(error = failure.message ?: "Invalid A2A client configuration"),
    )
}

/** Stored user JSON is self-written; a corrupted blob must not take the packaged profiles down with it. */
private fun parseStoredUserProfiles(json: String?): List<DebugA2aProfile> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val profiles = Json.parseToJsonElement(json).jsonArray.map { parseProfile(it.jsonObject).copy(userDefined = true) }
        require(profiles.map { it.id }.distinct().size == profiles.size) { "Duplicate A2A profile id" }
        profiles
    }.getOrElse { emptyList() }
}

private fun readUserProfiles(preferences: SharedPreferences): List<DebugA2aProfile> =
    parseStoredUserProfiles(preferences.getString(A2A_USER_PROFILES, null))

private fun writeUserProfiles(preferences: SharedPreferences, profiles: List<DebugA2aProfile>) {
    val json = JsonArray(
        profiles.map { profile ->
            buildJsonObject {
                put("id", profile.id)
                put("label", profile.label)
                put("moduleId", profile.moduleId)
                put("endpoint", profile.endpoint.toString())
                put("allowLoopbackHttpForDebug", profile.allowLoopbackHttpForDebug)
                if (profile.description.isNotEmpty()) put("description", profile.description)
            }
        },
    )
    preferences.edit().putString(A2A_USER_PROFILES, json.toString()).apply()
}

private fun validateUserProfile(
    label: String,
    moduleId: String,
    endpoint: String,
    allowLoopbackHttpForDebug: Boolean,
    registry: DebugA2aModuleRegistry,
): String? {
    if (label.isEmpty()) return "Label is required"
    if (moduleId !in registry.moduleIds()) return "Unknown A2A module: $moduleId"
    val uri = parseHttpEndpoint(endpoint) ?: return "Endpoint must be a valid http(s) URL"
    if (uri.userInfo != null) return "Endpoint must not include user credentials"
    if (!allowLoopbackHttpForDebug && (uri.scheme == HTTP_SCHEME || isLoopbackHost(uri.host))) {
        return "Plain http or loopback endpoints require loopback debug access"
    }
    return null
}

private fun parseHttpEndpoint(text: String): URI? =
    runCatching { URI(text) }.getOrNull()?.takeIf { uri ->
        (uri.scheme == HTTP_SCHEME || uri.scheme == HTTPS_SCHEME) && !uri.host.isNullOrBlank()
    }

private fun isLoopbackHost(host: String?): Boolean {
    val normalized = host?.trim()?.removePrefix("[").orEmpty().removeSuffix("]").lowercase()
    return normalized == LOOPBACK_LOCALHOST || normalized == LOOPBACK_IPV4 || normalized == LOOPBACK_IPV6
}

/** Deterministic unique id: `user-` + label slug, numeric suffix on collision with asset/user ids. */
private fun generateUserProfileId(label: String, takenIds: Set<String>): String {
    val slug = label.lowercase()
        .replace(USER_ID_SLUG_UNSAFE, "-")
        .trim('-')
        .ifBlank { USER_ID_FALLBACK_SLUG }
    val base = "$USER_ID_PREFIX$slug"
    var candidate = base
    var suffix = USER_ID_SUFFIX_START
    while (candidate in takenIds) {
        candidate = "$base-$suffix"
        suffix += 1
    }
    return candidate
}

private fun parseProfile(value: JsonObject): DebugA2aProfile {
    val id = requireNotNull(value.string("id")) { "A2A profile id is required" }
    val moduleId = requireNotNull(value.string("moduleId")) { "A2A profile moduleId is required" }
    val endpointText = requireNotNull(value.string("endpoint")) { "A2A profile endpoint is required" }
    val endpoint = requireNotNull(parseHttpEndpoint(endpointText)) {
        "A2A profile endpoint must be a valid http(s) URL"
    }
    require(endpoint.userInfo == null) { "A2A profile endpoint must not include user credentials" }
    val allowLoopback = value.boolean("allowLoopbackHttpForDebug") ?: false
    require(allowLoopback || (endpoint.scheme != HTTP_SCHEME && !isLoopbackHost(endpoint.host))) {
        "A2A profile plain http or loopback endpoint requires debug access"
    }
    return DebugA2aProfile(
        id = id,
        label = value.string("label") ?: id,
        moduleId = moduleId,
        endpoint = endpoint,
        enabled = value.boolean("enabled") ?: false,
        allowLoopbackHttpForDebug = allowLoopback,
        description = value.string("description").orEmpty(),
    )
}

private fun JsonObject.string(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.boolean(name: String): Boolean? = get(name)?.jsonPrimitive?.booleanOrNull

private fun JsonObject.requiredArray(name: String) = requireNotNull(get(name)) { "$name is required" }.jsonArray

private const val A2A_PROFILE_ASSET = "a2a-client-profiles.json"
private const val A2A_PREFS_NAME = "debug_a2a_client"
private const val A2A_ENABLED_PROFILES = "enabled_profile_ids"
private const val A2A_SELECTED_PROFILE = "selected_profile_id"
private const val A2A_USER_PROFILES = "user_profiles_json"
private const val HTTP_SCHEME = "http"
private const val HTTPS_SCHEME = "https"
private const val LOOPBACK_LOCALHOST = "localhost"
private const val LOOPBACK_IPV4 = "127.0.0.1"
private const val LOOPBACK_IPV6 = "::1"
private const val USER_ID_PREFIX = "user-"
private const val USER_ID_FALLBACK_SLUG = "profile"
private const val USER_ID_SUFFIX_START = 2
private val USER_ID_SLUG_UNSAFE = Regex("[^a-z0-9]+")
internal const val DIRECT_MODULE_ID = "direct-jsonrpc"
internal const val OFFICIAL_MODULE_ID = "official-java-sdk"
