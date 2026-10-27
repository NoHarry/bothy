@file:OptIn(ExperimentalA2aClientApi::class)

package cc.noharry.bothy.app.a2a
import cc.noharry.agent.a2a.A2aArtifact
import cc.noharry.agent.a2a.A2aClientError
import cc.noharry.agent.a2a.A2aMessage
import cc.noharry.agent.a2a.A2aPart
import cc.noharry.agent.a2a.A2aResult
import cc.noharry.agent.a2a.A2aTask
import cc.noharry.agent.a2a.A2aValue
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.evidence.requiresBusinessAction
import cc.noharry.bothy.app.plans.matches
import cc.noharry.bothy.app.voice.tts.closesReply
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class A2aDelegationsTest {
    private val delegation = A2aDelegation("Grok Bot", "task-1", "给我自己发封测试邮件", startedAtEpochMillis = 0L)

    @Test
    fun followsUntilSettledWithAShorterPollFirst() = runBlocking {
        val clock = FakeClock()
        val answers = ArrayDeque(listOf(task(A2aTask.Status.WORKING), task(A2aTask.Status.WORKING), done()))

        val followed = followDelegation(delegation, clock::now, clock::pause) { A2aResult.Success(answers.removeFirst()) }

        assertEquals(A2aTask.Status.COMPLETED, followed.task?.status)
        assertEquals(listOf(3_000L, 3_000L, 3_000L), clock.pauses)
    }

    @Test
    fun aCompletedReportShowsShortAndGivesTheModelCardPicturesAndDetails() {
        val pictures = listOf(A2aReportPicture("generated-images/delegation-task1/1.png", "门头"))

        val report = delegationReport(delegation, A2aFollowed(done()), pictures)

        assertEquals("【委托回报】Grok Bot：邮件已发出。", report.display)
        assertEquals(
            listOf(
                "【委托回报】Grok Bot 完成了「给我自己发封测试邮件」：邮件已发出。",
                "卡片：{\"title\":\"香香川菜\",\"value\":\"人均 90\"}",
                "图片：generated-images/delegation-task1/1.png（门头）",
                "详细：{\"to\":\"me\",\"count\":1}",
            ),
            report.model.lines(),
        )
        assertTrue(report.display.isDelegationReport())
        assertEquals("委托回报", turnLabel(report.display))
        assertEquals("You", turnLabel("帮我导航回家"))
    }

    @Test
    fun reportPartsSplitCardPicturesAndOtherData() {
        val parts = reportParts(done())

        assertEquals("邮件已发出。", parts.text)
        assertEquals("{\"title\":\"香香川菜\",\"value\":\"人均 90\"}", parts.card)
        assertEquals("{\"to\":\"me\",\"count\":1}", parts.details)
        assertEquals(listOf("/files/0f98-a/1.png" to "门头"), parts.pictures)
    }

    @Test
    fun aQuestionKeepsTheTaskIdForTheModelOnly() {
        val report = delegationReport(delegation, A2aFollowed(task(A2aTask.Status.INPUT_REQUIRED, artifact = "发到哪个邮箱？")))

        assertEquals("【委托追问】Grok Bot：发到哪个邮箱？", report.display)
        assertEquals(
            "【委托追问】Grok Bot 关于「给我自己发封测试邮件」问：发到哪个邮箱？（用户回答后，用 Grok Bot 的 a2a_message_send 带 taskId=task-1 把回答发回去）",
            report.model,
        )
    }

    @Test
    fun failuresCancellationsAndLostTracksAreReportedAsFailures() {
        assertEquals(
            "【委托失败】Grok Bot 没能完成「给我自己发封测试邮件」：超过 15 分钟没有回执。",
            delegationReport(delegation, A2aFollowed(task(A2aTask.Status.FAILED, artifact = "超过 15 分钟没有回执。"))).model,
        )
        assertEquals(
            "【委托失败】Grok Bot：已停止等待。",
            delegationReport(delegation, A2aFollowed(task(A2aTask.Status.CANCELLED, statusText = "已停止等待。"))).display,
        )
        assertEquals(
            "【委托失败】Grok Bot：处理「给我自己发封测试邮件」时，连续 5 次查不到进度（connection_refused）。",
            delegationReport(delegation, A2aFollowed(null, "连续 5 次查不到进度（connection_refused）")).display,
        )
    }

    @Test
    fun repeatedPollFailuresStopFollowingAndSayWhy() = runBlocking {
        val clock = FakeClock()

        val followed = followDelegation(delegation, clock::now, clock::pause) {
            A2aResult.Failure(A2aClientError.Transport("connection_refused"))
        }

        assertNull(followed.task)
        assertEquals("连续 5 次查不到进度（connection_refused）", followed.problem)
        assertEquals(MAX_POLL_FAILURES, clock.pauses.size)
    }

    @Test
    fun followingStopsAtTheTimeLimit() = runBlocking {
        val clock = FakeClock()

        val followed = followDelegation(delegation, clock::now, clock::pause) { A2aResult.Success(task(A2aTask.Status.WORKING)) }

        assertTrue(followed.problem.orEmpty().contains("过了 30 分钟还没有结果"))
        assertTrue(clock.millis >= FOLLOW_LIMIT_MS)
    }

    @Test
    fun pollingSlowsDownAsTheTaskRunsLonger() {
        assertEquals(3_000L, delegationPollMillis(0))
        assertEquals(6_000L, delegationPollMillis(60_000))
        assertEquals(15_000L, delegationPollMillis(300_000))
    }

    @Test
    fun longRequestsAreShortenedInTheReport() {
        val long = delegation.copy(instruction = "帮我".repeat(30))

        assertTrue(delegationReport(long, A2aFollowed(done())).model.contains("「${"帮我".repeat(20)}…」"))
    }

    @Test
    fun picturesComeOnlyFromTheRelaysFilesRoute() {
        val endpoint = URI("https://relay.example.com/a2a")
        val id = "0f9866f0-1d06-4b26-b0c2-150168931aa1"

        assertEquals("https://relay.example.com/files/$id/1.png", reportPictureUrl(endpoint, "/files/$id/1.png").toString())
        assertNull(reportPictureUrl(endpoint, "https://evil.example.com/files/$id/1.png"))
        assertNull(reportPictureUrl(endpoint, "/files/$id/../../a2a"))
        assertNull(reportPictureUrl(endpoint, "/a2a"))
        assertNull(reportPictureUrl(endpoint, "/files/$id/1.svg"))
        assertEquals("delegation-0f9866f01d064b26", reportPictureFolder(id))
    }

    @Test
    fun tokenFilesAreNamedByDigestNotByProfileId() {
        val name = A2aTokens.fileName("user-grok-bot")

        assertTrue(Regex("[0-9a-f]{32}").matches(name))
        assertEquals(name, A2aTokens.fileName("user-grok-bot"))
        assertNotEquals(name, A2aTokens.fileName("user-other"))
    }

    @Test
    fun reportsAreNeverJudgedAsRequestsForABusinessAction() {
        assertFalse("【委托追问】Grok Bot：想去哪个城市？回答后发回去".requiresBusinessAction())
        assertTrue("导航去公司".requiresBusinessAction())
    }

    @Test
    fun aDelegationCallClosesTheReplyForProgressSpeech() {
        assertTrue(closesReply("mcp__experimental_a2a_user_grok_bot__a2a_message_send"))
        assertTrue(closesReply("finish"))
        // "已经派出三路…" with spawn_agent would be said again by the reply; when the agent waits instead, it is said then.
        assertTrue(closesReply("spawn_agent"))
        assertFalse(closesReply("wait_agent"))
        assertFalse(closesReply("mcp__device__navigation_route_plan"))
    }

    @Test
    fun theRelaysStageAndStepsAreReadFromTheStatusMessage() {
        val relay = A2aValue.ObjectValue(mapOf(
            "stage" to A2aValue.StringValue("working"),
            "steps" to A2aValue.ListValue(listOf(
                A2aValue.ObjectValue(mapOf("title" to A2aValue.StringValue("查航班"), "status" to A2aValue.StringValue("done"))),
                A2aValue.ObjectValue(mapOf("title" to A2aValue.StringValue("比价格"))),
                A2aValue.ObjectValue(mapOf("status" to A2aValue.StringValue("done"))),
            )),
        ))
        val message = A2aMessage(
            role = A2aMessage.Role.AGENT,
            parts = listOf(A2aPart.Text("正在比价格"), A2aPart.Data(A2aValue.ObjectValue(mapOf("relay" to relay)))),
        )

        val progress = relayProgress(task(A2aTask.Status.WORKING).copy(statusMessage = message))

        assertEquals(DelegationStage.WORKING, progress.stage)
        assertEquals(listOf(DelegationStep("查航班", "done"), DelegationStep("比价格", "todo")), progress.steps)
        assertEquals(DelegationStage.DELIVERED, relayProgress(task(A2aTask.Status.WORKING, statusText = "已交给")).stage)
    }

    @Test
    fun everyOpenAnswerIsPassedOnWhileFollowing() = runBlocking {
        val clock = FakeClock()
        val answers = ArrayDeque(listOf(task(A2aTask.Status.WORKING, statusText = "一"), task(A2aTask.Status.WORKING, statusText = "二"), done()))
        val seen = mutableListOf<String>()

        followDelegation(delegation, clock::now, clock::pause, onPoll = { seen += it.statusMessage?.parts.orEmpty().filterIsInstance<A2aPart.Text>().single().text }) {
            A2aResult.Success(answers.removeFirst())
        }

        assertEquals(listOf("一", "二"), seen)
    }

    private fun task(status: A2aTask.Status, artifact: String? = null, statusText: String? = null): A2aTask = A2aTask(
        id = "task-1",
        contextId = "ctx-1",
        status = status,
        statusMessage = statusText?.let { A2aMessage(role = A2aMessage.Role.AGENT, parts = listOf(A2aPart.Text(it))) },
        artifacts = artifact?.let { listOf(A2aArtifact("task-1-round-1", parts = listOf(A2aPart.Text(it)))) }.orEmpty(),
    )

    private fun done(): A2aTask {
        val card = A2aValue.ObjectValue(mapOf("title" to A2aValue.StringValue("香香川菜"), "value" to A2aValue.StringValue("人均 90")))
        val image = A2aValue.ObjectValue(mapOf("path" to A2aValue.StringValue("/files/0f98-a/1.png"), "caption" to A2aValue.StringValue("门头")))
        val data = A2aValue.ObjectValue(
            mapOf(
                "to" to A2aValue.StringValue("me"),
                "count" to A2aValue.NumberValue(1.0),
                "card" to card,
                "images" to A2aValue.ListValue(listOf(image)),
                "images_failed" to A2aValue.NumberValue(1.0),
            ),
        )
        return A2aTask(
            id = "task-1",
            contextId = "ctx-1",
            status = A2aTask.Status.COMPLETED,
            artifacts = listOf(A2aArtifact("task-1-round-1", parts = listOf(A2aPart.Text("邮件已发出。"), A2aPart.Data(data)))),
        )
    }

    private class FakeClock {
        var millis = 0L
        val pauses = mutableListOf<Long>()

        fun now(): Long = millis

        suspend fun pause(duration: Long) {
            pauses += duration
            millis += duration
        }
    }
}
