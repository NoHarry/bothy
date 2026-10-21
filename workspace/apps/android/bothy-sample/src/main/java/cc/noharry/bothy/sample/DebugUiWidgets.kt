package cc.noharry.bothy.sample

import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToLong

/**
 * One control of a reply's panel: a slider, a switch or a choice, each bound to checked tool calls. A reply may hold
 * a few; one confirm sends the changed ones together ("媒体音量设为22档，座椅加热打开") as the next user turn, and
 * [debugControlPicks] reads that, or the user's "音量调到20", back into checked calls.
 */
internal sealed interface DebugControl {
    val label: String
}

/** A value to set within a range, bound to one tool and the numeric argument it varies. */
internal data class DebugSliderOffer(
    override val label: String,
    /** The tool and its fixed arguments; [param] is filled in at the chosen value. */
    val call: DebugBoundCall,
    val param: String,
    val min: Double,
    val max: Double,
    val step: Double,
    val value: Double,
    val unit: String,
    val integer: Boolean,
) : DebugControl {
    fun format(number: Double): String =
        if (integer || abs(number - round(number)) < 1e-9) number.roundToLong().toString() else (round(number * 10) / 10).toString()

    /** One slider's part of the confirmed reply, as the user would say it. */
    fun replyText(number: Double): String = "${label}设为${format(snap(number) ?: number)}$unit"

    /** On the step grid and in range, or null outside it. */
    fun snap(number: Double): Double? {
        if (number < min - EPSILON || number > max + EPSILON) return null
        val steps = round((number - min) / step)
        return (min + steps * step).coerceIn(min, max)
    }

    /** "设为30%", "调到 30" or a bare "30": the value said for this slider, on its grid, or null. */
    fun valueSaid(text: String): Double? {
        // The unit the reply was built with ("24℃") is dropped first, whatever unit the model gave the slider.
        val said = text.trim().replace(SLIDER_NOISE, "").let { if (unit.isNotEmpty()) it.removeSuffix(unit) else it }
        val match = SLIDER_VALUE.matchEntire(said) ?: return null
        return match.groupValues[1].toDoubleOrNull()?.let(::snap)
    }

    fun callAt(number: Double): DebugBoundCall {
        val fixed = parseDebugJsonObject(call.argumentsJson) ?: JsonObject(emptyMap())
        val chosen = if (integer) JsonPrimitive(number.roundToLong()) else JsonPrimitive(number)
        return call.copy(argumentsJson = JsonObject(fixed + (param to chosen)).toString())
    }
}

/** On or off, each bound to its own checked call (one tool with a state argument, or two tools). */
internal data class DebugSwitchOffer(
    override val label: String,
    val on: DebugBoundCall,
    val off: DebugBoundCall,
    /** The state now, as read before offering. */
    val value: Boolean,
) : DebugControl {
    fun replyText(state: Boolean): String = "$label${if (state) "打开" else "关闭"}"

    fun callAt(state: Boolean): DebugBoundCall = if (state) on else off
}

/** One option of a choice: what it shows and the checked call it stands for. */
internal data class DebugChoiceOption(val label: String, val call: DebugBoundCall)

/**
 * A mode or level from a few values ("自动 / 制冷 / 制热"), or with [multiple] several actions to run together
 * ("主驾 / 副驾" seat heating). [value] holds the indices chosen now: the current one for a single choice, none for
 * multiple.
 */
internal data class DebugChoiceOffer(
    override val label: String,
    val options: List<DebugChoiceOption>,
    val value: List<Int>,
    val multiple: Boolean,
) : DebugControl {
    fun replyText(chosen: List<Int>): String {
        val names = chosen.sorted().mapNotNull { options.getOrNull(it)?.label }
        return if (multiple) "${label}选${names.joinToString("、")}" else "${label}设为${names.firstOrNull().orEmpty()}"
    }

    fun callsAt(chosen: List<Int>): List<DebugBoundCall> = chosen.sorted().mapNotNull { options.getOrNull(it)?.call }

    /**
     * The option a word names exactly ("制冷", "改成制冷", "制冷模式"), or null. Only a whole label counts, like a pick
     * by label: "帮我找台自动售货机" must not set the mode 自动.
     */
    fun optionSaid(said: String): Int? {
        val key = said.offerKey().replace(CHOICE_VERB_LEADING, "").removeOptionSuffix()
        if (key.isEmpty()) return null
        return options.indexOfFirst { it.label.offerKey().removeOptionSuffix() == key }.takeIf { it >= 0 }
    }
}

/** A control's value: a slider's number, a switch's state, a choice's chosen indices. */
internal fun DebugControl.initialValue(): Any = when (this) {
    is DebugSliderOffer -> value
    is DebugSwitchOffer -> value
    is DebugChoiceOffer -> value
}

/** What [value] says for this control, as the user would say it. */
internal fun DebugControl.replyText(value: Any): String = when (this) {
    is DebugSliderOffer -> replyText(value as Double)
    is DebugSwitchOffer -> replyText(value as Boolean)
    is DebugChoiceOffer -> replyText(value.indices())
}

/** The checked calls that set [value]; none for a multiple choice with nothing chosen. */
internal fun DebugControl.callsAt(value: Any): List<DebugBoundCall> = when (this) {
    is DebugSliderOffer -> listOf(callAt(snap(value as Double) ?: value))
    is DebugSwitchOffer -> listOf(callAt(value as Boolean))
    is DebugChoiceOffer -> callsAt(value.indices())
}

