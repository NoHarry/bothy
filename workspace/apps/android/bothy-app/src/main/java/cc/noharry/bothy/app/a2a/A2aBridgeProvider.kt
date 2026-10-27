package cc.noharry.bothy.app.a2a
import cc.noharry.agent.a2a.A2aClient
import cc.noharry.agent.a2a.A2aClientError
import cc.noharry.agent.a2a.A2aMessage
import cc.noharry.agent.a2a.A2aPart
import cc.noharry.agent.a2a.A2aResult
import cc.noharry.agent.a2a.A2aRetryHint
import cc.noharry.agent.a2a.A2aSendMessageRequest
import cc.noharry.agent.a2a.A2aSendMessageResult
import cc.noharry.agent.a2a.A2aTask
import cc.noharry.agent.a2a.A2aValue
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import cc.noharry.agent.a2a.RemoteAgentRef
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolPrefetchProvider
import cc.noharry.agent.kernel.api.ToolPrefetchRequest
import cc.noharry.agent.kernel.api.ToolPrefetchTrace
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.bothy.app.console.status
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Experimental A2A integration, not an MCP server: bridges one enabled A2A profile into the
 * debug model loop as a single `a2a_message_send` tool. It only reuses the [McpClientProvider]
 * host-tool injection SPI so the runtime can mount the tool; no MCP protocol is involved.
 *
 * The tool is always pre-activated (a local hint), every message carries the device's local time and zone in
 * `metadata.context` so the remote agent resolves "明天下午两点" the device's way, and a task the remote agent
 * answers as still working is handed to [delegations] to follow and report once it settles.
 */
