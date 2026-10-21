package cc.noharry.bothy.sample

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToLong

/**
 * `offer_slider` arguments to a checked [DebugSliderOffer]: the parameter must be a number in the tool's schema, the
 * range stays inside the schema's bounds, and the call is valid at both ends. Throws [IllegalArgumentException]
 * with what to fix.
 */
internal suspend fun parseSliderOffer(args: JsonObject, bindable: DebugBindableTools): DebugSliderOffer {
    val label = args.widgetText("label")
    require(label.isNotEmpty()) { "label is required." }
    val toolName = args.widgetText("tool")
    val target = bindable.find(toolName) ?: throw IllegalArgumentException("Unknown tool $toolName (use a name search_tools returned).")
    val param = args.widgetText("param")
    val property = (parseDebugJsonObject(target.descriptor.inputSchemaJson)?.get("properties") as? JsonObject)?.get(param) as? JsonObject
        ?: throw IllegalArgumentException("${target.descriptor.name} has no parameter $param.")
    val integer = when (property["type"]?.jsonPrimitive?.contentOrNull) {
        "integer" -> true
        "number" -> false
        else -> throw IllegalArgumentException("Parameter $param must be a number.")
    }
    val range = sliderRange(args, property, integer)
    val fixed = JsonObject((args["arguments"] as? JsonObject).orEmpty() - param)
    for (end in listOf(range.min, range.max)) {
        checkArguments(target.descriptor.inputSchemaJson, JsonObject(fixed + (param to sliderNumber(end, integer))))
            ?.let { throw IllegalArgumentException("At $param=$end: $it") }
    }
    return DebugSliderOffer(
        label = label,
        call = DebugBoundCall(target.providerId, target.descriptor.name, target.qualifiedName, fixed.toString()),
        param = param,
        min = range.min,
        max = range.max,
        step = range.step,
        // A slider that starts at its minimum would mute or darken on a bare confirm; the middle is the safer guess.
        value = (args.widgetDouble("value") ?: (range.min + range.max) / 2).coerceIn(range.min, range.max),
        unit = args.widgetText("unit"),
        integer = integer,
    )
}

private data class SliderRange(val min: Double, val max: Double, val step: Double)

private fun sliderRange(args: JsonObject, property: JsonObject, integer: Boolean): SliderRange {
    val schemaMin = property.widgetDouble("minimum")
    val schemaMax = property.widgetDouble("maximum")
    val min = args.widgetDouble("min") ?: schemaMin ?: throw IllegalArgumentException("Give min: the schema has no minimum.")
    val max = args.widgetDouble("max") ?: schemaMax ?: throw IllegalArgumentException("Give max: the schema has no maximum.")
    require(min < max) { "min must be below max." }
    require((schemaMin == null || min >= schemaMin) && (schemaMax == null || max <= schemaMax)) {
        "The range must stay within ${schemaMin ?: "-∞"}..${schemaMax ?: "∞"}."
    }
    val step = args.widgetDouble("step") ?: 1.0
    require(step > 0 && step <= max - min) { "step must be positive and fit the range." }
    require(!integer || listOf(min, max, step).all { abs(it - round(it)) < 1e-9 }) { "An integer parameter needs whole min, max and step." }
    return SliderRange(min, max, step)
}

private fun sliderNumber(value: Double, integer: Boolean): JsonPrimitive =
    if (integer) JsonPrimitive(value.roundToLong()) else JsonPrimitive(value)

/**
 * `offer_switch` arguments to a checked [DebugSwitchOffer]: `on` and `off` each name a tool and its full arguments,
 * checked against that tool's schema (one tool with a state argument, or an open and a close tool).
 */
internal suspend fun parseSwitchOffer(args: JsonObject, bindable: DebugBindableTools): DebugSwitchOffer {
    val label = args.widgetText("label")
    require(label.isNotEmpty()) { "label is required." }
    val on = boundCall(args["on"], "on", bindable)
    val off = boundCall(args["off"], "off", bindable)
    return DebugSwitchOffer(label, on, off, value = (args["value"] as? JsonPrimitive)?.booleanOrNull ?: false)
}

