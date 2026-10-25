package cc.noharry.bothy.sample

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 设置 → 连接 → MCP: a standard MCP server is its Streamable HTTP endpoint, with an optional bearer token and request
 * headers.
 */
internal enum class DebugMcpServerKind(val label: String) {
    HTTP("标准 MCP（Streamable HTTP）"),
}

internal data class DebugMcpServer(
    /** Also the model-visible server name: its tools are `mcp__<id>__<tool>`. */
    val id: String,
    val label: String,
    val kind: DebugMcpServerKind,
    val enabled: Boolean,
    val url: String = "",
) {
    val deletable: Boolean get() = true

    fun summary(): String = url
}

/**
 * What the add/edit form (or 连接应用) submits. A blank [token], [headers] or [query] keeps the stored one unless
 * [clearSecrets].
 */
internal class DebugMcpServerDraft(
    /** Null for a new server. */
    val id: String?,
    val label: String,
    val kind: DebugMcpServerKind,
    val url: String = "",
    val token: String = "",
    /** One `Name: value` per line. */
    val headers: String = "",
    val clearSecrets: Boolean = false,
    /** A key the server wants in its address (`?key=…`): kept with the token, added only when a request is sent. */
    val query: Map<String, String> = emptyMap(),
    /** The id a new server gets when it is free (连接应用 names its servers, so their tools keep stable names). */
    val preferredId: String? = null,
)

/** The configured servers; none before the first one is added. */
internal fun loadDebugMcpServers(context: Context): List<DebugMcpServer> {
    val stored = context.getSharedPreferences(MCP_SERVER_PREFS, Context.MODE_PRIVATE).getString(KEY_SERVERS, null)
    return stored?.let(::parseDebugMcpServers).orEmpty()
}

internal sealed interface DebugMcpSaveResult {
    data class Saved(val id: String) : DebugMcpSaveResult

    data class Rejected(val reason: String) : DebugMcpSaveResult
}

/** Adds or replaces the server [draft] describes. */
internal fun saveDebugMcpServer(context: Context, draft: DebugMcpServerDraft): DebugMcpSaveResult {
    val servers = loadDebugMcpServers(context)
    val existing = draft.id?.let { id -> servers.firstOrNull { it.id == id } ?: return DebugMcpSaveResult.Rejected("这个 MCP 已经被删除了") }
    validateDebugMcpDraft(draft, existing)?.let { return DebugMcpSaveResult.Rejected(it) }
    val headers = parseDebugMcpHeaders(draft.headers).getOrElse { return DebugMcpSaveResult.Rejected(it.message.orEmpty()) }
    val server = DebugMcpServer(
        id = existing?.id ?: draft.preferredId?.takeIf { id -> servers.none { it.id == id } && id !in FIXED_PROVIDER_IDS }
            ?: newDebugMcpServerId(draft.label, servers),
        label = draft.label.trim(),
        kind = draft.kind,
        enabled = existing?.enabled ?: true,
        url = draft.url.trim(),
    )
    if (draft.clearSecrets) DebugMcpSecrets.clear(context, server.id)
    if (draft.kind == DebugMcpServerKind.HTTP && (draft.token.isNotBlank() || headers.isNotEmpty() || draft.query.isNotEmpty())) {
        val kept = DebugMcpSecrets.read(context, server.id)
        val secrets = DebugMcpSecrets.Stored(
            draft.token.trim().ifEmpty { kept.token },
            headers.ifEmpty { kept.headers },
            draft.query.ifEmpty { kept.query },
        )
        DebugMcpSecrets.save(context, server.id, secrets)?.let { return DebugMcpSaveResult.Rejected(it) }
    }
    storeDebugMcpServers(context, if (existing == null) servers + server else servers.map { if (it.id == server.id) server else it })
    return DebugMcpSaveResult.Saved(server.id)
}

/** Removes a server and its stored token. */
internal fun deleteDebugMcpServer(context: Context, id: String): Boolean {
    val servers = loadDebugMcpServers(context)
    if (servers.none { it.id == id && it.deletable }) return false
    storeDebugMcpServers(context, servers.filterNot { it.id == id })
    DebugMcpSecrets.clear(context, id)
    return true
}

/** False when [id] is not a configured server. */
internal fun setDebugMcpServerEnabled(context: Context, id: String, enabled: Boolean): Boolean {
    val servers = loadDebugMcpServers(context)
    if (servers.none { it.id == id }) return false
    storeDebugMcpServers(context, servers.map { if (it.id == id) it.copy(enabled = enabled) else it })
    return true
}

internal fun validateDebugMcpDraft(draft: DebugMcpServerDraft, existing: DebugMcpServer?): String? = when {
    draft.label.isBlank() -> "请填名称"
    draft.label.trim().length > MAX_LABEL_CHARS -> "名称最多 $MAX_LABEL_CHARS 个字"
    else -> validateDebugMcpUrl(draft.url.trim())
}

private fun validateDebugMcpUrl(url: String): String? {
    val uri = try {
        URI(url)
    } catch (_: URISyntaxException) {
        return "地址格式不对，比如 https://example.com/mcp"
    }
    return when {
        uri.scheme !in setOf("http", "https") -> "地址要以 https:// 或 http:// 开头"
        uri.host.isNullOrBlank() -> "地址里没有主机名"
        // network_security_config.xml permits cleartext only on loopback (a PC server through adb reverse).
        uri.scheme == "http" && uri.host !in LOOPBACK_MCP_HOSTS -> "http:// 只能连本机（127.0.0.1 或 localhost，配合 adb reverse），其他地址请用 https://"
        else -> null
    }
}

private val LOOPBACK_MCP_HOSTS = setOf("127.0.0.1", "localhost")

