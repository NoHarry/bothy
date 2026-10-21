@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import cc.noharry.bothy.kernel.api.SessionAttachmentRef
import cc.noharry.bothy.kernel.api.SessionEvent
import cc.noharry.bothy.kernel.api.SessionEventPayload
import cc.noharry.bothy.kernel.api.SessionEventType
import cc.noharry.bothy.kernel.api.SessionJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrajectoryProjectorTest {

    @Test
    fun everyPersistedEventTypeMapsToAStableKindAndLane() {
        val expected = mapOf(
            SessionEventType.RUN_STARTED to (TrajectoryKind.RUN_START to TrajectoryLane.INPUT),
            SessionEventType.USER_MESSAGE to (TrajectoryKind.INPUT to TrajectoryLane.INPUT),
            SessionEventType.USER_INTERACTION to (TrajectoryKind.STATUS to TrajectoryLane.SYSTEM),
            SessionEventType.MODEL_REQUEST to (TrajectoryKind.MODEL_CALL to TrajectoryLane.MODEL),
            SessionEventType.ASSISTANT_CHUNK to (TrajectoryKind.MODEL_MESSAGE to TrajectoryLane.MODEL),
            SessionEventType.ASSISTANT_MESSAGE to (TrajectoryKind.MODEL_MESSAGE to TrajectoryLane.MODEL),
            SessionEventType.MODEL_TOOL_CALL to (TrajectoryKind.MODEL_TOOL to TrajectoryLane.MODEL),
            SessionEventType.TOOL_STARTED to (TrajectoryKind.TOOL_START to TrajectoryLane.TOOLS),
            SessionEventType.TOOL_RESULT to (TrajectoryKind.TOOL_FINISH to TrajectoryLane.TOOLS),
            SessionEventType.USAGE to (TrajectoryKind.USAGE to TrajectoryLane.MODEL),
            SessionEventType.MODEL_RESPONSE_METADATA to (TrajectoryKind.MODEL_CALL to TrajectoryLane.MODEL),
            SessionEventType.MODEL_CALL_FINISHED to (TrajectoryKind.MODEL_CALL to TrajectoryLane.MODEL),
            SessionEventType.RUN_STATUS to (TrajectoryKind.STATUS to TrajectoryLane.SYSTEM),
            SessionEventType.RUN_FINISHED to (TrajectoryKind.RUN_END to TrajectoryLane.SYSTEM),
        )

        assertEquals(SessionEventType.entries.toSet(), expected.keys)
        SessionEventType.entries.forEach { type ->
            val record = sessionEvent(type = type).toTrajectoryRecord()
            assertEquals(expected.getValue(type).first, record.kind)
            assertEquals(expected.getValue(type).second, record.lane)
        }
    }

    @Test
    fun thinkingChunkUsesTheThinkingKind() {
        val record = sessionEvent(
            type = SessionEventType.ASSISTANT_CHUNK,
            safeMetadata = mapOf("kind" to "thinking", "text" to "plan first"),
        ).toTrajectoryRecord()

        assertEquals(TrajectoryKind.THINKING, record.kind)
        assertEquals("plan first", record.rich?.thinkingText)
    }

    @Test
    fun persistedRecordKeepsIdentitySequenceAndPersistedTimeQuality() {
        val record = sessionEvent(
            eventId = "evt-7",
            sequence = 7,
            recordedAtEpochMillis = 1_700L,
            type = SessionEventType.TOOL_STARTED,
            safeMetadata = mapOf("callId" to "run-1:call-a", "toolName" to "web_search", "source" to "BACKEND"),
            sourceEventSequences = listOf(1L),
        ).toTrajectoryRecord()

        assertEquals(TrajectoryRecordId("event:evt-7"), record.id)
        assertEquals(7L, record.sequence)
        assertEquals(1_700L, record.occurredAtEpochMillis)
        assertEquals(TrajectoryTimeQuality.PERSISTED_RECORDED, record.timeQuality)
        assertEquals(setOf(TrajectorySource.EVENT_LOG), record.sources)
        assertEquals("call-a", record.correlation.toolCallId)
        assertEquals(listOf(1L), record.sourceEventSequences)
        assertFalse(record.isLive)
        assertEquals(TrajectoryRichAvailability.AVAILABLE, record.richAvailability)
        assertNotNull(record.rich)
    }

    @Test
    fun modelRequestAndToolFactsStayDistinctRows() {
        val request = sessionEvent(
            eventId = "req",
            sequence = 2,
            type = SessionEventType.MODEL_REQUEST,
            safeMetadata = mapOf("modelRef" to "deepseek"),
        ).toTrajectoryRecord()
        val modelTool = sessionEvent(
            eventId = "model-tool",
            sequence = 3,
            type = SessionEventType.MODEL_TOOL_CALL,
            safeMetadata = mapOf("callId" to "call-a", "toolName" to "read_file"),
        ).toTrajectoryRecord()
        val started = sessionEvent(
            eventId = "tool-start",
            sequence = 4,
            type = SessionEventType.TOOL_STARTED,
            safeMetadata = mapOf("callId" to "call-a", "toolName" to "read_file"),
        ).toTrajectoryRecord()

        assertEquals(TrajectoryKind.MODEL_CALL, request.kind)
        assertEquals(TrajectoryKind.MODEL_TOOL, modelTool.kind)
        assertEquals(TrajectoryKind.TOOL_START, started.kind)
        assertEquals(TrajectoryLane.MODEL, modelTool.lane)
        assertEquals("call-a", modelTool.correlation.toolCallId)
        assertEquals("call-a", started.correlation.toolCallId)
        assertEquals("event:req", request.id.value)
        assertEquals("event:model-tool", modelTool.id.value)
        assertEquals("event:tool-start", started.id.value)
    }

    @Test
    fun inspectorTabsAreTypeSpecificAndAlwaysIncludeSummary() {
        val user = sessionEvent(type = SessionEventType.USER_MESSAGE).toTrajectoryRecord()
        val tool = sessionEvent(
            type = SessionEventType.TOOL_STARTED,
            safeMetadata = mapOf("callId" to "c1", "toolName" to "read_file"),
        ).toTrajectoryRecord()
        val request = sessionEvent(type = SessionEventType.MODEL_REQUEST).toTrajectoryRecord()

        assertTrue(inspectorTabsFor(user).containsAll(listOf(
            TrajectoryInspectorTab.SUMMARY,
            TrajectoryInspectorTab.PREVIEW,
            TrajectoryInspectorTab.RAW,
        )))
        assertTrue(inspectorTabsFor(tool).contains(TrajectoryInspectorTab.PAYLOAD))
        assertFalse(inspectorTabsFor(tool).contains(TrajectoryInspectorTab.REQUEST))
        assertTrue(inspectorTabsFor(request).contains(TrajectoryInspectorTab.REQUEST))
        assertFalse(inspectorTabsFor(user).contains(TrajectoryInspectorTab.PAYLOAD))
    }

    @Test
    fun rawPaginationNeverBuildsOneUnboundedPage() {
        val pages = paginateRawText("a".repeat(9_000), pageSize = 4_096)

        assertEquals(3, pages.size)
        assertEquals(4_096, pages[0].length)
        assertEquals(808, pages[2].length)
    }

    @Test
    fun readFailureRendersAnExplicitErrorBoundaryCarryingOnlyAReasonCode() {
        val record = loadErrorRecord("s1", "run-1", TrajectoryEventLogReason.REPLAY_FAILED)

        assertEquals(TrajectoryKind.LOAD_ERROR, record.kind)
        assertEquals(TrajectoryRecordId("error:s1:run-1"), record.id)
        assertEquals(TrajectoryLane.SYSTEM, record.lane)
        assertNull(record.sequence)
        assertTrue(record.sources.contains(TrajectorySource.SYNTHETIC_BOUNDARY))
        assertEquals(TrajectoryEventLogReason.REPLAY_FAILED, record.safeMetadata["reason"])
        assertNull(record.rich)
    }

    @Test
    fun modelRequestRawKeepsTheFullEnvelopeAndUntruncatedPayload() {
        val longPrompt = "x".repeat(TRAJECTORY_RICH_PREVIEW_CHARS + 500)
        val record = SessionEvent(
            schemaVersion = 2,
            eventId = "req-raw",
            sessionId = "s1",
            runId = "run-1",
            requestId = "req-1",
            sequence = 2,
            recordedAtEpochMillis = 1_000L,
            type = SessionEventType.MODEL_REQUEST,
            payload = SessionEventPayload.ModelRequest(
                messages = SessionJson.Array(
                    listOf(SessionJson.Object(mapOf("role" to SessionJson.Text("user"), "content" to SessionJson.Text(longPrompt)))),
                ),
                systemPrompt = "be helpful",
                tools = SessionJson.Array(listOf(SessionJson.Object(mapOf("name" to SessionJson.Text("read_file"))))),
                modelRef = "deepseek:chat",
                toolChoice = "auto",
                promptCacheKey = "cache-1",
                options = SessionJson.Object(mapOf("temperature" to SessionJson.Number("0.2"))),
                attachments = listOf(
                    SessionAttachmentRef(sha256 = "abc123", mediaType = "image/png", byteSize = 4, logicalName = "/tmp/a.png"),
                ),
            ),
            sourceEventSequences = listOf(1L),
        ).toTrajectoryRecord()

        val raw = record.rich?.rawText.orEmpty()
        assertTrue(raw.contains("schemaVersion=2"))
        assertTrue(raw.contains("eventId=req-raw"))
        assertTrue(raw.contains("type=MODEL_REQUEST"))
        assertTrue(raw.contains("toolChoice=auto"))
        assertTrue(raw.contains("promptCacheKey=cache-1"))
        assertTrue(raw.contains("temperature"))
        assertTrue(raw.contains("attachments="))
        assertTrue(raw.contains("abc123"))
        assertTrue(raw.contains(longPrompt))
        assertTrue(raw.length > TRAJECTORY_RICH_PREVIEW_CHARS)
        assertEquals(TrajectoryRichAvailability.HASH_ONLY, record.richAvailability)
        val pages = paginateRawText(raw)
        assertTrue(pages.size > 1)
        assertEquals(raw, pages.joinToString(""))
    }

    @Test
    fun uniqueToolCallIdsCountOnceAcrossModelAndExecutorRows() {
        val model = sessionEvent(
            eventId = "model-tool",
            sequence = 10,
            type = SessionEventType.MODEL_TOOL_CALL,
            safeMetadata = mapOf("callId" to "call_read", "toolName" to "read_file"),
        ).toTrajectoryRecord()
        val started = sessionEvent(
            eventId = "tool-start",
            sequence = 11,
            runId = "run-1",
            type = SessionEventType.TOOL_STARTED,
            safeMetadata = mapOf("callId" to "run-1:call_read", "toolName" to "read_file"),
        ).toTrajectoryRecord()
        val finished = sessionEvent(
            eventId = "tool-finish",
            sequence = 12,
            type = SessionEventType.TOOL_RESULT,
            safeMetadata = mapOf("callId" to "run-1:call_read", "toolName" to "read_file"),
        ).toTrajectoryRecord()
        val group = TrajectoryRunGroup(
            sessionId = "s1",
            runId = "run-1",
            records = listOf(model, started, finished),
        )

        assertEquals(TrajectoryKind.MODEL_TOOL, model.kind)
        assertEquals(TrajectoryKind.TOOL_START, started.kind)
        assertEquals("call_read", model.correlation.toolCallId)
        assertEquals("call_read", started.correlation.toolCallId)
        assertEquals("call_read", finished.correlation.toolCallId)
        assertEquals(1, group.toolCallCount)
    }

    @Test
    fun searchActivityMetadataProjectsToTheSearchKind() {
        val record = SessionEvent(
            schemaVersion = 2,
            eventId = "search-1",
            sessionId = "s1",
            runId = "run-1",
            requestId = "req-1",
            sequence = 4,
            recordedAtEpochMillis = 1_000L,
            type = SessionEventType.MODEL_RESPONSE_METADATA,
            payload = SessionEventPayload.ModelResponseMetadata(
                fields = SessionJson.Object(
                    mapOf(
                        "kind" to SessionJson.Text(SEARCH_ACTIVITY_KIND),
                        "provider" to SessionJson.Text("OpenAI"),
                        "queryPreview" to SessionJson.Text("latest sdk"),
                        "resultCount" to SessionJson.Number("2"),
                    ),
                ),
            ),
            sourceEventSequences = emptyList(),
        ).toTrajectoryRecord()

        assertEquals(TrajectoryKind.SEARCH, record.kind)
        assertEquals(TrajectoryLane.TOOLS, record.lane)
        assertEquals("Search activity", record.title)
        assertTrue(
            "raw=${record.rich?.rawText}",
            record.rich?.rawText.orEmpty().contains("search_activity"),
        )
        assertEquals("OpenAI", record.safeMetadata["provider"])
    }

    @Test
    fun repositoryNeverUpgradesUnknownRuntimeHealth() {
        val health = composeTrajectoryHealth(
            runtimeHealth = null,
            projection = CanonicalProjection(emptyList(), hasSequenceGap = false, hasSequenceConflict = false),
            richAvailability = TrajectoryRichAvailability.AVAILABLE,
        )
        assertEquals(
            cc.noharry.bothy.kernel.api.SessionEventLogHealthStatus.UNKNOWN,
            health.eventLogStatus,
        )
        assertFalse(health.hasLiveOnlyRecords)
    }
}

