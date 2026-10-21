package cc.noharry.bothy.sample

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal object DebugRunEvidenceJson {
    fun write(evidence: DebugRunEvidence): String {
        val replay = DebugRunReplayVerifier.verify(evidence)
        return buildJsonObject {
            put("schemaVersion", DEBUG_RUN_EVIDENCE_SCHEMA_VERSION)
            put("requestId", evidence.requestId)
            put("runId", evidence.runId)
            put("sessionId", evidence.sessionId)
            put("scene", evidence.scene)
            put("inputLength", evidence.inputLength)
            put("inputPreview", evidence.inputPreview)
            put("provider", evidence.provider)
            put("stream", evidence.stream)
            put("backend", evidence.backend)
            put("finalStatus", evidence.finalStatus)
            put("finalCompletionOutcome", evidence.finalCompletionOutcome)
            put("finalErrorCode", evidence.finalErrorCode)
            put("failureEventCode", evidence.failureEventCode)
            put("finalResponse", evidence.finalResponse)
            put("requiresBusinessAction", evidence.requiresBusinessAction)
            put("counts", evidence.counts.toJson())
            put("eventDispatch", evidence.eventDispatch.toJson())
            put("memory", evidence.memory.toJson())
            put("loopProbes", evidence.loopProbes.toJson())
            put("tools", buildJsonArray { evidence.tools.forEach { add(it.toJson()) } })
            put("replay", replay.toJson())
        }.toString()
    }

    fun read(file: File): DebugRunEvidence = parse(file.readText(Charsets.UTF_8))

    fun parse(json: String): DebugRunEvidence {
        val root = Json.parseToJsonElement(json).jsonObject
        val tools = (root["tools"] as? JsonArray).orEmpty().mapNotNull { element ->
            (element as? JsonObject)?.toToolEvidence()
        }
        return DebugRunEvidence(
            requestId = root.string("requestId"),
            runId = root.string("runId"),
            sessionId = root.string("sessionId"),
            scene = root.string("scene"),
            inputLength = root.int("inputLength"),
            inputPreview = root.string("inputPreview"),
            provider = root.string("provider"),
            stream = root.string("stream"),
            backend = root.string("backend"),
            finalStatus = root.stringOrNull("finalStatus"),
            finalCompletionOutcome = root.stringOrNull("finalCompletionOutcome"),
            finalErrorCode = root.stringOrNull("finalErrorCode"),
            failureEventCode = root.stringOrNull("failureEventCode"),
            finalResponse = root.string("finalResponse"),
            tools = tools,
            requiresBusinessAction = root.boolean("requiresBusinessAction"),
            counts = DebugRunEvidenceCounts.from(tools),
            eventDispatch = root.jsonObjectOrNull("eventDispatch")?.toEventDispatchEvidence()
                ?: DebugRuntimeEventDispatchEvidence(),
            memory = root.jsonObjectOrNull("memory")?.toMemoryEvidence()
                ?: DebugRuntimeMemoryEvidence(),
            loopProbes = root.jsonObjectOrNull("loopProbes")?.toLoopProbeEvidence()
                ?: DebugLoopProbeEvidence(),
        )
    }
}

private fun DebugLoopProbeEvidence.toJson(): JsonObject =
    buildJsonObject {
        put("emptyCompletionObservedCount", emptyCompletionObservedCount)
        put("emptyCompletionContinueCount", emptyCompletionContinueCount)
        put("lastEmptyCompletionKind", lastEmptyCompletionKind)
        put("lastEmptyCompletionContinued", lastEmptyCompletionContinued?.let { JsonPrimitive(it) } ?: JsonNull)
        put("lastEmptyCompletionModelRound", lastEmptyCompletionModelRound?.let { JsonPrimitive(it) } ?: JsonNull)
        put("lastEmptyCompletionTextChars", lastEmptyCompletionTextChars?.let { JsonPrimitive(it) } ?: JsonNull)
        put("lastEmptyCompletionThinkingChars", lastEmptyCompletionThinkingChars?.let { JsonPrimitive(it) } ?: JsonNull)
        put("lastHadStateAdvancingWork", lastHadStateAdvancingWork?.let { JsonPrimitive(it) } ?: JsonNull)
        put("lastSuccessfulBackendToolCount", lastSuccessfulBackendToolCount?.let { JsonPrimitive(it) } ?: JsonNull)
        put("observations", buildJsonArray { observations.forEach { add(it.toJson()) } })
    }

