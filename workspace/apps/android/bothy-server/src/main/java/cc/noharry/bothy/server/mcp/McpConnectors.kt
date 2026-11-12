package cc.noharry.bothy.server.mcp
import android.content.Context
import androidx.annotation.StringRes
import cc.noharry.agent.appserver.http.AppServerHttpTransport
import cc.noharry.agent.appserver.mcp.ScreenMcpMessages
import cc.noharry.agent.appserver.mcp.loadMcpServers
import cc.noharry.agent.appserver.protocol.McpServer
import cc.noharry.agent.appserver.protocol.McpServerDraft
import cc.noharry.agent.appserver.protocol.McpServerKind
import cc.noharry.agent.appserver.protocol.EnabledParams
import cc.noharry.agent.appserver.protocol.IdParams
import cc.noharry.agent.appserver.protocol.SettingsRequests
import cc.noharry.bothy.server.server.ServerLink
import cc.noharry.agent.mcp.McpHttpClient
import cc.noharry.agent.mcp.McpToolsListing
import cc.noharry.agent.mcp.listMcpTools
import cc.noharry.bothy.server.BuildConfig
import cc.noharry.bothy.server.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 连接应用 as an app market: each app's own page says how to connect it. These apps publish a remote MCP server, open to
 * anyone or taking a fixed key or token (the two 演示 apps are Bothy Playground, on this device); connecting one tests it
 * and then saves it as a configured server (设置 → 连接 → MCP shows it too), with the key in [McpSecrets], never in
 * the stored address. The open ones answered
 * initialize and tools/list without any key on 2026-10-21; the keyed ones answered without a key with their own
 * refusal (401, auth failed, INVALID_USER_KEY) on 2026-10-14.
 */
internal data class McpConnectorSpec(
    /** The configured server's id, so its tools are `mcp__<serverId>__…`. */
    val serverId: String,
    val url: String,
    /** A test address with made-up data (滴滴); offered first when present. */
    val sandboxUrl: String? = null,
    /** What the user pastes: 访问令牌, Key, MCP Key; null for an open server. */
    @StringRes val credentialLabel: Int? = null,
    /** Where the credential goes on each request. */
    val place: McpCredentialPlace = McpCredentialPlace.NONE,
    /** Opened on the phone through a QR code: where the credential is issued, or the service's page for an open one. */
    val portal: String,
    /** The steps, abilities, example and note are string resources: the screen shows them in the app's language. */
    val steps: List<Int> = emptyList(),
    val abilities: List<Int>,
    /** One thing to say to 小博 once connected. */
    @StringRes val example: Int,
    @StringRes val note: Int? = null,
) {
    /** False for a server anyone may call: connecting it only tests it. */
    val needsCredential: Boolean get() = place != McpCredentialPlace.NONE

    fun endpoint(url: String, credential: String): McpEndpoint = when (place) {
        McpCredentialPlace.BEARER -> McpEndpoint(url, headers = mapOf("Authorization" to "Bearer $credential"))
        McpCredentialPlace.QUERY_KEY -> McpEndpoint(url, query = mapOf(QUERY_KEY_NAME to credential))
        McpCredentialPlace.NONE -> McpEndpoint(url)
    }

    fun draft(existingId: String?, label: String, url: String, credential: String) = McpServerDraft(
        id = existingId,
        label = label,
        kind = McpServerKind.HTTP,
        url = url,
        token = if (place == McpCredentialPlace.BEARER) credential else "",
        query = if (place == McpCredentialPlace.QUERY_KEY) mapOf(QUERY_KEY_NAME to credential) else emptyMap(),
        clearSecrets = true,
        preferredId = serverId,
    )
}

internal enum class McpCredentialPlace { BEARER, QUERY_KEY, NONE }

/** A server address with what a request carries; the probe's input, so nothing is saved before it answers. */
internal data class McpEndpoint(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val query: Map<String, String> = emptyMap(),
)

/** Opens a session with [endpoint] and lists its tools, without saving anything; a problem in words otherwise. */
internal suspend fun probeMcpEndpoint(context: Context, endpoint: McpEndpoint): McpToolsListing = withContext(Dispatchers.IO) {
    val app = context.applicationContext
    // Only the screen shows these problems, so they are in the app's language.
    val messages = ScreenMcpMessages(app)
    val client = McpHttpClient(
        url = endpoint.url,
        headers = { endpoint.headers },
        http = AppServerHttpTransport(app),
        clientName = app.packageName,
        clientVersion = BuildConfig.VERSION_NAME,
        query = { endpoint.query },
        messages = messages,
    )
    try {
        withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            val connect = client.connect()
            if (connect.ok) {
                listMcpTools(client, messages)
            } else {
                val problem = connect.errorMessage ?: app.getString(R.string.mcp_problem_unreachable_plain)
                McpToolsListing(emptyList(), problem)
            }
        } ?: McpToolsListing(emptyList(), messages.timedOut(PROBE_TIMEOUT_MS / 1000))
    } finally {
        client.close()
    }
}

