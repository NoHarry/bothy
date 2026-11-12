package cc.noharry.agent.appserver.memory
import android.content.Context
import cc.noharry.agent.appserver.protocol.MAX_PER_CATEGORY
import cc.noharry.agent.appserver.protocol.MemoryCategory
import cc.noharry.agent.appserver.protocol.MemorySource
import cc.noharry.agent.appserver.protocol.UserMemory
import cc.noharry.agent.appserver.runtime.Cues
import cc.noharry.agent.appserver.runtime.modelText
import cc.noharry.agent.appserver.tools.SchemaValidation
import cc.noharry.agent.appserver.tools.validateInputSchema
import cc.noharry.agent.spi.ContextStability
import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.RuntimeContextBlock
import cc.noharry.agent.spi.RuntimeContextContribution
import cc.noharry.agent.spi.RuntimeContextDatum
import cc.noharry.agent.spi.RuntimeContextDatumKind
import cc.noharry.agent.spi.RuntimeContextProvider
import cc.noharry.agent.spi.RuntimeContextRequest
import cc.noharry.agent.spi.RuntimeContextScalar
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolCallTrace
import cc.noharry.agent.spi.ToolCatalogCacheIdentity
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolPrefetchProvider
import cc.noharry.agent.spi.ToolPrefetchRequest
import cc.noharry.agent.spi.ToolPrefetchTrace
import cc.noharry.agent.spi.mcp.McpClientProvider
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * `user_memory`: the assistant remembers, changes or forgets what the user says about themself (the memory page, which
 * the words name when the host has one: [memoryPage]). Mounted only while the memory switch is on; what is remembered
 * reaches each round through [UserMemoryContext], whose fact ids are the ones this tool changes and forgets. Words that
 * tell or take back something about the user ([MEMORY_CUES]) offer the tool on the first round; otherwise it is found
 * through search_tools (on the bench a search cost a model round).
 */
