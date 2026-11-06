@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.a2ui
import cc.noharry.agent.appserver.plans.number
import cc.noharry.agent.appserver.protocol.view.BoundCall
import cc.noharry.agent.appserver.protocol.view.Control
import cc.noharry.agent.appserver.protocol.view.MAX_OFFER_OPTIONS
import cc.noharry.agent.appserver.protocol.view.Offer
import cc.noharry.agent.appserver.protocol.view.OfferOption
import cc.noharry.agent.appserver.protocol.view.SliderOffer
import cc.noharry.agent.appserver.protocol.view.WidgetCard
import cc.noharry.agent.appserver.protocol.view.offerKey
import cc.noharry.agent.appserver.protocol.view.parseJsonObject
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.modelText
import cc.noharry.agent.appserver.tools.SchemaValidation
import cc.noharry.agent.appserver.tools.validateInputSchema
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolCallTrace
import cc.noharry.agent.kernel.api.ToolCatalogCacheIdentity
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolPrefetchProvider
import cc.noharry.agent.kernel.api.ToolPrefetchRequest
import cc.noharry.agent.kernel.api.ToolPrefetchTrace
import cc.noharry.agent.kernel.api.ToolProvider
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.agent.kernel.core.tools.mcpQualifiedName
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `offer_options`: the model registers the options its reply lists, each with the tool call it stands for. The
 * host checks every call against that tool's input schema and keeps the offer for the next user turn (see
 * [offerPick]); nothing runs now. Offered from the first round, since every reply may end in a choice.
 */
