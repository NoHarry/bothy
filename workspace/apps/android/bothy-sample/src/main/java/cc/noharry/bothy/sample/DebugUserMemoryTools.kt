package cc.noharry.bothy.sample

import android.content.Context
import cc.noharry.bothy.kernel.api.ContextStability
import cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.bothy.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.bothy.kernel.api.RuntimeContextBlock
import cc.noharry.bothy.kernel.api.RuntimeContextContribution
import cc.noharry.bothy.kernel.api.RuntimeContextDatum
import cc.noharry.bothy.kernel.api.RuntimeContextDatumKind
import cc.noharry.bothy.kernel.api.RuntimeContextProvider
import cc.noharry.bothy.kernel.api.RuntimeContextRequest
import cc.noharry.bothy.kernel.api.RuntimeContextScalar
import cc.noharry.bothy.kernel.api.ToolCallContext
import cc.noharry.bothy.kernel.api.ToolCallResult
import cc.noharry.bothy.kernel.api.ToolCallTrace
import cc.noharry.bothy.kernel.api.ToolCatalogCacheIdentity
import cc.noharry.bothy.kernel.api.ToolDescriptor
import cc.noharry.bothy.kernel.api.ToolPrefetchProvider
import cc.noharry.bothy.kernel.api.ToolPrefetchRequest
import cc.noharry.bothy.kernel.api.ToolPrefetchTrace
import cc.noharry.bothy.kernel.api.mcp.McpClientProvider
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * `user_memory`: 小博 remembers, changes or forgets what the user says about themself (设置 → 记忆). Mounted only
 * while 记忆's switch is on; what is remembered reaches each round through [DebugUserMemoryContext], whose fact ids are
 * the ones this tool changes and forgets. Words that tell or take back something about the user ([MEMORY_CUES]) offer
 * the tool on the first round; otherwise it is found through search_tools (on the bench a search cost a model round).
 */
@OptIn(ExperimentalAgentRuntimeApi::class)
internal class DebugUserMemoryToolProvider(
    private val write: (change: (DebugUserMemoryBook) -> DebugMemoryWrite) -> DebugMemoryWrite,
) : McpClientProvider, ToolPrefetchProvider, ToolCatalogCacheIdentity {
    constructor(context: Context) : this({ change -> DebugUserMemoryStore.write(context.applicationContext, change) })

    override val serverId: String = USER_MEMORY_SERVER_ID
    override val displayName: String = "记忆"
    override val catalogCacheIdentity: String = "bothy-sample:$USER_MEMORY_SERVER_ID:v1"
    override val providerConcurrency: ProviderConcurrencyDeclaration = ProviderConcurrencyDeclaration.threadSafe()
    override val remotePrefetchEnabled: Boolean get() = false

    override suspend fun listTools(): List<ToolDescriptor> = listOf(
        ToolDescriptor(name = USER_MEMORY_TOOL, title = "记住用户的事", description = USER_MEMORY_DESCRIPTION, inputSchemaJson = USER_MEMORY_SCHEMA),
    )

    override fun localToolNames(request: ToolPrefetchRequest): List<String> =
        if (MEMORY_CUES.any { it in request.input }) listOf(USER_MEMORY_TOOL) else emptyList()

    override suspend fun prefetchTools(request: ToolPrefetchRequest, trace: ToolPrefetchTrace): List<String> = emptyList()

    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
        if (toolName != USER_MEMORY_TOOL) return failure("UNKNOWN_TOOL", "Unknown memory tool: $toolName", validation = false)
        val invalid = validateDebugInputSchema(USER_MEMORY_SCHEMA, argumentsJson) as? DebugSchemaValidation.Invalid
        if (invalid != null) return failure(invalid.code, invalid.message, validation = false)
        val arguments = Json.parseToJsonElement(argumentsJson) as JsonObject
        fun string(name: String) = (arguments[name] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }
        val category = DebugMemoryCategory.byId(string("category"))
        val content = string("content")
        val id = string("id")
        val said = string("said")
        val action = string("action")
        val result = when {
            action == "remember" && category != null && content != null ->
                write { it.remember(category, content, DebugMemorySource.SAID, said) }
            action == "update" && id != null && content != null ->
                write { it.update(id, content, category, DebugMemorySource.SAID, said) }
            action == "forget" && id != null -> write { it.forget(id) }
            else -> DebugMemoryWrite.Refused(action?.let(MISSING_ARGUMENTS::get) ?:"action 只能是 remember、update 或 forget")
        }
        return outcome(result)
    }

    private fun outcome(result: DebugMemoryWrite): ToolCallResult = when (result) {
        is DebugMemoryWrite.Saved -> success(result.memory, if (result.created) "已记住" else "已更新或已经记着了")
        is DebugMemoryWrite.Removed -> success(result.memory, "已忘掉")
        is DebugMemoryWrite.Refused -> failure("NOT_SAVED", result.reason, validation = true)
    }

    private fun success(memory: DebugUserMemory, done: String): ToolCallResult = ToolCallResult(
        success = true,
        resultJson = buildJsonObject {
            put("ok", true)
            put("id", memory.id)
            put("category", memory.category.id)
            put("content", memory.content)
            put("message", "$done。用一句话告诉用户，复述记下或忘掉的内容；用户可以在 设置 → 记忆 查看和修改。")
        }.toString(),
        trace = ToolCallTrace(validationOk = true, validationCode = "OK"),
    )

    private fun failure(code: String, message: String, validation: Boolean): ToolCallResult = ToolCallResult(
        success = false,
        resultJson = buildJsonObject {
            put("ok", false)
            put("code", code)
            put("message", message)
        }.toString(),
        trace = ToolCallTrace(validationOk = validation, validationCode = code, validationMessage = message.takeIf { !validation }),
    )
}