private fun DebugEmptyCompletionObservation.toJson(): JsonObject =
    buildJsonObject {
        put("modelRound", modelRound)
        put("kind", kind)
        put("continued", continued)
        put("continueCount", continueCount)
        put("textChars", textChars)
        put("thinkingChars", thinkingChars)
        put("hadStateAdvancingWork", hadStateAdvancingWork)
        put("successfulBackendToolCount", successfulBackendToolCount)
    }

private fun JsonObject.toLoopProbeEvidence(): DebugLoopProbeEvidence {
    val observations = jsonArrayOrEmpty("observations").mapNotNull { element ->
        (element as? JsonObject)?.toEmptyCompletionObservation()
    }
    return DebugLoopProbeEvidence(
        emptyCompletionObservedCount = intOrNull("emptyCompletionObservedCount") ?: observations.size,
        emptyCompletionContinueCount = intOrNull("emptyCompletionContinueCount")
            ?: observations.count { it.continued },
        lastEmptyCompletionKind = stringOrNull("lastEmptyCompletionKind"),
        lastEmptyCompletionContinued = booleanOrNull("lastEmptyCompletionContinued"),
        lastEmptyCompletionModelRound = intOrNull("lastEmptyCompletionModelRound"),
        lastEmptyCompletionTextChars = intOrNull("lastEmptyCompletionTextChars"),
        lastEmptyCompletionThinkingChars = intOrNull("lastEmptyCompletionThinkingChars"),
        lastHadStateAdvancingWork = booleanOrNull("lastHadStateAdvancingWork"),
        lastSuccessfulBackendToolCount = intOrNull("lastSuccessfulBackendToolCount"),
        observations = observations,
    )
}

private fun JsonObject.toEmptyCompletionObservation(): DebugEmptyCompletionObservation =
    DebugEmptyCompletionObservation(
        modelRound = int("modelRound"),
        kind = string("kind"),
        continued = boolean("continued"),
        continueCount = int("continueCount"),
        textChars = int("textChars"),
        thinkingChars = int("thinkingChars"),
        hadStateAdvancingWork = boolean("hadStateAdvancingWork"),
        successfulBackendToolCount = int("successfulBackendToolCount"),
    )

private fun JsonObject.booleanOrNull(name: String): Boolean? =
    this[name]?.jsonPrimitive?.booleanOrNull

private fun JsonObject.intOrNull(name: String): Int? =
    this[name]?.jsonPrimitive?.intOrNull

private fun DebugRuntimeEventDispatchEvidence.toJson(): JsonObject =
    buildJsonObject {
        put("eventCount", eventCount)
        put("maxQueueDepth", maxQueueDepth)
        put("enqueueDurationMs", enqueueDurationMs)
        put("handleDurationMs", handleDurationMs)
        put("maxHandleDurationMs", maxHandleDurationMs)
    }

private fun DebugRuntimeMemoryEvidence.toJson(): JsonObject =
    buildJsonObject {
        put("activeCount", activeCount)
        put("pendingCount", pendingCount)
        put("active", buildJsonArray { active.forEach { add(it.toJson()) } })
        put("pending", buildJsonArray { pending.forEach { add(it.toJson()) } })
    }

private fun DebugLessonEntry.toJson(): JsonObject =
    buildJsonObject {
        put("scene", scene)
        put("content", content)
        put("confidence", confidence)
        put("hitCount", hitCount)
    }

private fun DebugRunEvidenceCounts.toJson(): JsonObject =
    buildJsonObject {
        put("publicStarted", publicStarted)
        put("publicFinished", publicFinished)
        put("rawPre", rawPre)
        put("rawPost", rawPost)
        put("pendingPublicTools", pendingPublicTools)
        put("rawHookGaps", rawHookGaps)
        put("failedRawTools", failedRawTools)
        put("orphanPublicFinishes", orphanPublicFinishes)
        put("meaningfulBusinessTools", meaningfulBusinessTools)
        put("readDurationMs", readDurationMs)
        put("searchDurationMs", searchDurationMs)
        put("backendDurationMs", backendDurationMs)
        put("recorderGapMs", recorderGapMs)
    }

private fun DebugRunToolEvidence.toJson(): JsonObject =
    buildJsonObject {
        put("sequence", sequence)
        put("toolCallId", toolCallId)
        put("toolName", toolName)
        put("category", category)
        put("publicStarted", publicStarted)
        put("publicFinished", publicFinished)
        put("rawPre", rawPre)
        put("rawPost", rawPost)
        put("rawPostStatus", rawPostStatus)
        put("publicStartedAtEpochMillis", publicStartedAtEpochMillis)
        put("publicFinishedAtEpochMillis", publicFinishedAtEpochMillis)
        put("rawPreAtEpochMillis", rawPreAtEpochMillis)
        put("rawPostAtEpochMillis", rawPostAtEpochMillis)
        put("publicDurationMs", publicDurationMs)
        put("rawDurationMs", rawDurationMs)
        put("recorderGapMs", recorderGapMs)
    }

