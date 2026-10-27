package cc.noharry.bothy.app.mcp
import android.content.Context
import android.os.Build
import cc.noharry.agent.mcp.McpToolsListing
import cc.noharry.bothy.app.a2a.A2aModuleConfig
import cc.noharry.bothy.app.a2a.A2aTokens
import cc.noharry.bothy.app.a2a.DIRECT_MODULE_ID
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.model.buildConfigValues
import cc.noharry.bothy.app.model.summary
import cc.noharry.bothy.app.runtime.RuntimeSession
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
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
internal data class Connector(
    val id: String,
    val name: String,
    val summary: String,
    val category: String,
    /** The A2A profile's description; empty for an MCP app. */
    val profileDescription: String = "",
    /** How an MCP app is reached; null for an agent that pairs through the relay. */
    val mcp: McpConnectorSpec? = null,
    /** The icon: a letter on a colour (no brand artwork ships with the sample). */
    val mark: String = name.take(1),
    val color: Long = 0xFF111111,
)

internal val GROK_BOT_CONNECTOR = Connector(
    id = "grok-bot",
    name = "Grok Bot",
    summary = "把发邮件、查资料、设提醒这类要用你个人账号的事交给你自己的 Grok Bot，办完回到这里播报。",
    category = "智能体",
    profileDescription = "用户自己的云端助手：发邮件、查资料、设提醒、网购等需要个人账号的事务",
)

/** The catalog: the MCP apps by category, then the agents. */
internal val DEBUG_CONNECTORS: List<Connector> = DEBUG_MCP_CONNECTORS + GROK_BOT_CONNECTOR

internal sealed interface ConnectorStatus {
    data object Disconnected : ConnectorStatus

    /** Waiting for the user to claim [code] on the relay's page; an expired or missing code needs a refresh. */
    data class Pairing(val code: String, val connectUrl: String, val expiresAtMillis: Long) : ConnectorStatus

    data class Connected(val verified: Boolean) : ConnectorStatus

    /** An MCP app whose server is switched off in 设置 → 连接 → MCP. */
    data object Paused : ConnectorStatus
}

internal class ConnectorException(message: String) : Exception(message)

/** Saves and removes the A2A profile a connection uses; the live app session by default. */
internal interface ConnectorProfiles {
    /** Saves (or replaces) the enabled profile [label]; an error message, or null. */
    suspend fun save(context: Context, label: String, endpoint: String, description: String, token: String): String?

    suspend fun remove(context: Context, label: String)
}

/** The live session's A2A profiles; changed on the main thread, like the console and the adb receiver do. */
internal object SessionConnectorProfiles : ConnectorProfiles {
    override suspend fun save(context: Context, label: String, endpoint: String, description: String, token: String): String? =
        withContext(Dispatchers.Main) { saveNow(context, label, endpoint, description, token) }

    override suspend fun remove(context: Context, label: String) = withContext(Dispatchers.Main) {
        userProfile(context, label)?.let { RuntimeSession.deleteA2aProfile(context, it.id) }
        Unit
    }

    private fun saveNow(context: Context, label: String, endpoint: String, description: String, token: String): String? {
        RuntimeSession.saveA2aProfile(
            context, label, DIRECT_MODULE_ID, endpoint, allowLoopbackHttpForDebug = false,
            description = description, token = token, replaceSameLabel = true,
        )?.let { return it }
        val profile = userProfile(context, label) ?: return "连接后没有找到 $label 的配置。"
        RuntimeSession.setA2aProfileEnabled(context, profile.id, enabled = true)
        return null
    }

    private fun userProfile(context: Context, label: String) =
        A2aModuleConfig.load(context).state.profiles.firstOrNull { it.userDefined && it.label == label }
}

/**
 * The relay's pairing API (`/connect/…`, see the relay README): register this car, ask whether it was claimed, get a
 * new code, forget it. Errors carry the relay's own message when it sent one.
 */
