package cc.noharry.bothy.protocol

import cc.noharry.agent.appserver.protocol.A2aClientItem
import cc.noharry.agent.appserver.protocol.A2aClientState
import cc.noharry.agent.appserver.protocol.A2aInputSource
import cc.noharry.agent.appserver.protocol.A2aModuleState
import cc.noharry.agent.appserver.protocol.A2aProfile
import cc.noharry.agent.appserver.protocol.A2aTaskStatus
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.CurrentRequest
import cc.noharry.agent.appserver.protocol.DelegationStage
import cc.noharry.agent.appserver.protocol.DelegationStep
import cc.noharry.agent.appserver.protocol.DelegationView
import cc.noharry.agent.appserver.protocol.EventLogHealthStatus
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.McpProviderState
import cc.noharry.agent.appserver.protocol.McpServer
import cc.noharry.agent.appserver.protocol.McpServerKind
import cc.noharry.agent.appserver.protocol.MemoryCategory
import cc.noharry.agent.appserver.protocol.MemorySource
import cc.noharry.agent.appserver.protocol.MemoryView
import cc.noharry.agent.appserver.protocol.ModelCallPhase
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.Plan
import cc.noharry.agent.appserver.protocol.PlanFiring
import cc.noharry.agent.appserver.protocol.PlanTrigger
import cc.noharry.agent.appserver.protocol.PlanWatch
import cc.noharry.agent.appserver.protocol.ProviderEditState
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.protocol.ProviderProfileOption
import cc.noharry.agent.appserver.protocol.QUESTION_PENDING
import cc.noharry.agent.appserver.protocol.REPORT_QUEUED
import cc.noharry.agent.appserver.protocol.RunActivity
import cc.noharry.agent.appserver.protocol.ServerNotifications
import cc.noharry.agent.appserver.protocol.ServerSnapshot
import cc.noharry.agent.appserver.protocol.SessionSummary
import cc.noharry.agent.appserver.protocol.StateChanged
import cc.noharry.agent.appserver.protocol.StateSection
import cc.noharry.agent.appserver.protocol.SubagentLane
import cc.noharry.agent.appserver.protocol.SubagentRunState
import cc.noharry.agent.appserver.protocol.SubscriptionState
import cc.noharry.agent.appserver.protocol.TokenUsageState
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import cc.noharry.agent.appserver.protocol.TrajectoryHealth
import cc.noharry.agent.appserver.protocol.TrajectoryKind
import cc.noharry.agent.appserver.protocol.TrajectoryLane
import cc.noharry.agent.appserver.protocol.TrajectoryModelCall
import cc.noharry.agent.appserver.protocol.TrajectoryQuestion
import cc.noharry.agent.appserver.protocol.TrajectoryRecord
import cc.noharry.agent.appserver.protocol.TrajectoryRecordId
import cc.noharry.agent.appserver.protocol.TrajectoryRichDetails
import cc.noharry.agent.appserver.protocol.TrajectoryRunGroup
import cc.noharry.agent.appserver.protocol.TrajectorySnapshot
import cc.noharry.agent.appserver.protocol.TrajectorySource
import cc.noharry.agent.appserver.protocol.UserMemory
import cc.noharry.agent.appserver.protocol.WatchOp
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.client.after
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcNotification
import cc.noharry.agent.appserver.protocol.jsonrpc.ProtocolJson
import cc.noharry.agent.appserver.protocol.view.BoundCall
import cc.noharry.agent.appserver.protocol.view.CardItem
import cc.noharry.agent.appserver.protocol.view.CardItemKind
import cc.noharry.agent.appserver.protocol.view.CardLayout
import cc.noharry.agent.appserver.protocol.view.CardProgress
import cc.noharry.agent.appserver.protocol.view.ChoiceOffer
import cc.noharry.agent.appserver.protocol.view.ChoiceOption
import cc.noharry.agent.appserver.protocol.view.Offer
import cc.noharry.agent.appserver.protocol.view.OfferOption
import cc.noharry.agent.appserver.protocol.view.OverlayPicture
import cc.noharry.agent.appserver.protocol.view.SliderOffer
import cc.noharry.agent.appserver.protocol.view.SwitchOffer
import cc.noharry.agent.appserver.protocol.view.WidgetCard
import java.net.URI
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class StateSnapshotTest {
    @Test
    fun aStateSentAsSnapshotComesBackTheSame() {
        val state = sample()
        val wire = ProtocolJson.encodeToString(ServerSnapshot.serializer(), state.snapshot())
        val back = ServerState.from(ProtocolJson.decodeFromString(ServerSnapshot.serializer(), wire))
        assertEquals(state, back)
    }

    @Test
    fun eachSectionChangesOnlyItsOwnFields() {
        val state = sample()
        val other = ServerState()
        state.sections().forEach { section ->
            val changed = other.after(notification(section))
            assertEquals(section, changed.sections().single { it.key == section.key })
        }
        val all = state.sections().fold(other) { acc, section -> acc.after(notification(section)) }
        assertEquals(state.copy(sessions = emptyList(), activeSessionId = "", items = emptyList()), all)
    }

    private fun notification(section: StateSection) =
        JsonRpcNotification(ServerNotifications.STATE_CHANGED.name, ProtocolJson.encodeToJsonElement(StateChanged.serializer(), StateChanged(section)))

    private fun record() = TrajectoryRecord(
        id = TrajectoryRecordId("event:1"),
        sessionId = "s1",
        runId = "r1",
        kind = TrajectoryKind.TOOL_START,
        lane = TrajectoryLane.TOOLS,
        title = "navigation_route",
        rich = TrajectoryRichDetails(
            question = TrajectoryQuestion("q1", "QUESTION", "去哪个门？", listOf("南门", "北门"), "PENDING"),
            modelCall = TrajectoryModelCall(ModelCallPhase.USAGE, inputTokens = 1200),
        ),
        sources = setOf(TrajectorySource.EVENT_LOG),
    )

    private fun editor() = ProviderEditState(
        model = "deepseek-v4-flash",
        variant = "",
        baseUrl = "https://example.invalid",
        provider = ModelProvider.OPENAI,
        endpoint = ProviderEndpoint.RESPONSES,
        profileOptions = listOf(ProviderProfileOption("p", "DeepSeek", ModelProvider.OPENAI, ProviderEndpoint.CHAT_COMPLETIONS, "flash", selected = true, canDelete = false)),
    )

    private fun plan() = Plan(
        id = "p1",
        title = "降价提醒",
        message = "机票降到 800 元以下了",
        instruction = null,
        trigger = PlanTrigger.Watch(
            PlanWatch("flights", "", "itineraries.0.price", WatchOp.BELOW, JsonPrimitive(800), "低于 800 元时", tool = "search", arguments = buildJsonObject { put("to", "成都") }),
            holdSeconds = 0,
            repeat = false,
        ),
        enabled = true,
        createdAtMillis = 1,
    )

    private fun clientMode() = A2aClientState(
        enabled = true,
        activeTaskId = "t1",
        items = listOf(
            A2aClientItem.User(1, "整理周报", A2aInputSource.CONSOLE, A2aClientItem.User.Kind.MESSAGE),
            A2aClientItem.Question(2, "t1", "q", "发给谁？", listOf("我", "组长"), QUESTION_PENDING),
            A2aClientItem.Terminal(3, "t1", A2aTaskStatus.COMPLETED, "好了", 2_000),
        ),
    )

    private fun sample(): ServerState {
        return ServerState(
            activity = RunActivity.RUNNING_TOOL,
            activityTool = "navigation_route",
            runtimeStatus = "Tool running: navigation_route",
            providerLabel = "DeepSeek",
            confirmationMode = ConfirmationMode.ASK_EVERY_TIME,
            isRunning = true,
            configEditor = editor(),
            sessions = listOf(SessionSummary("s1", "去世界之窗", 1, 2, compactedSummary = "")),
            activeSessionId = "s1",
            items = listOf(
                ConversationItem.TurnHeader(1, "You", "去世界之窗", "去世界之窗"),
                ConversationItem.ToolCallItem(2, "c1", "navigation_route", "{}", null, ToolCallStatus.RUNNING, startedAt = 5),
            ),
            tokenUsage = TokenUsageState(requestCount = 1, inputTokens = 1200, totalTokens = 1300),
            mcpProviders = listOf(McpProviderState("m", "Cabin", true, McpServer("m", "Cabin", McpServerKind.HTTP, true, "http://127.0.0.1:8790"))),
            a2aClients = A2aModuleState(
                profiles = listOf(A2aProfile("a", "NAS", "direct", URI("https://nas.invalid/a2a"), true, false)),
                enabledProfileIds = setOf("a"),
            ),
            currentRequest = CurrentRequest("req-1", "去世界之窗"),
            trajectory = TrajectorySnapshot(
                sessionId = "s1",
                runGroups = listOf(TrajectoryRunGroup("s1", "r1", records = listOf(record()), health = TrajectoryHealth(EventLogHealthStatus.COMPLETE))),
                recordAliases = mapOf(TrajectoryRecordId("live:1") to TrajectoryRecordId("event:1")),
                revision = 3,
            ),
            runStartedAtEpochMillis = 4,
            activeSessionCount = 1,
            a2aClient = clientMode(),
            plans = listOf(plan()),
            planHistory = listOf(PlanFiring("p1", "降价提醒", 5, finding(FindingCode.PLANS_NOTE_REMINDED))),
            planFeeds = mapOf("p1" to SubscriptionState.NO_PUSH),
            delegations = listOf(DelegationView("a", "NAS", "t9", "整理周报", 6, DelegationStage.WORKING, steps = listOf(DelegationStep("读日志", "doing")))),
            memory = MemoryView(listOf(UserMemory("m1", MemoryCategory.IDENTITY, "叫我老王", MemorySource.SAID, "叫我老王吧", 1, 2)), enabled = false),
            usageRevision = 7,
        ).withExtensions().withSurfaces()
    }

    /** Bothy's own sections ([BothyExtensions]): an on-device model downloading, a check run going on. */
    private fun ServerState.withExtensions() = withOnDevice(
        OnDeviceView(
            models = listOf(
                OnDeviceModelView("qwen3-4b", "Qwen3 4B", sizeBytes = 2_500_000_000, onDiskBytes = 10, download = ModelDownloadView(DownloadPhase.DOWNLOADING, 10, 20, "hf")),
            ),
            server = InferenceServerView(InferenceServerPhase.STARTING, "qwen3-4b", "CPU"),
        ),
    ).withChecks(
        ChecksView(
            checks = listOf(CheckView(CheckId.WAKE, CheckGroup.VOICE, CheckStatus.FAIL, finding(BothyFindingCode.CHECKS_WAKE_OFF), CheckFixKind.WAKE_ON)),
            running = true,
            checkedAtEpochMillis = 1_700_000_000_000,
        ),
    )

    /** What the reply surfaces show beside the text: offers, an A2A task's picture, the voice level, subagents. */
    private fun ServerState.withSurfaces() = withSpeechLevel(0.4f).copy(
        offers = mapOf("s1" to offer()),
        offerPictures = mapOf("task-7" to OverlayPicture("generated-images/YTJh/img-a.jpg", "猫")),
        subagents = mapOf("s1" to listOf(SubagentLane("sub-1", "查油价", SubagentRunState.RUNNING, 1, 2, 0, 3, latest = "web_search"))),
        subagentDeliveries = mapOf("s1/sub-1" to REPORT_QUEUED),
    )

    /** One of everything a reply can offer: a list with a bound call, a panel of each control, a card. */
    private fun offer(): Offer {
        val call = BoundCall("car", "car_climate", "mcp__car__car_climate", """{"zone":"driver"}""")
        return Offer(
            sessionId = "s1",
            turnId = "r1",
            options = listOf(OfferOption("打开空调", call, icon = "ac")),
            createdAtMillis = 5,
            controls = listOf(
                SliderOffer("温度", call, "temperature", 16.0, 30.0, 0.5, 22.0, "℃", integer = false),
                SwitchOffer("座椅加热", call, call.copy(argumentsJson = "{}"), value = true),
                ChoiceOffer("模式", listOf(ChoiceOption("自动", call)), value = listOf(0), multiple = false),
            ),
            cards = listOf(
                WidgetCard(
                    "上海天气",
                    "sunny",
                    "22°",
                    null,
                    listOf("湿度" to "40%"),
                    listOf(CardItem("rain", "明天", "小雨", kind = CardItemKind.WEATHER)),
                    layout = CardLayout.STRIP,
                    progress = listOf(CardProgress("电量", 0.8, 1.0, null)),
                ),
            ),
        )
    }
}
