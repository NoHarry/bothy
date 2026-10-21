@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.kernel.core

import cc.noharry.bothy.kernel.api.AgentCapabilities
import cc.noharry.bothy.kernel.api.AgentEvent
import cc.noharry.bothy.kernel.api.AgentPlugin
import cc.noharry.bothy.kernel.api.AgentPluginDescriptor
import cc.noharry.bothy.kernel.api.AgentPluginScope
import cc.noharry.bothy.kernel.api.AgentRequest
import cc.noharry.bothy.kernel.api.CancelPendingConfirmationRequest
import cc.noharry.bothy.kernel.api.CancelPendingResult
import cc.noharry.bothy.kernel.api.CapabilityCardinality
import cc.noharry.bothy.kernel.api.CapabilityKey
import cc.noharry.bothy.kernel.api.ConfirmationChoice
import cc.noharry.bothy.kernel.api.ConfirmationDecision
import cc.noharry.bothy.kernel.api.ConfirmationResumeResult
import cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.bothy.kernel.api.KernelApiRange
import cc.noharry.bothy.kernel.api.KernelApiVersions
import cc.noharry.bothy.kernel.api.PluginContractMessages
import cc.noharry.bothy.kernel.api.PluginVersion
import cc.noharry.bothy.kernel.api.PostUserInputRequest
import cc.noharry.bothy.kernel.api.PostUserInputResult
import cc.noharry.bothy.kernel.api.ProtocolRequestCancellation
import cc.noharry.bothy.kernel.api.ProtocolRequestCloseLoopResult
import cc.noharry.bothy.kernel.api.ProtocolRequestRejection
import cc.noharry.bothy.kernel.api.ProtocolRequestResolution
import cc.noharry.bothy.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.bothy.kernel.api.RuntimeHook
import cc.noharry.bothy.kernel.api.SessionEvent
import cc.noharry.bothy.kernel.api.SessionEventAppend
import cc.noharry.bothy.kernel.api.SessionEventLog
import cc.noharry.bothy.kernel.api.SessionEventQuery
import cc.noharry.bothy.kernel.api.ToolCallContext
import cc.noharry.bothy.kernel.api.ToolCallResult
import cc.noharry.bothy.kernel.api.ToolDescriptor
import cc.noharry.bothy.kernel.api.ToolProvider
import cc.noharry.bothy.kernel.api.WorkspaceCallContext
import cc.noharry.bothy.kernel.api.WorkspaceExecutionResult
import cc.noharry.bothy.kernel.api.WorkspacePort
import cc.noharry.bothy.kernel.api.model.ModelClient
import cc.noharry.bothy.kernel.api.model.ModelEvent
import cc.noharry.bothy.kernel.api.model.ModelRequest
import cc.noharry.bothy.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.bothy.kernel.api.reliability.RuntimeRunStatus
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelRuntimeBuilderTest {
    @Test
    fun missingModelFailsClosed() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            KernelRuntimeBuilder().workspace(InMemoryWorkspace()).build()
        }
        assertTrue(error.message.orEmpty().contains(RuntimeCompositionMessages.MISSING_MODEL))
    }

    @Test
    fun missingWorkspaceFailsClosed() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            KernelRuntimeBuilder().model(finishModel()).build()
        }
        assertTrue(error.message.orEmpty().contains(RuntimeCompositionMessages.MISSING_WORKSPACE))
    }

    @Test
    fun duplicatePluginsFailClosed() {
        val plugin = describedPlugin("dup")
        val error = assertThrows(IllegalArgumentException::class.java) {
            KernelRuntimeBuilder()
                .model(finishModel())
                .workspace(InMemoryWorkspace())
                .plugin(plugin)
                .plugin(describedPlugin("dup"))
                .build()
        }
        assertTrue(error.message.orEmpty().contains(PluginContractMessages.DUPLICATE_PLUGIN_ID))
    }

    @Test
    fun noncanonicalBuiltinKeyFailsClosed() {
        val alias = CapabilityKey<ToolProvider>("tool_provider", CapabilityCardinality.MULTIPLE)
        val error = assertThrows(IllegalStateException::class.java) {
            KernelRuntimeBuilder()
                .model(finishModel())
                .workspace(InMemoryWorkspace())
                .plugin(
                    describedPlugin("alias-tools", alias) {
                        provide(alias, catalogProvider("alias"), testSerializedConcurrency)
                    },
                )
                .build()
        }
        assertTrue(error.message.orEmpty().contains(RuntimeCompositionMessages.NONCANONICAL_BUILTIN_KEY))
    }

    @Test
    fun runUsesSingleNativeToolLoopAndRetainsTerminalResult() = runBlocking {
        assertEquals("cc.noharry.bothy.kernel.core.NativeToolLoop", NativeToolLoop::class.java.name)
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("cc.noharry.bothy.runtime.NativeToolLoop")
        }
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("cc.noharry.bothy.minimal.NativeToolLoop")
        }
        val seen = mutableListOf<ModelRequest>()
        val model = finishModel(seen)
        val workspace = InMemoryWorkspace()
        val runtime = KernelRuntimeBuilder()
            .profile(testProfile("core-min"))
            .model(model)
            .workspace(workspace)
            .build() as CoreAgentRuntime
        try {
            assertSame(model, runtime.modelClientForTest())
            assertSame(workspace, runtime.workspaceForTest())
            val events = runtime.run(request("core-min")).toList()
            assertTrue(events.first() is AgentEvent.Started)
            assertTrue(events.any { it is AgentEvent.ResultAvailable })
            assertTrue(events.last() is AgentEvent.Finished)
            val result = runtime.getRuntimeResult("request-core-min")
            assertEquals(RuntimeRunStatus.COMPLETED, result?.status)
            assertTrue(seen.single().tools.any { it.name == "finish" })
            assertTrue(seen.single().tools.none { it.name == "web_fetch" })
        } finally {
            runtime.close()
            runtime.close()
        }
    }

    @Test
    fun hostOwnedAutoCloseableModelAndWorkspaceSurviveCloseAndCanBeShared() = runBlocking {
        val model = CloseTrackingModel()
        val workspace = CloseTrackingWorkspace()
        val pluginClosed = AtomicInteger(0)
        KernelRuntimeBuilder()
            .model(model)
            .workspace(workspace)
            .plugin(ownedCapabilityPlugin("owned-close", AgentCapabilities.TOOL_PROVIDER, catalogProvider("owned")) {
                pluginClosed.incrementAndGet()
            })
            .build()
            .close()
        assertEquals(0, model.closeCount.get())
        assertEquals(0, workspace.closeCount.get())
        assertEquals(1, pluginClosed.get())
        val second = KernelRuntimeBuilder().model(model).workspace(workspace).build()
        try {
            assertTrue(second.run(request("shared-host")).toList().any { it is AgentEvent.Finished })
        } finally {
            second.close()
        }
        assertEquals(0, model.closeCount.get())
        assertEquals(0, workspace.closeCount.get())
    }

    @Test
    fun directPluginOwnsResourcesByDefaultAndCanOptOut() {
        val ownedModel = CloseTrackingModel()
        val ownedWorkspace = CloseTrackingWorkspace()
        val hostModel = CloseTrackingModel()
        val hostWorkspace = CloseTrackingWorkspace()
        ProfileCompiler().compile(
            testProfile("own-true"),
            listOfNotNull(DirectCapabilityPlugin.from(directDeclarations(ownedModel, ownedWorkspace))),
        ).close()
        assertEquals(1, ownedModel.closeCount.get())
        assertEquals(1, ownedWorkspace.closeCount.get())
        ProfileCompiler().compile(
            testProfile("own-false"),
            listOfNotNull(
                DirectCapabilityPlugin.from(directDeclarations(hostModel, hostWorkspace), ownResources = false),
            ),
        ).close()
        assertEquals(0, hostModel.closeCount.get())
        assertEquals(0, hostWorkspace.closeCount.get())
    }

    @Test
    fun pluginWorkspaceAndModelKeepObjectIdentity() {
        val model = finishModel()
        val workspace = InMemoryWorkspace()
        val runtime = KernelRuntimeBuilder()
            .model(model)
            .workspace(workspace)
            .build() as CoreAgentRuntime
        try {
            val blueprint = runtime.blueprintForTest()
            assertSame(model, blueprint.resolveShared(AgentCapabilities.MODEL_CLIENT))
            assertSame(workspace, blueprint.resolveShared(AgentCapabilities.WORKSPACE))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun buildIsOneShotAndClosesBuilderForSameInstance() {
        val builder = KernelRuntimeBuilder()
            .model(finishModel())
            .workspace(InMemoryWorkspace())
        builder.build().close()
        val error = assertThrows(IllegalStateException::class.java) { builder.build() }
        assertTrue(error.message.orEmpty().contains(RuntimeCompositionMessages.BUILDER_ALREADY_USED))
    }

    @Test
    fun failedBuildStillConsumesBuilderAndClosesCompiledBlueprint() {
        val closed = AtomicInteger(0)
        val builder = KernelRuntimeBuilder()
            .model(finishModel())
            .workspace(InMemoryWorkspace())
            .plugin(ownedCapabilityPlugin("hook-once", AgentCapabilities.RUNTIME_HOOK, UnusedHook) { closed.incrementAndGet() })
        val error = assertThrows(IllegalStateException::class.java) { builder.build() }
        assertTrue(error.message.orEmpty().contains(RuntimeCompositionMessages.UNSUPPORTED_INSTALLED_CAPABILITY))
        assertEquals(1, closed.get())
        val second = assertThrows(IllegalStateException::class.java) { builder.build() }
        assertTrue(second.message.orEmpty().contains(RuntimeCompositionMessages.BUILDER_ALREADY_USED))
        assertEquals(1, closed.get())
    }

    @Test
    fun unsupportedEventLogIsRejectedAfterCompileAndClosedOnce() {
        val closed = AtomicInteger(0)
        val error = assertThrows(IllegalStateException::class.java) {
            KernelRuntimeBuilder()
                .model(finishModel())
                .workspace(InMemoryWorkspace())
                .plugin(
                    ownedCapabilityPlugin(
                        "event-log",
                        AgentCapabilities.SESSION_EVENT_LOG,
                        UnusedEventLog,
                    ) { closed.incrementAndGet() },
                )
                .build()
        }
        assertTrue(error.message.orEmpty().contains(RuntimeCompositionMessages.UNSUPPORTED_INSTALLED_CAPABILITY))
        assertEquals(1, closed.get())
    }

    @Test
    fun configuredWorkspaceIdDefaultsToDefaultAndRejectsOtherIds() = runBlocking {
        val runtime = KernelRuntimeBuilder()
            .model(finishModel())
            .workspace(InMemoryWorkspace())
            .build() as CoreAgentRuntime
        try {
            assertEquals(KernelRuntimeBuilder.DEFAULT_WORKSPACE_ID, runtime.configuredWorkspaceIdForTest())
            assertTrue(runtime.limitedModelForTest() is ConcurrencyLimitedModelClient)
            val accepted = runtime.run(request("ws-default").copy(workspaceId = "default")).toList()
            assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRuntimeResult("request-ws-default")?.status)
            assertTrue(accepted.any { it is AgentEvent.Finished })
            val rejected = runtime.run(request("ws-other").copy(workspaceId = "other")).toList()
            val failed = rejected.filterIsInstance<AgentEvent.Failed>().single()
            assertEquals(RuntimeErrorCode.WORKSPACE_NOT_CONFIGURED.wireCode, failed.code)
            assertTrue(!failed.recoverable)
            assertEquals(
                RuntimeErrorCode.WORKSPACE_NOT_CONFIGURED.wireCode,
                runtime.getRuntimeResult("request-ws-other")?.errorCode,
            )
        } finally {
            runtime.close()
        }
    }

    @Test
    fun controlAndQueryFailClosedWhenNoPendingControlExists() = runBlocking {
        val runtime = KernelRuntimeBuilder()
            .model(finishModel())
            .workspace(InMemoryWorkspace())
            .build()
        try {
            assertEquals(
                ConfirmationResumeResult.NotFound,
                runtime.resumeConfirmation(
                    ConfirmationDecision("pending", "request", ConfirmationChoice.APPROVE),
                ),
            )
            assertNull(runtime.getPendingConfirmation("pending"))
            assertEquals(
                CancelPendingResult.NotFound,
                runtime.cancelPendingConfirmation(CancelPendingConfirmationRequest("pending", "request")),
            )
            assertNull(runtime.getRunState("missing"))
            assertNull(runtime.getRuntimeResult("missing"))
            assertNull(runtime.getRuntimeHandoff("missing"))
            assertNull(runtime.getOpenConfusion("missing"))
            assertNull(runtime.resumeUserInput(request("resume").copy(resumeRunId = "missing")))
            assertEquals(
                PostUserInputResult.NotFound,
                runtime.postUserInput(PostUserInputRequest("missing", "request", "steer")),
            )
            assertEquals(
                ProtocolRequestCloseLoopResult.NotFound,
                runtime.resolveRuntimeProtocolRequest(ProtocolRequestResolution("missing", "request")),
            )
            assertEquals(
                ProtocolRequestCloseLoopResult.NotFound,
                runtime.rejectRuntimeProtocolRequest(ProtocolRequestRejection("missing", "request", "no")),
            )
            assertEquals(
                ProtocolRequestCloseLoopResult.NotFound,
                runtime.cancelRuntimeProtocolRequest(ProtocolRequestCancellation("missing", "request")),
            )
            assertTrue(runtime.getPendingProtocolRequests().isEmpty())
            assertNull(runtime.getRuntimeEvidence("missing"))
            assertTrue(runtime.getRuntimeTraceEvents("missing").isEmpty())
        } finally {
            runtime.close()
        }
    }
}

