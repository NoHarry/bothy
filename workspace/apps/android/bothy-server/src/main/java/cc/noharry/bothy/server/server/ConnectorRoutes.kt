package cc.noharry.bothy.server.server

import android.content.Context
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.TextResult
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcErrorCodes
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import cc.noharry.agent.appserver.server.RouteModule
import cc.noharry.agent.appserver.server.RouteTable
import cc.noharry.bothy.protocol.ConnectorCatalog
import cc.noharry.bothy.protocol.ConnectorPage
import cc.noharry.bothy.protocol.ConnectorRequests
import cc.noharry.bothy.protocol.ConnectorStatus
import cc.noharry.bothy.protocol.ConnectorStatuses
import cc.noharry.bothy.protocol.ConnectorTools
import cc.noharry.bothy.protocol.ConnectorView
import cc.noharry.bothy.protocol.McpAppView
import cc.noharry.bothy.server.R
import cc.noharry.bothy.server.mcp.Connector
import cc.noharry.bothy.server.mcp.ConnectorController
import cc.noharry.bothy.server.mcp.ConnectorException
import cc.noharry.bothy.server.mcp.DEBUG_CONNECTORS
import cc.noharry.bothy.server.mcp.McpConnectorSpec
import kotlinx.coroutines.CancellationException

/**
 * 连接应用: the market and each app's page, pairing an agent through the relay or connecting an MCP app with its key.
 * One [ConnectorController] serves every client, so its lock keeps one change of state from being applied twice.
 */
internal class ConnectorRoutes(private val app: Context) : RouteModule {
    private val controller = ConnectorController(app)

    override fun RouteTable.install() {
        on(ConnectorRequests.LIST) {
            ConnectorCatalog(DEBUG_CONNECTORS.map { it.view() }, DEBUG_CONNECTORS.associate { it.id to controller.status(it) })
        }
        on(ConnectorRequests.REFRESH_ALL) {
            DEBUG_CONNECTORS.filter { controller.status(it) !is ConnectorStatus.Disconnected }
                .forEach { runCatching { controller.refresh(it) } }
            ConnectorStatuses(DEBUG_CONNECTORS.associate { it.id to controller.status(it) })
        }
        on(ConnectorRequests.REFRESH) { acting { controller.refresh(connector(it.id)) } }
        on(ConnectorRequests.PAGE) { page(connector(it.id)) }
        pairing()
        apps()
    }

    private fun RouteTable.pairing() {
        on(ConnectorRequests.CONNECT) { acting { controller.connect(connector(it.id)) } }
        on(ConnectorRequests.NEW_CODE) { acting { controller.refreshCode(connector(it.id)) } }
        on(ConnectorRequests.SIGN_IN) { params ->
            acting { controller.signIn(connector(params.id), params.name, params.password, params.register) }
        }
        on(ConnectorRequests.SIGN_OUT) {
            val connector = connector(it.id)
            // The page showed a failed sign-out's own words.
            try {
                controller.signOut(connector)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw JsonRpcException(JsonRpcErrorCodes.REJECTED, failure.message.orEmpty())
            }
            Empty
        }
        on(ConnectorRequests.TEST) { TextResult(acting { controller.test(connector(it.id)) }) }
        on(ConnectorRequests.DISCONNECT) { TextResult(controller.disconnect(connector(it.id))) }
    }

    private fun RouteTable.apps() {
        on(ConnectorRequests.CONNECT_APP) { params ->
            ConnectorTools(acting { controller.connectApp(connector(params.id), params.credential, params.sandbox) })
        }
        on(ConnectorRequests.CHECK_APP) { params ->
            ConnectorTools(acting { controller.checkApp(connector(params.id), params.record) })
        }
        on(ConnectorRequests.RESUME_APP) { acting { controller.resumeApp(connector(it.id)) } }
    }

    private fun page(connector: Connector) = ConnectorPage(
        status = controller.status(connector),
        account = controller.account(connector),
        accountPage = controller.accountPage(connector),
        relayHost = controller.relayHost(connector),
        events = controller.events(connector),
        sandbox = controller.usesSandbox(connector),
    )

    private fun connector(id: String): Connector = DEBUG_CONNECTORS.firstOrNull { it.id == id }
        ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "no connector $id")

    /** What the screens showed for a failure: the controller's own words, or the generic line with the cause. */
    private suspend fun <T> acting(block: suspend () -> T): T = try {
        block()
    } catch (failure: ConnectorException) {
        throw JsonRpcException(JsonRpcErrorCodes.REJECTED, failure.message.orEmpty())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (refused: JsonRpcException) {
        throw refused
    } catch (failure: Exception) {
        val cause = failure.message ?: failure.javaClass.simpleName
        throw JsonRpcException(JsonRpcErrorCodes.REJECTED, app.getString(R.string.mcp_error_generic, cause))
    }

    private fun Connector.view() = ConnectorView(
        id = id,
        name = name,
        title = app.getString(title),
        summary = app.getString(summary),
        category = app.getString(category),
        mark = app.getString(mark ?: title).take(1),
        color = color,
        app = mcp?.view(),
    )

    private fun McpConnectorSpec.view() = McpAppView(
        url = url,
        sandboxUrl = sandboxUrl,
        credentialLabel = credentialLabel?.let { app.getString(it) }.orEmpty(),
        needsCredential = needsCredential,
        portal = portal,
        steps = steps.map { app.getString(it) },
        abilities = abilities.map { app.getString(it) },
        example = app.getString(example),
        note = note?.let { app.getString(it) },
    )
}