/**
 * What 小博 remembers, as facts before each round: one block per kind, each memory a fact whose id is the one
 * `user_memory` changes and forgets. The runtime marks the block as data, never instructions.
 */
@OptIn(ExperimentalAgentRuntimeApi::class)
internal class DebugUserMemoryContext(private val memories: () -> List<DebugUserMemory>) : RuntimeContextProvider {
    constructor(context: Context) : this({ DebugUserMemoryStore.inUse(context.applicationContext) })

    override val id: String = USER_MEMORY_SERVER_ID

    override suspend fun provide(request: RuntimeContextRequest): RuntimeContextContribution {
        val all = memories()
        if (all.isEmpty()) return RuntimeContextContribution.empty(id)
        val blocks = all.groupBy { it.category }.toSortedMap().map { (category, items) ->
            val data = items.take(MAX_PER_CATEGORY).map { memory ->
                RuntimeContextDatum(
                    id = memory.id,
                    kind = RuntimeContextDatumKind.FACT,
                    subject = category.label,
                    predicate = USER_MEMORY_PREDICATE,
                    value = RuntimeContextScalar.Text(memory.content),
                )
            }
            RuntimeContextBlock("$USER_MEMORY_PREDICATE.${category.id}", ContextStability.STABLE, data, digest(items.joinToString("\n") { it.id + it.content }))
        }
        return RuntimeContextContribution(id, blocks, fingerprint = digest(blocks.joinToString { it.payloadDigest }))
    }
}

private fun digest(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(DIGEST_CHARS)

internal const val USER_MEMORY_TOOL = "user_memory"
private const val USER_MEMORY_SERVER_ID = "memory"
private const val USER_MEMORY_PREDICATE = "user_memory"
private const val DIGEST_CHARS = 16
/** Words that tell 小博 something lasting about the user, or take it back. */
internal val MEMORY_CUES = listOf(
    "记住", "记一下", "记下", "别忘", "忘掉", "忘了吧", "别记", "不用记", "记得我", "记得什么", "叫我", "我叫", "称呼",
    "我家", "我住", "我公司", "我单位", "我喜欢", "我爱听", "我不喜欢", "我一般", "我习惯", "我平时", "我老婆", "我老公",
    "我媳妇", "我儿子", "我女儿", "我妈", "我爸", "我生日",
)
private val MISSING_ARGUMENTS = mapOf(
    "remember" to "remember 需要 category 和 content",
    "update" to "update 需要 id 和 content",
    "forget" to "forget 需要 id",
)
private val USER_MEMORY_DESCRIPTION = """
    记住 / 修改 / 忘掉关于用户本人的长期信息（用户画像，设置 → 记忆）。只记用户亲口说的、关于他自己、以后还用得上的事：
    怎么称呼他、家和公司在哪、温度灯光音量等设备偏好、喜欢的歌手电台、通勤习惯、家人。用户说「记住…」「以后…」「我一般…」
    「叫我…」时 remember（称呼写成「叫我老王」这样）；信息变了用 update；说「忘掉…」「别记…」时 forget。不记一次性的指令、你自己猜的事和别人的隐私，
    不记密码、验证码、证件号、银行卡号。已记住的在 runtime_context 里 predicate 为 user_memory 的事实中，id 用于 update
    和 forget；回答「你记得我什么」也看那里。said 填用户的原话。完成后用一句话告诉用户。
""".trimIndent().replace("\n", "")
private const val USER_MEMORY_SCHEMA =
    """{"type":"object","properties":{""" +
        """"action":{"type":"string","enum":["remember","update","forget"]},""" +
        """"category":{"type":"string","enum":["identity","places","home","media","travel","people","other"],""" +
        """"description":"identity 称呼与身份；places 常去地点；home 设备与家居偏好；media 娱乐；travel 出行习惯；people 家人朋友；other 其他"},""" +
        """"content":{"type":"string","minLength":1,"maxLength":100,"description":"记下的内容，一句完整的话，比如「家在望京花园 3 号楼」"},""" +
        """"id":{"type":"string","description":"要修改或忘掉的记忆 id"},""" +
        """"said":{"type":"string","maxLength":200,"description":"用户的原话"}},""" +
        """"required":["action"],"additionalProperties":false}"""