/** How a chosen value reads in the next turn's frame: "「空调温度」24℃", "「座椅加热」打开". */
internal fun DebugControl.valueText(value: Any): String = "「$label」" + when (this) {
    is DebugSliderOffer -> "${format(value as Double)}$unit"
    is DebugSwitchOffer -> if (value as Boolean) "打开" else "关闭"
    is DebugChoiceOffer -> value.indices().sorted().mapNotNull { options.getOrNull(it)?.label }.joinToString("、")
}

@Suppress("UNCHECKED_CAST")
private fun Any.indices(): List<Int> = this as List<Int>

/** "制冷模式" and "2档" name the options 制冷 and 2. */
private fun String.removeOptionSuffix(): String = removeSuffix("模式").removeSuffix("档").removeSuffix("挡")

/**
 * What one confirm of [controls] at [values] sends: the controls the user changed, or all of them when nothing
 * changed (a bare confirm sets what is shown). A multiple choice with nothing chosen says nothing.
 */
internal fun debugPanelReply(controls: List<DebugControl>, values: List<Any>): String {
    val pairs = controls.zip(values)
    val changed = pairs.filter { (control, value) -> !control.sameValue(value) }
    return changed.ifEmpty { pairs }
        .filterNot { (control, value) -> control is DebugChoiceOffer && value.indices().isEmpty() }
        .joinToString("，") { (control, value) -> control.replyText(value) }
}

private fun DebugControl.sameValue(value: Any): Boolean = when (this) {
    is DebugSliderOffer -> abs((snap(value as Double) ?: value) - this.value) < EPSILON
    is DebugSwitchOffer -> value == this.value
    is DebugChoiceOffer -> value.indices().sorted() == this.value.sorted()
}

/**
 * The values a reply sets on [controls]: "媒体音量设为22档", "座椅加热打开", "空调模式改成制冷", "主驾、副驾". Each part names
 * its control unless one control could take it; a part naming only an option joins the previous part's multiple
 * choice. Null when a part names nothing, names a control twice, or its value is out of range, so the text then
 * reaches the model as said.
 */
internal fun debugControlPicks(text: String, controls: List<DebugControl>): List<Pair<DebugControl, Any>>? {
    if (controls.isEmpty()) return null
    val parts = text.trim().split(SLIDER_PARTS).map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val picks = mutableListOf<Pair<DebugControl, Any>>()
    for (part in parts) {
        val previous = picks.lastOrNull()?.first as? DebugChoiceOffer
        val (control, value) = controlPart(part, controls, previous) ?: return null
        val earlier = picks.indexOfFirst { it.first == control }
        when {
            earlier < 0 -> picks += control to value
            control is DebugChoiceOffer && control.multiple ->
                picks[earlier] = control to (picks[earlier].second.indices() + value.indices()).distinct()
            else -> return null
        }
    }
    return picks
}

private fun controlPart(part: String, controls: List<DebugControl>, previous: DebugChoiceOffer?): Pair<DebugControl, Any>? {
    sliderPart(part, controls.filterIsInstance<DebugSliderOffer>())?.let { return it }
    switchPart(part, controls.filterIsInstance<DebugSwitchOffer>())?.let { return it }
    return choicePart(part, controls.filterIsInstance<DebugChoiceOffer>(), previous)
}

private fun sliderPart(part: String, sliders: List<DebugSliderOffer>): Pair<DebugControl, Any>? {
    if (sliders.isEmpty()) return null
    val split = SLIDER_VERB.find(part)?.range?.first ?: part.indexOfFirst { it.isDigit() || it == '-' }.coerceAtLeast(0)
    val name = part.substring(0, split).offerKey()
    val slider = if (name.isEmpty()) sliders.singleOrNull() else sliders.named(name) ?: return null
    return slider?.valueSaid(part.substring(split))?.let { slider to it }
}

/** "座椅加热打开", "打开座椅加热", "关掉方向盘加热", or a bare "打开" when there is one switch. */
private fun switchPart(part: String, switches: List<DebugSwitchOffer>): Pair<DebugControl, Any>? {
    if (switches.isEmpty()) return null
    val key = part.offerKey()
    val verb = SWITCH_LEADING.find(key) ?: SWITCH_TRAILING.find(key) ?: return null
    val name = key.removeRange(verb.range)
    val switch = if (name.isEmpty()) switches.singleOrNull() else switches.named(name)
    return switch?.let { it to ('关' !in verb.value) }
}

/** "空调模式设为制冷", "模式改成制冷", "制冷"; an option alone goes to the one choice that has it, else to [previous]. */
private fun choicePart(part: String, choices: List<DebugChoiceOffer>, previous: DebugChoiceOffer?): Pair<DebugControl, Any>? {
    if (choices.isEmpty()) return null
    val key = part.offerKey()
    val split = CHOICE_VERB.find(key)?.range
    val named = split?.let { key.substring(0, it.first) }?.takeIf { it.isNotEmpty() }?.let { choices.named(it) }
        ?: choices.filter { it.label.offerKey().let { label -> label.isNotEmpty() && key.startsWith(label) } }.maxByOrNull { it.label.length }
    if (named != null) {
        val said = if (key.startsWith(named.label.offerKey())) key.removePrefix(named.label.offerKey()) else key.substring(split?.first ?: 0)
        return named.optionSaid(said)?.let { named to listOf(it) }
    }
    previous?.optionSaid(key)?.let { return previous to listOf(it) }
    val holders = choices.mapNotNull { choice -> choice.optionSaid(key)?.let { choice to listOf(it) } }
    return holders.singleOrNull()
}

