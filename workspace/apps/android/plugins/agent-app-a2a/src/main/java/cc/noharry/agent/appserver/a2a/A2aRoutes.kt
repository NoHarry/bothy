package cc.noharry.agent.appserver.a2a

import android.content.Context
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.FeatureRequests
import cc.noharry.agent.appserver.protocol.SaveResult
import cc.noharry.agent.appserver.protocol.SettingsRequests
import cc.noharry.agent.appserver.protocol.TextResult
import cc.noharry.agent.appserver.protocol.text
import cc.noharry.agent.appserver.server.RouteModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The A2A plugin's requests ([A2aPlugin]), on the hub's main thread: the profiles (`a2a/profile/setEnabled`, `save`,
 * `delete`), client mode (`a2aClient/…`), and, only where the local endpoint runs ([localServer]), its token
 * (`a2a/server/token`, an explicit read: the credential never enters published state or notifications).
 */
fun a2aRoutes(context: Context, localServer: Boolean): RouteModule {
    val app = context.applicationContext
    return RouteModule {
        on(SettingsRequests.A2A_PROFILE_SET_ENABLED) { Empty.also { _ -> A2aProfiles.setEnabled(app, it.id, it.enabled) } }
        on(SettingsRequests.A2A_PROFILE_SAVE) { draft ->
            // The profile store words its refusals itself; they come through as they are until it reports them as findings.
            SaveResult(with(draft) { A2aProfiles.save(app, label, moduleId, endpoint, allowLoopback, description, token) }?.let(::text))
        }
        on(SettingsRequests.A2A_PROFILE_DELETE) { Empty.also { _ -> A2aProfiles.delete(app, it.id) } }
        on(FeatureRequests.A2A_CLIENT_SET_ENABLED) { Empty.also { _ -> A2aClientConsole.setEnabled(app, it.on) } }
        on(FeatureRequests.A2A_CLIENT_SET_SPEAK_EXTERNAL) { Empty.also { _ -> A2aClientConsole.setSpeakExternal(app, it.on) } }
        on(FeatureRequests.A2A_CLIENT_SEND) { Empty.also { _ -> A2aClientConsole.send(app, it.text, it.source) } }
        on(FeatureRequests.A2A_CLIENT_STEER) { Empty.also { _ -> A2aClientConsole.steer(app, it.text, it.source) } }
        on(FeatureRequests.A2A_CLIENT_ANSWER) { Empty.also { _ -> A2aClientConsole.answer(app, it.questionId, it.text, it.source) } }
        on(FeatureRequests.A2A_CLIENT_CANCEL) { Empty.also { _ -> A2aClientConsole.cancel(app) } }
        on(FeatureRequests.A2A_CLIENT_NEW_CONVERSATION) { Empty.also { _ -> A2aClientConsole.newConversation(app) } }
        if (localServer) {
            on(SettingsRequests.A2A_SERVER_TOKEN) { withContext(Dispatchers.IO) { TextResult(A2aServerManager.serverToken(app)) } }
        }
    }
}
