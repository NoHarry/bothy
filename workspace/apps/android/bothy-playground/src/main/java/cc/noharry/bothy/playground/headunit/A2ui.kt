package cc.noharry.bothy.playground.headunit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToLong

/**
 * What 小博's reply shows besides its words, as Bothy sends it to the head unit: information cards, picks to tap and a
 * panel of controls. The head unit draws them itself; a pick or a confirmed panel goes back as the words the user
 * would say, as from Bothy's own card.
 */
data class A2ui(val cards: List<A2uiCard> = emptyList(), val choices: List<A2uiChoice> = emptyList(), val controls: List<A2uiControl> = emptyList()) {
    val isEmpty: Boolean get() = cards.isEmpty() && choices.isEmpty() && controls.isEmpty()

    /** Something waits for the user: a pick or a panel. */
    val asks: Boolean get() = choices.isNotEmpty() || controls.isNotEmpty()
}

data class A2uiCard(
    val title: String,
    val icon: String? = null,
    val value: String? = null,
    val subtitle: String? = null,
    val layout: String = "list",
    val fields: List<Pair<String, String>> = emptyList(),
    val progress: List<A2uiProgress> = emptyList(),
    val items: List<A2uiItem> = emptyList(),
)

data class A2uiItem(
    val title: String,
    val icon: String? = null,
    val detail: String? = null,
    val value: String? = null,
    val group: String? = null,
    val fields: List<Pair<String, String>> = emptyList(),
)

data class A2uiProgress(val label: String, val value: Double, val max: Double, val text: String?)

/** A pick; [reply] is what a tap says (「第2个」). */
data class A2uiChoice(val ordinal: Int, val label: String, val recommended: Boolean, val icon: String?, val reply: String)

sealed interface A2uiControl {
    val label: String

    data class Slider(
        override val label: String,
        val min: Double,
        val max: Double,
        val step: Double,
        val value: Double,
        val unit: String,
        val integer: Boolean,
    ) : A2uiControl {
        fun snap(number: Double): Double {
            if (step <= 0.0) return number.coerceIn(min, max)
            return (min + round((number - min) / step) * step).coerceIn(min, max)
        }

        fun format(number: Double): String =
            if (integer || abs(number - round(number)) < 1e-9) number.roundToLong().toString() else (round(number * 10) / 10).toString()
    }

    data class Switch(override val label: String, val value: Boolean) : A2uiControl

    data class Choice(override val label: String, val options: List<String>, val value: List<Int>, val multiple: Boolean) : A2uiControl
}

/** A control's value as shown: a slider's number, a switch's state, a choice's chosen options. */
internal fun A2uiControl.initialValue(): Any = when (this) {
    is A2uiControl.Slider -> value
    is A2uiControl.Switch -> value
    is A2uiControl.Choice -> value
}

/**
 * What confirming the panel at [values] says, as Bothy's own card says it: the controls changed, or all of them when
 * none was ("亮度设为30%，座椅加热打开"); a multiple choice with nothing chosen says nothing.
 */
internal fun a2uiPanelReply(controls: List<A2uiControl>, values: List<Any>): String {
    val pairs = controls.zip(values)
    val changed = pairs.filter { (control, value) -> !control.same(value) }
    return changed.ifEmpty { pairs }
        .filterNot { (control, value) -> control is A2uiControl.Choice && (value as List<*>).isEmpty() }
        .joinToString("，") { (control, value) -> control.said(value) }
}

private fun A2uiControl.same(value: Any): Boolean = when (this) {
    is A2uiControl.Slider -> abs(snap(value as Double) - this.value) < 1e-6
    is A2uiControl.Switch -> value == this.value
    is A2uiControl.Choice -> (value as List<*>).toSet() == this.value.toSet()
}

@Suppress("UNCHECKED_CAST")
private fun A2uiControl.said(value: Any): String = when (this) {
    is A2uiControl.Slider -> "${label}设为${format(snap(value as Double))}$unit"
    is A2uiControl.Switch -> "$label${if (value as Boolean) "打开" else "关闭"}"
    is A2uiControl.Choice -> {
        val names = (value as List<Int>).sorted().mapNotNull { options.getOrNull(it) }
        if (multiple) "${label}选${names.joinToString("、")}" else "${label}设为${names.firstOrNull().orEmpty()}"
    }
}

/** Bothy's `a2ui` extra; nothing when it is empty or cannot be read. */
internal fun a2uiFrom(text: String?): A2ui {
    if (text.isNullOrBlank()) return A2ui()
    val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return A2ui()
    return A2ui(
        cards = root.list("cards").map(::card),
        choices = root.list("choices").map { item ->
            A2uiChoice(item.int("ordinal") ?: 0, item.str("label").orEmpty(), item.bool("recommended") == true, item.str("icon"), item.str("reply").orEmpty())
        },
        controls = root.list("controls").mapNotNull(::control),
    )
}

private fun card(item: JsonObject) = A2uiCard(
    title = item.str("title").orEmpty(),
    icon = item.str("icon"),
    value = item.str("value"),
    subtitle = item.str("subtitle"),
    layout = item.str("layout") ?: "list",
    fields = item.pairs("fields"),
    progress = item.list("progress").map { A2uiProgress(it.str("label").orEmpty(), it.num("value") ?: 0.0, it.num("max") ?: 1.0, it.str("text")) },
    items = item.list("items").map {
        A2uiItem(it.str("title").orEmpty(), it.str("icon"), it.str("detail"), it.str("value"), it.str("group"), it.pairs("fields"))
    },
)

private fun control(item: JsonObject): A2uiControl? {
    val label = item.str("label").orEmpty()
    return when (item.str("type")) {
        "slider" -> A2uiControl.Slider(
            label, item.num("min") ?: 0.0, item.num("max") ?: 100.0, item.num("step") ?: 1.0, item.num("value") ?: 0.0,
            item.str("unit").orEmpty(), item.bool("integer") == true,
        )
        "switch" -> A2uiControl.Switch(label, item.bool("value") == true)
        "choice" -> A2uiControl.Choice(
            label,
            (item["options"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
            (item["value"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.intOrNull },
            item.bool("multiple") == true,
        )
        else -> null
    }
}

private fun JsonObject.list(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
private fun JsonObject.str(key: String): String? = (this[key])?.primitiveContent()
private fun JsonObject.num(key: String): Double? = this[key]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }
private fun JsonObject.int(key: String): Int? = this[key]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
private fun JsonObject.bool(key: String): Boolean? = this[key]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }
private fun JsonObject.pairs(key: String): List<Pair<String, String>> = (this[key] as? JsonArray).orEmpty().mapNotNull { pair ->
    val both = runCatching { pair.jsonArray }.getOrNull() ?: return@mapNotNull null
    val name = both.getOrNull(0)?.primitiveContent() ?: return@mapNotNull null
    name to both.getOrNull(1)?.primitiveContent().orEmpty()
}

private fun JsonElement.primitiveContent(): String? = runCatching { jsonPrimitive.contentOrNull }.getOrNull()