/** The one control whose label contains [name] or is contained in it ("音量" for 媒体音量). */
private fun <T : DebugControl> List<T>.named(name: String): T? =
    filter { it.label.offerKey().let { label -> label.contains(name) || name.contains(label) } }.singleOrNull()

/**
 * Information shown as a card, weather style: a lead icon and value, a few labelled fields, progress bars, and items
 * laid out as rows, a grid of readings, a comparison, or a strip (a forecast).
 */
internal data class DebugCard(
    val title: String,
    val icon: String?,
    val value: String?,
    val subtitle: String?,
    val fields: List<Pair<String, String>>,
    val items: List<DebugCardItem>,
    /** A saved picture (`generated-images/<folder>/<file>`) shown above the card, such as a delegated report's photo. */
    val image: String? = null,
    val layout: DebugCardLayout = DebugCardLayout.LIST,
    val progress: List<DebugCardProgress> = emptyList(),
) {
    /**
     * The picture shown as the big tappable frame beside the reply: only a card that is mostly its photo (a delegated
     * restaurant). With items (a trip's stops) the photo is a banner inside the card, so the rows still fit.
     */
    val framedPicture: String? get() = image?.takeIf { items.isEmpty() }
}

/** One item; [value] is its reading ("2.4 bar", "30/22°"), [fields] its rows in a comparison. */
internal data class DebugCardItem(
    val icon: String?,
    val title: String,
    val detail: String?,
    val value: String? = null,
    /** A saved picture, like the card's own. */
    val image: String? = null,
    val fields: List<Pair<String, String>> = emptyList(),
    /** A list section, such as a trip's day; consecutive items of one group sit under one heading. */
    val group: String? = null,
    /** What the item is on a [DebugCardLayout.TRIP] card: a stop of a day, a leg there or back, or a hotel. */
    val kind: DebugCardItemKind = DebugCardItemKind.PLACE,
)

internal enum class DebugCardItemKind(val wire: String) { PLACE("place"), TRANSPORT("transport"), STAY("stay"), WEATHER("weather") }

internal data class DebugCardProgress(val label: String, val value: Double, val max: Double, val text: String?)

/** How a card lays out its items, and how many it takes. */
internal enum class DebugCardLayout(val wire: String, val items: IntRange) {
    /** Icon or picture rows, the reading at the end, in sections when items carry a group (a trip's days). */
    /** Up to 14: a three-day trip with its flights and hotels (owner 2026-10-17). */
    LIST("list", 0..14),
    /** Two columns of tiles: four tyres, battery and range. */
    GRID("grid", 2..6),
    /** Two or three side by side, a row per field: charging stations, parking. */
    COMPARE("compare", 2..3),
    /** One row of short columns: the days of a forecast. */
    STRIP("strip", 2..6),
    /** A trip on the wide card: legs as tickets, a column per day, hotels with pictures. */
    TRIP("trip", 1..18),
}

/**
 * The controls as one A2UI surface: control i binds `/v{i}`, and one confirm Button sends every value in its event,
 * so setting the second does not end the first.
 */
internal fun debugA2uiPanelMessages(surfaceId: String, controls: List<DebugControl>): List<String> {
    val components = buildJsonArray {
        add(a2uiComponent("root", "Column") {
            put("align", "stretch")
            putJsonArray("children") {
                controls.forEachIndexed { index, control ->
                    add(controlId(index))
                    if (control is DebugSliderOffer) add("range_$index")
                }
                add("confirm")
            }
        })
        controls.forEachIndexed { index, control ->
            when (control) {
                is DebugSliderOffer -> addSlider(index, control)
                is DebugSwitchOffer -> addSwitch(index, control)
                is DebugChoiceOffer -> addChoice(index, control)
            }
        }
        add(a2uiComponent("confirm_label", "Text") { put("text", "确定") })
        // Not "primary": the device look marks a primary Button as the recommended pick.
        add(a2uiComponent("confirm", "Button") {
            put("child", "confirm_label")
            putJsonObject("action") {
                putJsonObject("event") {
                    put("name", DEBUG_A2UI_SET_EVENT)
                    putJsonObject("context") {
                        controls.indices.forEach { putJsonObject(controlKey(it)) { put("path", "/${controlKey(it)}") } }
                    }
                }
            }
        })
    }
    return listOf(
        a2uiCreateSurface(surfaceId),
        a2uiMessage("updateComponents") {
            put("surfaceId", surfaceId)
            put("components", components)
        },
        panelDataModel(surfaceId, controls),
    )
}

/** Each control's value now, under its key: a number, a state, or the chosen option indices as strings. */
private fun panelDataModel(surfaceId: String, controls: List<DebugControl>): String = a2uiMessage("updateDataModel") {
    put("surfaceId", surfaceId)
    put("path", "/")
    putJsonObject("value") {
        controls.forEachIndexed { index, control ->
            when (control) {
                is DebugSliderOffer -> put(controlKey(index), control.value)
                is DebugSwitchOffer -> put(controlKey(index), control.value)
                is DebugChoiceOffer -> putJsonArray(controlKey(index)) { control.value.forEach { add(it.toString()) } }
            }
        }
    }
}