private fun directDeclarations(
    model: ModelClient,
    workspace: WorkspacePort,
): RuntimeCapabilityDeclarations =
    RuntimeCapabilityDeclarations.Collector().apply {
        modelClient(model)
        workspace(workspace)
    }.snapshot()

private class CloseTrackingModel : ModelClient, AutoCloseable {
    val closeCount = AtomicInteger(0)

    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()

    override fun complete(request: ModelRequest) =
        flowOf(
            ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""),
            ModelEvent.Completed,
        )

    override fun close() {
        closeCount.incrementAndGet()
    }
}

private class CloseTrackingWorkspace : WorkspacePort, AutoCloseable {
    val closeCount = AtomicInteger(0)

    override suspend fun execute(
        toolName: String,
        argumentsJson: String,
        context: WorkspaceCallContext,
    ): WorkspaceExecutionResult = WorkspaceExecutionResult("""{"ok":true}""")

    override fun close() {
        closeCount.incrementAndGet()
    }
}

private class InMemoryWorkspace : WorkspacePort {
    override suspend fun execute(
        toolName: String,
        argumentsJson: String,
        context: WorkspaceCallContext,
    ): WorkspaceExecutionResult = WorkspaceExecutionResult("""{"ok":true}""")
}

private fun finishModel(seen: MutableList<ModelRequest> = mutableListOf()): ModelClient =
    object : ModelClient {
        override val providerConcurrency: ProviderConcurrencyDeclaration =
            ProviderConcurrencyDeclaration.serialized()

        override fun complete(request: ModelRequest) =
            flowOf(
                ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""),
                ModelEvent.Completed,
            ).also { seen += request }
    }