/** The configured servers 连接应用 adds, removes and switches; the live session's by default. */
internal interface ConnectorServers {
    fun find(context: Context, id: String): McpServer?

    /** Why it was refused, or null once saved. */
    suspend fun save(context: Context, draft: McpServerDraft): String?

    suspend fun delete(context: Context, id: String)

    suspend fun setEnabled(context: Context, id: String, enabled: Boolean)
}

/** As the protocol's MCP requests ([ServerLink.request]), so the next run sees the change; on the main thread like settings changes it. */
internal object SessionConnectorServers : ConnectorServers {
    override fun find(context: Context, id: String): McpServer? = loadMcpServers(context).firstOrNull { it.id == id }

    override suspend fun save(context: Context, draft: McpServerDraft): String? =
        withContext(Dispatchers.Main) { ServerLink.request(SettingsRequests.MCP_SAVE, draft).error?.let { it.args.firstOrNull() ?: it.code } }

    override suspend fun delete(context: Context, id: String) =
        withContext(Dispatchers.Main) { ServerLink.request(SettingsRequests.MCP_DELETE, IdParams(id)); Unit }

    override suspend fun setEnabled(context: Context, id: String, enabled: Boolean) =
        withContext(Dispatchers.Main) { ServerLink.request(SettingsRequests.MCP_SET_ENABLED, EnabledParams(id, enabled)); Unit }
}

/** Why a key was not taken, with what to check: the network, or the key itself. */
internal fun Connector.connectFailure(context: Context, problem: String): String {
    val app = displayName(context)
    val label = context.getString(mcp?.credentialLabel ?: R.string.mcp_token)
    val network = ScreenMcpMessages(context).isNetwork(problem)
    val local = mcp?.url?.startsWith("http://127.0.0.1:") == true
    return when {
        local && network -> context.getString(R.string.mcp_connect_failed_local, app)
        network -> context.getString(R.string.mcp_connect_failed_network, app, problem)
        mcp?.needsCredential == false -> context.getString(R.string.mcp_connect_failed_open, app, problem)
        else -> context.getString(R.string.mcp_connect_failed_refused, app, label, problem)
    }
}

private val LUCKIN = Connector(
    id = "luckin",
    name = "瑞幸咖啡",
    title = R.string.mcp_app_luckin,
    summary = R.string.mcp_luckin_summary,
    category = R.string.mcp_category_food,
    color = 0xFF0B2E6B,
    mcp = McpConnectorSpec(
        serverId = "luckin",
        url = "https://gwmcp.lkcoffee.com/order/user/mcp",
        credentialLabel = R.string.mcp_credential_access_token,
        place = McpCredentialPlace.BEARER,
        portal = "https://open.lkcoffee.com",
        steps = listOf(R.string.mcp_luckin_step_1, R.string.mcp_luckin_step_2),
        abilities = listOf(
            R.string.mcp_luckin_ability_1,
            R.string.mcp_luckin_ability_2,
            R.string.mcp_luckin_ability_3,
            R.string.mcp_luckin_ability_4,
        ),
        example = R.string.mcp_luckin_example,
        note = R.string.mcp_luckin_note,
    ),
)

// Steps and address from 麦当劳中国's own guide (github.com/M-China/mcd-mcp-server, owner 2026-10-14 pointed to
// github.com/polaris-smart/mcd-agent-gourmet): Bearer token, at most 600 requests a minute; 35 tools on 2026-10-13.
private val MCDONALDS = Connector(
    id = "mcdonalds",
    name = "麦当劳",
    title = R.string.mcp_app_mcdonalds,
    summary = R.string.mcp_mcdonalds_summary,
    category = R.string.mcp_category_food,
    color = 0xFFDA291C,
    mcp = McpConnectorSpec(
        serverId = "mcdonalds",
        url = "https://mcp.mcd.cn",
        credentialLabel = R.string.mcp_credential_mcp_token,
        place = McpCredentialPlace.BEARER,
        portal = "https://open.mcd.cn/mcp",
        steps = listOf(R.string.mcp_mcdonalds_step_1, R.string.mcp_mcdonalds_step_2),
        abilities = listOf(
            R.string.mcp_mcdonalds_ability_1,
            R.string.mcp_mcdonalds_ability_2,
            R.string.mcp_mcdonalds_ability_3,
            R.string.mcp_mcdonalds_ability_4,
            R.string.mcp_mcdonalds_ability_5,
        ),
        example = R.string.mcp_mcdonalds_example,
        note = R.string.mcp_mcdonalds_note,
    ),
)