/**
 * `offer_choice` arguments to a checked [DebugChoiceOffer]. With `tool` and `param`, each option's `value` fills that
 * parameter (a mode, a level); otherwise each option carries its own `tool` and `arguments` (actions to run
 * together, for `multiple`). Every option's call is checked against its tool's schema.
 */
internal suspend fun parseChoiceOffer(args: JsonObject, bindable: DebugBindableTools): DebugChoiceOffer {
    val label = args.widgetText("label")
    require(label.isNotEmpty()) { "label is required." }
    val multiple = (args["multiple"] as? JsonPrimitive)?.booleanOrNull ?: false
    val items = args.widgetObjects("options")
    require(items.size in 2..MAX_CHOICE_OPTIONS) { "Give 2 to $MAX_CHOICE_OPTIONS options." }
    val toolName = args.widgetText("tool")
    val param = args.widgetText("param")
    val shared = toolName.takeIf { it.isNotEmpty() }?.let {
        bindable.find(it) ?: throw IllegalArgumentException("Unknown tool $it (use a name search_tools returned).")
    }
    require(shared == null || param.isNotEmpty()) { "param is required with tool: the parameter each option sets." }
    val fixed = JsonObject((args["arguments"] as? JsonObject).orEmpty() - param)
    val options = items.mapIndexed { index, item ->
        val name = "Option ${index + 1}"
        val optionLabel = item.widgetText("label")
        require(optionLabel.isNotEmpty()) { "$name needs a label." }
        val call = if (shared != null) {
            val value = item["value"] ?: throw IllegalArgumentException("$name needs value: an allowed value of $param.")
            val arguments = JsonObject(fixed + (param to value))
            checkArguments(shared.descriptor.inputSchemaJson, arguments)?.let { throw IllegalArgumentException("$name: $it") }
            DebugBoundCall(shared.providerId, shared.descriptor.name, shared.qualifiedName, arguments.toString())
        } else {
            boundCall(item, name, bindable)
        }
        DebugChoiceOption(optionLabel, call)
    }
    require(options.map { it.label.offerKey() }.toSet().size == options.size) { "Option labels must differ." }
    val current = args.widgetText("current")
    // The option in effect now, by its label or its parameter value; a multiple choice starts with none chosen.
    val chosen = items.indexOfFirst { item ->
        current.isNotEmpty() && (item.widgetText("label") == current || (item["value"] as? JsonPrimitive)?.contentOrNull == current)
    }
    return DebugChoiceOffer(label, options, if (multiple || chosen < 0) emptyList() else listOf(chosen), multiple)
}

/** `{tool, arguments}` checked against the tool's schema; [name] says where in the arguments it was. */
private suspend fun boundCall(element: JsonElement?, name: String, bindable: DebugBindableTools): DebugBoundCall {
    val call = element as? JsonObject ?: throw IllegalArgumentException("$name needs tool and arguments.")
    val toolName = call.widgetText("tool")
    val target = bindable.find(toolName) ?: throw IllegalArgumentException("$name: unknown tool $toolName (use a name search_tools returned).")
    val arguments = call["arguments"] as? JsonObject ?: JsonObject(emptyMap())
    checkArguments(target.descriptor.inputSchemaJson, arguments)?.let { throw IllegalArgumentException("$name: $it") }
    return DebugBoundCall(target.providerId, target.descriptor.name, target.qualifiedName, arguments.toString())
}