internal fun sessionEvent(
    eventId: String = "evt-1",
    sessionId: String = "s1",
    runId: String = "run-1",
    requestId: String = "req-1",
    sequence: Long = 1L,
    recordedAtEpochMillis: Long = 1_000L,
    type: SessionEventType = SessionEventType.RUN_STARTED,
    safeMetadata: Map<String, String> = emptyMap(),
    @Suppress("UNUSED_PARAMETER") contentHashes: Map<String, String> = emptyMap(),
    sourceEventSequences: List<Long> = emptyList(),
): SessionEvent = SessionEvent(
    schemaVersion = 2,
    eventId = eventId,
    sessionId = sessionId,
    runId = runId,
    requestId = requestId,
    sequence = sequence,
    recordedAtEpochMillis = recordedAtEpochMillis,
    type = type,
    payload = payloadFor(type, safeMetadata),
    sourceEventSequences = sourceEventSequences,
)

private fun payloadFor(
    type: SessionEventType,
    metadata: Map<String, String>,
): SessionEventPayload = when (type) {
    SessionEventType.USER_MESSAGE -> SessionEventPayload.UserMessage(text = metadata["text"].orEmpty())
    SessionEventType.USER_INTERACTION -> SessionEventPayload.UserInteraction("INPUT", "RECEIVED", text = metadata["text"].orEmpty())
    SessionEventType.ASSISTANT_CHUNK -> SessionEventPayload.AssistantChunk(
        kind = metadata["kind"] ?: "text",
        text = metadata["text"].orEmpty(),
    )
    SessionEventType.ASSISTANT_MESSAGE -> SessionEventPayload.AssistantMessage(
        text = metadata["text"].orEmpty(),
        finishReason = metadata["finishReason"].orEmpty(),
    )
    SessionEventType.MODEL_TOOL_CALL,
    SessionEventType.TOOL_STARTED,
    -> SessionEventPayload.ToolCall(
        toolCallId = metadata["callId"].orEmpty(),
        name = metadata["toolName"].orEmpty(),
        source = metadata["source"].orEmpty(),
    )
    SessionEventType.TOOL_RESULT -> SessionEventPayload.ToolResult(
        toolCallId = metadata["callId"].orEmpty(),
        name = metadata["toolName"].orEmpty(),
        source = metadata["source"].orEmpty(),
        status = metadata["status"].orEmpty(),
    )
    SessionEventType.USAGE -> SessionEventPayload.Usage()
    SessionEventType.MODEL_CALL_FINISHED -> SessionEventPayload.ModelCallFinished(
        callId = metadata["callId"].orEmpty(),
        status = metadata["status"].orEmpty(),
        modelRef = metadata["modelRef"].orEmpty(),
    )
    SessionEventType.MODEL_REQUEST -> SessionEventPayload.ModelRequest(
        modelRef = metadata["modelRef"].orEmpty(),
    )
    SessionEventType.MODEL_RESPONSE_METADATA -> SessionEventPayload.ModelResponseMetadata()
    else -> SessionEventPayload.RunLifecycle(
        phase = metadata["phase"].orEmpty(),
        status = metadata["status"].orEmpty(),
        modelRef = metadata["modelRef"].orEmpty(),
        scene = metadata["scene"].orEmpty(),
        errorCode = metadata["errorCode"].orEmpty(),
        todoCompletion = metadata["todoCompletion"].orEmpty(),
    )
}
