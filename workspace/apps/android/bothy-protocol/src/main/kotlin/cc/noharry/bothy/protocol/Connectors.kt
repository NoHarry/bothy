package cc.noharry.bothy.protocol

import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.IdParams
import cc.noharry.agent.appserver.protocol.TextResult
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where one app in 连接应用 stands. */
@Serializable
sealed interface ConnectorStatus {
    @Serializable
    @SerialName("disconnected")
    data object Disconnected : ConnectorStatus

    /** Waiting for the user to claim [code] on the relay's page; an expired or missing code needs a refresh. */
    @Serializable
    @SerialName("pairing")
    data class Pairing(val code: String, val connectUrl: String, val expiresAtMillis: Long) : ConnectorStatus

    @Serializable
    @SerialName("connected")
    data class Connected(val verified: Boolean) : ConnectorStatus

    /** An MCP app whose server is switched off in 设置 → 连接 → MCP. */
    @Serializable
    @SerialName("paused")
    data object Paused : ConnectorStatus
}

/**
 * One app in 连接应用, in the screen's language: an agent that pairs through the relay, or an MCP [app] whose page says
 * how to connect it.
 */
@Serializable
data class ConnectorView(
    val id: String,
    /** The label its MCP server or A2A profile is saved under, which the model reads: the same in every language. */
    val name: String,
    val title: String,
    val summary: String,
    val category: String,
    /** The icon: a letter on a colour (ARGB). */
    val mark: String,
    val color: Long,
    val app: McpAppView? = null,
)

/** How an MCP app is connected and what it does, as its page shows it. */
@Serializable
data class McpAppView(
    val url: String,
    /** A test address with made-up data; offered first when present. */
    val sandboxUrl: String? = null,
    /** What the user pastes (访问令牌, Key, MCP Key); empty for an open server. */
    val credentialLabel: String = "",
    val needsCredential: Boolean = false,
    /** Opened on the phone through a QR code: where the credential is issued, or the service's page. */
    val portal: String = "",
    val steps: List<String> = emptyList(),
    val abilities: List<String> = emptyList(),
    /** One thing to say to 小博 once connected. */
    val example: String = "",
    val note: String? = null,
)

/** The market: every app, and where each stands as far as this device knows. */
@Serializable
data class ConnectorCatalog(
    val connectors: List<ConnectorView> = emptyList(),
    val statuses: Map<String, ConnectorStatus> = emptyMap(),
)

@Serializable
data class ConnectorStatuses(val statuses: Map<String, ConnectorStatus> = emptyMap())

/** One app's page: where it stands, the account, the relay, the latest events (newest first) and the test address. */
@Serializable
data class ConnectorPage(
    val status: ConnectorStatus = ConnectorStatus.Disconnected,
    val account: String? = null,
    /** Where the account is looked after in a browser. */
    val accountPage: String = "",
    val relayHost: String = "",
    val events: List<String> = emptyList(),
    /** Connected to the app's test address (made-up data). */
    val sandbox: Boolean = false,
)

@Serializable
data class ConnectorSignInParams(val id: String, val name: String, val password: String, val register: Boolean)

/** An MCP app's key ([credential], never sent back) and whether to use its test address. */
@Serializable
data class ConnectAppParams(val id: String, val credential: String = "", val sandbox: Boolean = false)

/** Lists a connected MCP app's tools; [record] puts the check in its events. */
@Serializable
data class CheckAppParams(val id: String, val record: Boolean = true)

/** The tools an MCP app listed. */
@Serializable
data class ConnectorTools(val tools: List<String> = emptyList())

/** 连接应用. A request that fails is refused with why, in the screen's language. */
object ConnectorRequests {
    /** The apps and their statuses, without asking the relay. */
    val LIST = RequestMethod("connectors/list", Empty.serializer(), ConnectorCatalog.serializer())

    /** Asks the relay about every app that is not disconnected; then every app's status. */
    val REFRESH_ALL = RequestMethod("connectors/refreshAll", Empty.serializer(), ConnectorStatuses.serializer())

    /** Asks the relay whether one app was claimed (a pairing that finished). */
    val REFRESH = RequestMethod("connectors/refresh", IdParams.serializer(), ConnectorStatus.serializer())
    val PAGE = RequestMethod("connectors/page", IdParams.serializer(), ConnectorPage.serializer())

    /** Registers this device with the relay and starts pairing an agent. */
    val CONNECT = RequestMethod("connectors/connect", IdParams.serializer(), ConnectorStatus.serializer())
    val NEW_CODE = RequestMethod("connectors/newCode", IdParams.serializer(), ConnectorStatus.serializer())
    val SIGN_IN = RequestMethod("connectors/signIn", ConnectorSignInParams.serializer(), ConnectorStatus.serializer())
    val SIGN_OUT = RequestMethod("connectors/signOut", IdParams.serializer(), Empty.serializer())

    /** Sends the connection test to a paired agent and answers with its report. */
    val TEST = RequestMethod("connectors/test", IdParams.serializer(), TextResult.serializer())

    /** Unpairs or disconnects; answers with what happened and never fails. */
    val DISCONNECT = RequestMethod("connectors/disconnect", IdParams.serializer(), TextResult.serializer())
    val CONNECT_APP = RequestMethod("connectors/connectApp", ConnectAppParams.serializer(), ConnectorTools.serializer())
    val CHECK_APP = RequestMethod("connectors/checkApp", CheckAppParams.serializer(), ConnectorTools.serializer())
    val RESUME_APP = RequestMethod("connectors/resumeApp", IdParams.serializer(), ConnectorStatus.serializer())
}
