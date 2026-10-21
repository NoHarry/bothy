package cc.noharry.bothy.sample

import android.content.Context
import android.os.Build
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * One app in 连接应用. An agent (Grok Bot) pairs through the relay and becomes the A2A profile [name] that delegation
 * uses; an app with [mcp] becomes a configured MCP server. The page shows both the same way.
 */
internal data class DebugConnector(
    val id: String,
    val name: String,
    val summary: String,
    val category: String,
    /** The A2A profile's description; empty for an MCP app. */
    val profileDescription: String = "",
    /** How an MCP app is reached; null for an agent that pairs through the relay. */
    val mcp: DebugMcpConnectorSpec? = null,
    /** The icon: a letter on a colour (no brand artwork ships with the sample). */
    val mark: String = name.take(1),
    val color: Long = 0xFF111111,
)

internal val GROK_BOT_CONNECTOR = DebugConnector(
    id = "grok-bot",
    name = "Grok Bot",
    summary = "把发邮件、查资料、设提醒这类要用你个人账号的事交给你自己的 Grok Bot，办完回到这里播报。",
    category = "智能体",
    profileDescription = "用户自己的云端助手：发邮件、查资料、设提醒、网购等需要个人账号的事务",
)

/** The catalog: the MCP apps by category, then the agents. */
internal val DEBUG_CONNECTORS: List<DebugConnector> = DEBUG_MCP_CONNECTORS + GROK_BOT_CONNECTOR

internal sealed interface DebugConnectorStatus {
    data object Disconnected : DebugConnectorStatus

    /** Waiting for the user to claim [code] on the relay's page; an expired or missing code needs a refresh. */
    data class Pairing(val code: String, val connectUrl: String, val expiresAtMillis: Long) : DebugConnectorStatus

    data class Connected(val verified: Boolean) : DebugConnectorStatus

    /** An MCP app whose server is switched off in 设置 → 连接 → MCP. */
    data object Paused : DebugConnectorStatus
}

internal class DebugConnectorException(message: String) : Exception(message)

/** Saves and removes the A2A profile a connection uses; the live app session by default. */
internal interface DebugConnectorProfiles {
    /** Saves (or replaces) the enabled profile [label]; an error message, or null. */
    suspend fun save(context: Context, label: String, endpoint: String, description: String, token: String): String?

    suspend fun remove(context: Context, label: String)
}

/** The live session's A2A profiles; changed on the main thread, like the console and the adb receiver do. */
internal object SessionConnectorProfiles : DebugConnectorProfiles {
    override suspend fun save(context: Context, label: String, endpoint: String, description: String, token: String): String? =
        withContext(Dispatchers.Main) { saveNow(context, label, endpoint, description, token) }

    override suspend fun remove(context: Context, label: String) = withContext(Dispatchers.Main) {
        userProfile(context, label)?.let { DebugRuntimeSession.deleteA2aProfile(context, it.id) }
        Unit
    }

    private fun saveNow(context: Context, label: String, endpoint: String, description: String, token: String): String? {
        DebugRuntimeSession.saveA2aProfile(
            context, label, DIRECT_MODULE_ID, endpoint, allowLoopbackHttpForDebug = false,
            description = description, token = token, replaceSameLabel = true,
        )?.let { return it }
        val profile = userProfile(context, label) ?: return "连接后没有找到 $label 的配置。"
        DebugRuntimeSession.setA2aProfileEnabled(context, profile.id, enabled = true)
        return null
    }

    private fun userProfile(context: Context, label: String) =
        DebugA2aModuleConfig.load(context).state.profiles.firstOrNull { it.userDefined && it.label == label }
}

/**
 * The relay's pairing API (`/connect/…`, see the relay README): register this car, ask whether it was claimed, get a
 * new code, forget it. Errors carry the relay's own message when it sent one.
 */
internal class DebugRelayConnectClient(private val base: HttpUrl, private val client: OkHttpClient = CONNECT_HTTP_CLIENT) {
    data class Registered(val token: String, val pairing: DebugConnectorStatus.Pairing)

    data class CarState(val connected: Boolean, val verified: Boolean, val account: String? = null)