private val AMAP = Connector(
    id = "amap",
    name = "高德地图",
    title = R.string.mcp_app_amap,
    summary = R.string.mcp_amap_summary,
    category = R.string.mcp_category_travel,
    color = 0xFF1677FF,
    mcp = McpConnectorSpec(
        serverId = "amap",
        url = "https://mcp.amap.com/mcp",
        credentialLabel = R.string.mcp_credential_key,
        place = McpCredentialPlace.QUERY_KEY,
        portal = "https://console.amap.com/dev/key/app",
        steps = listOf(R.string.mcp_amap_step_1, R.string.mcp_amap_step_2),
        abilities = listOf(
            R.string.mcp_amap_ability_1,
            R.string.mcp_amap_ability_2,
            R.string.mcp_amap_ability_3,
            R.string.mcp_amap_ability_4,
        ),
        example = R.string.mcp_amap_example,
        note = R.string.mcp_amap_note,
    ),
)

private val DIDI = Connector(
    id = "didi",
    name = "滴滴出行",
    title = R.string.mcp_app_didi,
    summary = R.string.mcp_didi_summary,
    category = R.string.mcp_category_travel,
    color = 0xFFFF7A00,
    mcp = McpConnectorSpec(
        serverId = "didi",
        url = "https://mcp.didichuxing.com/mcp-servers",
        sandboxUrl = "https://mcp.didichuxing.com/mcp-servers-sandbox",
        credentialLabel = R.string.mcp_credential_mcp_key,
        place = McpCredentialPlace.QUERY_KEY,
        portal = "https://mcp.didichuxing.com",
        steps = listOf(R.string.mcp_didi_step_1, R.string.mcp_didi_step_2),
        abilities = listOf(
            R.string.mcp_didi_ability_1,
            R.string.mcp_didi_ability_2,
            R.string.mcp_didi_ability_3,
            R.string.mcp_didi_ability_4,
        ),
        example = R.string.mcp_didi_example,
        note = R.string.mcp_didi_note,
    ),
)

private val TENCENT_DOCS = Connector(
    id = "tencent-docs",
    name = "腾讯文档",
    title = R.string.mcp_app_tencent_docs,
    summary = R.string.mcp_tencent_docs_summary,
    category = R.string.mcp_category_work,
    mark = R.string.mcp_mark_tencent_docs,
    color = 0xFF2B6DE5,
    mcp = McpConnectorSpec(
        serverId = "tdocs",
        url = "https://docs.qq.com/openapi/mcp",
        credentialLabel = R.string.mcp_credential_access_token,
        place = McpCredentialPlace.BEARER,
        portal = "https://docs.qq.com/open/document/",
        steps = listOf(R.string.mcp_tencent_docs_step_1, R.string.mcp_tencent_docs_step_2),
        abilities = listOf(
            R.string.mcp_tencent_docs_ability_1,
            R.string.mcp_tencent_docs_ability_2,
            R.string.mcp_tencent_docs_ability_3,
        ),
        example = R.string.mcp_tencent_docs_example,
    ),
)

// Open servers first: they connect with one tap, so the demo works before any account is made.
/** Bothy Playground's two servers: an app in this repository, on this device, so its address is loopback and open. */
private val PLAYGROUND_NOTE = R.string.mcp_playground_note

private val VIRTUAL_CABIN = Connector(
    id = "cabin",
    name = "虚拟座舱",
    title = R.string.mcp_app_cabin,
    summary = R.string.mcp_cabin_summary,
    category = R.string.mcp_category_demo,
    mark = R.string.mcp_mark_cabin,
    color = 0xFF3F7D5E,
    mcp = McpConnectorSpec(
        serverId = "cabin",
        url = "http://127.0.0.1:8790/cabin/mcp",
        portal = PLAYGROUND_PORTAL,
        abilities = listOf(
            R.string.mcp_cabin_ability_1,
            R.string.mcp_cabin_ability_2,
            R.string.mcp_cabin_ability_3,
            R.string.mcp_cabin_ability_4,
            R.string.mcp_cabin_ability_5,
            R.string.mcp_cabin_ability_6,
        ),
        example = R.string.mcp_cabin_example,
        note = PLAYGROUND_NOTE,
    ),
)