private fun JsonArrayBuilder.addSlider(index: Int, slider: DebugSliderOffer) {
    add(a2uiComponent(controlId(index), "Slider") {
        put("label", if (slider.unit.isBlank()) slider.label else "${slider.label}（${slider.unit}）")
        put("min", slider.min)
        put("max", slider.max)
        putJsonObject("value") { put("path", "/${controlKey(index)}") }
    })
    add(a2uiComponent("range_$index", "Text") {
        put("text", "${slider.format(slider.min)}${slider.unit} – ${slider.format(slider.max)}${slider.unit}")
        put("variant", "caption")
    })
}

private fun JsonArrayBuilder.addSwitch(index: Int, switch: DebugSwitchOffer) {
    add(a2uiComponent(controlId(index), "CheckBox") {
        put("label", switch.label)
        putJsonObject("value") { put("path", "/${controlKey(index)}") }
    })
}

private fun JsonArrayBuilder.addChoice(index: Int, choice: DebugChoiceOffer) {
    add(a2uiComponent(controlId(index), "ChoicePicker") {
        put("label", choice.label)
        put("variant", if (choice.multiple) "multipleSelection" else "mutuallyExclusive")
        // Short labels sit side by side as chips; long ones read better one per row.
        put("displayStyle", if (choice.options.all { it.label.length <= CHIP_LABEL_MAX }) "chips" else "checkbox")
        putJsonArray("options") {
            choice.options.forEachIndexed { option, it ->
                add(buildJsonObject {
                    put("label", it.label)
                    put("value", option.toString())
                })
            }
        }
        putJsonObject("value") { put("path", "/${controlKey(index)}") }
    })
}

private fun controlId(index: Int) = "control_$index"

/** The data model key, and event context key, of control [index]. */
internal fun controlKey(index: Int): String = "v$index"

/** The card as an A2UI surface: header (icon, value, subtitle), a row of fields, progress bars, then the items. */
internal fun debugA2uiCardMessages(surfaceId: String, card: DebugCard): List<String> {
    val components = buildJsonArray {
        add(a2uiComponent("root", "Column") {
            put("align", "stretch")
            putJsonArray("children") {
                if (card.image != null && card.framedPicture == null) add("banner")
                add("header")
                if (card.fields.isNotEmpty()) add("fields")
                card.progress.indices.forEach { add("progress_$it") }
                if (card.items.isNotEmpty()) add("items")
            }
        })
        if (card.image != null && card.framedPicture == null) add(a2uiPicture("banner", card.image, card.title, "header"))
        addCardHeader(card)
        if (card.fields.isNotEmpty()) addCardFields(card.fields)
        card.progress.forEachIndexed { index, bar ->
            add(a2uiComponent("progress_$index", PROGRESS_COMPONENT) {
                put("label", bar.label)
                put("value", bar.value)
                put("max", bar.max)
                bar.text?.let { put("text", it) }
            })
        }
        if (card.items.isNotEmpty()) {
            when (card.layout) {
                DebugCardLayout.LIST -> addItemRows(card.items)
                DebugCardLayout.GRID -> addItemTiles(card.items, columns = 2, centered = false)
                DebugCardLayout.STRIP -> addItemTiles(card.items, columns = card.items.size, centered = true)
                DebugCardLayout.COMPARE -> addComparison(card.items)
                DebugCardLayout.TRIP -> addTrip(card.items)
            }
        }
    }
    return listOf(
        a2uiCreateSurface(surfaceId),
        a2uiMessage("updateComponents") {
            put("surfaceId", surfaceId)
            put("components", components)
        },
    )
}

private fun JsonArrayBuilder.addCardHeader(card: DebugCard) {
    add(a2uiComponent("header", "Row") {
        put("align", "center")
        putJsonArray("children") {
            if (card.icon != null) add("header_icon")
            add("header_text")
        }
    })
    card.icon?.let { name -> add(a2uiComponent("header_icon", "Icon") { put("name", debugIconJson(name)) }) }
    add(a2uiComponent("header_text", "Column") {
        putJsonArray("children") {
            add("title")
            if (card.value != null) add("value")
            if (card.subtitle != null) add("subtitle")
        }
    })
    add(a2uiText("title", card.title, "caption"))
    card.value?.let { add(a2uiText("value", it, "h1")) }
    card.subtitle?.let { add(a2uiText("subtitle", it, "body")) }
}

private fun JsonArrayBuilder.addCardFields(fields: List<Pair<String, String>>) {
    add(a2uiComponent("fields", "Row") {
        put("justify", "spaceBetween")
        putJsonArray("children") { fields.indices.forEach { add("field_$it") } }
    })
    fields.forEachIndexed { index, (label, value) ->
        add(a2uiComponent("field_$index", "Column") {
            putJsonArray("children") {
                add("field_${index}_label")
                add("field_${index}_value")
            }
        })
        add(a2uiText("field_${index}_label", label, "caption"))
        add(a2uiText("field_${index}_value", value, "h5"))
    }
}

