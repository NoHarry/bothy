package cc.noharry.bothy.sample

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 连接应用 as an app market: each app's own page says how to connect it. These apps publish a remote MCP server, open to
 * anyone or taking a fixed key or token (the two 演示 apps are Bothy Playground, on this device); connecting one tests it
 * and then saves it as a configured server (设置 → 连接 → MCP shows it too), with the key in [DebugMcpSecrets], never in
 * the stored address. The open ones answered
 * initialize and tools/list without any key on 2026-10-21; the keyed ones answered without a key with their own
 * refusal (401, auth failed, INVALID_USER_KEY) on 2026-10-14.
 */
internal data class DebugMcpConnectorSpec(
    /** The configured server's id, so its tools are `mcp__<serverId>__…`. */
    val serverId: String,
    val url: String,
    /** A test address with made-up data (滴滴); offered first when present. */
    val sandboxUrl: String? = null,
    /** What the user pastes: 访问令牌, Key, MCP Key; empty for an open server. */
    val credentialLabel: String = "",
    /** Where the credential goes on each request. */
    val place: DebugMcpCredentialPlace = DebugMcpCredentialPlace.NONE,
    /** Opened on the phone through a QR code: where the credential is issued, or the service's page for an open one. */
    val portal: String,
    val steps: List<String> = emptyList(),
    val abilities: List<String>,
    /** One thing to say to 小博 once connected. */
    val example: String,
    val note: String = "",
) {
    /** False for a server anyone may call: connecting it only tests it. */
    val needsCredential: Boolean get() = place != DebugMcpCredentialPlace.NONE

    fun endpoint(url: String, credential: String): DebugMcpEndpoint = when (place) {
        DebugMcpCredentialPlace.BEARER -> DebugMcpEndpoint(url, headers = mapOf("Authorization" to "Bearer $credential"))
        DebugMcpCredentialPlace.QUERY_KEY -> DebugMcpEndpoint(url, query = mapOf(QUERY_KEY_NAME to credential))
        DebugMcpCredentialPlace.NONE -> DebugMcpEndpoint(url)
    }

    fun draft(existingId: String?, label: String, url: String, credential: String) = DebugMcpServerDraft(
        id = existingId,
        label = label,
        kind = DebugMcpServerKind.HTTP,
        url = url,
        token = if (place == DebugMcpCredentialPlace.BEARER) credential else "",
        query = if (place == DebugMcpCredentialPlace.QUERY_KEY) mapOf(QUERY_KEY_NAME to credential) else emptyMap(),
        clearSecrets = true,
        preferredId = serverId,
    )
}

internal enum class DebugMcpCredentialPlace { BEARER, QUERY_KEY, NONE }

/** A server address with what a request carries; the probe's input, so nothing is saved before it answers. */
internal data class DebugMcpEndpoint(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val query: Map<String, String> = emptyMap(),
)

/** Opens a session with [endpoint] and lists its tools, without saving anything; a problem in words otherwise. */
internal suspend fun probeMcpEndpoint(context: Context, endpoint: DebugMcpEndpoint): McpToolsListing = withContext(Dispatchers.IO) {
    val app = context.applicationContext
    val client = McpHttpClient(
        url = endpoint.url,
        headers = { endpoint.headers },
        http = BenchHttpTransport(app),
        clientName = app.packageName,
        clientVersion = BuildConfig.VERSION_NAME,
        query = { endpoint.query },
    )
    try {
        withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            val connect = client.connect()
            if (connect.ok) listMcpTools(client) else McpToolsListing(emptyList(), connect.errorMessage ?: "连不上")
        } ?: McpToolsListing(emptyList(), "${PROBE_TIMEOUT_MS / 1000} 秒没有回应")
    } finally {
        client.close()
    }
}

/** The configured servers 连接应用 adds, removes and switches; the live session's by default. */
internal interface DebugConnectorServers {
    fun find(context: Context, id: String): DebugMcpServer?

    /** Why it was refused, or null once saved. */
    suspend fun save(context: Context, draft: DebugMcpServerDraft): String?

    suspend fun delete(context: Context, id: String)

    suspend fun setEnabled(context: Context, id: String, enabled: Boolean)
}