/** `show_card` arguments to a [DebugCard]; display only, so nothing is bound or run. */
internal fun parseCard(args: JsonObject): DebugCard {
    val title = args.widgetText("title")
    require(title.isNotEmpty()) { "title is required." }
    val fields = args.widgetFields("fields", MAX_CARD_FIELDS)
    val layout = args.widgetText("layout").ifEmpty { DebugCardLayout.LIST.wire }.let { wire ->
        DebugCardLayout.entries.firstOrNull { it.wire == wire }
            ?: throw IllegalArgumentException("layout must be one of ${DebugCardLayout.entries.joinToString { it.wire }}.")
    }
    val items = args.widgetObjects("items").map { item ->
        DebugCardItem(
            icon = item.widgetIcon("icon"),
            title = item.widgetText("title"),
            detail = item.widgetText("detail").ifEmpty { null },
            value = item.widgetText("value").ifEmpty { null },
            image = item.widgetPicture("image"),
            fields = item.widgetFields("fields", MAX_COMPARE_FIELDS),
            group = item.widgetText("group").ifEmpty { null },
            kind = item.widgetText("kind").ifEmpty { DebugCardItemKind.PLACE.wire }.let { wire ->
                DebugCardItemKind.entries.firstOrNull { it.wire == wire }
                    ?: throw IllegalArgumentException("kind must be one of ${DebugCardItemKind.entries.joinToString { it.wire }}.")
            },
        )
    }
    require(items.all { it.title.isNotEmpty() }) { "Each item needs a title." }
    require(items.size in layout.items) {
        "layout ${layout.wire} takes ${layout.items.first} to ${layout.items.last} items, not ${items.size}."
    }
    val progress = args.widgetObjects("progress").map { bar ->
        val label = bar.widgetText("label")
        val value = bar.widgetDouble("value") ?: throw IllegalArgumentException("progress $label needs a number value.")
        val max = bar.widgetDouble("max") ?: DEFAULT_PROGRESS_MAX
        require(label.isNotEmpty() && max > 0 && value in 0.0..max) { "progress: each with a label and a value within 0..max (max ${max})." }
        DebugCardProgress(label, value, max, bar.widgetText("text").ifEmpty { null })
    }
    require(progress.size <= MAX_CARD_PROGRESS) { "progress: at most $MAX_CARD_PROGRESS bars." }
    val value = args.widgetText("value").ifEmpty { null }
    require(value != null || fields.isNotEmpty() || items.isNotEmpty() || progress.isNotEmpty()) { "Give value, fields, items or progress." }
    return DebugCard(
        title, args.widgetIcon("icon"), value, args.widgetText("subtitle").ifEmpty { null }, fields, items,
        args.widgetPicture("image"), layout, progress,
    )
}

/** A picture saved on the device (generated or delegated) or a public https photo; nothing else is ever fetched. */
private fun JsonObject.widgetPicture(key: String): String? {
    val path = widgetText(key).ifEmpty { return null }
    require(SAVED_PICTURE.matches(path) || remotePictureUrl(path) != null) {
        "$key must be a picture saved on the device (generated-images/<folder>/1.jpg) or a public https URL from a tool result."
    }
    return path
}

private fun JsonObject.widgetFields(key: String, max: Int): List<Pair<String, String>> {
    val fields = widgetObjects(key).map { it.widgetText("label") to it.widgetText("value") }
    require(fields.size <= max && fields.all { (label, value) -> label.isNotEmpty() && value.isNotEmpty() }) {
        "$key: at most $max, each with label and value."
    }
    return fields
}

/** A known icon name, or null when absent; an unknown one is an error that lists the names to use. */
internal fun JsonObject.widgetIcon(key: String): String? {
    val name = widgetText(key).ifEmpty { return null }
    require(debugIconKnown(name)) { "Unknown icon $name; use one of: ${DEBUG_ICON_HINT}." }
    return name
}