private fun catalogProvider(id: String): ToolProvider =
    object : ToolProvider {
        override val providerId: String = id
        override val providerConcurrency: ProviderConcurrencyDeclaration =
            ProviderConcurrencyDeclaration.serialized()

        override suspend fun listTools(): List<ToolDescriptor> = emptyList()

        override suspend fun callTool(
            toolName: String,
            argumentsJson: String,
            context: ToolCallContext,
        ): ToolCallResult = ToolCallResult(success = true, resultJson = "{}")
    }

private fun describedPlugin(
    id: String,
    key: CapabilityKey<*>? = null,
    install: AgentPluginScope.() -> Unit = {},
): AgentPlugin =
    object : AgentPlugin {
        override val id: String = id
        override val descriptor = AgentPluginDescriptor(
            id = id,
            pluginVersion = PluginVersion(1, 0, 0),
            kernelApiRange = KernelApiRange(KernelApiVersions.CURRENT, KernelApiVersions.CURRENT),
            provides = setOfNotNull(key),
        )

        override fun install(scope: AgentPluginScope) {
            scope.install()
        }
    }

private fun request(name: String) = AgentRequest(
    requestId = "request-$name",
    sessionId = "session-$name",
    input = name,
)

private fun <T : Any> ownedCapabilityPlugin(
    id: String,
    key: CapabilityKey<T>,
    value: T,
    onClose: () -> Unit,
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
            scope.provide(key, value, testSerializedConcurrency)
            scope.own(AutoCloseable { onClose() })
        }
    }

private object UnusedHook : RuntimeHook

private object UnusedEventLog : SessionEventLog {
    override fun append(event: SessionEventAppend): SessionEvent = error("unused")

    override fun query(query: SessionEventQuery): List<SessionEvent> = emptyList()

    override fun replay(sessionId: String, runId: String): List<SessionEvent> = emptyList()
}