/** `Name: value` per line. Names the client sets itself (content type, session, protocol version) are refused. */
internal fun parseDebugMcpHeaders(text: String): Result<Map<String, String>> = runCatching {
    text.lines().map(String::trim).filter(String::isNotEmpty).associate { line ->
        val name = line.substringBefore(':', "").trim()
        val value = line.substringAfter(':', "").trim()
        require(HEADER_NAME.matches(name) && value.isNotEmpty()) { "请求头要写成「名称: 值」，一行一个：$line" }
        require(name.lowercase() !in RESERVED_HEADERS) { "请求头 $name 由 App 自己设置，不能填" }
        name to value
    }
}

/** A short ascii id from [label] (letters and digits only, so it is one group in the prompt), unique in [servers]. */
internal fun newDebugMcpServerId(label: String, servers: List<DebugMcpServer>): String {
    val base = label.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(MAX_ID_CHARS).ifEmpty { "mcp" }
    val taken = servers.map { it.id }.toSet() + FIXED_PROVIDER_IDS
    return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base$it" }.first { it !in taken }
}

private fun storeDebugMcpServers(context: Context, servers: List<DebugMcpServer>) {
    val json = buildJsonArray {
        servers.forEach { server ->
            add(
                buildJsonObject {
                    put("id", server.id)
                    put("label", server.label)
                    put("kind", server.kind.name)
                    put("enabled", server.enabled)
                    put("url", server.url)
                },
            )
        }
    }
    context.getSharedPreferences(MCP_SERVER_PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SERVERS, json.toString()).apply()
}

internal fun parseDebugMcpServers(text: String): List<DebugMcpServer>? {
    val array = try {
        Json.parseToJsonElement(text) as? JsonArray
    } catch (_: SerializationException) {
        null
    } ?: return null
    return array.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val field = { name: String -> (item[name] as? JsonPrimitive)?.contentOrNull.orEmpty() }
        DebugMcpServer(
            id = field("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
            label = field("label"),
            kind = DebugMcpServerKind.entries.firstOrNull { it.name == field("kind") } ?: return@mapNotNull null,
            enabled = (item["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
            url = field("url"),
        )
    }
}

/**
 * A standard server's bearer token, request headers and address keys, which may hold keys: kept out of settings, in
 * no-backup storage, and never shown again, logged or uploaded. The rows only say that some are set.
 */
internal object DebugMcpSecrets {
    data class Stored(
        val token: String = "",
        val headers: Map<String, String> = emptyMap(),
        /** Query parameters added to the address on each request (高德 and 滴滴 take their key this way). */
        val query: Map<String, String> = emptyMap(),
    ) {
        val isEmpty: Boolean get() = token.isEmpty() && headers.isEmpty() && query.isEmpty()
    }

    fun read(context: Context, id: String): Stored {
        val text = runCatching { file(context, id).readText(Charsets.UTF_8) }.getOrNull() ?: return Stored()
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return Stored()
        val map = { name: String -> (root[name] as? JsonObject)?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }.orEmpty() }
        return Stored((root["token"] as? JsonPrimitive)?.contentOrNull.orEmpty(), map("headers"), map("query"))
    }

    /** Returns why it could not be saved, or null. */
    fun save(context: Context, id: String, stored: Stored): String? {
        if (stored.token.any(Char::isWhitespace)) return "令牌不能有空格"
        if (stored.query.values.any { value -> value.any(Char::isWhitespace) }) return "密钥不能有空格"
        val json = buildJsonObject {
            put("token", stored.token)
            put("headers", JsonObject(stored.headers.mapValues { JsonPrimitive(it.value) }))
            put("query", JsonObject(stored.query.mapValues { JsonPrimitive(it.value) }))
        }
        return try {
            file(context, id).apply { parentFile?.mkdirs() }.writeText(json.toString(), Charsets.UTF_8)
            null
        } catch (_: IOException) {
            "令牌没能保存"
        }
    }

    fun clear(context: Context, id: String) {
        file(context, id).delete()
    }

    /** What a request to the server carries: its headers, and `Authorization: Bearer` when a token is set. */
    fun requestHeaders(context: Context, id: String): Map<String, String> {
        val stored = read(context, id)
        val auth = stored.token.takeIf { it.isNotEmpty() && stored.headers.keys.none { it.equals(AUTHORIZATION, true) } }
        return stored.headers + listOfNotNull(auth?.let { AUTHORIZATION to "Bearer $it" })
    }

    /** The query parameters a request to the server adds to its address. */
    fun requestQuery(context: Context, id: String): Map<String, String> = read(context, id).query

    private fun file(context: Context, id: String): File =
        File(File(context.noBackupFilesDir, SECRETS_DIR), "${id.filter { it.isLetterOrDigit() || it == '_' }}.json")
}

/** The providers that are not configurable servers: their ids are never given to one. */
internal val FIXED_PROVIDER_IDS = setOf(
    DEBUG_MCP_PROVIDER_TOOLS_JSON,
    DEBUG_MCP_PROVIDER_DEVICE_CALENDAR,
    DEBUG_MCP_PROVIDER_DEVICE_NOTIFICATION,
    DEBUG_MCP_PROVIDER_DEVICE_MEDIA,
)

private const val MCP_SERVER_PREFS = "debug_mcp_servers"
private const val KEY_SERVERS = "servers"
private const val SECRETS_DIR = "mcp-secrets"
private const val AUTHORIZATION = "Authorization"
private const val MAX_LABEL_CHARS = 40
private const val MAX_ID_CHARS = 24
private val HEADER_NAME = Regex("""[A-Za-z0-9!#$%&'*+.^_`|~-]+""")
private val RESERVED_HEADERS = setOf("content-type", "accept", "mcp-session-id", "mcp-protocol-version", "content-length", "host")
