package cc.noharry.bothy.app.a2ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cc.noharry.agent.appserver.protocol.view.CardItem
import cc.noharry.agent.appserver.protocol.view.CardItemKind
import cc.noharry.agent.appserver.protocol.view.CardLayout
import cc.noharry.agent.appserver.protocol.view.ChoiceOffer
import cc.noharry.agent.appserver.protocol.view.Control
import cc.noharry.agent.appserver.protocol.view.SliderOffer
import cc.noharry.agent.appserver.protocol.view.SwitchOffer
import cc.noharry.agent.appserver.protocol.view.WidgetCard
import cc.noharry.agent.appserver.protocol.view.iconJson
import cc.noharry.agent.appserver.protocol.view.tripMode
import cc.noharry.bothy.app.R
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The host's own words on a card or panel, around what the model wrote: the panel's confirm, the day of a trip's stops
 * that name none, and the heading over its hotels. The screen passes them in its language ([a2uiLabels]).
 */
internal data class A2uiLabels(val confirm: String, val tripDay: String, val stays: String) {
    companion object {
        /** What the surfaces showed before their callers passed the screen's words. */
        val CHINESE = A2uiLabels(confirm = "确定", tripDay = "行程", stays = "住宿")
    }
}

/** [A2uiLabels] in the screen's language. */
@Composable
internal fun a2uiLabels(): A2uiLabels = A2uiLabels(
    confirm = stringResource(R.string.common_ok),
    tripDay = stringResource(R.string.a2ui_trip_day),
    stays = stringResource(R.string.a2ui_trip_stays),
)

/**
 * The controls as one A2UI surface: control i binds `/v{i}`, and one confirm Button sends every value in its event,
 * so setting the second does not end the first.
 */