    fun register(label: String): Registered {
        val body = buildJsonObject { put("label", label) }.toString().toRequestBody(JSON_TYPE)
        val json = call(Request.Builder().url(url("connect/devices")).post(body).build())
            ?: throw DebugConnectorException("中转服务没有返回设备令牌。")
        return Registered(json.text("token"), pairing(json))
    }

    /** Null once the relay no longer knows this car (it was disconnected or dropped). */
    fun state(token: String): CarState? {
        val json = call(authorized("connect/device", token).get().build(), unknownIsNull = true) ?: return null
        val verified = (json["verified"] as? JsonPrimitive)?.booleanOrNull == true
        return CarState(json.text("state") == "connected", verified, json.text("account").ifEmpty { null })
    }


    fun newCode(token: String): DebugConnectorStatus.Pairing {
        val json = call(authorized("connect/device/code", token).post(ByteArray(0).toRequestBody(null)).build())
            ?: throw DebugConnectorException("中转服务已经不认识这台设备，请重新连接。")
        return pairing(json)
    }

    fun remove(token: String) {
        call(authorized("connect/device", token).delete().build(), unknownIsNull = true)
    }

    /** Unpairs; `state` cleaning names the bot's clean-up task to follow, disconnected carries the relay's words. */
    fun unpair(token: String): JsonObject =
        call(authorized("connect/device/unpair", token).post(ByteArray(0).toRequestBody(null)).build(), unknownIsNull = true)
            ?: buildJsonObject { put("state", "disconnected"); put("text", "已解除绑定。") }

    /** An A2A JSON-RPC call on the relay with the device token; the `result` object. */
    fun rpc(token: String, method: String, params: JsonObject): JsonObject {
        val json = call(authorized("a2a", token).post(rpcBody(method, params)).build())
            ?: throw DebugConnectorException("中转服务已经不认识这台设备，请重新连接。")
        (json["error"] as? JsonObject)?.let { throw DebugConnectorException("测试没发出去：${it.text("message")}") }
        return json["result"] as? JsonObject ?: throw DebugConnectorException("中转服务的回答不完整。")
    }

    private fun url(path: String): HttpUrl = base.newBuilder().addPathSegments(path).build()

    internal fun authorized(path: String, token: String): Request.Builder =
        Request.Builder().url(url(path)).header("Authorization", "Bearer $token")

    internal fun call(request: Request, unknownIsNull: Boolean = false): JsonObject? = relayCall(client, request, unknownIsNull)
}

/** Signed in to [account]; [connected] once the account's connection took the device (or the device's became the account's). */
internal data class DebugRelaySignedIn(val account: String, val connected: Boolean)

/** Signs the device in to an account (or registers one); the relay's words when the name or password is refused. */
internal fun DebugRelayConnectClient.signIn(token: String, name: String, password: String, register: Boolean): DebugRelaySignedIn {
    val body = buildJsonObject {
        put("name", name)
        put("password", password)
        put("register", register)
    }.toString().toRequestBody(JSON_TYPE)
    val json = call(authorized("connect/device/login", token).post(body).build())
        ?: throw DebugConnectorException("中转服务没有回答。")
    return DebugRelaySignedIn(json.text("account"), json.text("state") == "connected")
}

internal fun DebugRelayConnectClient.signOut(token: String) {
    call(authorized("connect/device/logout", token).post(ByteArray(0).toRequestBody(null)).build(), unknownIsNull = true)
}

/** The relay's JSON answer; null for 401 when [unknownIsNull]; otherwise errors carry the relay's own message. */
private fun relayCall(client: OkHttpClient, request: Request, unknownIsNull: Boolean): JsonObject? = try {
    client.newCall(request).execute().use { response ->
        val json = runCatching { Json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject }.getOrNull()
        when {
            response.isSuccessful -> json
            response.code == 401 && unknownIsNull -> null
            else -> throw DebugConnectorException(json?.text("error")?.ifBlank { null } ?: "中转服务返回 HTTP ${response.code}。")
        }
    }
} catch (_: IOException) {
    throw DebugConnectorException("连不上中转服务，请检查设备网络。")
}

private fun pairing(json: JsonObject) = DebugConnectorStatus.Pairing(
    code = json.text("code"),
    connectUrl = json.text("connect_url"),
    expiresAtMillis = runCatching { Instant.parse(json.text("code_expires_at")).toEpochMilli() }.getOrDefault(0L),
)

