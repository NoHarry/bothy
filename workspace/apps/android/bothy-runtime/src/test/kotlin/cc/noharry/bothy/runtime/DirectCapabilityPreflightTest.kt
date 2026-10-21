@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.runtime

import cc.noharry.bothy.kernel.api.AgentCapabilities
import cc.noharry.bothy.kernel.api.AgentPlugin
import cc.noharry.bothy.kernel.api.AgentPluginDescriptor
import cc.noharry.bothy.kernel.api.AgentPluginScope
import cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.bothy.kernel.api.KernelApiRange
import cc.noharry.bothy.kernel.api.KernelApiVersions
import cc.noharry.bothy.kernel.api.PluginContractMessages
import cc.noharry.bothy.kernel.api.PluginVersion
import cc.noharry.bothy.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.bothy.kernel.api.SessionCapabilityFactory
import cc.noharry.bothy.kernel.api.model.ModelClient
import cc.noharry.bothy.kernel.api.model.ModelEvent
import cc.noharry.bothy.kernel.api.model.ModelRequest
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import cc.noharry.bothy.kernel.core.RuntimeCapabilityDeclarations
import cc.noharry.bothy.kernel.core.RuntimeCompositionMessages

class DirectCapabilityPreflightTest {
    @Test
    fun declaredDirectAndPluginSingleCollisionIsSideEffectFree() {
        var installed = false
        val model = preflightFinishModel()
        val error = assertThrows(IllegalArgumentException::class.java) {
            AgentRuntimeBuilder.create()
                .modelClient(model)
                .plugin(describedPlugin("dup-model", AgentCapabilities.MODEL_CLIENT) {
                    installed = true
                    provide(AgentCapabilities.MODEL_CLIENT, model, testSerializedConcurrency)
                })
                .build()
        }
        assertTrue(error.message.orEmpty().contains(PluginContractMessages.DUPLICATE_SINGLE))
        assertTrue(!installed)
    }

    @Test
    fun nonToolProviderSessionFactoryFailsAtBuildWithUnsupportedComposition() {
        val error = assertThrows(IllegalStateException::class.java) {
            AgentRuntimeBuilder.create()
                .plugin(
                    describedPlugin("factory-model", AgentCapabilities.MODEL_CLIENT) {
                        val factory = SessionCapabilityFactory<cc.noharry.bothy.kernel.api.model.ModelClient> {
                            preflightFinishModel()
                        }
                        provide(AgentCapabilities.MODEL_CLIENT, factory)
                    },
                )
                .build()
        }
        assertTrue(
            error.message.orEmpty().contains(RuntimeCompositionMessages.UNSUPPORTED_FACTORY_COMPOSITION),
        )
    }

    @Test
    fun noncanonicalBuiltinDiagnostic_failsAtRuntimeAdapter() {
        val alias = cc.noharry.bothy.kernel.api.CapabilityKey<cc.noharry.bothy.kernel.api.ToolProvider>(
            "tool_provider",
            cc.noharry.bothy.kernel.api.CapabilityCardinality.MULTIPLE,
        )
        val error = assertThrows(IllegalStateException::class.java) {
            RuntimeProfileAdapter.compile(
                testProfile("noncanonical"),
                RuntimeCapabilityDeclarations.Collector().snapshot(),
                listOf(
                    describedPlugin("alias-tools", alias) {
                        provide(
                            alias,
                            object : cc.noharry.bothy.kernel.api.ToolProvider {
                                override val providerId: String = "alias"
                                override val providerConcurrency = testSerializedConcurrency
                                override suspend fun listTools() = emptyList<cc.noharry.bothy.kernel.api.ToolDescriptor>()
                                override suspend fun callTool(
                                    toolName: String,
                                    argumentsJson: String,
                                    context: cc.noharry.bothy.kernel.api.ToolCallContext,
                                ) = cc.noharry.bothy.kernel.api.ToolCallResult(success = true, resultJson = "{}")
                            },
                            testSerializedConcurrency,
                        )
                    },
                ),
            )
        }
        assertTrue(error.message.orEmpty().contains(RuntimeCompositionMessages.NONCANONICAL_BUILTIN_KEY))
    }

    @Test
    fun directSessionFactoryModelStillFailsClosed() {
        val error = assertThrows(IllegalStateException::class.java) {
            AgentRuntimeBuilder.create()
                .modelClient(object : ModelClient {
                    override val providerConcurrency =
                        ProviderConcurrencyDeclaration.sessionFactory(1)
                    override fun complete(request: ModelRequest) = flowOf(ModelEvent.Completed)
                })
                .build()
        }
        assertTrue(error.message.orEmpty().contains("SESSION_FACTORY"))
    }
}

private fun preflightFinishModel(): ModelClient =
    object : ModelClient {
        override val providerConcurrency = testSerializedConcurrency

        override fun complete(request: ModelRequest) =
            flowOf(
                ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""),
                ModelEvent.Completed,
            )
    }

private fun describedPlugin(
    id: String,
    key: cc.noharry.bothy.kernel.api.CapabilityKey<*>,
    install: AgentPluginScope.() -> Unit,
): AgentPlugin =
    object : AgentPlugin {
        override val id: String = id
        override val descriptor = AgentPluginDescriptor(
            id = id,
            pluginVersion = PluginVersion(1, 0, 0),
            kernelApiRange = KernelApiRange(KernelApiVersions.CURRENT, KernelApiVersions.CURRENT),
            provides = setOf(key),
        )

        override fun install(scope: AgentPluginScope) {
            scope.install()
        }
    }