/** Icon (or picture) rows; a reading sits at the row's end, and a new group starts with its heading. */
private fun JsonArrayBuilder.addItemRows(items: List<DebugCardItem>) {
    val headed = items.indices.filter { index -> items[index].group != null && items[index].group != items.getOrNull(index - 1)?.group }
    add(a2uiComponent("items", "Column") {
        put("align", "stretch")
        putJsonArray("children") {
            items.indices.forEach { index ->
                if (index in headed) add("group_$index")
                add("item_$index")
            }
        }
    })
    headed.forEach { index -> add(a2uiText("group_$index", items[index].group.orEmpty(), "h4")) }
    items.forEachIndexed { index, item ->
        val id = "item_$index"
        if (item.value == null && item.image == null) {
            addIconRow(id, item.icon, item.title, item.detail)
            return@forEachIndexed
        }
        add(a2uiComponent(id, "Row") {
            put("align", "center")
            put("justify", "spaceBetween")
            putJsonArray("children") {
                add("${id}_main")
                if (item.value != null) add("${id}_value")
            }
        })
        if (item.image != null) {
            add(a2uiComponent("${id}_main", "Row") {
                put("align", "center")
                putJsonArray("children") {
                    add("${id}_picture")
                    add("${id}_text")
                }
            })
            add(a2uiPicture("${id}_picture", item.image, item.title, "smallFeature"))
            add(a2uiComponent("${id}_text", "Column") {
                putJsonArray("children") {
                    add("${id}_title")
                    if (item.detail != null) add("${id}_detail")
                }
            })
            add(a2uiText("${id}_title", item.title, "h5"))
            item.detail?.let { add(a2uiText("${id}_detail", it, "caption")) }
        } else {
            addIconRow("${id}_main", item.icon, item.title, item.detail)
        }
        item.value?.let { add(a2uiText("${id}_value", it, "h4")) }
    }
}

/**
 * Items as equal tiles in a [GRID_COMPONENT]: a name over a large reading and a note (a tyre, a day). [centered]
 * stacks a strip's short columns around their middle.
 */
private fun JsonArrayBuilder.addItemTiles(items: List<DebugCardItem>, columns: Int, centered: Boolean) {
    add(a2uiComponent("items", GRID_COMPONENT) {
        put("columns", columns.coerceAtMost(MAX_GRID_COLUMNS))
        putJsonArray("children") { items.indices.forEach { add("item_$it") } }
    })
    items.forEachIndexed { index, item ->
        val id = "item_$index"
        add(a2uiComponent(id, "Column") {
            put("align", if (centered) "center" else "start")
            putJsonArray("children") {
                if (item.image != null) add("${id}_picture")
                add("${id}_title")
                if (item.icon != null) add("${id}_icon")
                if (item.value != null) add("${id}_value")
                if (item.detail != null) add("${id}_detail")
            }
        })
        item.image?.let { add(a2uiPicture("${id}_picture", it, item.title, "mediumFeature")) }
        add(a2uiText("${id}_title", item.title, "caption"))
        item.icon?.let { name -> add(a2uiComponent("${id}_icon", "Icon") { put("name", debugIconJson(name)) }) }
        item.value?.let { add(a2uiText("${id}_value", it, if (centered) "h5" else "h3")) }
        item.detail?.let { add(a2uiText("${id}_detail", it, "caption")) }
    }
}

/**
 * Two or three items side by side as a table: a column per item and a row per field, every row one [GRID_COMPONENT]
 * row so its cells line up whatever their text. The first column names the fields.
 */
private fun JsonArrayBuilder.addComparison(items: List<DebugCardItem>) {
    val labels = items.flatMap { item -> item.fields.map { it.first } }.distinct()
    val cells = mutableListOf<String>()
    fun row(name: String, build: JsonArrayBuilder.(String, Int) -> Unit) {
        cells += "${name}_label"
        add(a2uiText("${name}_label", "", "caption"))
        items.indices.forEach { column ->
            val id = "${name}_$column"
            cells += id
            build(id, column)
        }
    }
    if (items.any { it.image != null }) {
        row("pictures") { id, column ->
            val item = items[column]
            if (item.image != null) add(a2uiPicture(id, item.image, item.title, "mediumFeature")) else add(a2uiText(id, "", "caption"))
        }
    }
    row("names") { id, column -> add(a2uiText(id, items[column].title, "h5")) }
    if (items.any { it.value != null }) row("values") { id, column -> add(a2uiText(id, items[column].value ?: "—", "h3")) }
    if (items.any { it.detail != null }) row("details") { id, column -> add(a2uiText(id, items[column].detail.orEmpty(), "caption")) }
    labels.forEachIndexed { index, label ->
        cells += "field_${index}_label"
        add(a2uiText("field_${index}_label", label, "caption"))
        items.forEachIndexed { column, item ->
            val id = "field_${index}_$column"
            cells += id
            add(a2uiText(id, item.fields.firstOrNull { it.first == label }?.second ?: "—", "body"))
        }
    }
    add(a2uiComponent("items", GRID_COMPONENT) {
        put("columns", items.size + 1)
        put("framed", false)
        putJsonArray("children") { cells.forEach { add(it) } }
    })
}

/**
 * A trip on the wide card (owner 2026-10-17: 信息量大的时候卡片扩大，以更人类友好的形式展示): a [TICKET_COMPONENT] per
 * direction (other flights of it as options), a column per day with its name, its first photo and a [STOP_COMPONENT]
 * per place, and the hotels as pictures over a [STAY_COMPONENT].
 */
private fun JsonArrayBuilder.addTrip(items: List<DebugCardItem>) {
    val indexed = items.withIndex().toList()
    val legs = indexed.filter { it.value.kind == DebugCardItemKind.TRANSPORT }.groupBy { it.value.legName() }
    val stays = indexed.filter { it.value.kind == DebugCardItemKind.STAY }
    // A day is its places and its weather, in the order the days first come.
    val days = indexed.filter { it.value.kind == DebugCardItemKind.PLACE || it.value.kind == DebugCardItemKind.WEATHER }
        .groupBy { it.value.group ?: TRIP_DAY_FALLBACK }
    add(a2uiComponent("items", "Column") {
        put("align", "stretch")
        putJsonArray("children") {
            if (legs.isNotEmpty()) add("trip_legs")
            if (days.isNotEmpty()) add("trip_days")
            if (stays.isNotEmpty()) {
                add("trip_stays_title")
                add("trip_stays")
            }
        }
    })
    if (legs.isNotEmpty()) addTripTickets(legs)
    if (days.isNotEmpty()) addTripDays(days)
    if (stays.isNotEmpty()) addTripStays(stays)
}

