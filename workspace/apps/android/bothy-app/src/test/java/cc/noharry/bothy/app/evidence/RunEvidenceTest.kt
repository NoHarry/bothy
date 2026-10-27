package cc.noharry.bothy.app.evidence
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.session.LessonEntry
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunEvidenceTest {
    @Test
    fun completedRunWithPublicToolLifecycleReplaysPassFromJson() {
        val recorder = recorder(input = "one\ntwo")
        recorder.recordEvent(AgentEvent.Started(RUN_ID))
        recorder.recordEvent(AgentEvent.NativeToolCallStarted(RUN_ID, TOOL_NAME, """{"redacted":true}"""))
        recorder.recordRawToolEvent("Raw tool arguments", rawArguments("""{"token":"sk-secret"}"""))
        recorder.recordEvent(AgentEvent.NativeToolCallFinished(RUN_ID, TOOL_NAME, """{"redacted":true}"""))
        recorder.recordRawToolEvent("Raw tool result", rawResult(ok = true, result = """{"secret":"home-address"}"""))
        recorder.recordEvent(AgentEvent.ResultAvailable(result(RuntimeRunStatus.COMPLETED)))

        val file = recorder.write()
        val evidence = RunEvidenceJson.read(file)
        val replay = RunReplayVerifier.verify(evidence)
        val json = file.readText()

        assertTrue(replay.passed)
        assertEquals(7, evidence.inputLength)
        assertEquals("one two", evidence.inputPreview)
        assertEquals(1, evidence.counts.publicStarted)
        assertEquals(1, evidence.counts.publicFinished)
        assertEquals(1, evidence.counts.meaningfulBusinessTools)
        assertEquals("done", evidence.finalResponse)
        assertFalse(json.contains("sk-secret"))
        assertFalse(json.contains("home-address"))
    }

    @Test
    fun answeringFirstAndLeavingTheWorkToSubagentsIsNotAFailure() {
        val recorder = recorder()
        recorder.recordEvent(AgentEvent.Started(RUN_ID))
        recorder.recordEvent(AgentEvent.NativeToolCallStarted(RUN_ID, "spawn_agent", "{}"))
        recorder.recordEvent(AgentEvent.NativeToolCallFinished(RUN_ID, "spawn_agent", "{}"))
        val partial = result(RuntimeRunStatus.COMPLETED, "已经分三路在后台查了").copy(
            completion = RuntimeCompletionSummary(outcome = RuntimeCompletionOutcome.PARTIAL),
        )
        recorder.recordEvent(AgentEvent.ResultAvailable(partial))

        assertTrue(RunReplayVerifier.verify(recorder.snapshot()).passed)
    }

    @Test
    fun missingPublicFinishFailsReplayEvenWhenFinalResultCompleted() {
        val recorder = recorder()
        recorder.recordEvent(AgentEvent.Started(RUN_ID))
        recorder.recordEvent(AgentEvent.NativeToolCallStarted(RUN_ID, TOOL_NAME, """{"redacted":true}"""))
        recorder.recordEvent(AgentEvent.ResultAvailable(result(RuntimeRunStatus.COMPLETED)))

        val replay = RunReplayVerifier.verify(recorder.snapshot())

        assertFalse(replay.passed)
        assertTrue(replay.failures.single().contains("public tool calls still pending"))
    }

    @Test
    fun terminalConfirmationResumeClosesPendingPublicToolEvidence() {
        val recorder = recorder()
        recorder.recordEvent(AgentEvent.Started(RUN_ID))
        recorder.recordEvent(AgentEvent.NativeToolCallStarted(RUN_ID, TOOL_NAME, "{}", "call-1"))
        recorder.recordRawToolEvent("Raw tool arguments", rawArgumentsWithTimestamp("call-1", 120, "{}"))

        recorder.recordPendingPublicToolFinishes()
        recorder.recordTerminalResult(result(RuntimeRunStatus.COMPLETED, "HTTP 200"))

        val replay = RunReplayVerifier.verify(recorder.snapshot())

        assertTrue(replay.passed)
        assertEquals(1, recorder.snapshot().counts.publicFinished)
        assertEquals(0, recorder.snapshot().counts.pendingPublicTools)
        assertEquals(1, recorder.snapshot().counts.rawHookGaps)
        assertTrue(replay.warnings.single().contains("raw post hook missing"))
    }

    @Test
    fun cancelledConfirmationResumeClosesPendingRawToolWithoutWeakeningReplay() {
        val publicTimes = ArrayDeque(listOf(100L, 200L, 260L))
        val recorder = recorder(nowEpochMillis = { publicTimes.removeFirst() })
        recorder.recordEvent(AgentEvent.Started(RUN_ID))
        recorder.recordEvent(AgentEvent.NativeToolCallStarted(RUN_ID, TOOL_NAME, "{}", "call-1"))
        recorder.recordRawToolEvent("Raw tool arguments", rawArgumentsWithTimestamp("call-1", 120, "{}"))

        recorder.recordPendingPublicToolFinishes()
        recorder.recordPendingRawToolFinishes(RAW_STATUS_CANCELLED)
        recorder.recordTerminalResult(result(RuntimeRunStatus.CANCELLED, "Confirmation denied."))

        val evidence = recorder.snapshot()
        val replay = RunReplayVerifier.verify(evidence)

        assertFalse(replay.passed)
        assertEquals(
            listOf("final result is not a first-party pass (status=CANCELLED completion=FAILED)"),
            replay.failures,
        )
        assertTrue(replay.warnings.isEmpty())
        assertEquals(1, evidence.counts.publicFinished)
        assertEquals(1, evidence.counts.rawPost)
        assertEquals(0, evidence.counts.rawHookGaps)
        assertEquals(RAW_STATUS_CANCELLED, evidence.tools.single().rawPostStatus)
    }

    @Test
    fun recordsToolTimingCategoriesAndRecorderGap() {
        val publicTimes = ArrayDeque(listOf(100L, 200L))
        val recorder = recorder(nowEpochMillis = { publicTimes.removeFirst() })
        recorder.recordEvent(AgentEvent.Started(RUN_ID))
        recorder.recordEvent(AgentEvent.NativeToolCallStarted(RUN_ID, TOOL_NAME, "{}", "call-1"))
        recorder.recordRawToolEvent("Raw tool arguments", rawArgumentsWithTimestamp("call-1", 120, """{"keyword":"parking"}"""))
        recorder.recordEvent(AgentEvent.NativeToolCallFinished(RUN_ID, TOOL_NAME, "{}", "call-1"))
        recorder.recordRawToolEvent("Raw tool result", rawResultWithTimestamp("call-1", 260, ok = true, result = "{}"))

        val evidence = RunEvidenceJson.parse(RunEvidenceJson.write(recorder.snapshot()))
        val tool = evidence.tools.single()

        assertEquals(DEBUG_TOOL_CATEGORY_BACKEND, tool.category)
        assertEquals(100L, tool.publicDurationMs)
        assertEquals(140L, tool.rawDurationMs)
        assertEquals(60L, tool.recorderGapMs)
        assertEquals(100L, evidence.counts.backendDurationMs)
        assertEquals(60L, evidence.counts.recorderGapMs)
    }

    @Test
    fun memoryEvidenceRoundTripsThroughJson() {
        val recorder = recorder()
        recorder.recordMemoryEvidence(
            RuntimeMemoryEvidence(
                activeCount = 1,
                pendingCount = 1,
                active = listOf(LessonEntry("navigation", "destination POI", 0.9f, 2)),
                pending = listOf(LessonEntry("navigation", "screen brighten adjust", 0.45f, 1)),
            ),
        )

        val evidence = RunEvidenceJson.parse(RunEvidenceJson.write(recorder.snapshot()))

        assertEquals(1, evidence.memory.activeCount)
        assertEquals(1, evidence.memory.pendingCount)
        assertEquals("destination POI", evidence.memory.active.single().content)
        assertEquals("screen brighten adjust", evidence.memory.pending.single().content)
    }

    @Test
    fun rawPostWithoutMetadataPairsWithLatestRawPreInsteadOfCreatingUnknownGap() {
        val publicTimes = ArrayDeque(listOf(100L, 200L, 260L))
        val recorder = recorder(nowEpochMillis = { publicTimes.removeFirst() })
        recorder.recordEvent(AgentEvent.Started(RUN_ID))
        recorder.recordEvent(AgentEvent.NativeToolCallStarted(RUN_ID, TOOL_NAME, "{}", "call-1"))
        recorder.recordRawToolEvent("Raw tool arguments", rawArgumentsWithTimestamp("call-1", 120, """{"keyword":"parking"}"""))
        recorder.recordEvent(AgentEvent.NativeToolCallFinished(RUN_ID, TOOL_NAME, "{}", "call-1"))
        recorder.recordRawToolEvent("Raw tool result", "result={}")

        val evidence = recorder.snapshot()

        assertEquals(1, evidence.tools.size)
        assertEquals(0, evidence.counts.rawHookGaps)
        assertEquals(RAW_STATUS_UNKNOWN, evidence.tools.single().rawPostStatus)
        assertEquals(60L, evidence.tools.single().recorderGapMs)
    }


    @Test
    fun missingRawPostIsWarningWhenPublicFinishIsPresent() {
        val recorder = recorder()
        recorder.recordEvent(AgentEvent.Started(RUN_ID))
        recorder.recordEvent(AgentEvent.NativeToolCallStarted(RUN_ID, TOOL_NAME, """{"redacted":true}"""))
        recorder.recordRawToolEvent("Raw tool arguments", rawArguments("""{"destination":"广州塔"}"""))
        recorder.recordEvent(AgentEvent.NativeToolCallFinished(RUN_ID, TOOL_NAME, """{"redacted":true}"""))
        recorder.recordEvent(AgentEvent.ResultAvailable(result(RuntimeRunStatus.COMPLETED)))

        val replay = RunReplayVerifier.verify(recorder.snapshot())

        assertTrue(replay.passed)
        assertEquals(1, recorder.snapshot().counts.rawHookGaps)
        assertTrue(replay.warnings.single().contains("raw post hook missing"))
    }

    @Test
    fun rawToolFailureWarnsButDoesNotFailCompletedReplay() {
        val recorder = recorder()
        recorder.recordEvent(AgentEvent.Started(RUN_ID))
        recorder.recordEvent(AgentEvent.NativeToolCallStarted(RUN_ID, TOOL_NAME, """{"redacted":true}"""))
        recorder.recordRawToolEvent("Raw tool arguments", rawArguments("""{"value":1}"""))
        recorder.recordEvent(AgentEvent.NativeToolCallFinished(RUN_ID, TOOL_NAME, """{"redacted":true}"""))
        recorder.recordRawToolEvent("Raw tool result", rawResult(ok = false, result = """{"code":"BACKEND"}"""))
        recorder.recordEvent(AgentEvent.ResultAvailable(result(RuntimeRunStatus.COMPLETED)))

        val replay = RunReplayVerifier.verify(recorder.snapshot())

        assertTrue(replay.passed)
        assertTrue(replay.warnings.single().contains("raw hook tool failures"))
    }

    @Test
    fun toolCallIdPairsReplayEvidenceForSameNameTools() {
        val recorder = recorder()
        recorder.recordEvent(AgentEvent.Started(RUN_ID))
        recorder.recordEvent(AgentEvent.NativeToolCallStarted(RUN_ID, TOOL_NAME, "{}", "call-1"))
        recorder.recordEvent(AgentEvent.NativeToolCallStarted(RUN_ID, TOOL_NAME, "{}", "call-2"))
        recorder.recordRawToolEvent("Raw tool arguments", rawArguments("call-2", """{"keyword":"parking"}"""))
        recorder.recordRawToolEvent("Raw tool arguments", rawArguments("call-1", """{"keyword":"home"}"""))
        recorder.recordEvent(AgentEvent.NativeToolCallFinished(RUN_ID, TOOL_NAME, "{}", "call-1"))
        recorder.recordRawToolEvent("Raw tool result", rawResult("call-2", ok = true, result = """{"items":[]}"""))
        recorder.recordEvent(AgentEvent.NativeToolCallFinished(RUN_ID, TOOL_NAME, "{}", "call-2"))
        recorder.recordRawToolEvent("Raw tool result", rawResult("call-1", ok = true, result = """{"items":[]}"""))
        recorder.recordEvent(AgentEvent.ResultAvailable(result(RuntimeRunStatus.COMPLETED)))

        val evidence = RunEvidenceJson.parse(RunEvidenceJson.write(recorder.snapshot()))
        val replay = RunReplayVerifier.verify(evidence)

        assertTrue(replay.passed)
        assertEquals(2, evidence.counts.publicStarted)
        assertEquals(2, evidence.counts.rawPost)
        assertEquals(listOf("call-1", "call-2"), evidence.tools.map { it.toolCallId })
    }

    @Test
    fun toolCallIdFinishClearsPendingBeforeLegacyFallback() {
        val recorder = recorder()
        recorder.recordEvent(AgentEvent.Started(RUN_ID))
        recorder.recordEvent(AgentEvent.NativeToolCallStarted(RUN_ID, TOOL_NAME, "{}", "call-1"))
        recorder.recordEvent(AgentEvent.NativeToolCallStarted(RUN_ID, TOOL_NAME, "{}", "call-2"))
        recorder.recordEvent(AgentEvent.NativeToolCallFinished(RUN_ID, TOOL_NAME, "{}", "call-2"))
        recorder.recordEvent(AgentEvent.NativeToolCallFinished(RUN_ID, TOOL_NAME, "{}"))
        recorder.recordRawToolEvent("Raw tool arguments", rawArguments("call-1", """{"keyword":"home"}"""))
        recorder.recordRawToolEvent("Raw tool arguments", rawArguments("call-2", """{"keyword":"parking"}"""))
        recorder.recordRawToolEvent("Raw tool result", rawResult("call-2", ok = true, result = """{"items":[]}"""))
        recorder.recordRawToolEvent("Raw tool result", rawResult(ok = true, result = """{"items":[]}"""))
        recorder.recordEvent(AgentEvent.ResultAvailable(result(RuntimeRunStatus.COMPLETED)))

        val evidence = recorder.snapshot()
        val replay = RunReplayVerifier.verify(evidence)

        assertTrue(replay.passed)
        assertEquals(2, evidence.counts.publicFinished)
        assertEquals(0, evidence.counts.pendingPublicTools)
        assertEquals(2, evidence.counts.rawPost)
        assertEquals(0, evidence.counts.rawHookGaps)
    }

    @Test
    fun actionableRequestWithoutBusinessToolFailsReplayEvenWhenCompleted() {
        val recorder = recorder(input = "我想去广州塔，在那找一个停车场")
        recorder.recordEvent(AgentEvent.Started(RUN_ID))
        recorder.recordEvent(AgentEvent.NativeToolCallStarted(RUN_ID, "read_file", """{"redacted":true}"""))
        recorder.recordEvent(AgentEvent.NativeToolCallFinished(RUN_ID, "read_file", """{"redacted":true}"""))
        recorder.recordEvent(AgentEvent.NativeToolCallStarted(RUN_ID, "finish", """{"redacted":true}"""))
        recorder.recordEvent(AgentEvent.NativeToolCallFinished(RUN_ID, "finish", """{"redacted":true}"""))
        recorder.recordEvent(AgentEvent.ResultAvailable(result(RuntimeRunStatus.COMPLETED)))

        val evidence = recorder.snapshot()
        val replay = RunReplayVerifier.verify(evidence)

        assertTrue(evidence.requiresBusinessAction)
        assertEquals(0, evidence.counts.meaningfulBusinessTools)
        assertFalse(replay.passed)
        assertTrue(replay.failures.single().contains("without a meaningful business tool"))
    }

    @Test
    fun musicChatWithHostedWebSearchCountsAsBusinessToolAndPassesReplay() {
        val recorder = recorder(input = "最近有个很火的电视剧，主题曲是王菲唱的，你知道是什么歌吗")
        recorder.recordEvent(AgentEvent.Started(RUN_ID))
        recorder.recordEvent(
            AgentEvent.SearchActivity(
                runId = RUN_ID,
                provider = "Anthropic",
                queryPreview = "王菲 电视剧 主题曲",
                resultCount = 5,
            ),
        )
        recorder.recordEvent(AgentEvent.NativeToolCallStarted(RUN_ID, "finish", """{"redacted":true}"""))
        recorder.recordEvent(AgentEvent.NativeToolCallFinished(RUN_ID, "finish", """{"redacted":true}"""))
        recorder.recordEvent(
            AgentEvent.ResultAvailable(
                result(RuntimeRunStatus.COMPLETED, "主题曲是《主角》。"),
            ),
        )

        val evidence = recorder.snapshot()
        val replay = RunReplayVerifier.verify(evidence)

        assertTrue(evidence.requiresBusinessAction)
        assertEquals(1, evidence.counts.meaningfulBusinessTools)
        assertEquals("web_search", evidence.tools.single { it.toolName == "web_search" }.toolName)
        assertEquals("search", evidence.tools.single { it.toolName == "web_search" }.category)
        assertTrue(evidence.tools.single { it.toolName == "web_search" }.publicStarted)
        assertTrue(evidence.tools.single { it.toolName == "web_search" }.publicFinished)
        assertTrue(replay.passed)
    }

    @Test
    fun musicChatWithoutSearchOrPlayFailsReplay() {
        val recorder = recorder(input = "最近有个很火的电视剧，主题曲是王菲唱的，你知道是什么歌吗")
        recorder.recordEvent(AgentEvent.Started(RUN_ID))
        recorder.recordEvent(AgentEvent.NativeToolCallStarted(RUN_ID, "finish", """{"redacted":true}"""))
        recorder.recordEvent(AgentEvent.NativeToolCallFinished(RUN_ID, "finish", """{"redacted":true}"""))
        recorder.recordEvent(
            AgentEvent.ResultAvailable(result(RuntimeRunStatus.COMPLETED, "应该是《主角》。")),
        )

        val evidence = recorder.snapshot()
        val replay = RunReplayVerifier.verify(evidence)

        assertTrue(evidence.requiresBusinessAction)
        assertEquals(0, evidence.counts.meaningfulBusinessTools)
        assertFalse(replay.passed)
        assertTrue(replay.failures.single().contains("without a meaningful business tool"))
    }

    @Test
    fun musicPlayBackendToolCountsAsBusinessAction() {
        val playTool = "mcp__tools_json__command_music_PlaySearchMusic"
        val recorder = recorder(input = "播放周杰伦的歌")
        recorder.recordEvent(AgentEvent.Started(RUN_ID))
        recorder.recordEvent(AgentEvent.NativeToolCallStarted(RUN_ID, playTool, """{"redacted":true}""", "play-1"))
        recorder.recordEvent(AgentEvent.NativeToolCallFinished(RUN_ID, playTool, """{"redacted":true}""", "play-1"))
        recorder.recordRawToolEvent(
            "Raw tool arguments",
            """
                runId=$RUN_ID
                toolCallId=play-1
                tool=$playTool
                source=BACKEND
                arguments={}
            """.trimIndent(),
        )
        recorder.recordRawToolEvent(
            "Raw tool result",
            """
                runId=$RUN_ID
                toolCallId=play-1
                tool=$playTool
                source=BACKEND
                ok=true
                retryable=false
                code=
                result={"ok":true}
            """.trimIndent(),
        )
        recorder.recordEvent(AgentEvent.NativeToolCallStarted(RUN_ID, "finish", """{"redacted":true}"""))
        recorder.recordEvent(AgentEvent.NativeToolCallFinished(RUN_ID, "finish", """{"redacted":true}"""))
        recorder.recordEvent(AgentEvent.ResultAvailable(result(RuntimeRunStatus.COMPLETED, "已播放。")))

        val evidence = recorder.snapshot()
        val replay = RunReplayVerifier.verify(evidence)

        assertTrue(evidence.requiresBusinessAction)
        assertEquals(1, evidence.counts.meaningfulBusinessTools)
        assertTrue(replay.passed)
    }

    @Test
    fun complexNameRouteFinalResponseFailsReplayWhenItIsProcessNarration() {
        val recorder = completedComplexNameRouteRecorder(
            response = "All tasks completed! Let me update the todo and provide the final response.",
        )

        val replay = RunReplayVerifier.verify(recorder.snapshot())

        assertFalse(replay.passed)
        assertTrue(replay.failures.any { it.contains("process narration") })
    }

    @Test
    fun complexNameRouteFinalResponseAllowsMixedNarrationWithResults() {
        val recorder = completedComplexNameRouteRecorder(
            response = "Let me now call finish with a final response: 我是小博，名字代表随时响应。" +
                "已规划不走高速路线去广州塔，途中去深圳北站附近肯德基，找停车场和沿途厕所，并播放周杰伦的歌。",
        )

        val replay = RunReplayVerifier.verify(recorder.snapshot())

        assertTrue(replay.passed)
    }

    @Test
    fun complexNameRouteFinalResponseWarnsWhenNameOrRouteContentIsMissing() {
        val recorder = completedComplexNameRouteRecorder(response = "已完成。")

        val replay = RunReplayVerifier.verify(recorder.snapshot())

        assertTrue(replay.passed)
        assertTrue(replay.warnings.any { it.contains("missing name explanation") })
        assertTrue(replay.warnings.any { it.contains("missing route key info") })
    }

    @Test
    fun complexNameRouteFinalResponsePassesReplayWhenItIsDirectAndComplete() {
        val recorder = completedComplexNameRouteRecorder(
            response = "我是小博，这个名字代表我会贴近你的日常任务、快速响应。" +
                "已为你规划不走高速路线去广州塔，途中去深圳北站附近肯德基用餐，" +
                "同时查找广州塔停车场和沿途厕所；开始导航后会播放周杰伦的歌。",
        )

        val evidence = RunEvidenceJson.parse(RunEvidenceJson.write(recorder.snapshot()))
        val replay = RunReplayVerifier.verify(evidence)

        assertTrue(replay.passed)
        assertEquals(
            "我是小博，这个名字代表我会贴近你的日常任务、快速响应。" +
                "已为你规划不走高速路线去广州塔，途中去深圳北站附近肯德基用餐，" +
                "同时查找广州塔停车场和沿途厕所；开始导航后会播放周杰伦的歌。",
            evidence.finalResponse,
        )
    }

    @Test
    fun hotFollowUpRequiresBusinessTool() {
        val recorder = recorder(input = "还是很热")
        recorder.recordEvent(AgentEvent.Started(RUN_ID))
        recorder.recordEvent(AgentEvent.NativeToolCallStarted(RUN_ID, "read_file", """{"redacted":true}"""))
        recorder.recordEvent(AgentEvent.NativeToolCallFinished(RUN_ID, "read_file", """{"redacted":true}"""))
        recorder.recordEvent(AgentEvent.NativeToolCallStarted(RUN_ID, "finish", """{"redacted":true}"""))
        recorder.recordEvent(AgentEvent.NativeToolCallFinished(RUN_ID, "finish", """{"redacted":true}"""))
        recorder.recordEvent(AgentEvent.ResultAvailable(result(RuntimeRunStatus.COMPLETED)))

        val evidence = recorder.snapshot()
        val replay = RunReplayVerifier.verify(evidence)

        assertTrue(evidence.requiresBusinessAction)
        assertFalse(replay.passed)
        assertTrue(replay.failures.single().contains("without a meaningful business tool"))
    }

    @Test
    fun emptyCompletionProbesRoundTripThroughJsonWithoutUiMode() {
        // Probes are pure AgentEvent data — not Demo/Debug presentation mode.
        val recorder = recorder(input = "导航去广州塔")
        recordEmptyCompletionProbeEvents(recorder)
        val file = recorder.write()
        assertEmptyCompletionProbeJsonRoundTrip(file)
    }

    private fun recordEmptyCompletionProbeEvents(recorder: RunEvidenceRecorder) {
        recorder.recordEvent(AgentEvent.Started(RUN_ID))
        recorder.recordEvent(thinkingOnlyEmptyCompletionEvent())
        recorder.recordEvent(emptyNoOutputCompletionEvent())
        recorder.recordEvent(
            AgentEvent.Failed(
                runId = RUN_ID,
                code = "MODEL_FINISHED_WITHOUT_RUNTIME_FINISH",
                message = "Model completed without finish(response).",
                recoverable = true,
            ),
        )
        recorder.recordEvent(
            AgentEvent.ResultAvailable(
                result(
                    RuntimeRunStatus.FAILED,
                    response = "Model completed without finish(response).",
                    errorCode = "MODEL_FINISHED_WITHOUT_RUNTIME_FINISH",
                ),
            ),
        )
    }

    private fun thinkingOnlyEmptyCompletionEvent(): AgentEvent.EmptyCompletionObserved =
        AgentEvent.EmptyCompletionObserved(
            runId = RUN_ID,
            modelRound = 3,
            kind = "THINKING_ONLY",
            continued = true,
            continueCount = 1,
            textChars = 0,
            thinkingChars = 42,
            hadStateAdvancingWork = true,
            successfulBackendToolCount = 2,
        )

    private fun emptyNoOutputCompletionEvent(): AgentEvent.EmptyCompletionObserved =
        AgentEvent.EmptyCompletionObserved(
            runId = RUN_ID,
            modelRound = 4,
            kind = "EMPTY_NO_OUTPUT",
            continued = false,
            continueCount = 1,
            textChars = 0,
            thinkingChars = 0,
            hadStateAdvancingWork = true,
            successfulBackendToolCount = 2,
        )

    private fun assertEmptyCompletionProbeJsonRoundTrip(file: java.io.File) {
        val json = file.readText()
        val evidence = RunEvidenceJson.read(file)
        val probes = evidence.loopProbes
        assertTrue(json.contains("\"schemaVersion\":2"))
        assertTrue(json.contains("\"loopProbes\""))
        assertEquals(2, probes.emptyCompletionObservedCount)
        assertEquals(1, probes.emptyCompletionContinueCount)
        assertEquals("EMPTY_NO_OUTPUT", probes.lastEmptyCompletionKind)
        assertEquals(false, probes.lastEmptyCompletionContinued)
        assertEquals(4, probes.lastEmptyCompletionModelRound)
        assertEquals(0, probes.lastEmptyCompletionTextChars)
        assertEquals(0, probes.lastEmptyCompletionThinkingChars)
        assertEquals(true, probes.lastHadStateAdvancingWork)
        assertEquals(2, probes.lastSuccessfulBackendToolCount)
        assertEquals(2, probes.observations.size)
        assertEquals("THINKING_ONLY", probes.observations[0].kind)
        assertTrue(probes.observations[0].continued)
        assertEquals(42, probes.observations[0].thinkingChars)
        assertEquals("EMPTY_NO_OUTPUT", probes.observations[1].kind)
        assertFalse(probes.observations[1].continued)
        assertEquals("FAILED", evidence.finalStatus)
        assertEquals("MODEL_FINISHED_WITHOUT_RUNTIME_FINISH", evidence.finalErrorCode)
    }

    private fun recorder(
        input: String = "user text",
        nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    ): RunEvidenceRecorder =
        RunEvidenceRecorder(
            evidenceDir = Files.createTempDirectory("debug-run-evidence-").toFile(),
            request = AgentRequest(REQUEST_ID, SESSION_ID, input, "debug-console"),
            providerLabel = "OpenAI",
            streamLabel = "stream",
            backendLabel = "simulated MCP",
            nowEpochMillis = nowEpochMillis,
        )

    private fun completedComplexNameRouteRecorder(response: String): RunEvidenceRecorder {
        val recorder = recorder(input = COMPLEX_NAME_ROUTE_INPUT)
        recorder.recordEvent(AgentEvent.Started(RUN_ID))
        recorder.recordEvent(AgentEvent.NativeToolCallStarted(RUN_ID, TOOL_NAME, """{"redacted":true}"""))
        recorder.recordEvent(AgentEvent.NativeToolCallFinished(RUN_ID, TOOL_NAME, """{"redacted":true}"""))
        recorder.recordEvent(AgentEvent.ResultAvailable(result(RuntimeRunStatus.COMPLETED, response)))
        return recorder
    }

    private fun result(
        status: RuntimeRunStatus,
        response: String = "done",
        errorCode: String? = null,
    ): RuntimeResult =
        RuntimeResult(
            RUN_ID,
            status,
            response = response,
            errorCode = errorCode,
            completion = cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary(
                outcome = if (status == RuntimeRunStatus.COMPLETED) {
                    cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome.SUCCEEDED
                } else {
                    cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome.FAILED
                },
            ),
        )

    private fun rawArguments(arguments: String): String =
        """
            runId=$RUN_ID
            tool=$TOOL_NAME
            source=BACKEND
            arguments=$arguments
        """.trimIndent()

    private fun rawArguments(toolCallId: String, arguments: String): String =
        """
            runId=$RUN_ID
            toolCallId=$toolCallId
            tool=$TOOL_NAME
            source=BACKEND
            arguments=$arguments
        """.trimIndent()

    private fun rawArgumentsWithTimestamp(toolCallId: String, timestampMs: Long, arguments: String): String =
        """
            runId=$RUN_ID
            timestampMs=$timestampMs
            toolCallId=$toolCallId
            tool=$TOOL_NAME
            source=BACKEND
            arguments=$arguments
        """.trimIndent()

    private fun rawResult(ok: Boolean, result: String): String =
        """
            runId=$RUN_ID
            tool=$TOOL_NAME
            source=BACKEND
            ok=$ok
            retryable=false
            code=
            result=$result
        """.trimIndent()

    private fun rawResultWithTimestamp(toolCallId: String, timestampMs: Long, ok: Boolean, result: String): String =
        """
            runId=$RUN_ID
            timestampMs=$timestampMs
            toolCallId=$toolCallId
            tool=$TOOL_NAME
            source=BACKEND
            ok=$ok
            retryable=false
            code=
            result=$result
        """.trimIndent()

    private fun rawResult(toolCallId: String, ok: Boolean, result: String): String =
        """
            runId=$RUN_ID
            toolCallId=$toolCallId
            tool=$TOOL_NAME
            source=BACKEND
            ok=$ok
            retryable=false
            code=
            result=$result
        """.trimIndent()

    private companion object {
        const val REQUEST_ID = "request-1"
        const val SESSION_ID = "session-1"
        const val RUN_ID = "run-1"
        const val TOOL_NAME = "command_navi_SearchParking"
        const val COMPLEX_NAME_ROUTE_INPUT =
            "介绍下你自己，为什么叫这个名字，我想去广州塔，在那找一个停车场，中间去一下深圳北站附近的肯德基吃个饭，" +
                "不走高速，出了肯德基之后找一下沿途的厕所，开始导航后放首周杰伦的歌"
    }
}