internal class RelayConnectClient(private val base: HttpUrl, private val client: OkHttpClient = CONNECT_HTTP_CLIENT) {
    data class Registered(val token: String, val pairing: ConnectorStatus.Pairing)

    data class CarState(val connected: Boolean, val verified: Boolean, val account: String? = null)

    fun register(label: String): Registered {
        val body = buildJsonObject { put("label", label) }.toString().toRequestBody(JSON_TYPE)
        val json = call(Request.Builder().url(url("connect/devices")).post(body).build())
            ?: throw ConnectorException("中转服务没有返回设备令牌。")
        return Registered(json.text("token"), pairing(json))
    }

    /** Null once the relay no longer knows this car (it was disconnected or dropped). */
    fun state(token: String): CarState? {
        val json = call(authorized("connect/device", token).get().build(), unknownIsNull = true) ?: return null
        val verified = (json["verified"] as? JsonPrimitive)?.booleanOrNull == true
        return CarState(json.text("state") == "connected", verified, json.text("account").ifEmpty { null })
    }


    fun newCode(token: String): ConnectorStatus.Pairing {
        val json = call(authorized("connect/device/code", token).post(ByteArray(0).toRequestBody(null)).build())
            ?: throw ConnectorException("中转服务已经不认识这台设备，请重新连接。")
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
            ?: throw ConnectorException("中转服务已经不认识这台设备，请重新连接。")
        (json["error"] as? JsonObject)?.let { throw ConnectorException("测试没发出去：${it.text("message")}") }
        return json["result"] as? JsonObject ?: throw ConnectorException("中转服务的回答不完整。")
    }

    private fun url(path: String): HttpUrl = base.newBuilder().addPathSegments(path).build()

    internal fun authorized(path: String, token: String): Request.Builder =
        Request.Builder().url(url(path)).header("Authorization", "Bearer $token")

    internal fun call(request: Request, unknownIsNull: Boolean = false): JsonObject? = relayCall(client, request, unknownIsNull)
}

/** Signed in to [account]; [connected] once the account's connection took the device (or the device's became the account's). */
internal data class RelaySignedIn(val account: String, val connected: Boolean)

/** Signs the device in to an account (or registers one); the relay's words when the name or password is refused. */
internal fun RelayConnectClient.signIn(token: String, name: String, password: String, register: Boolean): RelaySignedIn {
    val body = buildJsonObject {
        put("name", name)
        put("password", password)
        put("register", register)
    }.toString().toRequestBody(JSON_TYPE)
    val json = call(authorized("connect/device/login", token).post(body).build())
        ?: throw ConnectorException("中转服务没有回答。")
    return RelaySignedIn(json.text("account"), json.text("state") == "connected")
}

internal fun RelayConnectClient.signOut(token: String) {
    call(authorized("connect/device/logout", token).post(ByteArray(0).toRequestBody(null)).build(), unknownIsNull = true)
}

/** The relay's JSON answer; null for 401 when [unknownIsNull]; otherwise errors carry the relay's own message. */
private fun relayCall(client: OkHttpClient, request: Request, unknownIsNull: Boolean): JsonObject? = try {
    client.newCall(request).execute().use { response ->
        val json = runCatching { Json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject }.getOrNull()
        when {
            response.isSuccessful -> json
            response.code == 401 && unknownIsNull -> null
            else -> throw ConnectorException(json?.text("error")?.ifBlank { null } ?: "中转服务返回 HTTP ${response.code}。")
        }
    }
} catch (_: IOException) {
    throw ConnectorException("连不上中转服务，请检查设备网络。")
}