internal fun a2uiPanelMessages(
    surfaceId: String,
    controls: List<Control>,
    labels: A2uiLabels = A2uiLabels.CHINESE,
): List<String> {
    val components = buildJsonArray {
        add(a2uiComponent("root", "Column") {
            put("align", "stretch")
            putJsonArray("children") {
                controls.forEachIndexed { index, control ->
                    add(controlId(index))
                    if (control is SliderOffer) add("range_$index")
                }
                add("confirm")
            }
        })
        controls.forEachIndexed { index, control ->
            when (control) {
                is SliderOffer -> addSlider(index, control)
                is SwitchOffer -> addSwitch(index, control)
                is ChoiceOffer -> addChoice(index, control)
            }
        }
        add(a2uiComponent("confirm_label", "Text") { put("text", labels.confirm) })
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
private fun panelDataModel(surfaceId: String, controls: List<Control>): String = a2uiMessage("updateDataModel") {
    put("surfaceId", surfaceId)
    put("path", "/")
    putJsonObject("value") {
        controls.forEachIndexed { index, control ->
            when (control) {
                is SliderOffer -> put(controlKey(index), control.value)
                is SwitchOffer -> put(controlKey(index), control.value)
                is ChoiceOffer -> putJsonArray(controlKey(index)) { control.value.forEach { add(it.toString()) } }
            }
        }
    }
}

private fun JsonArrayBuilder.addSlider(index: Int, slider: SliderOffer) {
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

private fun JsonArrayBuilder.addSwitch(index: Int, switch: SwitchOffer) {
    add(a2uiComponent(controlId(index), "CheckBox") {
        put("label", switch.label)
        putJsonObject("value") { put("path", "/${controlKey(index)}") }
    })
}

private fun JsonArrayBuilder.addChoice(index: Int, choice: ChoiceOffer) {
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
internal fun a2uiCardMessages(
    surfaceId: String,
    card: WidgetCard,
    labels: A2uiLabels = A2uiLabels.CHINESE,
): List<String> {
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
        card.image?.takeIf { card.framedPicture == null }?.let { add(a2uiPicture("banner", it, card.title, "header")) }
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
                CardLayout.LIST -> addItemRows(card.items)
                CardLayout.GRID -> addItemTiles(card.items, columns = 2, centered = false)
                CardLayout.STRIP -> addItemTiles(card.items, columns = card.items.size, centered = true)
                CardLayout.COMPARE -> addComparison(card.items)
                CardLayout.TRIP -> addTrip(card.items, labels)
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

private fun JsonArrayBuilder.addCardHeader(card: WidgetCard) {
    add(a2uiComponent("header", "Row") {
        put("align", "center")
        putJsonArray("children") {
            if (card.icon != null) add("header_icon")
            add("header_text")
        }
    })
    card.icon?.let { name -> add(a2uiComponent("header_icon", "Icon") { put("name", iconJson(name)) }) }
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
private fun JsonArrayBuilder.addItemRows(items: List<CardItem>) {
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
        val image = item.image
        if (image != null) {
            add(a2uiComponent("${id}_main", "Row") {
                put("align", "center")
                putJsonArray("children") {
                    add("${id}_picture")
                    add("${id}_text")
                }
            })
            add(a2uiPicture("${id}_picture", image, item.title, "smallFeature"))
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
private fun JsonArrayBuilder.addItemTiles(items: List<CardItem>, columns: Int, centered: Boolean) {
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
        item.icon?.let { name -> add(a2uiComponent("${id}_icon", "Icon") { put("name", iconJson(name)) }) }
        item.value?.let { add(a2uiText("${id}_value", it, if (centered) "h5" else "h3")) }
        item.detail?.let { add(a2uiText("${id}_detail", it, "caption")) }
    }
}

/**
 * Two or three items side by side as a table: a column per item and a row per field, every row one [GRID_COMPONENT]
 * row so its cells line up whatever their text. The first column names the fields.
 */
private fun JsonArrayBuilder.addComparison(items: List<CardItem>) {
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
            val image = item.image
            if (image != null) add(a2uiPicture(id, image, item.title, "mediumFeature")) else add(a2uiText(id, "", "caption"))
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
private fun JsonArrayBuilder.addTrip(items: List<CardItem>, labels: A2uiLabels) {
    val indexed = items.withIndex().toList()
    val legs = indexed.filter { it.value.kind == CardItemKind.TRANSPORT }.groupBy { it.value.legName() }
    val stays = indexed.filter { it.value.kind == CardItemKind.STAY }
    // A day is its places and its weather, in the order the days first come.
    val days = indexed.filter { it.value.kind == CardItemKind.PLACE || it.value.kind == CardItemKind.WEATHER }
        .groupBy { it.value.group ?: labels.tripDay }
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
    if (stays.isNotEmpty()) addTripStays(stays, labels.stays)
}

/** 「去程 · 9C8819 春秋航空」: the direction before the dot, the flight after it. */
private fun CardItem.legName(): String = if ('·' in title) title.substringBefore('·').trim() else title

private fun CardItem.service(): String = if ('·' in title) title.substringAfter('·').trim() else ""

private fun JsonArrayBuilder.addTripTickets(legs: Map<String, List<IndexedValue<CardItem>>>) {
    val all = legs.values.flatten().map { it.value }
    add(a2uiComponent("trip_legs", GRID_COMPONENT) {
        put("columns", legs.size.coerceIn(1, TRIP_TICKET_COLUMNS))
        putJsonArray("children") { legs.keys.indices.forEach { add("trip_ticket_$it") } }
    })
    legs.entries.forEachIndexed { index, (leg, options) ->
        val main = options.first().value
        val route = main.detail?.substringBefore(" · ")
        val alternatives = options.drop(1).joinToString("；") { listOf(it.value.service(), it.value.value.orEmpty()).joinToString(" ").trim() }
        val note = main.detail?.substringAfter(" · ", "").orEmpty()
        add(a2uiComponent("trip_ticket_$index", TICKET_COMPONENT) {
            put("leg", if (leg == main.title) "" else leg)
            put("service", main.service().ifEmpty { main.title })
            main.value?.let { put("times", it) }
            route?.let { put("route", it) }
            if (note.isNotBlank()) put("note", note)
            if (alternatives.isNotBlank()) put("alternatives", alternatives)
            put("mode", main.tripMode(all).wire)
        })
    }
}

/** A column per day: its name, its weather, its first photo, then a [STOP_COMPONENT] per place. */
private fun JsonArrayBuilder.addTripDays(days: Map<String, List<IndexedValue<CardItem>>>) {
    add(a2uiComponent("trip_days", GRID_COMPONENT) {
        put("columns", days.size.coerceIn(1, TRIP_MAX_COLUMNS))
        putJsonArray("children") { days.keys.indices.forEach { add("trip_day_$it") } }
    })
    days.entries.forEachIndexed { day, (name, entries) ->
        val column = "trip_day_$day"
        val weather = entries.firstOrNull { it.value.kind == CardItemKind.WEATHER }?.value
        val stops = entries.filter { it.value.kind == CardItemKind.PLACE }
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
private fun JsonArrayBuilder.addDayWeather(id: String, weather: CardItem) {
    add(a2uiComponent(id, "Row") {
        put("align", "center")
        putJsonArray("children") {
            if (weather.icon != null) add("${id}_icon")
            add("${id}_text")
        }
    })
    weather.icon?.let { name -> add(a2uiComponent("${id}_icon", "Icon") { put("name", iconJson(name)) }) }
    add(a2uiText("${id}_text", listOfNotNull(weather.title, weather.value, weather.detail).joinToString(" · "), "body"))
}

/** Hotels in a row, each a [STAY_COMPONENT] with its picture as a thumbnail, so they fit under the days. */
private fun JsonArrayBuilder.addTripStays(stays: List<IndexedValue<CardItem>>, title: String) {
    add(a2uiText("trip_stays_title", title, "h4"))
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
    icon?.let { name -> add(a2uiComponent("${id}_icon", "Icon") { put("name", iconJson(name)) }) }
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

internal const val DEBUG_A2UI_SET_EVENT = "set"

private const val TRIP_MAX_COLUMNS = 3

/** There and back side by side. */
private const val TRIP_TICKET_COLUMNS = 2

/** Labels up to this many characters sit side by side as chips. */
private const val CHIP_LABEL_MAX = 6