/** 「去程 · 9C8819 春秋航空」: the direction before the dot, the flight after it. */
private fun DebugCardItem.legName(): String = if ('·' in title) title.substringBefore('·').trim() else title

private fun DebugCardItem.service(): String = if ('·' in title) title.substringAfter('·').trim() else ""

private fun JsonArrayBuilder.addTripTickets(legs: Map<String, List<IndexedValue<DebugCardItem>>>) {
    val all = legs.values.flatten().map { it.value }
    add(a2uiComponent("trip_legs", GRID_COMPONENT) {
        put("columns", legs.size.coerceIn(1, TRIP_TICKET_COLUMNS))
        putJsonArray("children") { legs.keys.indices.forEach { add("trip_ticket_$it") } }
    })
    legs.entries.forEachIndexed { index, (leg, options) ->
        val main = options.first().value
        val route = main.detail?.substringBefore(" · ")
        val alternatives = options.drop(1).joinToString("；") { listOf(it.value.service(), it.value.value.orEmpty()).joinToString(" ").trim() }
        val note = listOfNotNull(main.detail?.substringAfter(" · ", "")?.ifBlank { null }, alternatives.ifBlank { null }?.let { "备选 $it" })
            .joinToString(" · ")
        add(a2uiComponent("trip_ticket_$index", TICKET_COMPONENT) {
            put("leg", if (leg == main.title) "" else leg)
            put("service", main.service().ifEmpty { main.title })
            main.value?.let { put("times", it) }
            route?.let { put("route", it) }
            if (note.isNotEmpty()) put("note", note)
            put("mode", main.tripMode(all).wire)
        })
    }
}

/** A column per day: its name, its weather, its first photo, then a [STOP_COMPONENT] per place. */
private fun JsonArrayBuilder.addTripDays(days: Map<String, List<IndexedValue<DebugCardItem>>>) {
    add(a2uiComponent("trip_days", GRID_COMPONENT) {
        put("columns", days.size.coerceIn(1, TRIP_MAX_COLUMNS))
        putJsonArray("children") { days.keys.indices.forEach { add("trip_day_$it") } }
    })
    days.entries.forEachIndexed { day, (name, entries) ->
        val column = "trip_day_$day"
        val weather = entries.firstOrNull { it.value.kind == DebugCardItemKind.WEATHER }?.value
        val stops = entries.filter { it.value.kind == DebugCardItemKind.PLACE }
        val photo = stops.firstNotNullOfOrNull { it.value.image }
        add(a2uiComponent(column, "Column") {
            putJsonArray("children") {
                add("${column}_name")
                if (weather != null) add("${column}_weather")
                if (photo != null) add("${column}_photo")
                stops.forEach { add("trip_item_${it.index}") }
            }
        })
        add(a2uiText("${column}_name", name, "h4"))
        weather?.let { addDayWeather("${column}_weather", it) }
        photo?.let { add(a2uiPicture("${column}_photo", it, name, "mediumFeature")) }
        stops.forEach { (index, stop) ->
            add(a2uiComponent("trip_item_$index", STOP_COMPONENT) {
                put("title", stop.title)
                stop.value?.let { put("time", it) }
                stop.detail?.let { put("note", it) }
            })
        }
    }
}

/** A day's weather in one line: its icon, the sky and the temperatures, then the rain chance. */
private fun JsonArrayBuilder.addDayWeather(id: String, weather: DebugCardItem) {
    add(a2uiComponent(id, "Row") {
        put("align", "center")
        putJsonArray("children") {
            if (weather.icon != null) add("${id}_icon")
            add("${id}_text")
        }
    })
    weather.icon?.let { name -> add(a2uiComponent("${id}_icon", "Icon") { put("name", debugIconJson(name)) }) }
    add(a2uiText("${id}_text", listOfNotNull(weather.title, weather.value, weather.detail).joinToString(" · "), "body"))
}

/** Hotels in a row, each a [STAY_COMPONENT] with its picture as a thumbnail, so they fit under the days. */
private fun JsonArrayBuilder.addTripStays(stays: List<IndexedValue<DebugCardItem>>) {
    add(a2uiText("trip_stays_title", TRIP_STAYS_TITLE, "h4"))
    add(a2uiComponent("trip_stays", GRID_COMPONENT) {
        put("columns", stays.size.coerceIn(1, TRIP_MAX_COLUMNS))
        putJsonArray("children") { stays.forEach { add("trip_item_${it.index}") } }
    })
    stays.forEach { (index, stay) ->
        add(a2uiComponent("trip_item_$index", STAY_COMPONENT) {
            put("title", stay.title)
            stay.value?.let { put("price", it) }
            stay.detail?.let { put("note", it) }
            stay.image?.let { put("image", it) }
        })
    }
}

private fun a2uiPicture(id: String, path: String, description: String, variant: String): JsonObject = a2uiComponent(id, "Image") {
    put("url", path)
    put("description", description)
    put("fit", "cover")
    put("variant", variant)
}

