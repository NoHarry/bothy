package cc.noharry.bothy.app.runtime
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.AgentRuntime
import cc.noharry.agent.kernel.api.CancelPendingConfirmationRequest
import cc.noharry.agent.kernel.api.CancelPendingResult
import cc.noharry.agent.kernel.api.ConfirmationChoice
import cc.noharry.agent.kernel.api.ConfirmationDecision
import cc.noharry.agent.kernel.api.ConfirmationResumeResult
import cc.noharry.agent.kernel.api.PendingConfirmation
import cc.noharry.agent.kernel.api.PostUserInputRequest
import cc.noharry.agent.kernel.api.PostUserInputResult
import cc.noharry.agent.kernel.api.ProtocolRequestCancellation
import cc.noharry.agent.kernel.api.ProtocolRequestCloseLoopResult
import cc.noharry.agent.kernel.api.ProtocolRequestRejection
import cc.noharry.agent.kernel.api.ProtocolRequestResolution
import cc.noharry.agent.kernel.api.reliability.OpenConfusion
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidence
import cc.noharry.agent.kernel.api.reliability.RuntimeHandoff
import cc.noharry.agent.kernel.api.reliability.RuntimeProtocolRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunSnapshot
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.api.reliability.RuntimeTraceEvent
import cc.noharry.bothy.app.a2a.a2aExecutionOutput
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.evidence.RunEvidence
import cc.noharry.bothy.app.evidence.RunEvidenceRecorder
import cc.noharry.bothy.app.model.summary
import cc.noharry.bothy.app.session.ConfirmationMode
import cc.noharry.bothy.app.session.SessionTurnRecorder
import cc.noharry.bothy.app.voice.tts.toolStarted
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRunCollectionTest {
    @Test fun markedProgressPublishesBeforeRuntimeCompletesWithoutAudioConfiguration() = runBlocking {
        val release = CountDownLatch(1)
        val subscription = ExecutionEvents.subscribe(REQUEST.requestId)
        val events = listOf(
            AgentEvent.TextDelta(RUN_ID, "T"),
            AgentEvent.TextDelta(RUN_ID, "TS: 正在核对目的地信息。"),
            toolStarted(RUN_ID),
            AgentEvent.ResultAvailable(firstPartyResult(RuntimeRunStatus.COMPLETED, "地点信息已查到。")),
        )
        val job = launch(Dispatchers.Default) {
            collectRuntime(
                FlowAgentRuntime(events, { index -> if (index == 3) check(release.await(10, TimeUnit.SECONDS)) }),
                REQUEST, null, evidenceRecorder(), SessionTurnRecorder(REQUEST, REQUEST.input),
                {}, {}, { error("unexpected failure: $it") }, { _, _ -> },
            )
        }
        try {
            val progress = withTimeout(3_000) {
                var message = subscription.events.receive()
                while (message !is ExecutionMessage.Speech) message = subscription.events.receive()
                message
            }
            assertEquals(ExecutionMessage.Speech("正在核对目的地信息。", false), progress)
            assertEquals(cc.noharry.agent.a2a.server.A2aOutput.Speech("正在核对目的地信息。", false), a2aExecutionOutput(progress))
            assertTrue(job.isActive)
            release.countDown()
            job.join()
            val remaining = mutableListOf<ExecutionMessage>()
            for (message in subscription.events) remaining += message
            assertEquals(listOf(ExecutionMessage.Speech("地点信息已查到。", true)), remaining.filterIsInstance<ExecutionMessage.Speech>())
        } finally {
            release.countDown()
            job.cancel()
            job.join()
            subscription.close()
        }
    }

    @Test
    fun blockedLogProducerLeavesUiResponsiveAndEventsOnUi() = runBlocking {
        Executors.newSingleThreadExecutor { Thread(it, "test-ui") }.asCoroutineDispatcher().use { ui ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val producerThread = AtomicReference<String>()
            val handledThreads = CopyOnWriteArrayList<String>()
            val job = launch(ui) {
                collectRuntime(
                    runtime = FlowAgentRuntime(listOf(AgentEvent.Started(RUN_ID)), {
                        producerThread.set(Thread.currentThread().name.substringBefore(" @"))
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }),
                    request = REQUEST, evidenceRecorder = null, runEvidenceRecorder = evidenceRecorder(),
                    turnRecorder = SessionTurnRecorder(REQUEST, REQUEST.input),
                    onEvent = { handledThreads += Thread.currentThread().name.substringBefore(" @") },
                    onStopped = {}, onFailure = { error("unexpected failure: $it") }, onFinally = { _, _ -> },
                )
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                withTimeout(2_000) { withContext(ui) { assertEquals("test-ui", Thread.currentThread().name.substringBefore(" @")) } }
                assertFalse(producerThread.get() == "test-ui")
                release.countDown()
                job.join()
                assertEquals(listOf("test-ui"), handledThreads)
            } finally {
                release.countDown()
                job.cancel()
                job.join()
            }
        }
    }

    @Test
    fun cancellationFlushStaysOffUiAndFinalizesOnce() = runBlocking {
        Executors.newSingleThreadExecutor { Thread(it, "test-ui") }.asCoroutineDispatcher().use { ui ->
            val entered = CountDownLatch(1)
            val releaseProducer = CountDownLatch(1)
            val flushing = CountDownLatch(1)
            val releaseFlush = CountDownLatch(1)
            val finalized = CopyOnWriteArrayList<String>()
            val runtime = FlowAgentRuntime(listOf(AgentEvent.Started(RUN_ID)), {
                entered.countDown()
                check(releaseProducer.await(10, TimeUnit.SECONDS))
            }, onFlowFinally = {
                assertFalse(Thread.currentThread().name.substringBefore(" @") == "test-ui")
                flushing.countDown()
                check(releaseFlush.await(10, TimeUnit.SECONDS))
            })
            val job = launch(ui) {
                collectRuntime(runtime, REQUEST, null, evidenceRecorder(),
                    SessionTurnRecorder(REQUEST, REQUEST.input), {}, {},
                    { error("unexpected failure: $it") }, { _, _ -> finalized += Thread.currentThread().name.substringBefore(" @") })
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                job.cancel()
                releaseProducer.countDown()
                assertTrue(flushing.await(5, TimeUnit.SECONDS))
                withTimeout(2_000) { withContext(ui) { assertEquals("test-ui", Thread.currentThread().name.substringBefore(" @")) } }
                releaseFlush.countDown()
                job.join()
                assertEquals(listOf("test-ui"), finalized)
            } finally {
                releaseProducer.countDown()
                releaseFlush.countDown()
                job.cancel()
                job.join()
            }
        }
    }

    @Test
    fun slowUiHandlerDoesNotBlockRuntimeEventCollection() = runBlocking {
        val uiStarted = CountDownLatch(1)
        val releaseUi = CountDownLatch(1)
        val secondEventReached = CountDownLatch(1)
        val handled = CopyOnWriteArrayList<String>()
        val finalEvidence = AtomicReference<RunEvidence>()

        val job = launch(Dispatchers.Default) {
            collectRuntime(
                runtime = runtime(secondEventReached),
                request = REQUEST,
                evidenceRecorder = null,
                runEvidenceRecorder = evidenceRecorder(),
                turnRecorder = SessionTurnRecorder(REQUEST, REQUEST.input),
                onEvent = slowOrderedHandler(uiStarted, releaseUi, handled),
                onStopped = {},
                onFailure = { error("unexpected failure: $it") },
                onFinally = { runRecorder, _ -> finalEvidence.set(runRecorder.snapshot()) },
            )
        }

        assertTrue(uiStarted.await(2, TimeUnit.SECONDS))
        assertTrue(secondEventReached.await(2, TimeUnit.SECONDS))
        releaseUi.countDown()
        job.join()

        assertEquals(listOf("Started", "TextDelta", "ResultAvailable"), handled)
        assertEquals(3, finalEvidence.get().eventDispatch.eventCount)
        assertTrue(finalEvidence.get().eventDispatch.maxQueueDepth >= 1)
    }

    @Test
    fun dispatcherDoesNotRunUiHandlerInlineOnCollectorThread() = runBlocking {
        val secondEventReached = CountDownLatch(1)
        val handled = CopyOnWriteArrayList<String>()
        val reachedWhileHandling = AtomicReference<Boolean>()

        val job = launch {
            collectRuntime(
                runtime = runtime(secondEventReached),
                request = REQUEST,
                evidenceRecorder = null,
                runEvidenceRecorder = evidenceRecorder(),
                turnRecorder = SessionTurnRecorder(REQUEST, REQUEST.input),
                onEvent = { event ->
                    handled += event::class.java.simpleName
                    // Still handling the first event, the collector must reach the next one: run inline on the
                    // collector thread, this handler would hold it and wait out the two seconds. (A 200 ms timeout on
                    // this same thread against a 700 ms sleep here failed under a loaded full run.)
                    if (event is AgentEvent.Started) reachedWhileHandling.set(secondEventReached.await(2, TimeUnit.SECONDS))
                },
                onStopped = {},
                onFailure = { error("unexpected failure: $it") },
                onFinally = { _, _ -> },
            )
        }
        job.join()

        assertEquals(true, reachedWhileHandling.get())
        assertEquals(listOf("Started", "TextDelta", "ResultAvailable"), handled)
    }

    @Test
    fun steeringPostAcceptedMapsToRuntimeTranscript() = runBlocking {
        val posted = AtomicReference<PostUserInputRequest>()
        val result = postRuntimeSteering(
            runtime = FlowAgentRuntime(
                events = emptyList(),
                beforeEvent = {},
                postResult = PostUserInputResult.Accepted,
                onPost = posted::set,
            ),
            runId = RUN_ID,
            requestId = "steer-1",
            text = "keep going",
        )

        assertEquals(PostUserInputRequest(RUN_ID, "steer-1", "keep going"), posted.get())
        assertEquals("Steering sent", result.status)
        val transcript = result.transcript as SteeringTranscript.Runtime
        assertEquals("Runtime steering input", transcript.title)
        assertEquals("keep going", transcript.body)
    }

    @Test
    fun steeringPostNotFoundMapsToVisibleStatus() = runBlocking {
        val result = postRuntimeSteering(
            runtime = FlowAgentRuntime(emptyList(), beforeEvent = {}, postResult = PostUserInputResult.NotFound),
            runId = RUN_ID,
            requestId = "steer-2",
            text = "late input",
        )

        assertEquals("Steering missing run", result.status)
        val transcript = result.transcript as SteeringTranscript.Status
        assertEquals("Steering ignored: active run was not found.", transcript.message)
    }

    @Test
    fun confirmationApproveAcceptedMapsToRuntimeTranscript() = runBlocking {
        val posted = AtomicReference<ConfirmationDecision>()
        val result = postRuntimeConfirmation(
            runtime = FlowAgentRuntime(
                events = emptyList(),
                beforeEvent = {},
                confirmationResult = ConfirmationResumeResult.Accepted,
                onConfirmation = posted::set,
            ),
            pendingId = "pending-1",
            requestId = "confirm-1",
            approve = true,
        )

        assertEquals(ConfirmationDecision("pending-1", "confirm-1", ConfirmationChoice.APPROVE), posted.get())
        assertEquals("Confirmation approved", result.status)
        assertEquals(ConfirmationChoice.APPROVE, result.appliedChoice)
        val transcript = result.transcript as SteeringTranscript.Runtime
        assertEquals("Runtime confirmation resolved", transcript.title)
        assertEquals("pendingId=pending-1 choice=APPROVE", transcript.body)
    }

    @Test
    fun confirmationModesMapToAppSideAutoDecisions() {
        val confirmed = setOf("example.com")

        assertEquals(null, ConfirmationMode.ASK_EVERY_TIME.autoDecision("example.com", confirmed))
        assertEquals(true, ConfirmationMode.ALLOW_CONFIRMED_DOMAINS.autoDecision("example.com", confirmed))
        assertEquals(null, ConfirmationMode.ALLOW_CONFIRMED_DOMAINS.autoDecision("openai.com", confirmed))
        assertEquals(true, ConfirmationMode.ALLOW_ALL_SESSION.autoDecision("openai.com", confirmed))
        assertEquals(false, ConfirmationMode.DENY_ALL.autoDecision("example.com", confirmed))
    }

    @Test
    fun shouldSuppressConfirmationCardForAutoResolvingModes() {
        val confirmed = setOf("example.com")

        // ASK_EVERY_TIME never suppresses — the card stays for a manual tap.
        assertFalse(shouldSuppressConfirmationCard(ConfirmationMode.ASK_EVERY_TIME, "example.com", confirmed))
        // ALLOW_CONFIRMED_DOMAINS suppresses only for already-confirmed domains.
        assertTrue(shouldSuppressConfirmationCard(ConfirmationMode.ALLOW_CONFIRMED_DOMAINS, "example.com", confirmed))
        assertFalse(shouldSuppressConfirmationCard(ConfirmationMode.ALLOW_CONFIRMED_DOMAINS, "openai.com", confirmed))
        // ALLOW_ALL_SESSION and DENY_ALL always auto-resolve, so the card is always suppressed.
        assertTrue(shouldSuppressConfirmationCard(ConfirmationMode.ALLOW_ALL_SESSION, "any.host", confirmed))
        assertTrue(shouldSuppressConfirmationCard(ConfirmationMode.DENY_ALL, "any.host", confirmed))
    }

    @Test
    fun confirmationApproveDisplaysTerminalResultWhenRuntimeCompletesAfterResume() = runBlocking {
        val result = postRuntimeConfirmation(
            runtime = FlowAgentRuntime(
                events = emptyList(),
                beforeEvent = {},
                confirmationResult = ConfirmationResumeResult.Accepted,
                runtimeResult = firstPartyResult(RuntimeRunStatus.COMPLETED, "fetched ok"),
            ),
            pendingId = "pending-1",
            requestId = "confirm-1",
            approve = true,
            runId = RUN_ID,
        )

        assertEquals("COMPLETED", result.status)
        assertTrue(result.terminal)
        val transcript = result.transcript as SteeringTranscript.Runtime
        assertEquals("Runtime result after confirmation", transcript.title)
        assertEquals("fetched ok", transcript.body)
    }

    @Test
    fun confirmationDoesNotPromoteCompletedWithoutSucceeded() = runBlocking {
        val outcomes = listOf(
            RuntimeCompletionOutcome.PARTIAL,
            RuntimeCompletionOutcome.BLOCKED,
            RuntimeCompletionOutcome.FAILED,
            RuntimeCompletionOutcome.UNKNOWN,
        )
        outcomes.forEach { outcome ->
            val result = postRuntimeConfirmation(
                runtime = FlowAgentRuntime(
                    events = emptyList(),
                    beforeEvent = {},
                    confirmationResult = ConfirmationResumeResult.Accepted,
                    runtimeResult = firstPartyResult(RuntimeRunStatus.COMPLETED, "partial body", outcome),
                ),
                pendingId = "pending-1",
                requestId = "confirm-1",
                approve = true,
                runId = RUN_ID,
            )
            assertTrue(outcome.name, result.terminal)
            val transcript = result.transcript as SteeringTranscript.Error
            assertEquals("Runtime result after confirmation", transcript.title)
            assertEquals("partial body", transcript.message)
        }
    }

    @Test
    fun recoverPendingConfirmationUsesResumeWaitingSnapshot() = runBlocking {
        val pending = PendingConfirmation(
            pendingId = "run-1:confirmation:2",
            sessionId = "session-1",
            runId = RUN_ID,
            expiresAtEpochMillis = 1_000,
            idempotencyKey = "run-1:confirmation:2:resume",
        )
        val recovered = recoverPendingConfirmation(
            runtime = FlowAgentRuntime(
                events = emptyList(),
                beforeEvent = {},
                runSnapshot = RuntimeRunSnapshot(
                    runId = RUN_ID,
                    sessionId = "session-1",
                    status = RuntimeRunStatus.WAITING_CONFIRMATION,
                    updatedAtEpochMillis = 1_000,
                    pendingConfirmationId = pending.pendingId,
                ),
                pendingConfirmation = pending,
                runtimeHandoff = RuntimeHandoff(
                    runId = RUN_ID,
                    sessionId = "session-1",
                    summary = "Run is waiting for host confirmation.",
                    nextActions = listOf("Approve web_fetch for https://www.w3.org/TR/WCAG22/?"),
                ),
            ),
            runId = RUN_ID,
        )

        assertEquals(pending, recovered?.pending)
        assertEquals("www.w3.org", recovered?.domain)
    }

    @Test
    fun confirmationNotFoundMapsToVisibleStatus() = runBlocking {
        val result = postRuntimeConfirmation(
            runtime = FlowAgentRuntime(
                events = emptyList(),
                beforeEvent = {},
                confirmationResult = ConfirmationResumeResult.NotFound,
            ),
            pendingId = "pending-missing",
            requestId = "confirm-2",
            approve = false,
        )

        assertEquals("Confirmation missing", result.status)
        val transcript = result.transcript as SteeringTranscript.Status
        assertEquals("Confirmation was not found.", transcript.message)
    }

    private fun runtime(secondEventReached: CountDownLatch): AgentRuntime =
        runtime { secondEventReached.countDown() }

    private fun runtime(beforeSecondEvent: () -> Unit): AgentRuntime =
        FlowAgentRuntime(
            events = listOf(
                AgentEvent.Started(RUN_ID),
                AgentEvent.TextDelta(RUN_ID, "hello"),
                AgentEvent.ResultAvailable(RuntimeResult(RUN_ID, RuntimeRunStatus.COMPLETED, "done")),
            ),
            beforeEvent = { index -> if (index == 1) beforeSecondEvent() },
        )

    private fun slowOrderedHandler(
        uiStarted: CountDownLatch,
        releaseUi: CountDownLatch,
        handled: MutableList<String>,
    ): (AgentEvent) -> Unit = { event ->
        handled += event::class.java.simpleName
        if (event is AgentEvent.Started) {
            uiStarted.countDown()
            assertTrue(releaseUi.await(2, TimeUnit.SECONDS))
        }
    }

    private fun evidenceRecorder(): RunEvidenceRecorder =
        RunEvidenceRecorder(
            evidenceDir = Files.createTempDirectory("debug-runtime-run-collection-").toFile(),
            request = REQUEST,
            providerLabel = "OpenAI",
            streamLabel = "non-stream",
            backendLabel = "live",
        )

    private class FlowAgentRuntime(
        private val events: List<AgentEvent>,
        private val beforeEvent: (Int) -> Unit,
        private val postResult: PostUserInputResult = PostUserInputResult.NotFound,
        private val onPost: (PostUserInputRequest) -> Unit = {},
        private val confirmationResult: ConfirmationResumeResult = ConfirmationResumeResult.NotFound,
        private val onConfirmation: (ConfirmationDecision) -> Unit = {},
        private val runtimeResult: RuntimeResult? = null,
        private val runSnapshot: RuntimeRunSnapshot? = null,
        private val pendingConfirmation: PendingConfirmation? = null,
        private val runtimeHandoff: RuntimeHandoff? = null,
        private val onFlowFinally: () -> Unit = {},
    ) : AgentRuntime {
        override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
            try {
                events.forEachIndexed { index, event ->
                    beforeEvent(index)
                    emit(event)
                }
            } finally {
                onFlowFinally()
            }
        }

        override suspend fun resumeConfirmation(decision: ConfirmationDecision): ConfirmationResumeResult {
            onConfirmation(decision)
            return confirmationResult
        }

        override suspend fun getPendingConfirmation(pendingId: String): PendingConfirmation? =
            pendingConfirmation?.takeIf { it.pendingId == pendingId }

        override suspend fun cancelPendingConfirmation(request: CancelPendingConfirmationRequest): CancelPendingResult =
            error("unused")

        override suspend fun getRunState(runId: String): RuntimeRunSnapshot? = runSnapshot?.takeIf { it.runId == runId }

        override suspend fun getRuntimeResult(runId: String): RuntimeResult? = runtimeResult

        override suspend fun getRuntimeHandoff(runId: String): RuntimeHandoff? =
            runtimeHandoff?.takeIf { it.runId == runId }

        override suspend fun getOpenConfusion(runId: String): OpenConfusion? = null

        override suspend fun resumeUserInput(request: AgentRequest): RuntimeResult? = null

        override suspend fun postUserInput(request: PostUserInputRequest): PostUserInputResult {
            onPost(request)
            return postResult
        }

        override suspend fun resolveRuntimeProtocolRequest(
            decision: ProtocolRequestResolution,
        ): ProtocolRequestCloseLoopResult = error("unused")

        override suspend fun rejectRuntimeProtocolRequest(
            decision: ProtocolRequestRejection,
        ): ProtocolRequestCloseLoopResult = error("unused")

        override suspend fun cancelRuntimeProtocolRequest(
            decision: ProtocolRequestCancellation,
        ): ProtocolRequestCloseLoopResult = error("unused")

        override suspend fun getPendingProtocolRequests(
            sessionId: String?,
            runId: String?,
        ): List<RuntimeProtocolRequest> = emptyList()

        override suspend fun getRuntimeEvidence(evidenceId: String): RuntimeEvidence? = null

        override suspend fun getRuntimeTraceEvents(runId: String): List<RuntimeTraceEvent> = emptyList()
    }

    private companion object {
        const val RUN_ID = "run-1"
        val REQUEST = AgentRequest(
            requestId = "debug-1",
            sessionId = "session-1",
            input = "hello",
            scene = "debug-console",
        )

        fun firstPartyResult(
            status: RuntimeRunStatus,
            response: String,
            outcome: RuntimeCompletionOutcome = RuntimeCompletionOutcome.SUCCEEDED,
        ): RuntimeResult = RuntimeResult(
            RUN_ID,
            status,
            response,
            completion = RuntimeCompletionSummary(outcome = outcome),
        )
    }
}