@OptIn(ExperimentalA2aClientApi::class, ExperimentalAgentRuntimeApi::class)
internal class A2aBridgeProvider(
    private val profile: A2aProfile,
    private val serverIdComponent: String = sanitizeServerIdComponent(profile.id),
    private val delegations: A2aDelegationSink = A2aDelegations,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clientProvider: () -> A2aClient?,
) : McpClientProvider, ToolPrefetchProvider {
    override val serverId: String = SERVER_ID_PREFIX + serverIdComponent
    override val displayName: String = profile.label + DISPLAY_NAME_SUFFIX
    /** The name the user calls this agent by. */
    val agentLabel: String get() = profile.label
    @OptIn(ExperimentalAgentRuntimeApi::class)
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        // Conservative until both direct and official A2A adapters have reentrancy conformance.
        ProviderConcurrencyDeclaration.serialized()

    override suspend fun listTools(): List<ToolDescriptor> = listOf(
        ToolDescriptor(
            name = TOOL_NAME,
            description = toolDescription(),
            inputSchemaJson = TOOL_INPUT_SCHEMA_JSON,
        ),
    )

    override val remotePrefetchEnabled: Boolean = false

    override fun localToolNames(request: ToolPrefetchRequest): List<String> = listOf(TOOL_NAME)

    override suspend fun prefetchTools(request: ToolPrefetchRequest, trace: ToolPrefetchTrace): List<String> = emptyList()

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult {
        if (toolName != TOOL_NAME) {
            return errorResult(ERROR_UNKNOWN_TOOL, "Unknown A2A bridge tool.", toolName)
        }
        val arguments = parseArguments(argumentsJson)
            ?: return errorResult(ERROR_INVALID_ARGUMENTS, "Arguments must be a JSON object with a non-blank text string.")
        val client = clientProvider()
            ?: return errorResult(ERROR_CLIENT_NOT_LOADED, "A2A client is not loaded for this profile.")
        val result = try {
            client.sendMessage(profile.toRemoteAgentRef(), arguments.toSendMessageRequest())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return errorResult(ERROR_SEND_FAILED, "A2A message send failed unexpectedly.")
        }
        return when (result) {
            is A2aResult.Success -> successResult(result.value, arguments.text, client)
            is A2aResult.Failure -> failureResult(result.error)
        }
    }

    private fun toolDescription(): String =
        "$EXPERIMENTAL_NOTICE Sends an A2A message/send request to the remote agent " +
            "'${profile.label}' (endpoint host: ${endpointHost()}). " +
            profile.description.takeIf(String::isNotBlank)?.let { "It can: $it. " }.orEmpty() +
            "The user may also call it 云端助手 or 个人助手 (their cloud / personal assistant). " +
            "Use it only when the user names this agent or agrees to hand the task to it; background or parallel work " +
            "the user did not hand to it goes to your own subagents (spawn_agent) or is done by you. " +
            "Pass $ARG_CONTEXT_ID or $ARG_TASK_ID from an earlier result to continue that conversation or task. " +
            "The device's local time and time zone are attached automatically. A task still working is followed in the " +
            "background and its result, question or failure comes back later as a 【委托回报】/【委托追问】/【委托失败】 " +
            "message: tell the user it was handed over and finish; never wait or poll." + inFlightNote() + pendingQuestionsNote()

    /**
     * Tasks this agent is still on, with where they stand, so "Grok 那边怎么样了" is answered from the device's own view
     * instead of a guess or a second delegation.
     */
    private fun inFlightNote(): String {
        val open = delegations.inFlight(profile.label).takeLast(MAX_PENDING_QUESTIONS_SHOWN)
        if (open.isEmpty()) return ""
        val now = clock()
        return open.joinToString(separator = " ", prefix = " Still being handled (do not send them again; when asked, say where they stand): ") { view ->
            val minutes = ((now - view.startedAtEpochMillis) / 60_000).coerceAtLeast(0)
            val done = view.steps.count { it.status == "done" }
            val steps = if (view.steps.isEmpty()) "" else ", steps $done/${view.steps.size} done"
            val latest = view.statusText.takeIf(String::isNotBlank)?.let { ", latest 「${it.take(MAX_QUESTION_CHARS)}」" }.orEmpty()
            "taskId=${view.taskId} 「${view.request}」 ${view.stage.label} for $minutes min$steps$latest."
        }
    }

    /** Questions this agent asked that the user has not answered: the answer goes back with that taskId. */
    private fun pendingQuestionsNote(): String {
        val pending = delegations.pendingQuestions(profile.label).takeLast(MAX_PENDING_QUESTIONS_SHOWN)
        if (pending.isEmpty()) return ""
        return pending.joinToString(separator = " ", prefix = " Waiting for the user's answer: ") { question ->
            "taskId=${question.taskId} asked 「${question.question.take(MAX_QUESTION_CHARS)}」."
        } + " When the user answers one of these, send the answer with that taskId."
    }

    private fun endpointHost(): String =
        profile.endpoint.host?.takeIf { it.isNotBlank() } ?: profile.endpoint.toString()

    private fun parseArguments(argumentsJson: String): A2aBridgeArguments? {
        val root = runCatching { Json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull() ?: return null
        if (root.keys.any { it !in ALLOWED_ARGUMENTS }) return null
        val text = root.stringArgument(ARG_TEXT) ?: return null
        val contextId = root.optionalStringArgument(ARG_CONTEXT_ID) ?: return null
        val taskId = root.optionalStringArgument(ARG_TASK_ID) ?: return null
        return A2aBridgeArguments(
            text = text,
            contextId = contextId.value,
            taskId = taskId.value,
        )
    }

    private fun successResult(result: A2aSendMessageResult, text: String, client: A2aClient): ToolCallResult = when (result) {
        is A2aSendMessageResult.Message -> ToolCallResult(success = true, resultJson = messageResultJson(result.value))
        is A2aSendMessageResult.Task -> {
            val task = result.value
            val followed = task.status == A2aTask.Status.SUBMITTED || task.status == A2aTask.Status.WORKING
            if (followed) {
                delegations.track(A2aDelegation(profile.label, task.id, text, clock(), profile.id), client, profile.toRemoteAgentRef())
            }
            ToolCallResult(success = true, resultJson = taskResultJson(task, followed))
        }
    }

    private fun messageResultJson(message: A2aMessage): String = buildJsonObject {
        put(KEY_OUTCOME, OUTCOME_MESSAGE)
        put(KEY_TEXT, message.textContent())
        message.contextId?.let { put(KEY_CONTEXT_ID, it) }
    }.toString()

    private fun taskResultJson(task: A2aTask, followed: Boolean): String = buildJsonObject {
        put(KEY_OUTCOME, OUTCOME_TASK)
        put(KEY_TASK_ID, task.id)
        put(KEY_STATUS, task.status.name)
        task.statusMessage?.textContent()?.takeUnless(String::isBlank)?.let { put(KEY_TEXT, it) }
        task.contextId?.let { put(KEY_CONTEXT_ID, it) }
        if (followed) put(KEY_FOLLOW_UP, FOLLOW_UP_NOTE)
    }.toString()

    private fun failureResult(error: A2aClientError): ToolCallResult = ToolCallResult(
        success = false,
        resultJson = buildJsonObject {
            put(KEY_ERROR, error.reason)
            put(KEY_RETRY_HINT, error.retryHint.name)
        }.toString(),
        // Only CONDITIONAL failures may be worth a blind model retry; NEVER and
        // AFTER_AUTH_REFRESH need host-side changes before retrying makes sense.
        retryable = error.retryHint == A2aRetryHint.CONDITIONAL,
    )

    private fun errorResult(code: String, message: String, toolName: String? = null): ToolCallResult =
        ToolCallResult(
            success = false,
            resultJson = buildJsonObject {
                put(KEY_ERROR, code)
                put(KEY_MESSAGE, message)
                put(KEY_PROFILE_ID, profile.id)
                toolName?.let { put(KEY_TOOL, it) }
            }.toString(),
        )

    private data class A2aBridgeArguments(
        val text: String,
        val contextId: String?,
        val taskId: String?,
    )

    private fun A2aBridgeArguments.toSendMessageRequest(): A2aSendMessageRequest =
        A2aSendMessageRequest(
            message = A2aMessage(
                messageId = UUID.randomUUID().toString(),
                role = A2aMessage.Role.USER,
                parts = listOf(A2aPart.Text(text)),
                contextId = contextId,
                taskId = taskId,
                metadata = A2aValue.ObjectValue(mapOf(METADATA_CONTEXT to carContext())),
            ),
        )

    /** Where and when the device is, so relative times in [A2aBridgeArguments.text] resolve the device's way. */
    private fun carContext(): A2aValue.ObjectValue {
        val zoneId = zone()
        val localTime = Instant.ofEpochMilli(clock()).atZone(zoneId).toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS)
        return A2aValue.ObjectValue(
            mapOf(
                CONTEXT_SOURCE to A2aValue.StringValue(CONTEXT_SOURCE_CAR),
                CONTEXT_TIMEZONE to A2aValue.StringValue(zoneId.id),
                CONTEXT_LOCAL_TIME to A2aValue.StringValue(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(localTime)),
            ),
        )
    }

    private fun A2aProfile.toRemoteAgentRef(): RemoteAgentRef =
        RemoteAgentRef(id = id, endpoint = endpoint)

    private fun A2aMessage.textContent(): String =
        parts.filterIsInstance<A2aPart.Text>().joinToString(TEXT_PART_SEPARATOR) { it.text }
}

