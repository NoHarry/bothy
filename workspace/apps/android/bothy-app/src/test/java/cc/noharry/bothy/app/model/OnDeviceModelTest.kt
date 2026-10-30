@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.model

import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelClientException
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.bothy.app.session.ModelProvider
import cc.noharry.bothy.llm.LlamaDevice
import cc.noharry.bothy.llm.LlamaServer
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OnDeviceModelTest {
    @Test
    fun theOnDeviceProfileIsAddedOnceToAnyList() {
        val cloud = ProviderProfile("deepseek-api", "deepseek api", ModelProvider.OPENAI, "", "deepseek-flash", "https://api.deepseek.com/v1")

        val profiles = listOf(cloud).withOnDeviceProfile().withOnDeviceProfile()

        assertEquals(listOf("deepseek-api", ON_DEVICE_PROFILE_ID), profiles.map { it.id })
        val onDevice = profiles.last()
        assertEquals(OnDeviceModel.spec.id, onDevice.model)
        assertEquals(true, onDevice.streamEnabled)
        assertFalse(onDevice.serverSideSearch)
    }

    @Test
    fun anOnDeviceProfileWithoutItsModelIsNotReadyAndSaysHowToGetIt() {
        val config = config(onDeviceProfile())

        assertFalse(config.isReady(config.activeProfile))
        assertTrue(config.missingMessage(config.activeProfile).contains("下载"))
        assertTrue(config.client(config.activeProfile, stream = true) is OnDeviceModelClient)
    }

    @Test
    fun theOnDeviceWindowIsTheServersWindow() {
        assertEquals(OnDeviceModel.spec.contextTokens.toLong(), defaultContextWindow(OnDeviceModel.spec.id))
    }

    @Test
    fun theClientWaitsForTheServerAndCallsItsAddress() = runBlocking {
        var calledWith: LlamaServer.State.Ready? = null
        val ready = LlamaServer.State.Ready("http://127.0.0.1:4321/v1", "key", LlamaDevice.NPU)
        val client = OnDeviceModelClient(ready = { ready }) { server ->
            calledWith = server
            fixed(ModelEvent.TextDelta("好的"), ModelEvent.Completed)
        }

        val events = client.complete(ModelRequest(messages = emptyList())).toList()

        assertEquals(ready, calledWith)
        assertEquals(listOf(ModelEvent.TextDelta("好的"), ModelEvent.Completed), events)
        assertEquals(ProviderConcurrencyDeclaration.serialized(), client.providerConcurrency)
    }

    @Test
    fun aServerThatCannotStartEndsTheRunWithItsReason() = runBlocking {
        val client = OnDeviceModelClient(ready = { error("本机模型启动失败：no NPU") }) { fail("no client without a server"); fixed() }

        try {
            client.complete(ModelRequest(messages = emptyList())).toList()
            fail("expected a model client failure")
        } catch (e: ModelClientException) {
            assertFalse(e.recoverable)
            assertEquals("本机模型启动失败：no NPU", e.message)
        }
    }

    private fun config(profile: ProviderProfile) = ProviderConfig(
        values = emptyMap(),
        sourcePath = null,
        expectedPaths = emptyList(),
        profiles = listOf(profile),
        defaultProfileId = profile.id,
        activeProfileId = profile.id,
        manualProfileEnabled = false,
    )

    private fun fixed(vararg events: ModelEvent) = object : ModelClient {
        override fun complete(request: ModelRequest) = flowOf(*events)
    }
}
