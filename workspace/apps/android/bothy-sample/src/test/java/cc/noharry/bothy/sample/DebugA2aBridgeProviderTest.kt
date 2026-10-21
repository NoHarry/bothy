@file:OptIn(ExperimentalA2aClientApi::class)

package cc.noharry.bothy.sample

import cc.noharry.bothy.a2a.A2aAgentCard
import cc.noharry.bothy.a2a.A2aClient
import cc.noharry.bothy.a2a.A2aClientError
import cc.noharry.bothy.a2a.A2aMessage
import cc.noharry.bothy.a2a.A2aPart
import cc.noharry.bothy.a2a.A2aResult
import cc.noharry.bothy.a2a.A2aSendMessageRequest
import cc.noharry.bothy.a2a.A2aSendMessageResult
import cc.noharry.bothy.a2a.A2aStreamEvent
import cc.noharry.bothy.a2a.A2aTask
import cc.noharry.bothy.a2a.A2aValue
import cc.noharry.bothy.a2a.ExperimentalA2aClientApi
import cc.noharry.bothy.a2a.RemoteAgentRef
import cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.bothy.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.bothy.kernel.api.ToolCallContext
import cc.noharry.bothy.kernel.api.ToolPrefetchRequest
import java.net.URI
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugA2aBridgeProviderTest {
    @OptIn(ExperimentalAgentRuntimeApi::class)
    @Test
    fun declaresSerializedConcurrencyUntilAdaptersProveReentrancy() {
        val provider = DebugA2aBridgeProvider(profile("direct-local")) { null }
        assertEquals(ProviderConcurrencyDeclaration.serialized(), provider.providerConcurrency)
    }

    @Test
    fun serverIdSanitizesHyphensAndUppercaseProfileIds() {
        val hyphenated = DebugA2aBridgeProvider(profile("direct-local")) { null }
        val messy = DebugA2aBridgeProvider(profile("User-My Agent!")) { null }

        assertEquals("experimental_a2a_direct_local", hyphenated.serverId)
        assertEquals("experimental_a2a_user_my_agent", messy.serverId)
    }

    @Test
    fun listToolsExposesSingleExperimentalBridgeTool() = runBlocking {
        val provider = DebugA2aBridgeProvider(profile("direct-local")) { null }

        val tools = provider.listTools()

        assertEquals(1, tools.size)
        val tool = tools.single()
        assertEquals("a2a_message_send", tool.name)
        assertTrue(tool.description.contains("Experimental A2A client bridge (not an MCP server)"))
        assertTrue(tool.description.contains("Direct Agent"))
        assertTrue(tool.description.contains("a2a.example.com"))
        assertTrue(tool.inputSchemaJson.contains("\"text\""))
        assertTrue(tool.inputSchemaJson.contains("\"required\": [\"text\"]"))
    }

    @Test
    fun callToolMessageResultPropagatesTextAndContext() = runBlocking {
        val client = FakeA2aClient(
            A2aResult.Success(
                A2aSendMessageResult.Message(
                    A2aMessage(
                        role = A2aMessage.Role.AGENT,
                        parts = listOf(A2aPart.Text("hello"), A2aPart.Text("world")),
                        contextId = "ctx-7",
                    ),
                ),
            ),
        )
        val provider = DebugA2aBridgeProvider(profile("direct-local")) { client }

        val result = provider.callTool(
            toolName = "a2a_message_send",
            argumentsJson = """{"text":"ping","contextId":"ctx-1","taskId":"task-1"}""",
            context = callContext(),
        )

        assertTrue(result.success)
        val json = Json.parseToJsonElement(result.resultJson).jsonObject
        assertEquals("message", json.getValue("outcome").jsonPrimitive.content)
        assertEquals("hello\nworld", json.getValue("text").jsonPrimitive.content)
        assertEquals("ctx-7", json.getValue("contextId").jsonPrimitive.content)
        val (remote, request) = client.sentMessages.single()
        assertEquals("direct-local", remote.id)
        assertEquals(URI("https://a2a.example.com"), remote.endpoint)
        assertEquals(A2aMessage.Role.USER, request.message.role)
        assertEquals(listOf(A2aPart.Text("ping")), request.message.parts)
        assertEquals("ctx-1", request.message.contextId)
        assertEquals("task-1", request.message.taskId)
        assertFalse(request.message.messageId.isNullOrBlank())
    }

    @Test
    fun callToolTaskResultPropagatesTaskState() = runBlocking {
        val client = FakeA2aClient(
            A2aResult.Success(
                A2aSendMessageResult.Task(
                    A2aTask(
                        id = "task-9",
                        contextId = "ctx-9",
                        status = A2aTask.Status.WORKING,
                        statusMessage = A2aMessage(
                            role = A2aMessage.Role.AGENT,
                            parts = listOf(A2aPart.Text("working on it")),
                        ),
                    ),
                ),
            ),
        )
        val followed = RecordingDelegations()
        val provider = DebugA2aBridgeProvider(profile("official-local"), delegations = followed, clock = { 1_000L }) { client }

        val result = provider.callTool("a2a_message_send", """{"text":"go"}""", callContext())

        assertTrue(result.success)
        val json = Json.parseToJsonElement(result.resultJson).jsonObject
        assertEquals("task", json.getValue("outcome").jsonPrimitive.content)
        assertEquals("task-9", json.getValue("taskId").jsonPrimitive.content)
        assertEquals("WORKING", json.getValue("status").jsonPrimitive.content)
        assertEquals("working on it", json.getValue("text").jsonPrimitive.content)
        assertEquals("ctx-9", json.getValue("contextId").jsonPrimitive.content)
        assertTrue(json.getValue("followUp").jsonPrimitive.content.contains("do not wait or poll"))
        assertEquals(DebugA2aDelegation("Direct Agent", "task-9", "go", 1_000L, profileId = "official-local"), followed.tracked.single().first)
        assertEquals("official-local", followed.tracked.single().second.id)
    }

    @Test
    fun settledTaskIsReturnedWithoutFollowing() = runBlocking {
        val client = FakeA2aClient(
            A2aResult.Success(A2aSendMessageResult.Task(A2aTask(id = "task-1", contextId = null, status = A2aTask.Status.COMPLETED))),
        )
        val followed = RecordingDelegations()
        val provider = DebugA2aBridgeProvider(profile("direct-local"), delegations = followed) { client }

        val result = provider.callTool("a2a_message_send", """{"text":"go"}""", callContext())

        assertFalse(Json.parseToJsonElement(result.resultJson).jsonObject.containsKey("followUp"))
        assertTrue(followed.tracked.isEmpty())
    }

    @Test
    fun everyMessageCarriesTheCarsLocalTimeAndZone() = runBlocking {
        val client = FakeA2aClient(A2aResult.Failure(A2aClientError.Transport("unused")))
        val provider = DebugA2aBridgeProvider(
            profile("direct-local"),
            delegations = RecordingDelegations(),
            clock = { 1_791_741_900_500L },
            zone = { ZoneId.of("Asia/Shanghai") },
        ) { client }

        provider.callTool("a2a_message_send", """{"text":"明天下午两点提醒我"}""", callContext())

        val metadata = client.sentMessages.single().second.message.metadata
        val context = metadata?.values?.get("context") as A2aValue.ObjectValue
        assertEquals(A2aValue.StringValue("car"), context.values["source"])
        assertEquals(A2aValue.StringValue("Asia/Shanghai"), context.values["timezone"])
        assertEquals(A2aValue.StringValue("2026-10-12T02:05:00+08:00"), context.values["local_time"])
    }

    @Test
    fun aPendingQuestionTellsTheModelWhichTaskTheAnswerBelongsTo() = runBlocking {
        val question = DebugA2aPendingQuestion("Direct Agent", "task-7", "发到哪个邮箱？", askedAtEpochMillis = 1L)
        val other = DebugA2aPendingQuestion("Someone Else", "task-8", "别的问题", askedAtEpochMillis = 1L)
        val provider = DebugA2aBridgeProvider(profile("direct-local"), delegations = RecordingDelegations(listOf(question, other))) { null }

        val description = provider.listTools().single().description

        assertTrue(description, description.contains("Waiting for the user's answer: taskId=task-7 asked 「发到哪个邮箱？」."))
        assertFalse(description.contains("task-8"))
    }

    @OptIn(ExperimentalAgentRuntimeApi::class)
    @Test
    fun toolIsAlwaysPreActivatedAndDescribesWhatTheAgentCanDo() = runBlocking {
        val provider = DebugA2aBridgeProvider(profile("direct-local").copy(description = "发邮件、设提醒")) { null }
        val tool = provider.listTools().single()
        val request = ToolPrefetchRequest("随便说点什么", listOf(tool), callContext(), maxTools = 4)

        assertEquals(listOf("a2a_message_send"), provider.localToolNames(request))
        assertFalse(provider.remotePrefetchEnabled)
        assertTrue(tool.description.contains("It can: 发邮件、设提醒."))
        assertTrue(tool.description.contains("【委托回报】"))
        // Owner 2026-10-17: only a named agent gets the task; other background work stays with the device's subagents.
        assertTrue(tool.description.contains("only when the user names this agent") && tool.description.contains("spawn_agent"))
        assertTrue(tool.description.contains("云端助手") && tool.description.contains("个人助手"))
    }

    @Test
    fun callToolFailureMapsReasonAndRetryHint() = runBlocking {
        val transport = DebugA2aBridgeProvider(profile("direct-local")) {
            FakeA2aClient(A2aResult.Failure(A2aClientError.Transport("connection_refused")))
        }

        val transportResult = transport.callTool("a2a_message_send", """{"text":"go"}""", callContext())

        assertFalse(transportResult.success)
        assertTrue(transportResult.retryable)
        val transportJson = Json.parseToJsonElement(transportResult.resultJson).jsonObject
        assertEquals("connection_refused", transportJson.getValue("error").jsonPrimitive.content)
        assertEquals("CONDITIONAL", transportJson.getValue("retryHint").jsonPrimitive.content)

        val configuration = DebugA2aBridgeProvider(profile("direct-local")) {
            FakeA2aClient(A2aResult.Failure(A2aClientError.Configuration("invalid_endpoint")))
        }
        val configResult = configuration.callTool("a2a_message_send", """{"text":"go"}""", callContext())

        assertFalse(configResult.success)
        assertFalse(configResult.retryable)
        assertTrue(configResult.resultJson.contains("invalid_endpoint"))
    }

    @Test
    fun callToolWithoutLoadedClientFails() = runBlocking {
        val provider = DebugA2aBridgeProvider(profile("direct-local")) { null }

        val result = provider.callTool("a2a_message_send", """{"text":"go"}""", callContext())

        assertFalse(result.success)
        assertFalse(result.retryable)
        val json = Json.parseToJsonElement(result.resultJson).jsonObject
        assertEquals("a2a_client_not_loaded", json.getValue("error").jsonPrimitive.content)
        assertEquals("direct-local", json.getValue("profileId").jsonPrimitive.content)
    }

    @Test
    fun callToolRejectsMissingBlankOrMalformedText() = runBlocking {
        val provider = DebugA2aBridgeProvider(profile("direct-local")) {
            FakeA2aClient(A2aResult.Failure(A2aClientError.Configuration("should_not_be_called")))
        }

        // lint_quality counts raw brace characters; keep the malformed payload brace-free.
        val missing = provider.callTool("a2a_message_send", """{"contextId":"ctx-1"}""", callContext())
        val blank = provider.callTool("a2a_message_send", """{"text":"  "}""", callContext())
        val malformed = provider.callTool("a2a_message_send", "not-json", callContext())

        assertFalse(missing.success)
        assertFalse(blank.success)
        assertFalse(malformed.success)
        assertTrue(missing.resultJson.contains("invalid_arguments"))
    }

    @Test
    fun callToolRejectsAdditionalPropertiesAndNonStringOptionalFields() = runBlocking {
        val client = FakeA2aClient(A2aResult.Failure(A2aClientError.Configuration("should_not_be_called")))
        val provider = DebugA2aBridgeProvider(profile("direct-local")) { client }

        val extra = provider.callTool("a2a_message_send", """{"text":"go","extra":"no"}""", callContext())
        val numeric = provider.callTool("a2a_message_send", """{"text":"go","contextId":7}""", callContext())
        val objectValue = provider.callTool("a2a_message_send", """{"text":"go","taskId":{}}""", callContext())

        assertFalse(extra.success)
        assertFalse(numeric.success)
        assertFalse(objectValue.success)
        assertTrue(extra.resultJson.contains("invalid_arguments"))
        assertTrue(client.sentMessages.isEmpty())
    }

    @Test
    fun unexpectedSendExceptionReturnsGenericStructuredFailure() = runBlocking {
        val client = FakeA2aClient(
            A2aResult.Failure(A2aClientError.Configuration("unused")),
            sendMessageFailure = IllegalStateException("credential=do-not-leak"),
        )
        val provider = DebugA2aBridgeProvider(profile("direct-local")) { client }

        val result = provider.callTool("a2a_message_send", """{"text":"go"}""", callContext())

        assertFalse(result.success)
        assertFalse(result.retryable)
        val json = Json.parseToJsonElement(result.resultJson).jsonObject
        assertEquals("a2a_send_failed", json.getValue("error").jsonPrimitive.content)
        assertEquals("A2A message send failed unexpectedly.", json.getValue("message").jsonPrimitive.content)
        assertFalse(result.resultJson.contains("credential"))
        assertFalse(result.resultJson.contains("do-not-leak"))
    }

    @Test
    fun sendCancellationExceptionIsRethrown() = runBlocking {
        val cancellation = CancellationException("caller cancelled")
        val client = FakeA2aClient(
            A2aResult.Failure(A2aClientError.Configuration("unused")),
            sendMessageFailure = cancellation,
        )
        val provider = DebugA2aBridgeProvider(profile("direct-local")) { client }

        var thrown: CancellationException? = null
        try {
            provider.callTool("a2a_message_send", """{"text":"go"}""", callContext())
        } catch (caught: CancellationException) {
            thrown = caught
        }

        assertTrue(thrown === cancellation)
    }

    @Test
    fun bridgeProvidersOnlyMountEnabledProfiles() {
        val config = DebugA2aModuleConfig(
            state = DebugA2aModuleState(
                profiles = listOf(
                    profile("direct-local", enabled = true),
                    profile("official-local", enabled = false),
                ),
            ),
        )

        val providers = debugA2aBridgeProviders(config) { null }

        assertEquals(listOf("experimental_a2a_direct_local"), providers.map { it.serverId })
    }

    @Test
    fun bridgeProvidersDisambiguateSanitizedProfileIdCollisions() {
        val config = DebugA2aModuleConfig(
            state = DebugA2aModuleState(
                profiles = listOf(
                    profile("agent-a", enabled = true),
                    profile("agent_a", enabled = true),
                    profile("other", enabled = true),
                ),
            ),
        )

        val serverIds = debugA2aBridgeProviders(config) { null }.map { it.serverId }

        assertEquals(3, serverIds.toSet().size)
        assertTrue(serverIds[0].startsWith("experimental_a2a_agent_a_"))
        assertTrue(serverIds[1].startsWith("experimental_a2a_agent_a_"))
        assertEquals("experimental_a2a_other", serverIds[2])
    }

    private fun profile(id: String, enabled: Boolean = true): DebugA2aProfile =
        DebugA2aProfile(
            id = id,
            label = "Direct Agent",
            moduleId = DIRECT_MODULE_ID,
            endpoint = URI("https://a2a.example.com"),
            enabled = enabled,
            allowLoopbackHttpForDebug = false,
        )

    @Test
    fun theToolDescriptionSaysWhichTasksAreStillBeingHandledAndWhereTheyStand() = runBlocking {
        val open = DebugDelegationView(
            profileId = "official-local", agentLabel = "Direct Agent", taskId = "task-7", request = "订明天的车位",
            startedAtEpochMillis = 0L, stage = DebugDelegationStage.WORKING, statusText = "在比价",
            steps = listOf(DebugDelegationStep("查空位", "done"), DebugDelegationStep("比价格", "doing")),
        )
        val provider = DebugA2aBridgeProvider(
            profile("official-local"), delegations = RecordingDelegations(open = listOf(open)), clock = { 180_000L },
        ) { null }

        val description = provider.listTools().single().description

        assertTrue(description, description.contains("Still being handled"))
        assertTrue(description, description.contains("taskId=task-7 「订明天的车位」 处理中 for 3 min, steps 1/2 done, latest 「在比价」."))
    }

    private fun callContext(): ToolCallContext = ToolCallContext("session", "run", "request")
}