/** Builds one bridge provider per enabled profile; disabled profiles stay unmounted. */
@OptIn(ExperimentalA2aClientApi::class)
internal fun appA2aBridgeProviders(
    config: A2aModuleConfig?,
    clientProvider: (String) -> A2aClient?,
): List<A2aBridgeProvider> {
    val profiles = config?.state?.profiles.orEmpty().filter { it.enabled }
    val components = profiles.associateWith { sanitizeServerIdComponent(it.id) }
    val counts = components.values.groupingBy { it }.eachCount()
    return profiles.map { profile ->
        val base = components.getValue(profile)
        val unique = if (counts.getValue(base) == 1) base else "${base}_${stableServerIdSuffix(profile.id)}"
        A2aBridgeProvider(profile, unique) { clientProvider(profile.id) }
    }
}

private fun sanitizeServerIdComponent(raw: String): String =
    raw.lowercase()
        .replace(SERVER_ID_UNSAFE_CHARS, SERVER_ID_REPLACEMENT)
        .trim(SERVER_ID_EDGE_CHAR)
        .ifBlank { SERVER_ID_FALLBACK }

private fun stableServerIdSuffix(raw: String): String =
    MessageDigest.getInstance(SERVER_ID_HASH_ALGORITHM)
        .digest(raw.toByteArray(Charsets.UTF_8))
        .take(SERVER_ID_HASH_BYTES)
        .joinToString(separator = "") { byte ->
            (byte.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(HEX_BYTE_WIDTH, '0')
        }

private fun JsonObject.stringArgument(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.optionalStringArgument(name: String): OptionalStringArgument? {
    val value = this[name] ?: return OptionalStringArgument(null)
    val primitive = value as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return OptionalStringArgument(primitive.content.trim().takeIf { it.isNotEmpty() })
}

private data class OptionalStringArgument(val value: String?)

private val TOOL_INPUT_SCHEMA_JSON = """
    {
      "type": "object",
      "properties": {
        "text": {
          "type": "string",
          "description": "Plain-text user message to send to the remote A2A agent."
        },
        "contextId": {
          "type": "string",
          "description": "Optional A2A context id from an earlier result, to continue that conversation."
        },
        "taskId": {
          "type": "string",
          "description": "Optional A2A task id from an earlier task result, to continue that task."
        }
      },
      "required": ["text"],
      "additionalProperties": false
    }
""".trimIndent()

private const val SERVER_ID_PREFIX = "experimental_a2a_"
private const val SERVER_ID_FALLBACK = "agent"
private const val SERVER_ID_REPLACEMENT = "_"
private const val SERVER_ID_EDGE_CHAR = '_'
private val SERVER_ID_UNSAFE_CHARS = Regex("[^a-z0-9_]+")
private const val SERVER_ID_HASH_ALGORITHM = "SHA-256"
private const val SERVER_ID_HASH_BYTES = 6
private const val BYTE_MASK = 0xff
private const val HEX_RADIX = 16
private const val HEX_BYTE_WIDTH = 2
private const val DISPLAY_NAME_SUFFIX = " (experimental A2A)"
private const val TOOL_NAME = "a2a_message_send"
private const val EXPERIMENTAL_NOTICE = "Experimental A2A client bridge (not an MCP server)."
private const val ARG_TEXT = "text"
private const val ARG_CONTEXT_ID = "contextId"
private const val ARG_TASK_ID = "taskId"
private val ALLOWED_ARGUMENTS = setOf(ARG_TEXT, ARG_CONTEXT_ID, ARG_TASK_ID)
private const val KEY_OUTCOME = "outcome"
private const val KEY_TEXT = "text"
private const val KEY_CONTEXT_ID = "contextId"
private const val KEY_TASK_ID = "taskId"
private const val KEY_STATUS = "status"
private const val KEY_ERROR = "error"
private const val KEY_RETRY_HINT = "retryHint"
private const val KEY_MESSAGE = "message"
private const val KEY_PROFILE_ID = "profileId"
private const val KEY_TOOL = "tool"
private const val MAX_PENDING_QUESTIONS_SHOWN = 3
private const val MAX_QUESTION_CHARS = 80
private const val KEY_FOLLOW_UP = "followUp"
private const val FOLLOW_UP_NOTE = "The car follows this task in the background and reports its result, question or failure " +
    "as a 【委托回报】/【委托追问】/【委托失败】 message. Tell the user in one short sentence that it was handed over; do not wait or poll."
private const val METADATA_CONTEXT = "context"
private const val CONTEXT_SOURCE = "source"
private const val CONTEXT_SOURCE_CAR = "car"
private const val CONTEXT_TIMEZONE = "timezone"
private const val CONTEXT_LOCAL_TIME = "local_time"
private const val OUTCOME_MESSAGE = "message"
private const val OUTCOME_TASK = "task"
private const val ERROR_UNKNOWN_TOOL = "unknown_a2a_bridge_tool"
private const val ERROR_INVALID_ARGUMENTS = "invalid_arguments"
private const val ERROR_CLIENT_NOT_LOADED = "a2a_client_not_loaded"
private const val ERROR_SEND_FAILED = "a2a_send_failed"
private const val TEXT_PART_SEPARATOR = "\n"
