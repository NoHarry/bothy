package cc.noharry.bothy.sample

import android.util.Log
import androidx.a2ui.compose.runtime.A2uiMessageParser
import androidx.a2ui.compose.ui.A2uiMessageProcessor
import androidx.a2ui.model.processor.A2uiMessageProcessor
import androidx.a2ui.model.protocol.A2uiClientEventMessage
import androidx.a2ui.model.protocol.A2uiClientToServerMessage
import androidx.a2ui.model.protocol.A2uiProtocolConstants
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Spike: A2UI rendering of a pick list. The host compiles the choices into A2UI v0.9 messages, so the model never
 * writes UI JSON, and the AndroidX Material basic catalog renders them natively (catalog-allowlisted, no executed
 * code). A tap comes back as the choice's ordinal, which the overlay already sends as the spoken "第N个".
 */
internal data class DebugA2uiChoice(val label: String, val recommended: Boolean, val icon: String? = null)

/**
 * A pick's line split for a row: the name is the title and the rest a detail line. Models write either
 * "东广场停车场（推荐，离进站口近）" or "珠江城地下停车场（推荐）——珠江西路15号，近地铁". "推荐" itself becomes the row's chip,
 * so only its reason stays, leading the detail.
 */
internal fun DebugA2uiChoice.titleAndDetail(): Pair<String, String?> {
    val recommendation = RECOMMENDATION_NOTE.find(label)
    val reason = recommendation?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    val text = (if (recommendation != null) label.removeRange(recommendation.range) else label).trim()
    val separator = text.detailSeparator()
    val (title, rest) = when {
        separator != null -> text.substring(0, separator.first).trim() to text.substring(separator.last + 1).trim()
        text.endsWith('）') && text.lastIndexOf('（') > 0 -> text.lastIndexOf('（').let { open ->
            text.substring(0, open).trimEnd() to text.substring(open + 1, text.length - 1)
        }
        else -> text to ""
    }
    val note = rest.split('，', ',', '、').map { it.trim() }.filterNot { it.isEmpty() || it == "推荐" }.joinToString("，")
    val detail = listOfNotNull(reason, note.ifEmpty { null }).joinToString("，")
    return (title.ifEmpty { label.trim() }) to detail.ifEmpty { null }
}

/**
 * Where a name ends and its details begin: an em dash, a colon or a spaced hyphen outside brackets. A colon between
 * digits is a time ("10:00-12:00"), and anything inside a bracket belongs to the note, never to the split.
 */
private fun String.detailSeparator(): IntRange? {
    var depth = 0
    for (index in indices) {
        when (this[index]) {
            '（', '(' -> depth++
            '）', ')' -> depth = (depth - 1).coerceAtLeast(0)
        }
        if (depth > 0 || index == 0) continue
        val match = DETAIL_SEPARATOR.matchAt(this, index) ?: continue
        val isTime = this[index] in ":：" && getOrNull(index - 1)?.isDigit() == true && getOrNull(index + 1)?.isDigit() == true
        if (!isTime) return match.range
    }
    return null
}

/** "（推荐）" or "（推荐，离进站口近）" anywhere in the line; group 1 is the reason. */
private val RECOMMENDATION_NOTE = Regex("""[（(]推荐[，,：:、\s]*([^）)]*)[）)]""")
private val DETAIL_SEPARATOR = Regex("""——|—|–|：|:|\s-\s""")

/**
 * [prompt] is omitted when the surrounding surface already shows the question. [firstOrdinal] numbers a later
 * column of a split list, so its picks keep the ordinals the user would say.
 */
internal fun debugA2uiChoiceMessages(
    surfaceId: String,
    prompt: String?,
    choices: List<DebugA2uiChoice>,
    firstOrdinal: Int = 1,
): List<String> {
    val ordinals = choices.indices.map { firstOrdinal + it }
    val components = buildJsonArray {
        add(component("root", "Column") {
            put("align", "stretch")
            putJsonArray("children") {
                if (prompt != null) add(PROMPT_ID)
                ordinals.forEach { add(choiceId(it)) }
            }
        })
        if (prompt != null) add(component(PROMPT_ID, "Text") { put("text", prompt) })
        choices.forEachIndexed { index, choice -> addChoice(ordinals[index], choice) }
    }
    return listOf(
        a2uiCreateSurface(surfaceId),
        message("updateComponents") {
            put("surfaceId", surfaceId)
            put("components", components)
        },
    )
}

internal fun a2uiCreateSurface(surfaceId: String): String = message("createSurface") {
    put("surfaceId", surfaceId)
    put("catalogId", DEBUG_A2UI_CATALOG_ID)
}

internal fun a2uiMessage(kind: String, body: JsonObjectBuilder.() -> Unit): String = message(kind, body)

internal fun a2uiComponent(id: String, type: String, body: JsonObjectBuilder.() -> Unit): JsonObject = component(id, type, body)

/**
 * One processor per surface owner; it knows only the host catalog ([debugA2uiCatalog]), so unknown components are
 * rejected. In-car surfaces render no remote media and open no URLs: those hooks are host-owned and deliberately inert.
 */
internal fun debugA2uiProcessor(carLook: Boolean = true): A2uiMessageProcessor =
    A2uiMessageProcessor(listOf(debugA2uiCatalog(carLook)))