private class RecordingDelegations(
    private val questions: List<DebugA2aPendingQuestion> = emptyList(),
    private val open: List<DebugDelegationView> = emptyList(),
) : DebugA2aDelegationSink {
    val tracked = mutableListOf<Pair<DebugA2aDelegation, RemoteAgentRef>>()

    override fun pendingQuestions(agentLabel: String): List<DebugA2aPendingQuestion> = questions.filter { it.agentLabel == agentLabel }

    override fun inFlight(agentLabel: String): List<DebugDelegationView> = open.filter { it.agentLabel == agentLabel }

    override fun track(delegation: DebugA2aDelegation, client: A2aClient, remote: RemoteAgentRef) {
        tracked += delegation to remote
    }
}

private class FakeA2aClient(
    private val sendMessageResult: A2aResult<A2aSendMessageResult>,
    private val sendMessageFailure: Throwable? = null,
) : A2aClient {
    val sentMessages = mutableListOf<Pair<RemoteAgentRef, A2aSendMessageRequest>>()

    override suspend fun getAgentCard(remote: RemoteAgentRef): A2aResult<A2aAgentCard> = unsupported()

    override suspend fun sendMessage(
        remote: RemoteAgentRef,
        request: A2aSendMessageRequest,
    ): A2aResult<A2aSendMessageResult> {
        sentMessages += remote to request
        sendMessageFailure?.let { throw it }
        return sendMessageResult
    }

    override fun streamMessage(remote: RemoteAgentRef, request: A2aSendMessageRequest): Flow<A2aStreamEvent> =
        emptyFlow()

    override suspend fun getTask(remote: RemoteAgentRef, taskId: String): A2aResult<A2aTask> = unsupported()

    override suspend fun cancelTask(remote: RemoteAgentRef, taskId: String): A2aResult<A2aTask> = unsupported()

    override fun resubscribeTask(remote: RemoteAgentRef, taskId: String): Flow<A2aStreamEvent> = emptyFlow()

    override fun close() = Unit

    private fun unsupported(): A2aResult.Failure =
        A2aResult.Failure(A2aClientError.Configuration("unsupported_in_fake"))
}