private val VIRTUAL_HOME = Connector(
    id = "home",
    name = "虚拟小家",
    title = R.string.mcp_app_home,
    summary = R.string.mcp_home_summary,
    category = R.string.mcp_category_demo,
    mark = R.string.mcp_mark_home,
    color = 0xFFC65A3A,
    mcp = McpConnectorSpec(
        serverId = "home",
        url = "http://127.0.0.1:8790/home/mcp",
        portal = PLAYGROUND_PORTAL,
        abilities = listOf(
            R.string.mcp_home_ability_1,
            R.string.mcp_home_ability_2,
            R.string.mcp_home_ability_3,
            R.string.mcp_home_ability_4,
            R.string.mcp_home_ability_5,
            R.string.mcp_home_ability_6,
        ),
        example = R.string.mcp_home_example,
        note = PLAYGROUND_NOTE,
    ),
)

private const val PLAYGROUND_PORTAL = "https://github.com/NoHarry/bothy#bothy-playground"

private val KIWI = Connector(
    id = "kiwi",
    name = "Kiwi.com 机票",
    title = R.string.mcp_app_kiwi,
    summary = R.string.mcp_kiwi_summary,
    category = R.string.mcp_category_travel,
    color = 0xFF00A991,
    mcp = McpConnectorSpec(
        serverId = "kiwi",
        url = "https://mcp.kiwi.com",
        portal = "https://www.kiwi.com",
        abilities = listOf(R.string.mcp_kiwi_ability_1, R.string.mcp_kiwi_ability_2, R.string.mcp_kiwi_ability_3),
        example = R.string.mcp_kiwi_example,
        note = R.string.mcp_kiwi_note,
    ),
)

private val DEEPWIKI = Connector(
    id = "deepwiki",
    name = "DeepWiki",
    title = R.string.mcp_app_deepwiki,
    summary = R.string.mcp_deepwiki_summary,
    category = R.string.mcp_category_dev,
    color = 0xFF2F3A4B,
    mcp = McpConnectorSpec(
        serverId = "deepwiki",
        url = "https://mcp.deepwiki.com/mcp",
        portal = "https://deepwiki.com",
        abilities = listOf(
            R.string.mcp_deepwiki_ability_1,
            R.string.mcp_deepwiki_ability_2,
            R.string.mcp_deepwiki_ability_3,
        ),
        example = R.string.mcp_deepwiki_example,
        note = R.string.mcp_deepwiki_note,
    ),
)

private val HUGGING_FACE = Connector(
    id = "huggingface",
    name = "Hugging Face",
    title = R.string.mcp_app_huggingface,
    summary = R.string.mcp_huggingface_summary,
    category = R.string.mcp_category_dev,
    color = 0xFFFF9D00,
    mcp = McpConnectorSpec(
        serverId = "huggingface",
        url = "https://huggingface.co/mcp",
        portal = "https://huggingface.co/settings/mcp",
        abilities = listOf(R.string.mcp_huggingface_ability_1, R.string.mcp_huggingface_ability_2),
        example = R.string.mcp_huggingface_example,
        note = R.string.mcp_huggingface_note,
    ),
)

private val CONTEXT7 = Connector(
    id = "context7",
    name = "Context7",
    title = R.string.mcp_app_context7,
    summary = R.string.mcp_context7_summary,
    category = R.string.mcp_category_dev,
    color = 0xFF16A34A,
    mcp = McpConnectorSpec(
        serverId = "context7",
        url = "https://mcp.context7.com/mcp",
        portal = "https://context7.com",
        abilities = listOf(R.string.mcp_context7_ability_1, R.string.mcp_context7_ability_2),
        example = R.string.mcp_context7_example,
        note = R.string.mcp_context7_note,
    ),
)

/** The MCP apps: the open ones first, then by category: travel, food, office. */
internal val DEBUG_MCP_CONNECTORS: List<Connector> =
    listOf(VIRTUAL_CABIN, VIRTUAL_HOME, KIWI, DEEPWIKI, HUGGING_FACE, CONTEXT7, AMAP, DIDI, LUCKIN, MCDONALDS, TENCENT_DOCS)

/**
 * The Playground's virtual car and home, devices a subagent only queries unless allowed to operate them: by their
 * connectors' ids, or the ids of servers added by hand at their addresses.
 */
internal fun playgroundDeviceServers(context: Context): Set<String> {
    val devices = listOf(VIRTUAL_CABIN, VIRTUAL_HOME).mapNotNull { it.mcp }
    val urls = devices.mapTo(hashSetOf()) { it.url }
    return devices.mapTo(linkedSetOf()) { it.serverId } +
        loadMcpServers(context).filter { it.url.trim().trimEnd('/') in urls }.map { it.id }
}

private const val QUERY_KEY_NAME = "key"
private const val PROBE_TIMEOUT_MS = 45_000L