/** Queues messages (the inbound channel is unbounded); they apply only while [A2uiMessageProcessor.collectMessages] runs. */
internal fun A2uiMessageProcessor.processJson(messages: List<String>) {
    messages.forEach { processMessage(DEBUG_A2UI_PARSER.parse(it)) }
}

/** The ordinal of a tapped pick, or null for any other outbound A2UI message. */
internal fun A2uiClientToServerMessage.pickedOrdinal(): Int? =
    ((this as? A2uiClientEventMessage)?.context?.get(ORDINAL_KEY) as? Number)?.toInt()

/**
 * The panel's confirmed values, resolved from the controls' data bindings when the Button fired: a slider's number,
 * a switch's state, a choice's chosen option indices. Null unless every control's value came in its type.
 */
internal fun A2uiClientToServerMessage.controlValues(controls: List<DebugControl>): List<Any>? {
    val context = (this as? A2uiClientEventMessage)?.context ?: return null
    if (controls.isEmpty()) return null
    return controls.mapIndexed { index, control ->
        val raw = context[controlKey(index)]
        when (control) {
            is DebugSliderOffer -> (raw as? Number)?.toDouble()
            is DebugSwitchOffer -> raw as? Boolean
            // A picker that was never touched may hand back its initial list or a single value.
            is DebugChoiceOffer -> when (raw) {
                is List<*> -> raw.mapNotNull { it?.toString()?.toIntOrNull() }.filter { it in control.options.indices }
                is String -> listOfNotNull(raw.toIntOrNull())
                else -> null
            }
        } ?: return null
    }
}

@Composable
internal fun DebugA2uiChoiceSurface(
    messages: List<String>,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    carLook: Boolean = true,
) {
    DebugA2uiSurface(messages, { event -> event.pickedOrdinal()?.let(onPick) }, modifier, carLook)
}

/** Any host-compiled surface; [onEvent] gets each outbound event (a pick's ordinal, a slider's value). */
@Composable
internal fun DebugA2uiSurface(
    messages: List<String>,
    onEvent: (A2uiClientToServerMessage) -> Unit,
    modifier: Modifier = Modifier,
    carLook: Boolean = true,
) {
    val processor = remember(messages, carLook) { debugA2uiProcessor(carLook).apply { processJson(messages) } }
    val surfaces by processor.activeSurfaces.collectAsState()
    // The surface outlives recompositions; a tap must reach the latest callback, not the first one.
    val currentOnEvent by rememberUpdatedState(onEvent)
    LaunchedEffect(processor) {
        launch { processor.collectMessages() }
        processor.outboundEvents.collect { event ->
            Log.i(DEBUG_A2UI_LOG_TAG, "outbound ${event::class.simpleName} ordinal=${event.pickedOrdinal()} context=${(event as? A2uiClientEventMessage)?.context}")
            currentOnEvent(event)
        }
    }
    Column(modifier.fillMaxWidth()) { surfaces.forEach { A2uiSurface(it, Modifier.fillMaxWidth()) } }
}

/** One pick: a Button whose child is the title over an optional detail, firing `pick` with its ordinal. */
private fun JsonArrayBuilder.addChoice(ordinal: Int, choice: DebugA2uiChoice) {
    val id = choiceId(ordinal)
    val (title, detail) = choice.titleAndDetail()
    // An icon list row: the icon leads the title and detail, as on a launcher or settings page.
    if (choice.icon != null) {
        addIconRow("${id}_body", choice.icon, title, detail)
        addChoiceButton(id, ordinal, choice.recommended)
        return
    }
    add(component("${id}_body", "Column") {
        putJsonArray("children") {
            add("${id}_title")
            if (detail != null) add("${id}_detail")
        }
    })
    add(component("${id}_title", "Text") {
        put("text", title)
        put("variant", "h5")
    })
    if (detail != null) {
        add(component("${id}_detail", "Text") {
            put("text", detail)
            put("variant", "caption")
        })
    }
    addChoiceButton(id, ordinal, choice.recommended)
}

private fun JsonArrayBuilder.addChoiceButton(id: String, ordinal: Int, recommended: Boolean) {
    add(component(id, "Button") {
        put("child", "${id}_body")
        if (recommended) put("variant", "primary")
        putJsonObject("action") {
            putJsonObject("event") {
                put("name", DEBUG_A2UI_PICK_EVENT)
                putJsonObject("context") { put(ORDINAL_KEY, ordinal) }
            }
        }
    })
}

private fun component(id: String, type: String, body: JsonObjectBuilder.() -> Unit) = buildJsonObject {
    put("id", id)
    put("component", type)
    body()
}

private fun message(kind: String, body: JsonObjectBuilder.() -> Unit): String = buildJsonObject {
    put("version", A2uiProtocolConstants.PROTOCOL_VERSION)
    putJsonObject(kind, body)
}.toString()

private fun choiceId(ordinal: Int) = "choice_$ordinal"

private val DEBUG_A2UI_PARSER = A2uiMessageParser()
internal const val DEBUG_A2UI_PICK_EVENT = "pick"
private const val DEBUG_A2UI_LOG_TAG = "DebugA2ui"
private const val ORDINAL_KEY = "ordinal"
private const val PROMPT_ID = "prompt"
/** Taken from the catalog itself, so a createSurface can never name a catalog the processor does not hold. */
private val DEBUG_A2UI_CATALOG_ID: String by lazy { debugA2uiCatalog().id }