internal fun JsonObject.widgetText(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

private fun JsonObject.widgetDouble(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

private fun JsonObject.widgetObjects(key: String): List<JsonObject> =
    (this[key] as? JsonArray).orEmpty().map { it as? JsonObject ?: throw IllegalArgumentException("$key must hold objects.") }

private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

private const val MAX_CARD_FIELDS = 4
private const val MAX_COMPARE_FIELDS = 5
private const val MAX_CARD_PROGRESS = 3
private const val MAX_CHOICE_OPTIONS = 6
private const val DEFAULT_PROGRESS_MAX = 100.0
private val DEBUG_ICON_HINT = (DEBUG_SVG_ICONS.keys + listOf("home", "settings", "locationOn", "phone", "star", "info", "warning", "event"))
    .joinToString(", ")

internal const val OFFER_SLIDER_TOOL = "offer_slider"
internal const val OFFER_SWITCH_TOOL = "offer_switch"
internal const val OFFER_CHOICE_TOOL = "offer_choice"
internal const val SHOW_CARD_TOOL = "show_card"

/** The panel rule every control's description repeats, so the model learns it from whichever tool it reads. */
private const val PANEL_RULE = "滑块、开关、档位会合成一个面板，共用一个确定（同一轮最多 5 个控件，其中滑块最多 3 个），" +
    "全部调用完再调用 finish 用一句话说明调好后点确定，也可以直接说（不列选项）；用户点确定或直接说出时，宿主直接给出这次调用。"

internal val OFFER_SLIDER_DESCRIPTION = """
    滑块 / 范围设值：用户想调一个或几个有范围的数值、但没说具体值时（“我想调下音量和亮度”、灯光色温、
    字体大小等），不要追问，直接给滑块，每个数值调用一次。tool 写设值工具全名
    （search_tools 返回的），param 写要调的数值参数名，arguments 写其余固定参数（如 screen 写 center），
    min/max 不写就用工具参数定义的范围，value 必填，写当前值（先用查询工具读出来，读不到写常用值），unit 写单位（如 %、℃、档）。
    $PANEL_RULE
""".trimIndent().replace("\n", "")

internal val OFFER_SWITCH_DESCRIPTION = """
    开关：用户想开或关某个功能但没说开还是关，或想一起设置几个开关时（“勿扰和夜间模式”“蓝牙相关的都看看”），
    不要追问，每个开关调用一次。label 写名称（如 勿扰模式），on 和 off 分别写打开、关闭时要调用的工具全名
    （search_tools 返回的）和完整参数，如 on={"tool":"…dnd_mode_set","arguments":{"enabled":true}}、
    off 同一工具 enabled 写 false；开和关是两个工具时各写各的。value 写当前状态（先读出来，读不到写 false）。$PANEL_RULE
""".trimIndent().replace("\n", "")

internal val OFFER_CHOICE_DESCRIPTION = """
    档位 / 模式选择：一个参数只有 2–6 个取值时（主题 浅色/深色/跟随系统、画质档位、播放模式），
    调用它显示成一排按钮。tool 写设值工具全名，param 写要选的参数名，arguments 写其余固定参数，
    options 每项写 label（显示的中文）和 value（参数允许的取值，如 dark），current 写当前取值。
    几项可以同时选、每项各是一个操作时（如 打开哪几个房间的灯），写 multiple: true，不写 tool/param，
    options 每项写 label、tool 和完整 arguments。$PANEL_RULE
""".trimIndent().replace("\n", "")

internal val SHOW_CARD_DESCRIPTION = """
    展示卡片 / 天气卡片 / 信息卡片：查天气、电量、行程、机票这类信息时，先调用它把结果做成卡片，再在同一轮
    调用 finish 用一两句话说要点，卡片里的内容不再逐条列出。title 写标题（如 花都区 · 今天），icon 写图标名，
    value 写主数值（如 31°），subtitle 写一句概况（如 晴 · 32/21°），fields 写 2–4 项名称和数值（如 湿度 60%），
    progress 写最多 3 条进度（label、value、max 不写按 100、text 写显示的文字，如 电量 78、充电 12/30 分钟）。
    items 写条目，layout 按内容选排法：多日天气、逐小时预报一律用 strip（2–6 项排成一行，title 写 周六、
    icon 写天气图标、value 写 30/22°）；几个传感器这类成组读数、几张照片用 grid（2–6 项两列小格，title 写名称、
    value 写读数、detail 写备注）；几个地点或方案比较用 compare（2–3 项并排，每项 title、value 写主数值，fields 写同样
    名称的几项，如 距离、价格、评分）；多日行程用 trip（宽卡片：去程回程画成车票，每天一栏、第一张图在栏顶，住宿带图；items 的 kind 写 transport、stay、place 或 weather，place 和 weather 的 group 写哪一天，最多 18 项）；
    单日行程、清单用 list（默认，最多 14 行，value 显示在行尾，多日行程每项 group 写
    第1天、第2天，同组连续排）。两个城市、两类东西分开查的，各做一张卡分别调用（同一轮最多 3 张，并排显示；
    同一标题再调用会替换那张）。image（卡片头图）和每项的 image 只填工具结果或委托回报里给出的图片：设备上已保存的路径
    （generated-images/…/1.jpg）或公网 https 地址（子 agent 结果里的「图片：https://…」也算，有就做成图文卡片）；不要编造图片地址，没有就不写。只展示，不执行操作。
    图标名：$DEBUG_ICON_HINT。
""".trimIndent().replace("\n", "")

private const val CALL_SCHEMA = """{"type":"object","additionalProperties":false,"required":["tool"],""" +
    """"properties":{"tool":{"type":"string","description":"工具全名"},"arguments":{"type":"object","description":"完整参数"}}}"""
private const val FIELDS_SCHEMA = """{"type":"array","items":{"type":"object","additionalProperties":false,"required":["label","value"],""" +
    """"properties":{"label":{"type":"string"},"value":{"type":"string"}}}}"""

internal const val OFFER_SLIDER_SCHEMA = """{"type":"object","additionalProperties":false,"required":["label","tool","param","value"],""" +
    """"properties":{"label":{"type":"string","description":"滑块名称，如 中控屏亮度"},""" +
    """"tool":{"type":"string","description":"设值工具全名"},"param":{"type":"string","description":"要调的数值参数名"},""" +
    """"arguments":{"type":"object","description":"其余固定参数"},"min":{"type":"number"},"max":{"type":"number"},""" +
    """"step":{"type":"number"},"value":{"type":"number","description":"当前值"},"unit":{"type":"string"}}}"""

internal const val OFFER_SWITCH_SCHEMA = """{"type":"object","additionalProperties":false,"required":["label","on","off"],""" +
    """"properties":{"label":{"type":"string","description":"开关名称，如 勿扰模式"},""" +
    """"on":$CALL_SCHEMA,"off":$CALL_SCHEMA,"value":{"type":"boolean","description":"当前是否打开"}}}"""

internal const val OFFER_CHOICE_SCHEMA = """{"type":"object","additionalProperties":false,"required":["label","options"],""" +
    """"properties":{"label":{"type":"string","description":"名称，如 主题"},""" +
    """"tool":{"type":"string","description":"设值工具全名；multiple 时不写"},"param":{"type":"string","description":"要选的参数名"},""" +
    """"arguments":{"type":"object","description":"其余固定参数"},"current":{"type":"string","description":"当前取值"},""" +
    """"multiple":{"type":"boolean","description":"几项可同时选，每项各是一个操作"},""" +
    """"options":{"type":"array","minItems":2,"maxItems":6,"items":{"type":"object","additionalProperties":false,"required":["label"],""" +
    """"properties":{"label":{"type":"string"},"value":{"description":"参数取值（有 tool/param 时）"},""" +
    """"tool":{"type":"string","description":"multiple 时这一项的工具全名"},"arguments":{"type":"object"}}}}}}"""

internal const val SHOW_CARD_SCHEMA = """{"type":"object","additionalProperties":false,"required":["title"],""" +
    """"properties":{"title":{"type":"string"},"icon":{"type":"string"},"value":{"type":"string"},"subtitle":{"type":"string"},""" +
    """"image":{"type":"string","description":"工具结果给出的图片：设备上已保存的路径或公网 https 地址"},""" +
    """"fields":$FIELDS_SCHEMA,"layout":{"type":"string","enum":["list","grid","compare","strip","trip"]},""" +
    """"progress":{"type":"array","maxItems":3,"items":{"type":"object","additionalProperties":false,"required":["label","value"],""" +
    """"properties":{"label":{"type":"string"},"value":{"type":"number"},"max":{"type":"number"},"text":{"type":"string"}}}},""" +
    """"items":{"type":"array","maxItems":18,"items":{"type":"object","additionalProperties":false,"required":["title"],""" +
    """"properties":{"icon":{"type":"string"},"title":{"type":"string"},"detail":{"type":"string"},"value":{"type":"string"},""" +
    """"image":{"type":"string"},"group":{"type":"string","description":"list 里的分组，如 第1天；trip 里写哪一天，如 D1 10/23 周五"},""" +
    """"kind":{"type":"string","enum":["place","transport","stay","weather"],"description":"trip 用：transport 去程回程，stay 住宿，place 每天的去处（默认），weather 那天的天气（group 写哪一天，icon 写天气图标，title 写天气，value 写气温，detail 写降雨）"},""" +
    """"fields":$FIELDS_SCHEMA}}}}}"""