private fun DebugRunReplayResult.toJson(): JsonObject =
    buildJsonObject {
        put("passed", passed)
        put("summary", summary)
        put("failures", buildJsonArray { failures.forEach { addString(it) } })
        put("warnings", buildJsonArray { warnings.forEach { addString(it) } })
    }

private fun JsonObject.toToolEvidence(): DebugRunToolEvidence =
    DebugRunToolEvidence(
        sequence = int("sequence"),
        toolCallId = string("toolCallId"),
        toolName = string("toolName"),
        category = string("category").ifBlank { string("toolName").debugToolCategoryForJson() },
        publicStarted = boolean("publicStarted"),
        publicFinished = boolean("publicFinished"),
        rawPre = boolean("rawPre"),
        rawPost = boolean("rawPost"),
        rawPostStatus = string("rawPostStatus").ifBlank { RAW_STATUS_MISSING },
        publicStartedAtEpochMillis = long("publicStartedAtEpochMillis"),
        publicFinishedAtEpochMillis = long("publicFinishedAtEpochMillis"),
        rawPreAtEpochMillis = long("rawPreAtEpochMillis"),
        rawPostAtEpochMillis = long("rawPostAtEpochMillis"),
        publicDurationMs = long("publicDurationMs"),
        rawDurationMs = long("rawDurationMs"),
        recorderGapMs = long("recorderGapMs"),
    )

private fun JsonObject.toEventDispatchEvidence(): DebugRuntimeEventDispatchEvidence =
    DebugRuntimeEventDispatchEvidence(
        eventCount = int("eventCount"),
        maxQueueDepth = int("maxQueueDepth"),
        enqueueDurationMs = long("enqueueDurationMs"),
        handleDurationMs = long("handleDurationMs"),
        maxHandleDurationMs = long("maxHandleDurationMs"),
    )

private fun JsonObject.toMemoryEvidence(): DebugRuntimeMemoryEvidence {
    val active = jsonArrayOrEmpty("active").mapNotNull { (it as? JsonObject)?.toLessonEntry() }
    val pending = jsonArrayOrEmpty("pending").mapNotNull { (it as? JsonObject)?.toLessonEntry() }
    return DebugRuntimeMemoryEvidence(
        activeCount = int("activeCount").takeIf { it > 0 } ?: active.size,
        pendingCount = int("pendingCount").takeIf { it > 0 } ?: pending.size,
        active = active,
        pending = pending,
    )
}

private fun JsonObject.toLessonEntry(): DebugLessonEntry =
    DebugLessonEntry(
        scene = string("scene"),
        content = string("content"),
        confidence = float("confidence"),
        hitCount = int("hitCount"),
    )

private fun JsonObject.string(name: String): String =
    stringOrNull(name).orEmpty()

private fun JsonObject.stringOrNull(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it != "null" }

private fun JsonObject.jsonObjectOrNull(name: String): JsonObject? =
    this[name] as? JsonObject

private fun JsonObject.jsonArrayOrEmpty(name: String): JsonArray =
    this[name] as? JsonArray ?: JsonArray(emptyList())

private fun JsonObject.boolean(name: String): Boolean =
    this[name]?.jsonPrimitive?.booleanOrNull ?: false

private fun JsonObject.int(name: String): Int =
    this[name]?.jsonPrimitive?.intOrNull ?: 0

private fun JsonObject.float(name: String): Float =
    this[name]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f

private fun JsonObject.long(name: String): Long =
    this[name]?.jsonPrimitive?.longOrNull ?: 0L

private fun JsonArrayBuilder.addString(value: String) {
    add(JsonPrimitive(value))
}

private fun String.debugToolCategoryForJson(): String =
    when {
        this == "read_file" || this == "list_dir" -> DEBUG_TOOL_CATEGORY_READ
        this == "grep" -> DEBUG_TOOL_CATEGORY_SEARCH
        startsWith("mcp__") || startsWith("command_") -> DEBUG_TOOL_CATEGORY_BACKEND
        this == "write_file" || this == "edit_file" -> DEBUG_TOOL_CATEGORY_WRITE
        this == "update_todo" -> DEBUG_TOOL_CATEGORY_TODO
        this == "finish" -> DEBUG_TOOL_CATEGORY_TERMINAL
        else -> DEBUG_TOOL_CATEGORY_INTERNAL
    }