/** A task known only by id, in the shape `tasks/get` answers, so polling can start from it. */
private fun taskRef(id: String): JsonObject = buildJsonObject {
    put("id", id)
    put("status", buildJsonObject { put("state", "working") })
}

private fun rpcBody(method: String, params: JsonObject) = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", UUID.randomUUID().toString())
    put("method", method)
    put("params", params)
}.toString().toRequestBody(JSON_TYPE)

/**
 * Connects this device to the apps in [DEBUG_CONNECTORS] through the relay. The device token lives in the no-backup token
 * store; prefs keep only where the connection stands. A connection that is claimed becomes the A2A profile named after
 * the app, enabled, so delegation picks it up at once.
 */
internal class DebugConnectorController(
    private val context: Context,
    private val relayUrl: () -> String = { connectorRelayUrl() },
    private val profiles: DebugConnectorProfiles = SessionConnectorProfiles,
    private val clientFor: (HttpUrl) -> DebugRelayConnectClient = { DebugRelayConnectClient(it) },
    private val carLabel: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
    private val note: (String) -> Unit = DebugRuntimeSession::noteConnection,
    private val clock: () -> Long = System::currentTimeMillis,
    private val servers: DebugConnectorServers = SessionConnectorServers,
    private val probe: suspend (DebugMcpEndpoint) -> McpToolsListing = { endpoint -> probeMcpEndpoint(context, endpoint) },
) {
    private val prefs get() = context.getSharedPreferences(CONNECTOR_PREFS, Context.MODE_PRIVATE)

    /** The relay [connector] goes through: the one it paired with, else the preset one it would pair with. */
    fun relayHost(connector: DebugConnector): String =
        (prefs.getString(key(connector, RELAY), null) ?: relayUrl()).toHttpUrlOrNull()?.host.orEmpty()

    /** The dialog's pairing poll and the footer's check can overlap; one change of state is applied (and recorded) once. */
    private val changes = Mutex()

    /** Where [connector] stands as far as this car knows, without asking the relay. */
    fun status(connector: DebugConnector): DebugConnectorStatus {
        connector.mcp?.let { return mcpStatus(connector, it) }
        val prefs = prefs
        return when (prefs.getString(key(connector, STATE), null)) {
            STATE_CONNECTED -> DebugConnectorStatus.Connected(prefs.getBoolean(key(connector, VERIFIED), false))
            STATE_PAIRING -> DebugConnectorStatus.Pairing(
                code = prefs.getString(key(connector, CODE), null).orEmpty(),
                connectUrl = prefs.getString(key(connector, CONNECT_URL), null).orEmpty(),
                expiresAtMillis = prefs.getLong(key(connector, CODE_EXPIRES_AT), 0L),
            )
            else -> DebugConnectorStatus.Disconnected
        }
    }

    suspend fun connect(connector: DebugConnector): DebugConnectorStatus = withContext(Dispatchers.IO) {
        val configured = relayUrl().trim().trimEnd('/')
        if (configured.isEmpty()) {
            throw DebugConnectorException("还没有配置中转：部署 tools/agent-relay 后，在 local.properties 写上 $AGENT_RELAY_URL_KEY=https://… 再重新打包。")
        }
        val base = configured.toHttpUrlOrNull()?.takeIf { it.isHttps }
            ?: throw DebugConnectorException("中转地址无效（打包时的 $AGENT_RELAY_URL_KEY 需要是 https 地址）。")
        val registered = clientFor(base).register(carLabel)
        DebugA2aTokens.save(context, tokenKey(connector), registered.token)?.let { throw DebugConnectorException(it) }
        prefs.edit().putString(key(connector, RELAY), base.toString()).commit()
        savePairing(connector, registered.pairing)
    }

    suspend fun refreshCode(connector: DebugConnector): DebugConnectorStatus = withContext(Dispatchers.IO) {
        val (client, token) = stored(connector) ?: return@withContext forget(connector)
        savePairing(connector, client.newCode(token))
    }

    /** The account this car is signed in to, as far as it knows; null when it is not. */
    fun account(connector: DebugConnector): String? = prefs.getString(key(connector, ACCOUNT), null)

    /** Where the account is looked after in a browser: devices, recent tasks, the routine's webhook. */
    fun accountPage(connector: DebugConnector): String =
        (prefs.getString(key(connector, RELAY), null) ?: relayUrl()).trim().trimEnd('/') + ACCOUNT_PATH

    /**
     * Signs in to (or registers) an account. A car not yet known to the relay registers first. An account that holds a
     * connection takes the device into it, so it is connected at once; a connected car hands its connection to an account
     * that has none; otherwise the device stays pairing and the account takes the connection the code makes.
     */
    suspend fun signIn(connector: DebugConnector, name: String, password: String, register: Boolean): DebugConnectorStatus =
        withContext(Dispatchers.IO) {
            if (stored(connector) == null) connect(connector)
            changes.withLock {
                val (client, token) = stored(connector) ?: throw DebugConnectorException("还没有连接。")
                val signedIn = client.signIn(token, name.trim(), password, register)
                prefs.edit().putString(key(connector, ACCOUNT), signedIn.account).commit()
                record(connector, if (register) "注册并登录了账号「${signedIn.account}」。" else "登录了账号「${signedIn.account}」。")
                refreshLocked(connector)
            }
        }

    /** Leaves the account; the device stays connected. */
    suspend fun signOut(connector: DebugConnector) = withContext(Dispatchers.IO) {
        val account = account(connector) ?: return@withContext
        stored(connector)?.let { (client, token) -> client.signOut(token) }
        prefs.edit().remove(key(connector, ACCOUNT)).commit()
        record(connector, "退出了账号「$account」，这台设备仍然连着 ${connector.name}。")
    }

    /** The newest connection events first, with their times, kept after a disconnect. */
    fun events(connector: DebugConnector): List<String> =
        prefs.getString(key(connector, EVENTS), null)?.split(EVENT_SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()

    /** Asks the relay whether the device was claimed; a claimed car gets its A2A profile. */
    suspend fun refresh(connector: DebugConnector): DebugConnectorStatus = withContext(Dispatchers.IO) {
        changes.withLock { refreshLocked(connector) }
    }

    private suspend fun refreshLocked(connector: DebugConnector): DebugConnectorStatus {
        connector.mcp?.let { return mcpStatus(connector, it) }
        val (client, token) = stored(connector) ?: return forget(connector)
        val wasConnected = status(connector) is DebugConnectorStatus.Connected
        val state = client.state(token) ?: return forget(connector).also {
            if (wasConnected) record(connector, "${connector.name} 那边解除了绑定。")
        }
        prefs.edit().apply { if (state.account == null) remove(key(connector, ACCOUNT)) else putString(key(connector, ACCOUNT), state.account) }.commit()
        return when {
            state.connected -> {
                if (!wasConnected) {
                    val endpoint = prefs.getString(key(connector, RELAY), null).orEmpty().trimEnd('/') + A2A_PATH
                    profiles.save(context, connector.name, endpoint, connector.profileDescription, token)
                        ?.let { throw DebugConnectorException(it) }
                }
                prefs.edit().putString(key(connector, STATE), STATE_CONNECTED).putBoolean(key(connector, VERIFIED), state.verified)
                    .remove(key(connector, CODE)).remove(key(connector, CONNECT_URL)).remove(key(connector, CODE_EXPIRES_AT)).commit()
                if (!wasConnected) record(connector, "已连接到你的 ${connector.name}。")
                DebugConnectorStatus.Connected(state.verified)
            }
            wasConnected -> {
                profiles.remove(context, connector.name)
                record(connector, "${connector.name} 那边解除了绑定。")
                savePairing(connector, DebugConnectorStatus.Pairing("", "", 0L))
            }
            else -> status(connector)
        }
    }

    /**
     * Sends the connection test through the same A2A path delegation uses and waits for the bot's report; returns
     * the reported text, or throws with why it did not come back.
     */
    suspend fun test(connector: DebugConnector): String = withContext(Dispatchers.IO) {
        val (client, token) = stored(connector) ?: throw DebugConnectorException("还没有连接。")
        val message = buildJsonObject {
            put("message", buildJsonObject {
                put("kind", "message")
                put("messageId", UUID.randomUUID().toString())
                put("role", "user")
                put("parts", buildJsonArray { add(buildJsonObject { put("kind", "text"); put("text", CONNECTION_TEST) }) })
            })
        }
        val first = client.rpc(token, "message/send", message)
        val text = try {
            awaitReport(client, token, first, connector)
        } catch (failure: DebugConnectorException) {
            record(connector, "测试连接没有通过：${failure.message}")
            throw failure
        }
        prefs.edit().putBoolean(key(connector, VERIFIED), true).commit()
        record(connector, "测试连接：${connector.name} 回报「$text」")
        text
    }

    /**
     * Unpairs and returns what happened, never throwing. When this is the user's last car the relay first asks the bot
     * to remove its routine and connector and to report; that report (or why none came within three minutes) is the
     * answer, and the device is forgotten on the relay afterwards. Recorded here and in the console either way.
     */
    suspend fun disconnect(connector: DebugConnector, onProgress: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        changes.withLock { disconnectLocked(connector, onProgress) }
    }

    private suspend fun disconnectLocked(connector: DebugConnector, onProgress: (String) -> Unit): String {
        connector.mcp?.let { spec ->
            if (!owns(connector)) return "还没有连接。"
            servers.delete(context, spec.serverId)
            prefs.edit().remove(key(connector, OWNED)).commit()
            return "已断开，小博不再使用${connector.name}。".also { record(connector, it) }
        }
        val pairing = status(connector) is DebugConnectorStatus.Pairing
        val text = stored(connector)?.let { (client, token) -> unpair(connector, client, token, onProgress) } ?: "已解除绑定。"
        forget(connector)
        return if (pairing) "已取消配对。".also { record(connector, it) } else text.also { record(connector, "解除绑定：$it") }
    }

    private suspend fun unpair(connector: DebugConnector, client: DebugRelayConnectClient, token: String, onProgress: (String) -> Unit): String {
        val reply = try {
            client.unpair(token)
        } catch (failure: DebugConnectorException) {
            return "本机已解除绑定，但${failure.message}${connector.name} 里的「车载语音助手任务」和 car-relay 连接器请手动删除。"
        }
        if (reply.text("state") != "cleaning") return reply.text("text").ifEmpty { "已解除绑定。" }
        onProgress("正在让 ${connector.name} 删除它那边的例行任务和连接器（最多等三分钟）…")
        val cleaned = runCatching { awaitReport(client, token, taskRef(reply.text("task_id")), connector) }
        runCatching { client.remove(token) }
        return cleaned.fold(
            onSuccess = { "${connector.name} 回报：$it" },
            onFailure = { "已解除绑定，但${it.message}「车载语音助手任务」和 car-relay 连接器请在 ${connector.name} 里手动删除。" },
        )
    }

    /** Polls the task until the bot reports; its words, or why there were none (failed, or three minutes passed). */
    private suspend fun awaitReport(client: DebugRelayConnectClient, token: String, start: JsonObject, connector: DebugConnector): String {
        var task = start
        val deadline = clock() + REPORT_TIMEOUT_MS
        while (task.state() == "working" || task.state() == "submitted") {
            if (clock() > deadline) throw DebugConnectorException("${connector.name} 三分钟内没有回报。")
            delay(REPORT_POLL_MS)
            task = client.rpc(token, "tasks/get", buildJsonObject { put("id", task.text("id")) })
        }
        val text = task.statusText()
        if (task.state() != "completed") throw DebugConnectorException(text.ifEmpty { "任务没有完成（${task.state()}）。" })
        return text
    }

    /**
     * Connects an MCP app: tries [credential] on the app's address (the sandbox one when [sandbox] and there is one)
     * and saves the server only once it lists its tools. Returns the tools' names; throws with what to check.
     */
    suspend fun connectApp(connector: DebugConnector, credential: String, sandbox: Boolean): List<String> = changes.withLock {
        withContext(Dispatchers.IO) { connectAppLocked(connector, credential, sandbox) }
    }

    private suspend fun connectAppLocked(connector: DebugConnector, credential: String, sandbox: Boolean): List<String> {
        val spec = connector.mcp ?: throw DebugConnectorException("${connector.name} 不是这样连接的。")
        servers.find(context, spec.serverId)?.takeUnless { owns(connector) }?.let { other ->
            throw DebugConnectorException("设置 → MCP 里已经有一个「${other.label}」用着这个名字（${other.id}），请先在那里删除它。")
        }
        val key = credential.trim()
        if (spec.needsCredential && key.isEmpty()) throw DebugConnectorException("请先填${spec.credentialLabel}。")
        if (key.any(Char::isWhitespace)) throw DebugConnectorException("${spec.credentialLabel}里不能有空格或换行。")
        val url = spec.sandboxUrl?.takeIf { sandbox } ?: spec.url
        val tools = listedTools(connector, probe(spec.endpoint(url, key)))
        servers.save(context, spec.draft(servers.find(context, spec.serverId)?.id, connector.name, url, key))
            ?.let { throw DebugConnectorException(it) }
        servers.find(context, spec.serverId)?.let { saved -> prefs.edit().putString(key(connector, OWNED), ownership(saved)).commit() }
        if (servers.find(context, spec.serverId)?.enabled == false) servers.setEnabled(context, spec.serverId, true)
        record(connector, "已连接${if (url == spec.sandboxUrl) "（沙箱）" else ""}，可以用 ${tools.size} 个功能。")
        return tools
    }

    /**
     * The MCP server under [connector]'s id is the one connecting here saved, not one added by hand in 设置 → MCP: the
     * address and name it was saved with still match.
     */
    private fun owns(connector: DebugConnector): Boolean {
        val server = connector.mcp?.let { servers.find(context, it.serverId) } ?: return false
        return prefs.getString(key(connector, OWNED), null) == ownership(server)
    }

    private fun ownership(server: DebugMcpServer) = "${server.url}\n${server.label}"

    /** Lists a connected MCP app's tools with its saved key; recorded only when the user asked ([record]). */
    suspend fun checkApp(connector: DebugConnector, record: Boolean = true): List<String> = withContext(Dispatchers.IO) {
        checkAppLocked(connector, record)
    }

    private suspend fun checkAppLocked(connector: DebugConnector, record: Boolean): List<String> {
        val spec = connector.mcp ?: throw DebugConnectorException("${connector.name} 不是这样连接的。")
        val server = servers.find(context, spec.serverId)?.takeIf { owns(connector) } ?: throw DebugConnectorException("还没有连接。")
        val endpoint = DebugMcpEndpoint(
            server.url,
            DebugMcpSecrets.requestHeaders(context, server.id),
            DebugMcpSecrets.requestQuery(context, server.id),
        )
        val tools = try {
            listedTools(connector, probe(endpoint))
        } catch (failure: DebugConnectorException) {
            if (record) record(connector, "检查没有通过：${failure.message}")
            throw failure
        }
        if (record) record(connector, "检查通过，可以用 ${tools.size} 个功能。")
        return tools
    }

    /** Switches a paused MCP app's server back on. */
    suspend fun resumeApp(connector: DebugConnector): DebugConnectorStatus {
        val spec = connector.mcp ?: return status(connector)
        servers.setEnabled(context, spec.serverId, true)
        record(connector, "重新启用了。")
        return status(connector)
    }

    /** True when [connector] is connected to its test address (made-up data). */
    fun usesSandbox(connector: DebugConnector): Boolean {
        val spec = connector.mcp ?: return false
        return spec.sandboxUrl != null && owns(connector) && servers.find(context, spec.serverId)?.url == spec.sandboxUrl
    }

    private fun mcpStatus(connector: DebugConnector, spec: DebugMcpConnectorSpec): DebugConnectorStatus {
        if (!owns(connector)) return DebugConnectorStatus.Disconnected
        val server = servers.find(context, spec.serverId) ?: return DebugConnectorStatus.Disconnected
        return if (server.enabled) DebugConnectorStatus.Connected(verified = true) else DebugConnectorStatus.Paused
    }

    private fun listedTools(connector: DebugConnector, listing: McpToolsListing): List<String> {
        listing.problem?.let { throw DebugConnectorException(connector.connectFailure(it)) }
        if (listing.tools.isEmpty()) throw DebugConnectorException("连上了，但 ${connector.name} 没有提供任何功能，请确认这个${connector.mcp?.credentialLabel}开通了权限。")
        return listing.tools.map { tool ->
            tool.title?.takeIf { it.isNotBlank() && it != tool.description && it.length <= MAX_TOOL_TITLE_CHARS } ?: tool.name
        }
    }

    private fun record(connector: DebugConnector, text: String) {
        val stamped = "${SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(clock()))} $text"
        val kept = (listOf(stamped) + events(connector)).take(MAX_EVENTS)
        prefs.edit().putString(key(connector, EVENTS), kept.joinToString(EVENT_SEPARATOR)).commit()
        note("【连接应用】$text")
    }

    private fun stored(connector: DebugConnector): Pair<DebugRelayConnectClient, String>? {
        val base = prefs.getString(key(connector, RELAY), null)?.toHttpUrlOrNull() ?: return null
        val token = DebugA2aTokens.read(context, tokenKey(connector)).ifEmpty { return null }
        return clientFor(base) to token
    }

    private fun savePairing(connector: DebugConnector, pairing: DebugConnectorStatus.Pairing): DebugConnectorStatus {
        prefs.edit().putString(key(connector, STATE), STATE_PAIRING).putString(key(connector, CODE), pairing.code)
            .putString(key(connector, CONNECT_URL), pairing.connectUrl).putLong(key(connector, CODE_EXPIRES_AT), pairing.expiresAtMillis)
            .commit()
        return pairing
    }

    private suspend fun forget(connector: DebugConnector): DebugConnectorStatus {
        if (status(connector) is DebugConnectorStatus.Connected) profiles.remove(context, connector.name)
        DebugA2aTokens.clear(context, tokenKey(connector))
        val edit = prefs.edit()
        CONNECTOR_KEYS.forEach { edit.remove(key(connector, it)) }
        edit.commit()
        return DebugConnectorStatus.Disconnected
    }

    private fun key(connector: DebugConnector, name: String) = "${connector.id}.$name"

    private fun tokenKey(connector: DebugConnector) = "connector-${connector.id}"
}

/** local.properties key for the relay that 连接应用 pairs through; embedded in every APK (it is an address, not a key). */
internal const val AGENT_RELAY_URL_KEY = "AGENT_RELAY_URL"

/**
 * The relay Grok Bot pairs and delegates through: `AGENT_RELAY_URL` set at build time (deploy your own from
 * tools/agent-relay), else [DEFAULT_AGENT_RELAY_URL].
 */
internal fun connectorRelayUrl(embedded: () -> Map<String, String> = ::buildConfigValues): String =
    embedded()[AGENT_RELAY_URL_KEY].orEmpty().ifBlank { DEFAULT_AGENT_RELAY_URL }

/** None ships with the app: pairing asks for `AGENT_RELAY_URL` until a relay is configured. */
internal const val DEFAULT_AGENT_RELAY_URL = ""

private fun JsonObject.text(name: String): String = (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.state(): String = (((this["status"] as? JsonObject)?.get("state") as? JsonPrimitive)?.contentOrNull).orEmpty()

/** The words of the task's status message (the relay puts the bot's report there). */
private fun JsonObject.statusText(): String =
    (((this["status"] as? JsonObject)?.get("message") as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonObject)?.text("text") }
        .joinToString("")

/** Same words the relay's own connection test uses. */
private const val CONNECTION_TEST =
    "这是设备连接测试，不用做任何别的事：直接调用 car_task_report 汇报 state=completed，text 写「连接成功，可以开始用了」。"
private const val REPORT_POLL_MS = 3_000L
private const val REPORT_TIMEOUT_MS = 180_000L
private const val EVENTS = "events"
/** The connector saved its MCP server itself ([DebugConnectorController.connectApp]). */
private const val OWNED = "mcp_server"
private const val MAX_TOOL_TITLE_CHARS = 20
private const val EVENT_SEPARATOR = "\n"
private const val MAX_EVENTS = 10
private const val CONNECTOR_PREFS = "debug_connectors"
private const val A2A_PATH = "/a2a"
private const val ACCOUNT_PATH = "/account"
private const val ACCOUNT = "account"
private const val RELAY = "relay"
private const val STATE = "state"
private const val CODE = "code"
private const val CONNECT_URL = "connect_url"
private const val CODE_EXPIRES_AT = "code_expires_at"
private const val VERIFIED = "verified"
private const val STATE_PAIRING = "pairing"
private const val STATE_CONNECTED = "connected"
private val CONNECTOR_KEYS = listOf(RELAY, STATE, CODE, CONNECT_URL, CODE_EXPIRES_AT, VERIFIED, ACCOUNT)
private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
private val CONNECT_HTTP_CLIENT = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .followRedirects(false)
    .build()