@OptIn(ExperimentalAgentRuntimeApi::class)
internal class UserMemoryToolProvider(
    private val memoryPage: String? = UserMemoryStore.page,
    private val write: (change: (UserMemoryBook) -> MemoryWrite) -> MemoryWrite,
) : McpClientProvider, ToolPrefetchProvider, ToolCatalogCacheIdentity {
    constructor(context: Context) : this(write = { change -> UserMemoryStore.write(context.applicationContext, change) })

    override val serverId: String = USER_MEMORY_SERVER_ID
    override val displayName: String get() = modelText("记忆", "Memory")
    override val catalogCacheIdentity: String get() = "app-server:$USER_MEMORY_SERVER_ID:v2:${modelText("zh", "en")}"
    override val providerConcurrency: ProviderConcurrencyDeclaration = ProviderConcurrencyDeclaration.threadSafe()
    override val remotePrefetchEnabled: Boolean get() = false

    override suspend fun listTools(): List<ToolDescriptor> = listOf(
        ToolDescriptor(
            name = USER_MEMORY_TOOL,
            title = modelText("记住用户的事", "Remember things about the user"),
            description = userMemoryDescription(memoryPage),
            inputSchemaJson = userMemorySchema(),
        ),
    )

    override fun localToolNames(request: ToolPrefetchRequest): List<String> =
        if (MEMORY_CUES.heardIn(request.input)) listOf(USER_MEMORY_TOOL) else emptyList()

    override suspend fun prefetchTools(request: ToolPrefetchRequest, trace: ToolPrefetchTrace): List<String> = emptyList()

    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
        if (toolName != USER_MEMORY_TOOL) return failure("UNKNOWN_TOOL", "Unknown memory tool: $toolName", validation = false)
        val invalid = validateInputSchema(userMemorySchema(), argumentsJson) as? SchemaValidation.Invalid
        if (invalid != null) return failure(invalid.code, invalid.message, validation = false)
        val arguments = Json.parseToJsonElement(argumentsJson) as JsonObject
        fun string(name: String) = (arguments[name] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }
        val category = MemoryCategory.byId(string("category"))
        val content = string("content")
        val id = string("id")
        val said = string("said")
        val action = string("action")
        val result = when {
            action == "remember" && category != null && content != null ->
                write { it.remember(category, content, MemorySource.SAID, said) }
            action == "update" && id != null && content != null ->
                write { it.update(id, content, category, MemorySource.SAID, said) }
            action == "forget" && id != null -> write { it.forget(id) }
            else -> MemoryWrite.Refused(
                action?.let(::missingArguments) ?: modelText("action 只能是 remember、update 或 forget", "action must be remember, update or forget"),
                MemoryRefusal.Arguments,
            )
        }
        return outcome(result)
    }

    private fun outcome(result: MemoryWrite): ToolCallResult = when (result) {
        is MemoryWrite.Saved ->
            success(result.memory, if (result.created) modelText("已记住", "Remembered") else modelText("已更新或已经记着了", "Updated, or already remembered"))
        is MemoryWrite.Removed -> success(result.memory, modelText("已忘掉", "Forgotten"))
        is MemoryWrite.Refused -> failure("NOT_SAVED", result.reason, validation = true)
    }

    private fun success(memory: UserMemory, done: String): ToolCallResult = ToolCallResult(
        success = true,
        resultJson = buildJsonObject {
            put("ok", true)
            put("id", memory.id)
            put("category", memory.category.id)
            put("content", memory.content)
            val where = memoryPage?.let { modelText("；用户可以在 $it 查看和修改", "; the user can see and change it on $it") }.orEmpty()
            put("message", modelText("$done。用一句话告诉用户，复述记下或忘掉的内容$where。", "$done. Tell the user in one sentence, restating what was remembered or forgotten$where."))
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
 * What the assistant remembers, as facts before each round: one block per kind, each memory a fact whose id is the one
 * `user_memory` changes and forgets. The runtime marks the block as data, never instructions.
 */
@OptIn(ExperimentalAgentRuntimeApi::class)
internal class UserMemoryContext(private val memories: () -> List<UserMemory>) : RuntimeContextProvider {
    constructor(context: Context) : this({ UserMemoryStore.inUse(context.applicationContext) })

    override val id: String = USER_MEMORY_SERVER_ID

    override suspend fun provide(request: RuntimeContextRequest): RuntimeContextContribution {
        val all = memories()
        if (all.isEmpty()) return RuntimeContextContribution.empty(id)
        val blocks = all.groupBy { it.category }.toSortedMap().map { (category, items) ->
            val data = items.take(MAX_PER_CATEGORY).map { memory ->
                RuntimeContextDatum(
                    id = memory.id,
                    kind = RuntimeContextDatumKind.FACT,
                    subject = category.forModel,
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
internal const val USER_MEMORY_SERVER_ID = "memory"
private const val USER_MEMORY_PREDICATE = "user_memory"
private const val DIGEST_CHARS = 16
/** Words that tell the assistant something lasting about the user, or take it back. */
internal val MEMORY_CUES = Cues(
    chinese = listOf(
        "记住", "记一下", "记下", "别忘", "忘掉", "忘了吧", "别记", "不用记", "记得我", "记得什么", "叫我", "我叫", "称呼",
        "我家", "我住", "我公司", "我单位", "我喜欢", "我爱听", "我不喜欢", "我一般", "我习惯", "我平时", "我老婆", "我老公",
        "我媳妇", "我儿子", "我女儿", "我妈", "我爸", "我生日",
    ),
    english = listOf(
        "remember", "forget", "call me", "my name is", "my home", "i live", "my office", "i work at", "i like", "i love",
        "i don't like", "i usually", "i always", "my wife", "my husband", "my son", "my daughter", "my mom", "my dad",
        "my birthday",
    ),
)
private fun missingArguments(action: String): String? = when (action) {
    "remember" -> modelText("remember 需要 category 和 content", "remember needs category and content")
    "update" -> modelText("update 需要 id 和 content", "update needs id and content")
    "forget" -> modelText("forget 需要 id", "forget needs id")
    else -> null
}
/** The tool's description; [memoryPage], when the host has one, is named as where the user sees the memories. */
private fun userMemoryDescription(memoryPage: String?) = modelText(userMemoryDescriptionZh(memoryPage), userMemoryDescriptionEn(memoryPage))

private fun userMemoryDescriptionZh(memoryPage: String?) = """
    记住 / 修改 / 忘掉关于用户本人的长期信息（用户画像${memoryPage?.let { "，$it" }.orEmpty()}）。只记用户亲口说的、关于他自己、以后还用得上的事：
    怎么称呼他、家和公司在哪、温度灯光音量等设备偏好、喜欢的歌手电台、通勤习惯、家人。用户说「记住…」「以后…」「我一般…」
    「叫我…」时 remember（称呼写成「叫我老王」这样）；信息变了用 update；说「忘掉…」「别记…」时 forget。不记一次性的指令、你自己猜的事和别人的隐私，
    不记密码、验证码、证件号、银行卡号。已记住的在 runtime_context 里 predicate 为 user_memory 的事实中，id 用于 update
    和 forget；回答「你记得我什么」也看那里。said 填用户的原话。完成后用一句话告诉用户。
""".trimIndent().replace("\n", "")
private fun userMemoryDescriptionEn(memoryPage: String?) = """
    Remember, change or forget lasting things about the user themself (the user's profile${memoryPage?.let { ", $it" }.orEmpty()}). Keep only
    what the user said about themself and will matter later: what to call them, where home and work are, their preferences,
    favourite music, routines, family. remember when the user says "remember…", "from now on…", "I usually…" or "call me…" (a
    name as "Call me Sam"); update when something changed; forget when the user says "forget…" or "don't keep…". Never keep
    one-off instructions, your own guesses or other people's private matters, nor passwords, codes, ID or card numbers. What is
    remembered is in runtime_context as facts whose predicate is user_memory; their ids are for update and forget; answer
    "what do you remember about me" from there too. said is the user's own words. When done, tell the user in one sentence.
""".trimIndent().replace("\n", " ")

private fun userMemorySchema(): String =
    """{"type":"object","properties":{""" +
        """"action":{"type":"string","enum":["remember","update","forget"]},""" +
        """"category":{"type":"string","enum":["identity","places","home","media","travel","people","other"],""" +
        """"description":"${modelText(
            "identity 称呼与身份；places 常去地点；home 设备与家居偏好；media 娱乐；travel 出行习惯；people 家人朋友；other 其他",
            "identity: name and identity; places: frequent places; home: device and home preferences; media: entertainment; " +
                "travel: travel habits; people: family and friends; other: anything else",
        )}"},""" +
        """"content":{"type":"string","minLength":1,"maxLength":100,"description":"${modelText(
            "记下的内容，一句完整的话，比如「家在望京花园 3 号楼」",
            "what is remembered, as one full sentence, such as 'Home is 3 Elm Street'",
        )}"},""" +
        """"id":{"type":"string","description":"${modelText("要修改或忘掉的记忆 id", "the id of the memory to change or forget")}"},""" +
        """"said":{"type":"string","maxLength":200,"description":"${modelText("用户的原话", "the user's own words")}"}},""" +
        """"required":["action"],"additionalProperties":false}"""
