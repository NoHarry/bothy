package cc.noharry.bothy.app.overlay

import android.app.Application
import android.util.Log
import cc.noharry.agent.appserver.protocol.ClientCapabilities
import cc.noharry.agent.appserver.protocol.ClientInfo
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.FeatureRequests
import cc.noharry.agent.appserver.protocol.PresentResult
import cc.noharry.agent.appserver.protocol.PresentationNotifications
import cc.noharry.agent.appserver.protocol.PresentationReports
import cc.noharry.agent.appserver.protocol.PresentationRequests
import cc.noharry.agent.appserver.protocol.PresentationState
import cc.noharry.agent.appserver.protocol.TakenResult
import cc.noharry.agent.appserver.protocol.client.ServerClient
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcNotification
import cc.noharry.agent.appserver.protocol.jsonrpc.paramsOf
import cc.noharry.bothy.protocol.BothyPresentationRequests
import cc.noharry.bothy.protocol.WakeOrigin
import cc.noharry.bothy.server.server.BothyServer
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The app's own connection to the server, from the process's start: it presents the assistant on this screen as the
 * server asks ([PresentationRequests]: bring the overlay up, open or fold its card, take a wake, show a plan coming due,
 * draw Android Use) and tells the server how the assistant stands ([report]) and what changed in the app around it. It
 * takes no state, so connecting it starts nothing in the server. Everything runs on the main thread.
 */
internal object HostConnection {
    private const val CLIENT_NAME = "host"
    private const val TAG = "HostConnection"

    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val reported = MutableStateFlow(PresentationState())
    private val connected = CompletableDeferred<ServerClient>()

    fun start(app: Application) {
        val client = ServerClient(
            channel = BothyServer.connect(app),
            clientInfo = ClientInfo(CLIENT_NAME),
            capabilities = ClientCapabilities(presentation = true, state = false),
            scope = main,
            onError = { error -> Log.w(TAG, error.message.orEmpty()) },
        )
        present(client, app)
        main.launch {
            launch(start = CoroutineStart.UNDISPATCHED) { client.notifications.collect { shown(app, it) } }
            client.connect()
            connected.complete(client)
            reported.collect { client.notify(PresentationReports.STATE, it) }
        }
    }

    /** How the assistant stands changed: the server hears the whole of it. Any thread. */
    fun report(change: PresentationState.() -> PresentationState) = reported.update(change)

    /** The screens' language changed: what the server words in it follows. */
    fun languageChanged() {
        main.launch { connected.await().request(FeatureRequests.LANGUAGE_CHANGED, Empty) }
    }

    private fun present(client: ServerClient, app: Application) {
        client.handle(PresentationRequests.ENSURE_RUNNING) { OverlayControl.ensureRunning(app) }
        client.handle(PresentationRequests.START) { PresentResult(OverlayControl.start(app, it.open)) }
        client.handle(PresentationRequests.CARD) { PresentResult(OverlayControl.apply(app, it.command)) }
        client.handle(PresentationRequests.WAKE) { wake ->
            TakenResult(WakeOrigin.entries.any { it.name == wake.origin && OverlayControl.wake(it) })
        }
        client.handle(BothyPresentationRequests.PREVIEW_MASCOT) { TakenResult(OverlayControl.previewMascot(it.name)) }
        client.handle(PresentationRequests.ANDROID_USE_BEGIN) { androidUse { done -> begin(done) } }
        client.handle(PresentationRequests.ANDROID_USE_POINT) { androidUse { done -> point(it.x, it.y, it.press, done) } }
        client.handle(PresentationRequests.ANDROID_USE_DRAG) {
            androidUse { done -> drag(it.fromX, it.fromY, it.toX, it.toY, it.durationMs, done) }
        }
        client.handle(PresentationRequests.ANDROID_USE_HIDE) { androidUse { done -> hide(done) } }
    }

    private fun shown(app: Application, notification: JsonRpcNotification) {
        when (notification.method) {
            PresentationNotifications.PLAN_DUE.name -> OverlayControl.planDue(app, notification.paramsOf(PresentationNotifications.PLAN_DUE))
            PresentationNotifications.PLAN_RAN.name ->
                notification.paramsOf(PresentationNotifications.PLAN_RAN).let { OverlayControl.planRan(it.planId, it.outcome) }
            PresentationNotifications.ANDROID_USE_REVEAL.name -> OverlayControl.androidUse?.reveal()
        }
    }

    /** Android Use on the running overlay, answered once it is shown; at once when no overlay is up. */
    private suspend fun androidUse(call: AndroidUseOverlay.(done: () -> Unit) -> Unit): Empty {
        val overlay = OverlayControl.androidUse ?: return Empty
        suspendCancellableCoroutine { waiting -> overlay.call { if (waiting.isActive) waiting.resume(Unit) } }
        return Empty
    }
}