/** Through [DebugRuntimeSession], so the next run sees the change; on the main thread like settings changes it. */
internal object SessionConnectorServers : DebugConnectorServers {
    override fun find(context: Context, id: String): DebugMcpServer? = loadDebugMcpServers(context).firstOrNull { it.id == id }

    override suspend fun save(context: Context, draft: DebugMcpServerDraft): String? =
        withContext(Dispatchers.Main) { DebugRuntimeSession.saveMcpServer(context, draft) }

    override suspend fun delete(context: Context, id: String) =
        withContext(Dispatchers.Main) { DebugRuntimeSession.deleteMcpServer(context, id) }

    override suspend fun setEnabled(context: Context, id: String, enabled: Boolean) =
        withContext(Dispatchers.Main) { DebugRuntimeSession.setMcpProviderEnabled(context, id, enabled) }
}

/** Why a key was not taken, with what to check: the network, or the key itself. */
internal fun DebugConnector.connectFailure(problem: String): String {
    val label = mcp?.credentialLabel ?: "令牌"
    val network = problem.startsWith("连不上") || problem.endsWith("秒没有回应")
    val local = mcp?.url?.startsWith("http://127.0.0.1:") == true
    return when {
        local && network -> "连不上$name：先在这台设备上安装并打开 Bothy Playground，再点连接。"
        network -> "连不上$name：$problem。请检查设备网络。"
        mcp?.needsCredential == false -> "$name 现在用不了（$problem）。稍后再试。"
        else -> "$name 没有接受这个$label（$problem）。请确认$label 没填错、没过期。"
    }
}

private val LUCKIN = DebugConnector(
    id = "luckin",
    name = "瑞幸咖啡",
    summary = "查附近门店、点咖啡，到店自取。",
    category = "餐饮",
    mark = "瑞",
    color = 0xFF0B2E6B,
    mcp = DebugMcpConnectorSpec(
        serverId = "luckin",
        url = "https://gwmcp.lkcoffee.com/order/user/mcp",
        credentialLabel = "访问令牌",
        place = DebugMcpCredentialPlace.BEARER,
        portal = "https://open.lkcoffee.com",
        steps = listOf("用手机扫码打开瑞幸 AI 开放平台", "用手机号登录，复制 MCP 访问令牌（大约 30 天有效，过期了回这里更换）"),
        abilities = listOf("找附近门店", "按口味推荐、搜索饮品", "下单前预览价格", "下单、取消订单（只能到店自取）"),
        example = "帮我在最近的瑞幸点一杯生椰拿铁",
        note = "付款要用微信扫码，这里只做到下单这一步。",
    ),
)

// Steps and address from 麦当劳中国's own guide (github.com/M-China/mcd-mcp-server, owner 2026-10-14 pointed to
// github.com/polaris-smart/mcd-agent-gourmet): Bearer token, at most 600 requests a minute; 35 tools on 2026-10-13.
private val MCDONALDS = DebugConnector(
    id = "mcdonalds",
    name = "麦当劳",
    summary = "领券、点餐，到店取餐或麦乐送。",
    category = "餐饮",
    mark = "麦",
    color = 0xFFDA291C,
    mcp = DebugMcpConnectorSpec(
        serverId = "mcdonalds",
        url = "https://mcp.mcd.cn",
        credentialLabel = "MCP Token",
        place = DebugMcpCredentialPlace.BEARER,
        portal = "https://open.mcd.cn/mcp",
        steps = listOf(
            "用手机扫码打开麦当劳 MCP 开放平台，点右上角「登录」，用手机号验证登录",
            "点右上角「控制台」→「激活」，同意服务协议后一键复制 MCP Token",
        ),
        abilities = listOf(
            "查附近门店、菜单和套餐搭配",
            "查我的积分和券，领可用的券",
            "按门店和取餐方式算价格",
            "下单：到店取餐、得来速或麦乐送",
            "查订单进度、活动日历",
        ),
        example = "看看麦当劳今天有什么优惠券",
        note = "付款要回麦当劳 App 完成。菜单上的价格只是展示，以算价结果为准。",
    ),
)

