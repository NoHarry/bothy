@file:OptIn(ExperimentalA2aClientApi::class, ExperimentalCoroutinesApi::class)

package cc.noharry.bothy.sample

import cc.noharry.bothy.a2a.A2aAgentCard
import cc.noharry.bothy.a2a.A2aArtifact
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
import cc.noharry.bothy.kernel.api.ToolCallContext
import cc.noharry.bothy.a2a.ExperimentalA2aClientApi
import cc.noharry.bothy.a2a.RemoteAgentRef
import cc.noharry.bothy.a2a.server.A2aOutput
import java.net.URI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugA2aClientSessionTest {
    @Test
    fun streamRoutesChannelsAndSpeaksOnlySpeech() = runTest(UnconfinedTestDispatcher()) {
        val client = FakeA2aClient()
        val speech = mutableListOf<DebugA2aSpeech>()
        val session = session(client, speech)

        assertTrue(session.send("导航去广州塔", DebugA2aInputSource.CONSOLE))
        client.stream().apply {
            send(A2aStreamEvent.Task(task(A2aTask.Status.SUBMITTED, "已接收文字请求")))
            send(A2aStreamEvent.Task(task(A2aTask.Status.WORKING, "navi 正在调用"), toolMeta("started")))
            send(A2aStreamEvent.Task(task(A2aTask.Status.WORKING, "navi 调用完成"), toolMeta("completed")))
            send(artifact("t1-0", "好的，马上出发", "channel" to "speech", "phase" to "progress"))
            send(artifact("t1-1", "已开始导航，全程约 30 公里", "channel" to "display", "phase" to "final"))
            send(artifact("t1-2", "已开始导航", "channel" to "speech", "phase" to "final"))
            send(A2aStreamEvent.Task(task(A2aTask.Status.COMPLETED, "已开始导航，全程约 30 公里")))
            send(A2aStreamEvent.Completed)
            close()
        }

        assertEquals(
            listOf(DebugA2aSpeech("t1", "好的，马上出发", false), DebugA2aSpeech("t1", "已开始导航", true)),
            speech,
        )
        val state = session.state
        assertFalse(state.running)
        assertEquals("c1", state.contextId)
        assertEquals("t1", state.lastTaskId)
        val tool = state.items.filterIsInstance<DebugA2aClientItem.Tool>().single()
        assertEquals("completed", tool.phase)
        assertTrue(tool.finishedAtEpochMillis != null)
        assertEquals("已开始导航，全程约 30 公里", state.items.filterIsInstance<DebugA2aClientItem.Display>().single().text)
        assertEquals(A2aTask.Status.COMPLETED, state.items.filterIsInstance<DebugA2aClientItem.Terminal>().single().state)
        assertTrue(state.lastOutcome.orEmpty().startsWith("completed"))
    }

    @Test
    fun legacyKindRoutesOnlyWithoutChannelAndUnknownChannelIsNeverSpoken() = runTest(UnconfinedTestDispatcher()) {
        val client = FakeA2aClient()
        val speech = mutableListOf<DebugA2aSpeech>()
        val session = session(client, speech)

        session.send("hi", DebugA2aInputSource.ADB)
        client.stream().apply {
            send(A2aStreamEvent.Task(task(A2aTask.Status.SUBMITTED, "")))
            send(artifact("t1-0", "旧客户端播报", "kind" to "tts", "phase" to "final"))
            send(artifact("t1-1", "调试数据", "channel" to "debug", "kind" to "tts"))
        }

        assertEquals(listOf(DebugA2aSpeech("t1", "旧客户端播报", true)), speech)
        assertTrue(session.state.items.filterIsInstance<DebugA2aClientItem.Notice>().single().text.contains("未知输出 debug"))
    }

    @Test
    fun busyConversationRefusesNewMessageAndNextMessageReusesContext() = runTest(UnconfinedTestDispatcher()) {
        val client = FakeA2aClient()
        val session = session(client)

        session.send("第一句", DebugA2aInputSource.CONSOLE)
        client.stream().send(A2aStreamEvent.Task(task(A2aTask.Status.SUBMITTED, "")))
        assertFalse(session.send("第二句", DebugA2aInputSource.ADB))
        assertEquals(1, client.streams.size)
        client.finish(A2aTask.Status.COMPLETED)

        assertTrue(session.send("第二句", DebugA2aInputSource.ADB))
        assertEquals("c1", client.streams[1].first.contextId)
        assertNull(client.streams[1].first.taskId)
    }

    @Test
    fun cancelBeforeTaskIdIsDeliveredOnFirstTaskEvent() = runTest(UnconfinedTestDispatcher()) {
        val client = FakeA2aClient()
        val session = session(client)

        session.send("长任务", DebugA2aInputSource.OVERLAY)
        session.cancel()
        assertTrue(client.cancelled.isEmpty())
        client.stream().send(A2aStreamEvent.Task(task(A2aTask.Status.SUBMITTED, "")))

        assertEquals(listOf("t1"), client.cancelled)
    }

    @Test
    fun answerOnActiveTaskPostsQuestionIdWithoutNewExecution() = runTest(UnconfinedTestDispatcher()) {
        val client = FakeA2aClient()
        val session = session(client)

        session.send("导航去机场", DebugA2aInputSource.CONSOLE)
        client.stream().apply {
            send(A2aStreamEvent.Task(task(A2aTask.Status.SUBMITTED, "")))
            send(question("pending"))
        }
        assertEquals(DebugQuestionAnswerMode.ACTIVE, session.state.questionCards.single().second)
        session.answer("q1", "是", DebugA2aInputSource.CONSOLE)

        val reply = client.sent.single()
        assertEquals("t1", reply.taskId)
        assertEquals("c1", reply.contextId)
        assertEquals(A2aValue.ObjectValue(mapOf("questionId" to A2aValue.StringValue("q1"))), reply.metadata)
        assertEquals(1, client.streams.size)
    }

    @Test
    fun refusedAnswerWaitsForStreamEndThenBecomesLateMessage() = runTest(UnconfinedTestDispatcher()) {
        val client = FakeA2aClient(sendResult = { A2aResult.Failure(A2aClientError.Rpc(-32002)) })
        val session = session(client)

        session.send("导航去机场", DebugA2aInputSource.CONSOLE)
        client.stream().apply {
            send(A2aStreamEvent.Task(task(A2aTask.Status.SUBMITTED, "")))
            send(question("pending"))
        }
        session.answer("q1", "是", DebugA2aInputSource.CONSOLE)
        assertEquals(1, client.streams.size)
        client.finish(A2aTask.Status.COMPLETED)

        val late = client.streams[1].first
        assertEquals(listOf(A2aPart.Text("关于你之前的问题「走高速吗？」，我的回答是：是")), late.parts)
        assertEquals("c1", late.contextId)
        assertNull(late.taskId)
        assertEquals(QUESTION_LATE_ANSWERED, session.state.question("q1")?.phase)
    }

    @Test
    fun answerToEndedTaskGoesStraightToLateMessage() = runTest(UnconfinedTestDispatcher()) {
        val client = FakeA2aClient()
        val session = session(client)

        session.send("导航去机场", DebugA2aInputSource.CONSOLE)
        client.stream().apply {
            send(A2aStreamEvent.Task(task(A2aTask.Status.SUBMITTED, "")))
            send(question("pending"))
            send(question("expired"))
        }
        client.finish(A2aTask.Status.COMPLETED)
        assertEquals(DebugQuestionAnswerMode.LATE, session.state.questionCards.single().second)
        session.answer("q1", "不走", DebugA2aInputSource.ASR)

        assertTrue(client.sent.isEmpty())
        assertEquals(listOf(A2aPart.Text("关于你之前的问题「走高速吗？」，我的回答是：不走")), client.streams[1].first.parts)
    }

    @Test
    fun incompleteStreamEndsRunWithUnknownOutcome() = runTest(UnconfinedTestDispatcher()) {
        val client = FakeA2aClient()
        val session = session(client)

        session.send("hi", DebugA2aInputSource.CONSOLE)
        client.stream().apply {
            send(A2aStreamEvent.Task(task(A2aTask.Status.SUBMITTED, "")))
            send(A2aStreamEvent.Incomplete(A2aClientError.Incomplete()))
            close()
        }

        assertFalse(session.state.running)
        val notice = session.state.items.last() as DebugA2aClientItem.Notice
        assertTrue(notice.error && notice.text.contains("结果未知"))
    }

    @Test
    fun replaceCancelsRunningTaskBeforeSending() = runTest(UnconfinedTestDispatcher()) {
        val client = FakeA2aClient()
        val session = session(client)

        session.send("第一句", DebugA2aInputSource.ASR)
        client.stream().send(A2aStreamEvent.Task(task(A2aTask.Status.SUBMITTED, "")))
        session.replace("第二句", DebugA2aInputSource.ASR)
        assertEquals(listOf("t1"), client.cancelled)
        assertEquals(1, client.streams.size)
        client.finish(A2aTask.Status.CANCELLED)

        assertEquals(listOf(A2aPart.Text("第二句")), client.streams[1].first.parts)
    }

    @Test
    fun newConversationClearsContextOnlyWhenIdle() = runTest(UnconfinedTestDispatcher()) {
        val client = FakeA2aClient()
        val session = session(client)

        session.send("hi", DebugA2aInputSource.CONSOLE)
        client.stream().send(A2aStreamEvent.Task(task(A2aTask.Status.SUBMITTED, "")))
        session.newConversation()
        assertEquals("c1", session.state.contextId)
        client.finish(A2aTask.Status.COMPLETED)
        session.newConversation()

        assertNull(session.state.contextId)
        session.send("again", DebugA2aInputSource.CONSOLE)
        assertNull(client.streams[1].first.contextId)
    }

    @Test
    fun mirroredDebugTaskIsShownAndSpokenWithoutBecomingOwnTask() = runTest(UnconfinedTestDispatcher()) {
        val client = FakeA2aClient()
        val speech = mutableListOf<DebugA2aSpeech>()
        val session = session(client, speech)
        session.send("本机请求", DebugA2aInputSource.CONSOLE)
        val posted = mutableListOf<(DebugA2aClientSession) -> Unit>()
        val mirror = DebugA2aMirror("pc-t", "pc-c", "PC 请求", DebugA2aInputSource.PC) { posted += it }

        mirror.publish(A2aOutput.Status("navi 正在调用", "started", "navi", "call-9"))
        mirror.publish(A2aOutput.Speech("好的，马上查", final = false))
        mirror.publish(A2aOutput.Result("查到了", A2aOutput.State.COMPLETED))
        mirror.close()
        posted.forEach { it(session) }

        val state = session.state
        assertTrue(state.running)
        assertNull(state.activeTaskId)
        assertTrue(state.mirrors.isEmpty())
        assertEquals(DebugA2aInputSource.PC, state.items.filterIsInstance<DebugA2aClientItem.User>().last().source)
        assertEquals(listOf(DebugA2aSpeech("pc-t", "好的，马上查", false)), speech)
        assertEquals("查到了", state.items.filterIsInstance<DebugA2aClientItem.Display>().single { it.taskId == "pc-t" }.text)
        assertEquals(A2aTask.Status.COMPLETED, state.items.filterIsInstance<DebugA2aClientItem.Terminal>().single().state)
        assertEquals(1, state.items.filterIsInstance<DebugA2aClientItem.Terminal>().size)
    }

    @Test
    fun everyInboundTaskIsMirroredButNeverTheCarsOwn() {
        assertEquals(DebugA2aInputSource.PC, debugA2aMirrorSource(mapOf(DEBUG_A2A_MIRROR_METADATA to "true")))
        assertEquals(DebugA2aInputSource.EXTERNAL, debugA2aMirrorSource(emptyMap()))
        // The device's own client talks to its own server: already on screen, never mirrored a second time.
        assertNull(debugA2aMirrorSource(mapOf(DEBUG_A2A_OWN_CLIENT_METADATA to "true")))
    }

    @Test
    fun ownTasksAreMarkedAndExternalMirrorsAreLabelled() = runTest(UnconfinedTestDispatcher()) {
        val client = FakeA2aClient()
        val session = session(client, mutableListOf())
        session.send("本机请求", DebugA2aInputSource.CONSOLE)
        val mark = client.streams.single().first.metadata?.values?.get(DEBUG_A2A_OWN_CLIENT_METADATA)
        assertEquals(A2aValue.StringValue("true"), mark)

        val posted = mutableListOf<(DebugA2aClientSession) -> Unit>()
        DebugA2aMirror("ext-t", "ext-c", "手机发来的请求", DebugA2aInputSource.EXTERNAL) { posted += it }
        posted.forEach { it(session) }
        val mirrored = session.state.items.filterIsInstance<DebugA2aClientItem.User>().last()
        assertEquals(DebugA2aInputSource.EXTERNAL, mirrored.source)
        assertEquals("手机发来的请求", mirrored.text)
        assertTrue("ext-t" in session.state.mirrors)
    }

    @Test
    fun externalTasksAreShownButNeverSpokenOnTheCar() = runTest(UnconfinedTestDispatcher()) {
        val speech = mutableListOf<DebugA2aSpeech>()
        val session = session(FakeA2aClient(), speech)
        val posted = mutableListOf<(DebugA2aClientSession) -> Unit>()
        val mirror = DebugA2aMirror("ext-t", "ext-c", "外部请求", DebugA2aInputSource.EXTERNAL) { posted += it }
        mirror.publish(A2aOutput.Speech("好的，马上办", final = false))
        mirror.publish(A2aOutput.Result("办好了", A2aOutput.State.COMPLETED))
        posted.forEach { it(session) }

        assertTrue(speech.isEmpty())
        assertEquals("好的，马上办", session.state.items.filterIsInstance<DebugA2aClientItem.Speech>().single().text)
        assertEquals("办好了", session.state.items.filterIsInstance<DebugA2aClientItem.Display>().single().text)
    }

    @Test
    fun externalTasksAreSpokenOnlyWithTheSpeechSwitchOn() = runTest(UnconfinedTestDispatcher()) {
        val speech = mutableListOf<DebugA2aSpeech>()
        val session = session(FakeA2aClient(), speech)
        session.updateState { it.copy(speakExternal = true) }
        val posted = mutableListOf<(DebugA2aClientSession) -> Unit>()
        val mirror = DebugA2aMirror("ext-t", "ext-c", "外部请求", DebugA2aInputSource.EXTERNAL) { posted += it }
        mirror.publish(A2aOutput.Speech("好的，马上办", final = true))
        posted.forEach { it(session) }

        assertEquals(listOf("好的，马上办"), speech.map { it.text })
    }

    @Test
    fun clientModeShowsThePictureTheImageToolDrewForItsTask() = runTest(UnconfinedTestDispatcher()) {
        val reference = "generated-images/YTJh/img-mv0a1b2c-qrst.jpg"
        val drawing = DebugImageToolProvider { _, _ -> DebugImageResult.Success(DebugGeneratedImage(reference, 1024, 1024, 1, 1, null)) }
        drawing.callTool(IMAGE_GENERATE_TOOL, """{"prompt":"猫"}""", ToolCallContext("a2a-ctx", "run-1", "task-7"))
        val state = DebugA2aClientState(
            items = listOf(
                DebugA2aClientItem.User(1, "画一只猫", DebugA2aInputSource.OVERLAY, DebugA2aClientItem.User.Kind.MESSAGE),
                DebugA2aClientItem.Display(2, "task-7", "画好了"),
                DebugA2aClientItem.Terminal(3, "task-7", A2aTask.Status.COMPLETED, "画好了", 20_000),
            ),
        )
        assertEquals(DebugOverlayPicture(reference, "猫"), state.overlayHud().picture)

        // A call that fails leaves no empty frame for its task.
        val failing = DebugImageToolProvider { _, _ -> DebugImageResult.Failure("Network error: timeout", 120_000) }
        failing.callTool(IMAGE_GENERATE_TOOL, """{"prompt":"狗"}""", ToolCallContext("a2a-ctx", "run-2", "task-8"))
        assertNull(DebugPictureBoard.forTask("task-8"))
    }

    @Test
    fun overlayCommandsAreReadFromMessageMetadata() {
        assertEquals(DebugOverlayCommand.SHOW, debugOverlayCommand(mapOf(CAR_OVERLAY_METADATA to " Show ")))
        assertEquals(DebugOverlayCommand.HIDE, debugOverlayCommand(mapOf(CAR_OVERLAY_METADATA to "hide")))
        assertNull(debugOverlayCommand(mapOf(CAR_OVERLAY_METADATA to "toggle")))
        assertNull(debugOverlayCommand(emptyMap()))
        assertTrue(isOverlayControlOnly(mapOf(CAR_OVERLAY_ONLY_METADATA to "true")))
        assertFalse(isOverlayControlOnly(mapOf(CAR_OVERLAY_ONLY_METADATA to "yes")))
        assertFalse(isOverlayControlOnly(emptyMap()))
    }

    @Test
    fun anEndedMirrorHandsItsContextToTheIdleCarSoAPickBindsThere() = runTest(UnconfinedTestDispatcher()) {
        val client = FakeA2aClient()
        val session = session(client, mutableListOf())
        val posted = mutableListOf<(DebugA2aClientSession) -> Unit>()
        val mirror = DebugA2aMirror("ext-t", "ext-c", "找停车场", DebugA2aInputSource.EXTERNAL) { posted += it }
        mirror.publish(A2aOutput.Result("1. 东广场（推荐）\n2. 西广场\n选哪个？", A2aOutput.State.COMPLETED))
        posted.forEach { it(session) }

        assertEquals("ext-c", session.state.contextId)
        session.send("第1个", DebugA2aInputSource.OVERLAY)
        assertEquals("ext-c", client.streams.single().first.contextId)
    }

    @Test
    fun aMirrorEndingAfterNewConversationDoesNotBringItsContextBack() = runTest(UnconfinedTestDispatcher()) {
        val session = session(FakeA2aClient(), mutableListOf())
        val posted = mutableListOf<(DebugA2aClientSession) -> Unit>()
        val mirror = DebugA2aMirror("ext-t", "ext-c", "外部请求", DebugA2aInputSource.EXTERNAL) { posted += it }
        posted.forEach { it(session) }
        posted.clear()

        session.newConversation()
        mirror.publish(A2aOutput.Result("好了", A2aOutput.State.COMPLETED))
        posted.forEach { it(session) }

        assertNull(session.state.contextId)
    }

    @Test
    fun aMirrorEndingWhileTheCarRunsLeavesTheCarsContextAlone() = runTest(UnconfinedTestDispatcher()) {
        val client = FakeA2aClient()
        val session = session(client, mutableListOf())
        session.send("本机请求", DebugA2aInputSource.CONSOLE)
        val own = session.state.contextId
        val posted = mutableListOf<(DebugA2aClientSession) -> Unit>()
        val mirror = DebugA2aMirror("ext-t", "ext-c", "外部请求", DebugA2aInputSource.EXTERNAL) { posted += it }
        mirror.publish(A2aOutput.Result("好了", A2aOutput.State.COMPLETED))
        posted.forEach { it(session) }

        assertTrue(session.state.running)
        assertEquals(own, session.state.contextId)
    }

    @Test
    fun newConversationKeepsRunningMirrorAlive() = runTest(UnconfinedTestDispatcher()) {
        val speech = mutableListOf<DebugA2aSpeech>()
        val session = session(FakeA2aClient(), speech)
        val posted = mutableListOf<(DebugA2aClientSession) -> Unit>()
        val mirror = DebugA2aMirror("pc-t", "pc-c", "PC 请求", DebugA2aInputSource.PC) { posted += it }
        posted.forEach { it(session) }
        posted.clear()

        session.newConversation()
        mirror.publish(A2aOutput.Speech("还在播", final = true))
        posted.forEach { it(session) }

        assertEquals(listOf(DebugA2aSpeech("pc-t", "还在播", true)), speech)
    }

    @Test
    fun mirrorProjectionMatchesServerChannelsAndEndsBrokenFlows() {
        val projection = DebugA2aMirrorProjection("t", "c")
        val rejected = projection.events(A2aOutput.Result("需要确认", A2aOutput.State.REJECTED))
        assertEquals("display", ((rejected[0] as A2aStreamEvent.Artifact).artifact.metadata?.values?.get("channel") as A2aValue.StringValue).value)
        assertEquals(A2aTask.Status.FAILED, (rejected[1] as A2aStreamEvent.Task).value.status)
        val question = projection.events(A2aOutput.Question("q", "走高速吗？", listOf("是"), "pending")).single() as A2aStreamEvent.Artifact
        assertEquals(A2aValue.ListValue(listOf(A2aValue.StringValue("是"))), question.artifact.metadata?.values?.get("options"))
        assertEquals(listOf("t-mirror-0", "t-mirror-1"), listOf(rejected[0], question).map { (it as A2aStreamEvent.Artifact).artifact.artifactId })

        val posted = mutableListOf<(DebugA2aClientSession) -> Unit>()
        DebugA2aMirror("t", "c", "x", DebugA2aInputSource.PC) { posted += it }.close()
        assertEquals(2, posted.size)
    }

    @Test
    fun overlayHudShowsWireOnlyStage() {
        val state = DebugA2aClientState(running = true, activeTaskId = "t1", runStartedAtEpochMillis = 1_000)
            .append { DebugA2aClientItem.User(it, "导航", DebugA2aInputSource.OVERLAY, DebugA2aClientItem.User.Kind.MESSAGE) }
            .append { DebugA2aClientItem.Speech(it, "t1", "好的", false) }
            .append { DebugA2aClientItem.Tool(it, "t1", "c", "mcp__device__navi", "started", 1_000, null) }
            .append { DebugA2aClientItem.Question(it, "t1", "q1", "走高速吗？", listOf("是"), QUESTION_PENDING) }

        val hud = state.overlayHud(nowEpochMillis = 4_000)

        assertEquals(DebugOverlayPhase.LIVE, hud.phase)
        assertEquals("Speech", hud.planTitle)
        assertEquals("等待回答：走高速吗？", hud.headline)
        assertEquals(listOf("🔊 好的", "问：走高速吗？"), hud.planRows.map { it.text })
        assertEquals("navi", hud.toolLines.single().name)
        assertEquals("好的", hud.resultText)
        assertEquals("3s", hud.elapsedLabel)
        assertEquals("暂无播报（只播报 channel=speech）", DebugA2aClientState().overlayHud().planEmptyText)
    }

    @Test
    fun adbRouteHonoursExplicitRouteThenClientMode() {
        assertEquals(DebugTextRoute.LOCAL, debugTextRoute(null, clientMode = false))
        assertEquals(DebugTextRoute.A2A_CLIENT, debugTextRoute(null, clientMode = true))
        assertEquals(DebugTextRoute.A2A_CLIENT, debugTextRoute("A2A", clientMode = false))
        assertEquals(DebugTextRoute.LOCAL, debugTextRoute("local", clientMode = true))
        assertEquals(DebugTextRoute.LOCAL, debugTextRoute("bogus", clientMode = false))
        assertEquals(true, parseDebugA2aClientSwitch("on"))
        assertEquals(false, parseDebugA2aClientSwitch(" OFF "))
        assertNull(parseDebugA2aClientSwitch("maybe"))
    }

    private fun TestScope.session(client: FakeA2aClient, speech: MutableList<DebugA2aSpeech> = mutableListOf()): DebugA2aClientSession {
        var now = 0L
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return DebugA2aClientSession(
            client = client,
            remote = RemoteAgentRef("test", URI("http://127.0.0.1:8766/a2a")),
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            clock = { now += 1_000; now },
            onSpeech = { speech += it },
        )
    }

    private fun task(status: A2aTask.Status, text: String) = A2aTask(
        id = "t1",
        contextId = "c1",
        status = status,
        statusMessage = A2aMessage(role = A2aMessage.Role.AGENT, parts = listOf(A2aPart.Text(text))),
    )

    private fun toolMeta(phase: String) = meta(
        "kind" to "tool-status", "phase" to phase, "toolName" to "navi", "toolCallId" to "call-1", "channel" to "status",
    )

    private fun question(phase: String) = A2aStreamEvent.Artifact(
        taskId = "t1",
        contextId = "c1",
        artifact = A2aArtifact(
            artifactId = "t1-q-$phase",
            parts = listOf(A2aPart.Text("走高速吗？")),
            metadata = A2aValue.ObjectValue(
                mapOf(
                    "channel" to A2aValue.StringValue("question"),
                    "questionId" to A2aValue.StringValue("q1"),
                    "phase" to A2aValue.StringValue(phase),
                    "options" to A2aValue.ListValue(listOf(A2aValue.StringValue("是"), A2aValue.StringValue("否"))),
                ),
            ),
        ),
    )

    private fun artifact(id: String, text: String, vararg metadata: Pair<String, String>) = A2aStreamEvent.Artifact(
        taskId = "t1",
        contextId = "c1",
        artifact = A2aArtifact(artifactId = id, parts = listOf(A2aPart.Text(text)), metadata = meta(*metadata)),
    )

    private fun meta(vararg pairs: Pair<String, String>) =
        A2aValue.ObjectValue(pairs.associate { (key, value) -> key to A2aValue.StringValue(value) })

    private inner class FakeA2aClient(
        private val sendResult: (A2aMessage) -> A2aResult<A2aSendMessageResult> = {
            A2aResult.Success(A2aSendMessageResult.Task(task(A2aTask.Status.WORKING, "")))
        },
    ) : A2aClient {
        val streams = mutableListOf<Pair<A2aMessage, Channel<A2aStreamEvent>>>()
        val sent = mutableListOf<A2aMessage>()
        val cancelled = mutableListOf<String>()

        fun stream(): Channel<A2aStreamEvent> = streams.last().second

        suspend fun finish(status: A2aTask.Status) {
            stream().send(A2aStreamEvent.Task(task(status, "")))
            stream().send(A2aStreamEvent.Completed)
            stream().close()
        }

        override suspend fun getAgentCard(remote: RemoteAgentRef): A2aResult<A2aAgentCard> = error("unused")

        override suspend fun sendMessage(remote: RemoteAgentRef, request: A2aSendMessageRequest): A2aResult<A2aSendMessageResult> {
            sent += request.message
            return sendResult(request.message)
        }

        override fun streamMessage(remote: RemoteAgentRef, request: A2aSendMessageRequest): Flow<A2aStreamEvent> {
            val channel = Channel<A2aStreamEvent>(Channel.UNLIMITED)
            streams += request.message to channel
            return channel.receiveAsFlow()
        }

        override suspend fun getTask(remote: RemoteAgentRef, taskId: String): A2aResult<A2aTask> = error("unused")

        override suspend fun cancelTask(remote: RemoteAgentRef, taskId: String): A2aResult<A2aTask> {
            cancelled += taskId
            return A2aResult.Success(task(A2aTask.Status.CANCELLED, ""))
        }

        override fun resubscribeTask(remote: RemoteAgentRef, taskId: String): Flow<A2aStreamEvent> = emptyFlow()

        override fun close() = Unit
    }
}