/** A row of an icon, a title and an optional detail; used by card items and by icon list choices. */
internal fun JsonArrayBuilder.addIconRow(id: String, icon: String?, title: String, detail: String?) {
    add(a2uiComponent(id, "Row") {
        put("align", "center")
        putJsonArray("children") {
            if (icon != null) add("${id}_icon")
            add("${id}_text")
        }
    })
    icon?.let { name -> add(a2uiComponent("${id}_icon", "Icon") { put("name", debugIconJson(name)) }) }
    add(a2uiComponent("${id}_text", "Column") {
        putJsonArray("children") {
            add("${id}_title")
            if (detail != null) add("${id}_detail")
        }
    })
    add(a2uiText("${id}_title", title, "h5"))
    detail?.let { add(a2uiText("${id}_detail", it, "caption")) }
}

private fun a2uiText(id: String, text: String, variant: String): JsonObject = a2uiComponent(id, "Text") {
    put("text", text)
    put("variant", variant)
}

/** A built-in icon name as is; a device or weather glyph as an SVG path the Icon component draws. */
internal fun debugIconJson(name: String): JsonElement =
    DEBUG_SVG_ICONS[name]?.let { path -> buildJsonObject { put("svgPath", path) } } ?: JsonPrimitive(name)

internal fun debugIconKnown(name: String): Boolean = name in DEBUG_SVG_ICONS || name in DEBUG_BUILT_IN_ICONS

/** The A2UI basic catalog's own icons (lower camel case, as the protocol names them). */
internal val DEBUG_BUILT_IN_ICONS = setOf(
    "accountCircle", "add", "arrowBack", "arrowForward", "calendarToday", "call", "camera", "check", "close", "delete",
    "download", "edit", "error", "event", "favorite", "folder", "help", "home", "info", "locationOn", "lock",
    "lockOpen", "mail", "menu", "notifications", "pause", "person", "phone", "photo", "play", "refresh", "search",
    "send", "settings", "share", "skipNext", "skipPrevious", "star", "stop", "visibility", "volumeDown", "volumeMute",
    "volumeOff", "volumeUp", "warning",
)

/**
 * Glyphs the catalog lacks, on a 24×24 grid: weather and car places; then places and trips from Material Icons
 * (Apache 2.0, the catalog's own look; the model reached for 「food」 30 times on the bench and fell back to 「star」).
 * Holes run counter-clockwise.
 */
internal val DEBUG_SVG_ICONS = mapOf(
    "sunny" to "M12 7a5 5 0 1 0 0 10a5 5 0 1 0 0-10Z M11 1h2v3h-2Z M11 20h2v3h-2Z M1 11h3v2H1Z M20 11h3v2h-3Z " +
        "M4.2 5.6L5.6 4.2L7.7 6.3L6.3 7.7Z M16.3 17.7L17.7 16.3L19.8 18.4L18.4 19.8Z " +
        "M4.2 18.4L6.3 16.3L7.7 17.7L5.6 19.8Z M16.3 6.3L18.4 4.2L19.8 5.6L17.7 7.7Z",
    "cloudy" to CLOUD,
    "partlyCloudy" to "M8 4a3 3 0 1 0 0 6a3 3 0 1 0 0-6Z M7.25 0.5h1.5v2h-1.5Z M0.5 6.25h2v1.5h-2Z " +
        "M2.4 3.5L3.5 2.4L4.9 3.8L3.8 4.9Z M11.1 3.8L12.5 2.4L13.6 3.5L12.2 4.9Z " +
        "M9 22a4 4 0 0 1-0.4-7.98A5.3 5.3 0 0 1 18.9 13.2A4.2 4.2 0 0 1 18.8 22Z",
    "rain" to "$CLOUD_HIGH M8 17h1.6l-1.1 4.5H6.9Z M12 17h1.6l-1.1 4.5H10.9Z M16 17h1.6l-1.1 4.5H14.9Z",
    "snow" to "$CLOUD_HIGH M8 17.5a1.2 1.2 0 1 0 0 2.4a1.2 1.2 0 1 0 0-2.4Z M12 19a1.2 1.2 0 1 0 0 2.4a1.2 1.2 0 1 0 0-2.4Z " +
        "M16 17.5a1.2 1.2 0 1 0 0 2.4a1.2 1.2 0 1 0 0-2.4Z",
    "thunderstorm" to "$CLOUD_HIGH M12.5 15L9.5 20.5H12L11 24L15.5 18H13L14 15Z",
    "fog" to "M3 7h18v2H3Z M3 11h18v2H3Z M3 15h18v2H3Z M6 19h12v2H6Z",
    "wind" to "M3 8h10a2 2 0 1 0-2-2H9a4 4 0 1 1 4 4H3Z M3 12h14a3 3 0 1 1-3 3h2a1 1 0 1 0 1-1H3Z",
    "charging" to "M13 2L4 14h6l-1 8l9-12h-6l1-8Z",
    "parking" to "M6 3h7a6 6 0 0 1 0 12H9v6H6Z M9 6v6h4a3 3 0 0 0 0-6Z",
    "fuel" to "M5 3h9v18H5Z M7 5v5h5V5Z M14 8h2l3 3v7a1.5 1.5 0 0 1-3 0v-4h-2v-2h3v-2.2L15.8 10H14Z",
    "temperature" to "M10 4a2 2 0 0 1 4 0v9.5a4.5 4.5 0 1 1-4 0Z",
    "music" to "M9 3v11.6A3.5 3.5 0 1 0 11 18V7h6V3Z",
    "food" to "M11 9H9V2H7v7H5V2H3v7c0 2.12 1.66 3.84 3.75 3.97V22h2.5v-9.03C11.34 12.84 13 11.12 13 9V2h-2v7z" +
        "m5-3v8h2.5v8H21V2c-2.76 0-5 2.24-5 4z",
    "hotel" to "M7 13c1.66 0 3-1.34 3-3S8.66 7 7 7s-3 1.34-3 3 1.34 3 3 3z" +
        "m12-6h-8v7H3V5H1v15h2v-3h18v3h2v-9c0-2.21-1.79-4-4-4z",
    "flight" to "M21 16v-2l-8-5V3.5c0-.83-.67-1.5-1.5-1.5S10 2.67 10 3.5V9l-8 5v2l8-2.5V19l-2 1.5V22" +
        "l3.5-1 3.5 1v-1.5L13 19v-5.5l8 2.5z",
    "train" to "M12 2c-4 0-8 .5-8 4v9.5C4 17.43 5.57 19 7.5 19L6 20.5v.5h2.23l2-2H14l2 2h2v-.5" +
        "L16.5 19c1.93 0 3.5-1.57 3.5-3.5V6c0-3.5-3.58-4-8-4z" +
        "M7.5 17c-.83 0-1.5-.67-1.5-1.5S6.67 14 7.5 14s1.5.67 1.5 1.5S8.33 17 7.5 17zm3.5-7H6V6h5v4z" +
        "m2 0V6h5v4h-5zm3.5 7c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5z",
    "coffee" to "M20 3H4v10c0 2.21 1.79 4 4 4h6c2.21 0 4-1.79 4-4v-3h2c1.11 0 2-.89 2-2V5c0-1.11-.89-2-2-2z" +
        "m0 5h-2V5h2v3zM4 19h16v2H4z",
    "shopping" to "M19 6h-2c0-2.76-2.24-5-5-5S7 3.24 7 6H5c-1.1 0-1.99.9-1.99 2L3 20c0 1.1.9 2 2 2" +
        "h14c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2z" +
        "m-7-3c1.66 0 3 1.34 3 3H9c0-1.66 1.34-3 3-3z" +
        "m0 10c-2.76 0-5-2.24-5-5h2c0 1.66 1.34 3 3 3s3-1.34 3-3h2c0 2.76-2.24 5-5 5z",
    "scenic" to "M14 6l-3.75 5 2.85 3.8-1.6 1.2C9.81 13.75 7 10 7 10l-6 8h22L14 6z",
    "ticket" to "M20 12c0-1.1.9-2 2-2V6c0-1.1-.9-2-2-2H4c-1.1 0-1.99.9-1.99 2v4c1.1 0 1.99.9 1.99 2" +
        "s-.89 2-2 2v4c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2v-4c-1.1 0-2-.9-2-2z" +
        "m-4.42 4.8L12 14.5l-3.58 2.3 1.08-4.12-3.29-2.69 4.24-.25L12 5.8l1.54 3.95 4.24.25-3.29 2.69 1.09 4.11z",
)