private val AMAP = DebugConnector(
    id = "amap",
    name = "高德地图",
    summary = "找地点、查周边、规划路线、查天气。",
    category = "出行",
    mark = "高",
    color = 0xFF1677FF,
    mcp = DebugMcpConnectorSpec(
        serverId = "amap",
        url = "https://mcp.amap.com/mcp",
        credentialLabel = "Key",
        place = DebugMcpCredentialPlace.QUERY_KEY,
        portal = "https://console.amap.com/dev/key/app",
        steps = listOf("用手机扫码打开高德开放平台控制台并登录", "创建应用、添加 Key，服务平台选「Web 服务」，复制这个 Key"),
        abilities = listOf("关键字和周边搜索（带评分、地址）", "驾车、步行、骑行、公交路线规划", "天气查询", "地址和坐标互查"),
        example = "附近两公里有什么评分高的火锅",
        note = "导航本身仍由手机或平板上的地图应用执行，高德补充搜索和路线信息。",
    ),
)

private val DIDI = DebugConnector(
    id = "didi",
    name = "滴滴出行",
    summary = "帮家人叫车：估价、叫车、查司机位置。",
    category = "出行",
    mark = "滴",
    color = 0xFFFF7A00,
    mcp = DebugMcpConnectorSpec(
        serverId = "didi",
        url = "https://mcp.didichuxing.com/mcp-servers",
        sandboxUrl = "https://mcp.didichuxing.com/mcp-servers-sandbox",
        credentialLabel = "MCP Key",
        place = DebugMcpCredentialPlace.QUERY_KEY,
        portal = "https://mcp.didichuxing.com",
        steps = listOf("用手机扫码打开滴滴 MCP 开放平台", "点「立即体验」并登录，复制 MCP Key"),
        abilities = listOf("估价", "叫车、预约用车", "查订单和司机位置", "取消订单"),
        example = "帮我妈叫一辆车从家到火车站，先估个价",
        note = "沙箱的数据是模拟的，不会真的叫车；正式环境会真实派单、扣费。",
    ),
)

private val TENCENT_DOCS = DebugConnector(
    id = "tencent-docs",
    name = "腾讯文档",
    summary = "查找、阅读和编辑你的在线文档。",
    category = "办公",
    mark = "文",
    color = 0xFF2B6DE5,
    mcp = DebugMcpConnectorSpec(
        serverId = "tdocs",
        url = "https://docs.qq.com/openapi/mcp",
        credentialLabel = "访问令牌",
        place = DebugMcpCredentialPlace.BEARER,
        portal = "https://docs.qq.com/open/document/",
        steps = listOf("用手机扫码打开腾讯文档开放平台并登录", "申请个人 MCP 访问令牌并复制"),
        abilities = listOf("搜索和读取文档", "新建、编辑文档", "知识库管理"),
        example = "把我周报文档的要点念给我听",
    ),
)

// Open servers first: they connect with one tap, so the demo works before any account is made.
/** Bothy Playground's two servers: an app in this repository, on this device, so its address is loopback and open. */
private val PLAYGROUND_NOTE = "Bothy Playground 是仓库里的另一个演示 App（workspace/apps/android/bothy-playground），装在这台设备上并打开后才能连。" +
    "车和家都是虚构的，只在 Playground 里显示，不会控制任何真实设备。"

private val VIRTUAL_CABIN = DebugConnector(
    id = "cabin",
    name = "虚拟座舱",
    summary = "一辆虚构的车：空调、座椅、车窗车门、后备箱、屏幕和音量、氛围灯，改了就在 Bothy Playground 里看得见。",
    category = "演示",
    mark = "座",
    color = 0xFF3F7D5E,
    mcp = DebugMcpConnectorSpec(
        serverId = "cabin",
        url = "http://127.0.0.1:8790/cabin/mcp",
        portal = PLAYGROUND_PORTAL,
        abilities = listOf(
            "空调、温度、风量",
            "四个座椅的加热和通风，知道谁坐在哪、谁在说话",
            "车窗、车门、后备箱，车门锁",
            "中控屏亮度、音量、氛围灯",
            "放音乐、导航（都是虚构的）",
            "车内温度、电量、车门这些读数可以设成计划来监听",
        ),
        example = "我有点冷，把主驾座椅加热开到二档",
        note = PLAYGROUND_NOTE,
    ),
)