private fun pairing(json: JsonObject) = ConnectorStatus.Pairing(
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
internal class ConnectorController(
    private val context: Context,
    private val relayUrl: () -> String = { connectorRelayUrl() },
    private val profiles: ConnectorProfiles = SessionConnectorProfiles,
    private val clientFor: (HttpUrl) -> RelayConnectClient = { RelayConnectClient(it) },
    private val carLabel: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
    private val note: (String) -> Unit = RuntimeSession::noteConnection,
    private val clock: () -> Long = System::currentTimeMillis,
    private val servers: ConnectorServers = SessionConnectorServers,
    private val probe: suspend (McpEndpoint) -> McpToolsListing = { endpoint -> probeMcpEndpoint(context, endpoint) },
) {
    private val prefs get() = context.getSharedPreferences(CONNECTOR_PREFS, Context.MODE_PRIVATE)

    /** The relay [connector] goes through: the one it paired with, else the preset one it would pair with. */
    fun relayHost(connector: Connector): String =
        (prefs.getString(key(connector, RELAY), null) ?: relayUrl()).toHttpUrlOrNull()?.host.orEmpty()

    /** The dialog's pairing poll and the footer's check can overlap; one change of state is applied (and recorded) once. */
    private val changes = Mutex()

    /** Where [connector] stands as far as this car knows, without asking the relay. */
    fun status(connector: Connector): ConnectorStatus {
        connector.mcp?.let { return mcpStatus(connector, it) }
        val prefs = prefs
        return when (prefs.getString(key(connector, STATE), null)) {
            STATE_CONNECTED -> ConnectorStatus.Connected(prefs.getBoolean(key(connector, VERIFIED), false))
            STATE_PAIRING -> ConnectorStatus.Pairing(
                code = prefs.getString(key(connector, CODE), null).orEmpty(),
                connectUrl = prefs.getString(key(connector, CONNECT_URL), null).orEmpty(),
                expiresAtMillis = prefs.getLong(key(connector, CODE_EXPIRES_AT), 0L),
            )
            else -> ConnectorStatus.Disconnected
        }
    }

    suspend fun connect(connector: Connector): ConnectorStatus = withContext(Dispatchers.IO) {
        val configured = relayUrl().trim().trimEnd('/')
        if (configured.isEmpty()) {
            throw ConnectorException("还没有配置中转：部署 tools/agent-relay 后，在 local.properties 写上 $AGENT_RELAY_URL_KEY=https://… 再重新打包。")
        }
        val base = configured.toHttpUrlOrNull()?.takeIf { it.isHttps }
            ?: throw ConnectorException("中转地址无效（打包时的 $AGENT_RELAY_URL_KEY 需要是 https 地址）。")
        val registered = clientFor(base).register(carLabel)
        A2aTokens.save(context, tokenKey(connector), registered.token)?.let { throw ConnectorException(it) }
        prefs.edit().putString(key(connector, RELAY), base.toString()).commit()
        savePairing(connector, registered.pairing)
    }

    suspend fun refreshCode(connector: Connector): ConnectorStatus = withContext(Dispatchers.IO) {
        val (client, token) = stored(connector) ?: return@withContext forget(connector)
        savePairing(connector, client.newCode(token))
    }

    /** The account this car is signed in to, as far as it knows; null when it is not. */
    fun account(connector: Connector): String? = prefs.getString(key(connector, ACCOUNT), null)

    /** Where the account is looked after in a browser: devices, recent tasks, the routine's webhook. */
    fun accountPage(connector: Connector): String =
        (prefs.getString(key(connector, RELAY), null) ?: relayUrl()).trim().trimEnd('/') + ACCOUNT_PATH

    /**
     * Signs in to (or registers) an account. A car not yet known to the relay registers first. An account that holds a
     * connection takes the device into it, so it is connected at once; a connected car hands its connection to an account
     * that has none; otherwise the device stays pairing and the account takes the connection the code makes.
     */
    suspend fun signIn(connector: Connector, name: String, password: String, register: Boolean): ConnectorStatus =
        withContext(Dispatchers.IO) {
            if (stored(connector) == null) connect(connector)
            changes.withLock {
                val (client, token) = stored(connector) ?: throw ConnectorException("还没有连接。")
                val signedIn = client.signIn(token, name.trim(), password, register)
                prefs.edit().putString(key(connector, ACCOUNT), signedIn.account).commit()
                record(connector, if (register) "注册并登录了账号「${signedIn.account}」。" else "登录了账号「${signedIn.account}」。")
                refreshLocked(connector)
            }
        }

    /** Leaves the account; the device stays connected. */
    suspend fun signOut(connector: Connector) = withContext(Dispatchers.IO) {
        val account = account(connector) ?: return@withContext
        stored(connector)?.let { (client, token) -> client.signOut(token) }
        prefs.edit().remove(key(connector, ACCOUNT)).commit()
        record(connector, "退出了账号「$account」，这台设备仍然连着 ${connector.name}。")
    }

    /** The newest connection events first, with their times, kept after a disconnect. */
    fun events(connector: Connector): List<String> =
        prefs.getString(key(connector, EVENTS), null)?.split(EVENT_SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()

    /** Asks the relay whether the device was claimed; a claimed car gets its A2A profile. */
    suspend fun refresh(connector: Connector): ConnectorStatus = withContext(Dispatchers.IO) {
        changes.withLock { refreshLocked(connector) }
    }

    private suspend fun refreshLocked(connector: Connector): ConnectorStatus {
        connector.mcp?.let { return mcpStatus(connector, it) }
        val (client, token) = stored(connector) ?: return forget(connector)
        val wasConnected = status(connector) is ConnectorStatus.Connected
        val state = client.state(token) ?: return forget(connector).also {
            if (wasConnected) record(connector, "${connector.name} 那边解除了绑定。")
        }
        prefs.edit().apply { if (state.account == null) remove(key(connector, ACCOUNT)) else putString(key(connector, ACCOUNT), state.account) }.commit()
        return when {
            state.connected -> {
                if (!wasConnected) {
                    val endpoint = prefs.getString(key(connector, RELAY), null).orEmpty().trimEnd('/') + A2A_PATH
                    profiles.save(context, connector.name, endpoint, connector.profileDescription, token)
                        ?.let { throw ConnectorException(it) }
                }
                prefs.edit().putString(key(connector, STATE), STATE_CONNECTED).putBoolean(key(connector, VERIFIED), state.verified)
                    .remove(key(connector, CODE)).remove(key(connector, CONNECT_URL)).remove(key(connector, CODE_EXPIRES_AT)).commit()
                if (!wasConnected) record(connector, "已连接到你的 ${connector.name}。")
                ConnectorStatus.Connected(state.verified)
            }
            wasConnected -> {
                profiles.remove(context, connector.name)
                record(connector, "${connector.name} 那边解除了绑定。")
                savePairing(connector, ConnectorStatus.Pairing("", "", 0L))
            }
            else -> status(connector)
        }
    }

    /**
     * Sends the connection test through the same A2A path delegation uses and waits for the bot's report; returns
     * the reported text, or throws with why it did not come back.
     */
    suspend fun test(connector: Connector): String = withContext(Dispatchers.IO) {
        val (client, token) = stored(connector) ?: throw ConnectorException("还没有连接。")
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
        } catch (failure: ConnectorException) {
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
    suspend fun disconnect(connector: Connector, onProgress: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        changes.withLock { disconnectLocked(connector, onProgress) }
    }

    private suspend fun disconnectLocked(connector: Connector, onProgress: (String) -> Unit): String {
        connector.mcp?.let { spec ->
            if (!owns(connector)) return "还没有连接。"
            servers.delete(context, spec.serverId)
            prefs.edit().remove(key(connector, OWNED)).commit()
            return "已断开，小博不再使用${connector.name}。".also { record(connector, it) }
        }
        val pairing = status(connector) is ConnectorStatus.Pairing
        val text = stored(connector)?.let { (client, token) -> unpair(connector, client, token, onProgress) } ?: "已解除绑定。"
        forget(connector)
        return if (pairing) "已取消配对。".also { record(connector, it) } else text.also { record(connector, "解除绑定：$it") }
    }

    private suspend fun unpair(connector: Connector, client: RelayConnectClient, token: String, onProgress: (String) -> Unit): String {
        val reply = try {
            client.unpair(token)
        } catch (failure: ConnectorException) {
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
    private suspend fun awaitReport(client: RelayConnectClient, token: String, start: JsonObject, connector: Connector): String {
        var task = start
        val deadline = clock() + REPORT_TIMEOUT_MS
        while (task.state() == "working" || task.state() == "submitted") {
            if (clock() > deadline) throw ConnectorException("${connector.name} 三分钟内没有回报。")
            delay(REPORT_POLL_MS)
            task = client.rpc(token, "tasks/get", buildJsonObject { put("id", task.text("id")) })
        }
        val text = task.statusText()
        if (task.state() != "completed") throw ConnectorException(text.ifEmpty { "任务没有完成（${task.state()}）。" })
        return text
    }

    /**
     * Connects an MCP app: tries [credential] on the app's address (the sandbox one when [sandbox] and there is one)
     * and saves the server only once it lists its tools. Returns the tools' names; throws with what to check.
     */
    suspend fun connectApp(connector: Connector, credential: String, sandbox: Boolean): List<String> = changes.withLock {
        withContext(Dispatchers.IO) { connectAppLocked(connector, credential, sandbox) }
    }

    private suspend fun connectAppLocked(connector: Connector, credential: String, sandbox: Boolean): List<String> {
        val spec = connector.mcp ?: throw ConnectorException("${connector.name} 不是这样连接的。")
        servers.find(context, spec.serverId)?.takeUnless { owns(connector) }?.let { other ->
            throw ConnectorException("设置 → MCP 里已经有一个「${other.label}」用着这个名字（${other.id}），请先在那里删除它。")
        }
        val key = credential.trim()
        if (spec.needsCredential && key.isEmpty()) throw ConnectorException("请先填${spec.credentialLabel}。")
        if (key.any(Char::isWhitespace)) throw ConnectorException("${spec.credentialLabel}里不能有空格或换行。")
        val url = spec.sandboxUrl?.takeIf { sandbox } ?: spec.url
        val tools = listedTools(connector, probe(spec.endpoint(url, key)))
        servers.save(context, spec.draft(servers.find(context, spec.serverId)?.id, connector.name, url, key))
            ?.let { throw ConnectorException(it) }
        servers.find(context, spec.serverId)?.let { saved -> prefs.edit().putString(key(connector, OWNED), ownership(saved)).commit() }
        if (servers.find(context, spec.serverId)?.enabled == false) servers.setEnabled(context, spec.serverId, true)
        record(connector, "已连接${if (url == spec.sandboxUrl) "（沙箱）" else ""}，可以用 ${tools.size} 个功能。")
        return tools
    }

    /**
     * The MCP server under [connector]'s id is the one connecting here saved, not one added by hand in 设置 → MCP: the
     * address and name it was saved with still match.
     */
    private fun owns(connector: Connector): Boolean {
        val server = connector.mcp?.let { servers.find(context, it.serverId) } ?: return false
        return prefs.getString(key(connector, OWNED), null) == ownership(server)
    }

    private fun ownership(server: McpServer) = "${server.url}\n${server.label}"

    /** Lists a connected MCP app's tools with its saved key; recorded only when the user asked ([record]). */
    suspend fun checkApp(connector: Connector, record: Boolean = true): List<String> = withContext(Dispatchers.IO) {
        checkAppLocked(connector, record)
    }

    private suspend fun checkAppLocked(connector: Connector, record: Boolean): List<String> {
        val spec = connector.mcp ?: throw ConnectorException("${connector.name} 不是这样连接的。")
        val server = servers.find(context, spec.serverId)?.takeIf { owns(connector) } ?: throw ConnectorException("还没有连接。")
        val endpoint = McpEndpoint(
            server.url,
            McpSecrets.requestHeaders(context, server.id),
            McpSecrets.requestQuery(context, server.id),
        )
        val tools = try {
            listedTools(connector, probe(endpoint))
        } catch (failure: ConnectorException) {
            if (record) record(connector, "检查没有通过：${failure.message}")
            throw failure
        }
        if (record) record(connector, "检查通过，可以用 ${tools.size} 个功能。")
        return tools
    }

    /** Switches a paused MCP app's server back on. */
    suspend fun resumeApp(connector: Connector): ConnectorStatus {
        val spec = connector.mcp ?: return status(connector)
        servers.setEnabled(context, spec.serverId, true)
        record(connector, "重新启用了。")
        return status(connector)
    }

    /** True when [connector] is connected to its test address (made-up data). */
    fun usesSandbox(connector: Connector): Boolean {
        val spec = connector.mcp ?: return false
        return spec.sandboxUrl != null && owns(connector) && servers.find(context, spec.serverId)?.url == spec.sandboxUrl
    }

    private fun mcpStatus(connector: Connector, spec: McpConnectorSpec): ConnectorStatus {
        if (!owns(connector)) return ConnectorStatus.Disconnected
        val server = servers.find(context, spec.serverId) ?: return ConnectorStatus.Disconnected
        return if (server.enabled) ConnectorStatus.Connected(verified = true) else ConnectorStatus.Paused
    }

    private fun listedTools(connector: Connector, listing: McpToolsListing): List<String> {
        listing.problem?.let { throw ConnectorException(connector.connectFailure(it)) }
        if (listing.tools.isEmpty()) throw ConnectorException("连上了，但 ${connector.name} 没有提供任何功能，请确认这个${connector.mcp?.credentialLabel}开通了权限。")
        return listing.tools.map { tool ->
            tool.title?.takeIf { it.isNotBlank() && it != tool.description && it.length <= MAX_TOOL_TITLE_CHARS } ?: tool.name
        }
    }

    private fun record(connector: Connector, text: String) {
        val stamped = "${SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(clock()))} $text"
        val kept = (listOf(stamped) + events(connector)).take(MAX_EVENTS)
        prefs.edit().putString(key(connector, EVENTS), kept.joinToString(EVENT_SEPARATOR)).commit()
        note("【连接应用】$text")
    }

    private fun stored(connector: Connector): Pair<RelayConnectClient, String>? {
        val base = prefs.getString(key(connector, RELAY), null)?.toHttpUrlOrNull() ?: return null
        val token = A2aTokens.read(context, tokenKey(connector)).ifEmpty { return null }
        return clientFor(base) to token
    }

    private fun savePairing(connector: Connector, pairing: ConnectorStatus.Pairing): ConnectorStatus {
        prefs.edit().putString(key(connector, STATE), STATE_PAIRING).putString(key(connector, CODE), pairing.code)
            .putString(key(connector, CONNECT_URL), pairing.connectUrl).putLong(key(connector, CODE_EXPIRES_AT), pairing.expiresAtMillis)
            .commit()
        return pairing
    }

    private suspend fun forget(connector: Connector): ConnectorStatus {
        if (status(connector) is ConnectorStatus.Connected) profiles.remove(context, connector.name)
        A2aTokens.clear(context, tokenKey(connector))
        val edit = prefs.edit()
        CONNECTOR_KEYS.forEach { edit.remove(key(connector, it)) }
        edit.commit()
        return ConnectorStatus.Disconnected
    }

    private fun key(connector: Connector, name: String) = "${connector.id}.$name"

    private fun tokenKey(connector: Connector) = "connector-${connector.id}"
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
/** The connector saved its MCP server itself ([ConnectorController.connectApp]). */
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