internal class OfferToolProvider(
    private val bindable: BindableTools,
    /** What a card gets before it shows: a trip's photos, the header the device picks ([cardFinisher]). */
    private val finishCard: (suspend (WidgetCard) -> WidgetCard)? = null,
    /** The user's own cloud assistant is connected (an enabled A2A profile): the closing offer names it too. */
    private val cloudAssistant: Boolean = false,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : McpClientProvider, ToolPrefetchProvider, ToolCatalogCacheIdentity {
    override val serverId: String = OFFER_TOOL_SERVER_ID
    override val displayName: String get() = modelText("选项卡片", "Cards and options")
    override val catalogCacheIdentity: String get() = "app-server:$OFFER_TOOL_SERVER_ID:v2:${if (AppHost.words.isChinese) "zh" else "en"}"
    override val providerConcurrency: ProviderConcurrencyDeclaration = ProviderConcurrencyDeclaration.threadSafe()
    override val remotePrefetchEnabled: Boolean get() = false
    private val tripTiming = TripTimingGate()

    override suspend fun listTools(): List<ToolDescriptor> = listOf(
        ToolDescriptor(
            OFFER_OPTIONS_TOOL, title = modelText("登记选项", "Register options"),
            description = modelText(OFFER_OPTIONS_DESCRIPTION, OFFER_OPTIONS_DESCRIPTION_EN),
            inputSchemaJson = OFFER_OPTIONS_SCHEMA.inModelWords(OFFER_OPTIONS_SCHEMA_ENGLISH),
        ),
        ToolDescriptor(
            OFFER_SLIDER_TOOL, title = modelText("滑块设值", "Slider"), description = modelText(OFFER_SLIDER_DESCRIPTION, OFFER_SLIDER_DESCRIPTION_EN),
            inputSchemaJson = OFFER_SLIDER_SCHEMA.inModelWords(WIDGET_SCHEMA_ENGLISH),
        ),
        ToolDescriptor(
            OFFER_SWITCH_TOOL, title = modelText("开关", "Switch"), description = modelText(OFFER_SWITCH_DESCRIPTION, OFFER_SWITCH_DESCRIPTION_EN),
            inputSchemaJson = OFFER_SWITCH_SCHEMA.inModelWords(WIDGET_SCHEMA_ENGLISH),
        ),
        ToolDescriptor(
            OFFER_CHOICE_TOOL, title = modelText("档位选择", "Choice"), description = modelText(OFFER_CHOICE_DESCRIPTION, OFFER_CHOICE_DESCRIPTION_EN),
            inputSchemaJson = OFFER_CHOICE_SCHEMA.inModelWords(WIDGET_SCHEMA_ENGLISH),
        ),
        ToolDescriptor(
            SHOW_CARD_TOOL, title = modelText("展示卡片", "Show a card"), description = modelText(SHOW_CARD_DESCRIPTION, SHOW_CARD_DESCRIPTION_EN),
            inputSchemaJson = SHOW_CARD_SCHEMA.inModelWords(WIDGET_SCHEMA_ENGLISH),
        ),
    )

    override fun localToolNames(request: ToolPrefetchRequest): List<String> =
        listOf(OFFER_OPTIONS_TOOL, OFFER_SLIDER_TOOL, OFFER_SWITCH_TOOL, OFFER_CHOICE_TOOL, SHOW_CARD_TOOL)

    override suspend fun prefetchTools(request: ToolPrefetchRequest, trace: ToolPrefetchTrace): List<String> = emptyList()

    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult =
        when (toolName) {
            OFFER_OPTIONS_TOOL -> offerOptions(argumentsJson, context)
            OFFER_SLIDER_TOOL, OFFER_SWITCH_TOOL, OFFER_CHOICE_TOOL, SHOW_CARD_TOOL -> showWidget(toolName, argumentsJson, context)
            else -> failure("UNKNOWN_TOOL", "Unknown tool: $toolName")
        }

    /** A control of the panel, or a card, checked and kept for this turn's reply. */
    private suspend fun showWidget(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
        val args = parseJsonObject(argumentsJson) ?: return failure("ARGUMENT_INVALID", "Arguments must be an object.", widgetFallback())
        val change: (Offer) -> Offer = try {
            when (toolName) {
                OFFER_SLIDER_TOOL -> withControl(parseSliderOffer(args, bindable))
                OFFER_SWITCH_TOOL -> withControl(parseSwitchOffer(args, bindable))
                OFFER_CHOICE_TOOL -> withControl(parseChoiceOffer(args, bindable))
                else -> parseCard(args).let { parsed ->
                    tripTiming.problem(context.requestId, parsed)?.let { throw IllegalArgumentException(it) }
                    withCard(finishCard?.invoke(parsed) ?: parsed)
                }
            }
        } catch (invalid: IllegalArgumentException) {
            return failure("WIDGET_INVALID", invalid.message.orEmpty(), widgetFallback())
        }
        val cards = OfferStore.update(context.sessionId, context.requestId, nowMillis(), change).cards.size
        val message = if (toolName == SHOW_CARD_TOOL) {
            val shown = if (cards > 1) modelText("（这一轮共 $cards 张卡片，一起显示）", " ($cards cards this round, shown together)") else ""
            // The reply's closing offer follows when the place is due (owner 2026-10-19: 周六的演出问「要不要算路过去」不符合
            // 现实). Here, right before finish, rather than in the voice skill, which has no room left in the host prompt.
            modelText(
                "已显示在屏幕上$shown。finish 用一两句话说要点，不要再逐条列出卡片内容。结尾问下一步要合时宜：马上要去的才问导航；" +
                    "改天或几个小时以后的，问要不要到点提醒出发。",
                "Shown on the screen$shown. In finish, give the main point in a sentence or two, without listing the card's content " +
                    "again. End by offering a next step that fits the time: directions only for a place to go now; for one days or " +
                    "hours away, a reminder when it is time to set off.",
            ) + if (cloudAssistant) cloudAssistantOffer() else ""
        } else {
            modelText(
                "已显示在屏幕上。finish 用一句话说明调好后点确定，也可以直接说，不要列选项。",
                "Shown on the screen. In finish, say in one sentence to confirm once it is set, or just say it; list no options.",
            )
        }
        return ToolCallResult(
            success = true,
            resultJson = buildJsonObject {
                put("ok", true)
                put("message", message)
            }.toString(),
            trace = ToolCallTrace(validationOk = true, validationCode = "OK"),
        )
    }

    /**
     * Several controls make one panel (音量和亮度, 温度和模式); a second call for the same label replaces it. The newest
     * [MAX_CONTROLS] stay, with at most [MAX_SLIDERS] sliders, which take the most room.
     */
    private fun withControl(control: Control): (Offer) -> Offer = { offer ->
        var controls = offer.controls.filterNot { it.label == control.label } + control
        if (controls.count { it is SliderOffer } > MAX_SLIDERS) controls = controls - controls.first { it is SliderOffer }
        offer.copy(controls = controls.takeLast(MAX_CONTROLS))
    }

    /**
     * Several cards show together (one per city, per kind of thing); a redraw of one ([redrawnAs]) replaces that card,
     * in its place. The newest [MAX_CARDS] stay.
     */
    private fun withCard(card: WidgetCard): (Offer) -> Offer = { offer ->
        val at = offer.cards.indexOfFirst { it.redrawnAs(card) }
        val cards = if (at >= 0) offer.cards.toMutableList().apply { set(at, card) } else offer.cards + card
        offer.copy(cards = cards.takeLast(MAX_CARDS))
    }

    private suspend fun offerOptions(argumentsJson: String, context: ToolCallContext): ToolCallResult {
        val items = (parseJsonObject(argumentsJson)?.get("options") as? JsonArray)?.map { it as? JsonObject }
            ?: return failure("ARGUMENT_INVALID", "options must be an array of objects.")
        if (items.size !in 2..MAX_OFFER_OPTIONS || items.any { it == null }) {
            return failure("ARGUMENT_INVALID", "Offer 2 to $MAX_OFFER_OPTIONS options, each an object.")
        }
        val problems = mutableListOf<String>()
        val options = items.filterNotNull().mapIndexed { index, item -> parseOption(index + 1, item, problems) }
        val labels = options.map { it.label.offerKey() }
        if (labels.toSet().size != labels.size) problems += "Labels must differ."
        if (problems.isNotEmpty()) return failure("OPTION_INVALID", problems.joinToString(" "))
        OfferStore.update(context.sessionId, context.requestId, nowMillis()) { it.copy(options = options) }
        return ToolCallResult(
            success = true,
            resultJson = buildJsonObject {
                put("ok", true)
                put("options", options.size)
                put("bound", options.count { it.call != null })
                put(
                    "message",
                    modelText(
                        "已登记。finish 里按同样顺序、以同样的文字开头列出这些选项；用户选中后宿主会给出要执行的调用。",
                        "Registered. In finish, list these options in the same order, each line starting with the same words; once " +
                            "the user picks one, the host hands you the call to make.",
                    ),
                )
            }.toString(),
            trace = ToolCallTrace(validationOk = true, validationCode = "OK"),
        )
    }

    private suspend fun parseOption(ordinal: Int, item: JsonObject, problems: MutableList<String>): OfferOption {
        val label = item.text("label").trim()
        if (label.isEmpty()) problems += "Option $ordinal needs a label."
        val icon = try {
            item.widgetIcon("icon")
        } catch (invalid: IllegalArgumentException) {
            problems += "Option $ordinal: ${invalid.message}"
            null
        }
        val tool = item.text("tool").trim()
        if (tool.isEmpty()) return OfferOption(label, call = null, icon = icon)
        val arguments = item["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val target = bindable.find(tool)
        val problem = when {
            target == null -> "Option $ordinal: unknown tool $tool (use a name search_tools returned)."
            else -> checkArguments(target.descriptor.inputSchemaJson, arguments)?.let { "Option $ordinal: $it" }
        }
        if (problem != null || target == null) {
            problems += problem.orEmpty()
            return OfferOption(label, call = null, icon = icon)
        }
        return OfferOption(label, BoundCall(target.providerId, target.descriptor.name, target.qualifiedName, arguments.toString()), icon)
    }

    private fun failure(code: String, message: String, fallback: String = optionsFallback()): ToolCallResult = ToolCallResult(
        success = false,
        resultJson = buildJsonObject {
            put("ok", false)
            put("code", code)
            put("message", "$message $fallback")
        }.toString(),
        trace = ToolCallTrace(validationOk = false, validationCode = code, validationMessage = message),
    )
}

/** Required fields, types and enums (shared validator), then unknown fields and numeric bounds. */
internal fun checkArguments(schemaJson: String, arguments: JsonObject): String? {
    (validateInputSchema(schemaJson, arguments.toString()) as? SchemaValidation.Invalid)?.let { return it.message }
    val properties = parseJsonObject(schemaJson)?.get("properties") as? JsonObject ?: JsonObject(emptyMap())
    arguments.keys.firstOrNull { it !in properties }?.let { return "Unknown field: $it" }
    for ((name, value) in arguments) {
        val property = properties[name] as? JsonObject ?: continue
        val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: continue
        val min = property["minimum"]?.jsonPrimitive?.doubleOrNull
        val max = property["maximum"]?.jsonPrimitive?.doubleOrNull
        if ((min != null && number < min) || (max != null && number > max)) return "Field $name must be within ${min ?: "-∞"}..${max ?: "∞"}."
    }
    return null
}

/** The tools an option may stand for: the mounted MCP providers' catalogs, listed once per run. */
internal class BindableTools(private val providers: List<ToolProvider>) {
    data class Tool(val providerId: String, val qualifiedName: String, val descriptor: ToolDescriptor)

    private var tools: List<Tool>? = null
    // Several offer_slider calls of one round arrive together: the first lists the catalogs, the others wait for it.
    private val listing = Mutex()

    suspend fun find(name: String): Tool? {
        val all = listing.withLock {
            tools ?: providers.flatMap { provider ->
                runCatching { provider.listTools() }.getOrDefault(emptyList()).map { descriptor ->
                    Tool(provider.providerId, qualifiedToolName(provider.providerId, descriptor.name), descriptor)
                }
            }.also { tools = it }
        }
        return all.firstOrNull { it.qualifiedName == name } ?: all.singleOrNull { it.descriptor.name == name }
    }
}

/** The Kernel's model-visible name for an MCP tool. */
internal fun qualifiedToolName(providerId: String, toolName: String): String = mcpQualifiedName(providerId, toolName)

private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

internal const val OFFER_OPTIONS_TOOL = "offer_options"
private const val MAX_SLIDERS = 3
private const val MAX_CONTROLS = 5
private const val MAX_CARDS = 3

/**
 * Owner 2026-10-19: 云端助手已经登录时，旅行规划要问要不要发给云端助手. The trip skill's own closing said so, but the
 * line here, right before finish, offered only reminders and won; with the assistant connected it now says it too.
 */
private fun cloudAssistantOffer() = modelText(
    "云端助手连着，同一个问题里也问要不要把这些发给云端助手（备份行程、订票、存进日历）。",
    " The user's own agent is connected: in the same question, also offer to hand this to it (to keep the itinerary, book " +
        "tickets, put it in the calendar).",
)

/**
 * Whether [next] draws this card again: the same title but for spaces and punctuation, or mostly the same items (on
 * the bench 「周六10/24 深圳亲子演出」 came back checked as 「周六 10/24 深圳亲子演出」 and both stood side by side, each
 * half as wide). An item matches when one title holds the other (「《摇篮之歌》」, 「《摇篮之歌》（中国首秀）」). Short names
 * every card has (「周六」「明天」「D1」) do not count: two cities' weather on one turn are two cards.
 */
internal fun WidgetCard.redrawnAs(next: WidgetCard): Boolean {
    if (title.cardKey() == next.title.cardKey()) return true
    val mine = items.map { it.title.cardKey() }.filter { it.length > GENERIC_ITEM_CHARS }
    val theirs = next.items.map { it.title.cardKey() }.filter { it.length > GENERIC_ITEM_CHARS }
    if (mine.isEmpty() || theirs.isEmpty()) return false
    val shared = theirs.count { item -> mine.any { it in item || item in it } }
    return shared * 2 >= minOf(mine.size, theirs.size) + 1
}

private fun String.cardKey(): String = lowercase().filter(Char::isLetterOrDigit)
private const val GENERIC_ITEM_CHARS = 3
private fun optionsFallback() = modelText("选项仍会照常显示，但点选时不会直接执行。", "The options still show, but a tap will not act directly.")
private fun widgetFallback() =
    modelText("没有显示；改正参数后再调用一次，或直接在 finish 里用文字说明。", "Not shown: fix the arguments and call again, or explain in words in finish.")
private const val OFFER_TOOL_SERVER_ID = "car_ui"
private val OFFER_OPTIONS_DESCRIPTION = """
    登记选项 / 让用户选 / 选项卡片：回复要让用户从 2–8 项里选一项时，先调用它登记，再在同一轮调用 finish，
    finish 的“看”里按同样顺序列出编号选项（推荐项标“（推荐，理由）”）。适用：参数值（颜色、模式、播放模式）、
    打开或关闭、几个常用档位（如亮度 30%/50%/80%）、是否执行某个具体操作（“1. 改成西广场停车场（推荐）”“2. 先不改”）、
    搜索结果里的地点或歌曲。每项写 label（与列表里这一项开头的文字相同）；能直接办成的项再写 tool
    （search_tools 返回的工具全名）和完整 arguments，取值只用工具参数允许的值（如 color 写 blue，不写“蓝色”）；
    “不用了”这类不调用工具的项只写 label；每项可加 icon（图标名同 show_card，如 charging、parking、settings），
    显示成图标列表。用户点选或说出这一项时，宿主直接给出这次调用，不必再搜索或追问。
    缺目的地、缺名字这类开放问题照常问，不为凑选项编造选项。只是推荐几家酒店、餐厅、景点给用户看（卡片已经列出）、
    选了也没有要办的事时不要登记，finish 里也不要编号标推荐。
""".trimIndent().replace("\n", "")
private val OFFER_OPTIONS_DESCRIPTION_EN = """
    Register options / let the user choose / option card: when the reply asks the user to pick one of 2–8 items, register them
    with this first, then call finish in the same round and list the options there, numbered in the same order (mark the
    recommended one "(recommended: why)"). For: parameter values (colour, mode, play mode), on or off, a few common levels (such
    as brightness 30%/50%/80%), whether to carry out a specific action ("1. Change to the west square car park (recommended)"
    "2. Leave it"), places or songs from search results. Each item has a label (the words its line in the list starts with); an
    item that can be done directly also has a tool (the full tool name search_tools returned) and complete arguments, using
    only values the tool's parameters allow (color is blue, not "Blue"); an item such as "No thanks" that calls no tool has
    only a label; each item may add an icon (names as for show_card, such as charging, parking, settings), shown as an icon
    list. When the user taps or says an item, the host hands you that call, with no search or question needed. Open questions,
    such as a missing destination or name, are asked as usual: never make up options to have some. When you only recommend a
    few hotels, restaurants or sights to look at (the card lists them) and choosing one leads to nothing to do, do not register
    them, and do not number them with a recommendation in finish either.
""".trimIndent().replace("\n", " ")
private val OFFER_OPTIONS_SCHEMA = """
    {"type":"object","additionalProperties":false,"required":["options"],"properties":{"options":{"type":"array",
    "minItems":2,"maxItems":$MAX_OFFER_OPTIONS,"description":"与 finish 列表同样顺序的选项","items":{"type":"object",
    "additionalProperties":false,"required":["label"],"properties":{
    "label":{"type":"string","description":"列表里这一项开头的文字，如 蓝色、打开、深圳北站西广场停车场"},
    "tool":{"type":"string","description":"选中后要调用的工具全名；不调用工具的项留空"},
    "arguments":{"type":"object","description":"该工具的完整参数，必须符合它的参数定义"},
    "icon":{"type":"string","description":"可选图标名，显示成图标列表"}}}}}}
""".trimIndent().replace("\n", "")
private val OFFER_OPTIONS_SCHEMA_ENGLISH = mapOf(
    "与 finish 列表同样顺序的选项" to "the options in the same order as finish's list",
    "列表里这一项开头的文字，如 蓝色、打开、深圳北站西广场停车场" to "the words this item's line starts with, such as Blue, On, West square car park",
    "选中后要调用的工具全名；不调用工具的项留空" to "the full name of the tool to call once chosen; empty for an item that calls none",
    "该工具的完整参数，必须符合它的参数定义" to "the tool's complete arguments, matching its parameter definition",
    "可选图标名，显示成图标列表" to "an optional icon name, shown as an icon list",
)