private val VIRTUAL_HOME = DebugConnector(
    id = "home",
    name = "虚拟小家",
    summary = "一个虚构的小家：灯、窗帘、空调、门锁、扫地机和场景，改了就在 Bothy Playground 里看得见。",
    category = "演示",
    mark = "家",
    color = 0xFFC65A3A,
    mcp = DebugMcpConnectorSpec(
        serverId = "home",
        url = "http://127.0.0.1:8790/home/mcp",
        portal = PLAYGROUND_PORTAL,
        abilities = listOf("四个房间的灯：开关、亮度、色调", "窗帘、空调", "入户门锁（开锁会先确认）", "扫地机", "回家、离家、睡觉、观影、起床场景", "房间温度可以设成计划来监听"),
        example = "我要睡觉了",
        note = PLAYGROUND_NOTE,
    ),
)

private const val PLAYGROUND_PORTAL = "https://github.com/NoHarry/bothy#bothy-playground"

private val KIWI = DebugConnector(
    id = "kiwi",
    name = "Kiwi.com 机票",
    summary = "搜全球航班、比价，不用注册。",
    category = "出行",
    mark = "K",
    color = 0xFF00A991,
    mcp = DebugMcpConnectorSpec(
        serverId = "kiwi",
        url = "https://mcp.kiwi.com",
        portal = "https://www.kiwi.com",
        abilities = listOf("按出发地、目的地和日期搜航班（可前后浮动几天）", "比价，看中转、时长和行李额", "给出预订链接，在 Kiwi.com 网页上完成预订"),
        example = "下周五从深圳飞成都最便宜的航班是哪班",
        note = "公开服务，不需要 key。价格以 Kiwi.com 网页为准；可以设成计划，在最低价低于某个数时提醒你。",
    ),
)

private val DEEPWIKI = DebugConnector(
    id = "deepwiki",
    name = "DeepWiki",
    summary = "读懂 GitHub 开源仓库：文档结构、内容和问答。",
    category = "开发",
    mark = "D",
    color = 0xFF2F3A4B,
    mcp = DebugMcpConnectorSpec(
        serverId = "deepwiki",
        url = "https://mcp.deepwiki.com/mcp",
        portal = "https://deepwiki.com",
        abilities = listOf("列出仓库文档的目录", "读某一页文档", "就仓库的代码和设计提问"),
        example = "用 DeepWiki 看看 k2-fsa/sherpa-onnx 是怎么做关键词唤醒的",
        note = "公开服务，不需要 key，只支持公开仓库。",
    ),
)

private val HUGGING_FACE = DebugConnector(
    id = "huggingface",
    name = "Hugging Face",
    summary = "搜模型、数据集和 Space，看下载量和许可。",
    category = "开发",
    mark = "H",
    color = 0xFFFF9D00,
    mcp = DebugMcpConnectorSpec(
        serverId = "huggingface",
        url = "https://huggingface.co/mcp",
        portal = "https://huggingface.co/settings/mcp",
        abilities = listOf("搜索模型、数据集和 Space", "看仓库详情：下载量、点赞、许可和文件"),
        example = "Hugging Face 上有哪些下载量高的中文语音识别模型",
        note = "匿名可用，不需要 key；要用自己账号的私有仓库时，可在 设置 → 连接 → MCP 里给它加访问令牌。",
    ),
)

private val CONTEXT7 = DebugConnector(
    id = "context7",
    name = "Context7",
    summary = "查各种库和框架的最新文档与示例代码。",
    category = "开发",
    mark = "C",
    color = 0xFF16A34A,
    mcp = DebugMcpConnectorSpec(
        serverId = "context7",
        url = "https://mcp.context7.com/mcp",
        portal = "https://context7.com",
        abilities = listOf("按名字找到库", "按问题查这个库的最新文档和示例"),
        example = "用 Context7 查一下 Jetpack Compose 的 LazyColumn 怎么做吸顶标题",
        note = "公开服务，不需要 key；用得多时可在 设置 → 连接 → MCP 里加 Context7 的 API key 提高额度。",
    ),
)

/** The MCP apps: the open ones first, then by category: travel, food, office. */
internal val DEBUG_MCP_CONNECTORS: List<DebugConnector> =
    listOf(VIRTUAL_CABIN, VIRTUAL_HOME, KIWI, DEEPWIKI, HUGGING_FACE, CONTEXT7, AMAP, DIDI, LUCKIN, MCDONALDS, TENCENT_DOCS)

private const val QUERY_KEY_NAME = "key"
private const val PROBE_TIMEOUT_MS = 45_000L