private const val CLOUD = "M6.5 19a4.5 4.5 0 0 1-0.5-8.97A6 6 0 0 1 17.65 9.5A4.75 4.75 0 0 1 17.5 19Z"
private const val CLOUD_HIGH = "M6.5 15a4.5 4.5 0 0 1-0.5-8.97A6 6 0 0 1 17.65 5.5A4.75 4.75 0 0 1 17.5 15Z"
internal const val DEBUG_A2UI_SET_EVENT = "set"
private const val EPSILON = 1e-6
private const val TRIP_MAX_COLUMNS = 3
/** There and back side by side. */
private const val TRIP_TICKET_COLUMNS = 2
private const val TRIP_DAY_FALLBACK = "行程"
private const val TRIP_STAYS_TITLE = "住宿"
/** "设为30%", "调到 30", "30" as a tap, a keyboard or the user says it. */
private val SLIDER_VALUE = Regex("""^(?:设为|设到|设成|调到|调成|调为|改成|改为|到)?(-?\d+(?:\.\d+)?)(?:%|％|度|档|级|格|℃|°C|°)?$""")
private val SLIDER_NOISE = Regex("""[\s。！!？?~～，,]""")
/** Parts of a reply that sets several sliders: "媒体音量设为22档，中控屏亮度设为60%", "音量调到20和亮度调到60". */
private val SLIDER_PARTS = Regex("""[，,；;、]|和|并且|再把""")
/** Where a part's slider name ends and its value begins. */
private val SLIDER_VERB = Regex("""设为|设到|设成|调到|调成|调为|改成|改为|到""")
/** A switch's state said before or after its name: "打开座椅加热", "座椅加热关掉". */
private val SWITCH_LEADING = Regex("""^(?:请|把)?(?:打开|开启|关闭|关掉|关上|开|关)""")
private val SWITCH_TRAILING = Regex("""(?:打开|开启|关闭|关掉|关上|开|关)(?:一下)?$""")
/** Where a choice's name ends and its option begins: "空调模式设为制冷", "座椅加热选主驾". */
private val CHOICE_VERB = Regex("""设为|设成|改成|改为|换成|切换到|切换成|切到|调成|调为|选|用""")
private val CHOICE_VERB_LEADING = Regex("""^(?:设为|设成|改成|改为|换成|切换到|切换成|切到|调成|调为|选|用)""")
/** Labels up to this many characters sit side by side as chips. */
private const val CHIP_LABEL_MAX = 6
