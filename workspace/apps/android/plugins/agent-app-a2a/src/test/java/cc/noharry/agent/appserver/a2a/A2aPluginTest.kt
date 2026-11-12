package cc.noharry.agent.appserver.a2a

import cc.noharry.agent.appserver.server.AppServerTesting
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.DELEGATION_TURN_LABEL
import cc.noharry.agent.appserver.protocol.FeatureRequests
import cc.noharry.agent.appserver.protocol.SettingsRequests
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcNotification
import cc.noharry.agent.appserver.runtime.RunFacts
import cc.noharry.agent.appserver.server.Delivery
import cc.noharry.agent.appserver.server.PluginAdditions
import cc.noharry.agent.appserver.server.PluginHost
import cc.noharry.agent.appserver.server.PluginSwitches
import cc.noharry.agent.appserver.server.PublishedState
import cc.noharry.agent.appserver.tools.isServiceTool
import kotlinx.coroutines.flow.Flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A2A comes only with its plugin, and goes with it: the profiles and their section, the hand-off tool (the main agent's
 * alone, in the app's own runs), its naming gate and where its tasks stand, the skills' `a2a` sections, the labels of
 * the reports' turns, client mode. The local endpoint is left off here: it would listen on the machine's 8766.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class A2aPluginTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val host = PluginHost(app, listOf(A2aPlugin()), AllOn(), conversation = { _, _ -> })

    @After
    fun stop() {
        host.setEnabled("a2a", false)
        AppServerTesting.reset()
    }

    @Test
    fun withoutThePluginNothingOfA2aRuns() {
        connectAgent()
        assertFalse(A2aRuntime.running)
        assertTrue(A2aProfiles.bridgeProviders(app).isEmpty())
        assertEquals(cc.noharry.agent.appserver.protocol.A2aModuleState(), A2aProfiles.state.value)
        A2aClientConsole.setEnabled(app, true)
        assertFalse(A2aClientConsole.isEnabled(app))
    }

    @Test
    fun runningItBringsTheProfilesTheToolItsGateAndItsSections() {
        host.startAll()
        connectAgent()
        val additions = host.additions
        val requests = listOf(
            SettingsRequests.A2A_PROFILE_SET_ENABLED, SettingsRequests.A2A_PROFILE_SAVE, SettingsRequests.A2A_PROFILE_DELETE,
            FeatureRequests.A2A_CLIENT_SET_ENABLED, FeatureRequests.A2A_CLIENT_SET_SPEAK_EXTERNAL, FeatureRequests.A2A_CLIENT_SEND,
            FeatureRequests.A2A_CLIENT_STEER, FeatureRequests.A2A_CLIENT_ANSWER, FeatureRequests.A2A_CLIENT_CANCEL,
            FeatureRequests.A2A_CLIENT_NEW_CONVERSATION,
        )
        // No local endpoint, so no token to read.
        assertEquals(requests.map { it.name }.toSet(), additions.routes.keys)
        val tools = additions.runTools.flatMap { it(facts()) }
        assertEquals(listOf("experimental_a2a_user-grok".replace('-', '_')), tools.map { it.providerId })
        assertTrue(additions.mainAgentOnly(tools.single().providerId) && additions.ownsByPrefix(tools.single().providerId))
        AppServerTesting.running(additions)
        assertFalse(isServiceTool("mcp__${tools.single().providerId}__a2a_message_send"))
        assertEquals(1, additions.runHooks.flatMap { it(facts()) }.size)
        // A subagent's run and an editor's never had it.
        listOf(facts(child = true), facts(editor = true)).forEach { run ->
            assertTrue(additions.runTools.flatMap { it(run) }.isEmpty() && additions.runHooks.flatMap { it(run) }.isEmpty())
        }
        assertEquals(setOf(A2A_SKILL_SECTION), additions.skillSections(app))
        assertEquals(DELEGATION_TURN_LABEL, additions.turnLabel("【委托回报】Grok：好了"))
        assertNull(additions.turnLabel("明天天气怎么样"))
        val state = RecordingState()
        host.publishInto(state)
        idle()
        assertEquals(listOf("Grok"), state.current.a2aClients.profiles.map { it.label })
    }

    @Test
    fun turnedOffItsToolsSectionsAndClientsGoAndComeBackOn() {
        host.startAll()
        connectAgent()
        val state = RecordingState()
        host.publishInto(state)
        idle()
        host.setEnabled("a2a", false)
        assertTrue(state.current.a2aClients.profiles.isEmpty() && state.current.delegations.isEmpty())
        with(host.additions) {
            assertTrue(routes.isEmpty() && runTools.isEmpty() && runHooks.isEmpty() && contextLines.isEmpty() && skillSections.isEmpty())
            assertFalse(mainAgentOnly("experimental_a2a_user_grok"))
            assertNull(turnLabel("【委托回报】Grok：好了"))
        }
        assertTrue(A2aProfiles.bridgeProviders(app).isEmpty())
        // The profile was kept: on again, it is there.
        host.setEnabled("a2a", true)
        assertEquals(1, host.additions.runTools.flatMap { it(facts()) }.size)
    }

    @Test
    fun aSettledDelegationReachesTheConversationAsATurnMarkedDeliveredOnArrival() {
        val delivery: Delivery = A2aReport("【委托回报】Grok：好了", "【委托回报】Grok 完成了", "task-1").toDelivery(app)
        assertEquals(app.getString(R.string.a2a_delegation_report_title), delivery.title)
        assertNull(delivery.said)
        assertEquals("【委托回报】Grok 完成了", delivery.forModel)
    }

    @Test
    fun aRunStartedOnTheDeviceTakesTheFocusBackFromAnotherClientsTask() {
        host.startAll()
        InboundFocus.inbound()
        host.additions.localTurnStarted()
        assertFalse(InboundFocus.external.value)        // Turned off, no other client's task holds it either.
        InboundFocus.inbound()
        host.setEnabled("a2a", false)
        assertFalse(InboundFocus.external.value)
    }

    private fun connectAgent() {
        assertNull(A2aProfiles.save(app, "Grok", DIRECT_MODULE_ID, "https://agent.example.com/a2a", allowLoopbackHttpForDebug = false))
        val id = A2aModuleConfig.load(app).state.profiles.single { it.label == "Grok" }.id
        A2aProfiles.setEnabled(app, id, true)
    }

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun facts(child: Boolean = false, editor: Boolean = false) =
        RunFacts(app as Context, "", child = child, webSearch = false, subagentTypes = emptySet(), editor = editor)

    private class RecordingState : PublishedState {
        override var current = ServerState()
        override fun update(change: ServerState.() -> ServerState) { current = current.change() }
        override fun <T> follow(flow: Flow<T>, into: ServerState.(T) -> ServerState) = error("a plugin follows through its own scope")
        override fun broadcast(notification: JsonRpcNotification) = Unit
    }

    private class AllOn : PluginSwitches {
        private val off = mutableSetOf<String>()

        override fun isOn(id: String): Boolean = id !in off

        override fun set(id: String, on: Boolean) {
            if (on) off -= id else off += id
        }
    }
}
