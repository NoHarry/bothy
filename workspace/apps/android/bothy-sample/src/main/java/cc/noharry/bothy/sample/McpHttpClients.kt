package cc.noharry.bothy.sample

import android.content.Context
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.mcp.McpClientMessages
import cc.noharry.agent.mcp.McpHttpClient
import cc.noharry.agent.mcp.McpToolProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The app's one connection per standard MCP server, shared by runs, the catalog cache and the tool preview. An edit
 * to the server's address makes a new one.
 */
internal object McpHttpClients {
    private val clients = mutableMapOf<String, Pair<String, McpHttpClient>>()
    /** Where a server nobody could reach is tried again, out of every run's way. */
    private val retries = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun get(context: Context, server: DebugMcpServer): McpHttpClient {
        val app = context.applicationContext
        return synchronized(clients) {
            clients[server.id]?.takeIf { it.first == server.url }?.second
                ?: McpHttpClient(
                    url = server.url,
                    headers = { DebugMcpSecrets.requestHeaders(app, server.id) },
                    http = BenchHttpTransport(app),
                    clientName = app.packageName,
                    clientVersion = BuildConfig.VERSION_NAME,
                    query = { DebugMcpSecrets.requestQuery(app, server.id) },
                    unreachableCooldownMillis = UNREACHABLE_COOLDOWN_MS,
                    retryScope = retries,
                    messages = ChineseMcpMessages,
                ).also { client -> clients.put(server.id, server.url to client)?.second?.close() }
        }
    }

    fun close(serverId: String) {
        synchronized(clients) { clients.remove(serverId) }?.second?.close()
    }

    /** How often a server nobody could reach is tried again (in the background); its switch toggled tries it at once. */
    private const val UNREACHABLE_COOLDOWN_MS = 5 * 60 * 1000L
}

/** A standard MCP server over HTTP (设置 → 连接 → MCP), with the app's shared connection and catalog for it. */
internal fun httpMcpToolProvider(context: Context, server: DebugMcpServer): McpToolProvider =
    McpToolProvider(
        client = McpHttpClients.get(context, server),
        serverId = server.id,
        displayName = server.label,
        catalog = McpToolCatalogs.cache(server.id),
        describe = { it.withSpokenHint() },
    )

/** The app's words for MCP problems, shown in 连接应用, the tool preview and a run's tool errors. */
internal object ChineseMcpMessages : McpClientMessages {
    override fun timedOut(seconds: Long): String = "$seconds 秒没有回应"

    override fun unreachable(detail: String): String = "连不上：$detail"

    override fun notJsonRpc(): String = "服务的回应不是 JSON-RPC"

    override fun initializeRefused(rawHint: String): String =
        if (rawHint.isEmpty()) "服务没有回应 initialize" else "服务没有回应 initialize：$rawHint"

    override fun httpProblem(status: Int): String? = when (status) {
        in 200..299 -> null
        401, 403 -> "服务拒绝了（HTTP $status），检查令牌或请求头"
        404 -> "地址不对（HTTP 404）"
        405 -> "这个地址不收 POST（HTTP 405），可能是旧版 SSE 服务，只支持 Streamable HTTP"
        406, 415 -> "服务不接受这个格式（HTTP $status）"
        in 500..599 -> "服务出错了（HTTP $status）"
        else -> "HTTP $status"
    }

    override fun toolsListFailed(statusCode: Int): String = "tools/list 失败（status=$statusCode）"

    override fun toolsListNotJson(): String = "tools/list 的回应不是 JSON"

    override fun toolsListError(message: String): String = "tools/list 出错：$message"

    override fun toolsListCursorRepeated(): String = "tools/list 的分页游标重复了"

    override fun toolsListTooManyPages(maxPages: Int): String = "tools/list 超过 $maxPages 页"
}

/** An order or payment tool, from any server, says it waits for the user's pick ([TRANSACTION_HINT]). */
internal fun ToolDescriptor.withSpokenHint(): ToolDescriptor =
    if (isTransaction(name)) copy(description = "$description $TRANSACTION_HINT") else this

/** On every order or payment tool ([isTransaction]): a model said 「先帮你下单了」 before [DebugActionGate] refused the call. */
private const val TRANSACTION_HINT =
    "这会真的下单或付款：先用预览把门店、商品、价格告诉用户，用 offer_options 给「下单」（绑定这次调用）和「先不下」，等用户选；" +
        "调用成功之前不要说已经下单。"
