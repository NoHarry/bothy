package cc.noharry.bothy.app.overlay
import cc.noharry.agent.appserver.protocol.view.ChoiceOffer
import cc.noharry.agent.appserver.protocol.view.Control
import cc.noharry.agent.appserver.protocol.view.DEBUG_SVG_ICONS
import cc.noharry.agent.appserver.protocol.view.SliderOffer
import cc.noharry.agent.appserver.protocol.view.SwitchOffer
import cc.noharry.agent.appserver.protocol.view.WidgetCard
import cc.noharry.agent.appserver.protocol.view.ReplyWords
import cc.noharry.bothy.app.console.ui.icon
import cc.noharry.bothy.protocol.view.OverlayHud
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * What the reply shows besides its words, for a surface that draws it itself (a head unit, [AssistantSurface]):
 * its information cards, the picks it offers and its panel of controls, as plain JSON; empty when there is none. A pick
 * tapped there comes back as [DisplayChoice.replyText] and a panel confirmed as [panelReply], exactly as from
 * the floating card. Pictures stay out: they are files of this app.
 */
internal fun OverlayHud.surfaceA2ui(words: ReplyWords): String {
    if (cards.isEmpty() && choices.isEmpty() && controls.isEmpty()) return ""
    return buildJsonObject {
        put("cards", JsonArray(cards.map(::cardJson)))
        put("choices", buildJsonArray {
            choices.forEach { choice ->
                add(buildJsonObject {
                    put("ordinal", choice.ordinal)
                    put("label", choice.label)
                    put("recommended", choice.recommended)
                    choice.iconFrom(choiceIcons)?.let { put("icon", surfaceIcon(it)) }
                    put("reply", choice.replyText(words))
                })
            }
        })
        put("controls", JsonArray(controls.map(::controlJson)))
    }.toString()
}

private fun cardJson(card: WidgetCard): JsonObject = buildJsonObject {
    put("title", card.title)
    card.icon?.let { put("icon", surfaceIcon(it)) }
    card.value?.let { put("value", it) }
    card.subtitle?.let { put("subtitle", it) }
    put("layout", card.layout.wire)
    putJsonArray("fields") { card.fields.forEach { (name, value) -> add(buildJsonArray { add(name); add(value) }) } }
    putJsonArray("progress") {
        card.progress.forEach { bar ->
            add(buildJsonObject {
                put("label", bar.label)
                put("value", bar.value)
                put("max", bar.max)
                bar.text?.let { put("text", it) }
            })
        }
    }
    putJsonArray("items") {
        card.items.forEach { item ->
            add(buildJsonObject {
                put("title", item.title)
                item.icon?.let { put("icon", surfaceIcon(it)) }
                item.detail?.let { put("detail", it) }
                item.value?.let { put("value", it) }
                item.group?.let { put("group", it) }
                put("kind", item.kind.wire)
                putJsonArray("fields") { item.fields.forEach { (name, value) -> add(buildJsonArray { add(name); add(value) }) } }
            })
        }
    }
}

private fun controlJson(control: Control): JsonObject = buildJsonObject {
    put("label", control.label)
    when (control) {
        is SliderOffer -> {
            put("type", "slider")
            put("min", control.min)
            put("max", control.max)
            put("step", control.step)
            put("value", control.value)
            put("unit", control.unit)
            put("integer", control.integer)
        }
        is SwitchOffer -> {
            put("type", "switch")
            put("value", control.value)
        }
        is ChoiceOffer -> {
            put("type", "choice")
            put("multiple", control.multiple)
            putJsonArray("options") { control.options.forEach { add(it.label) } }
            putJsonArray("value") { control.value.forEach { add(it) } }
        }
    }
}

/** An icon as the surface can draw it: `svg:` and the glyph's path for this app's own, else the catalog's name. */
internal fun surfaceIcon(name: String): String = DEBUG_SVG_ICONS[name]?.let { "svg:$it" } ?: name

/** The extra the surface reads [OverlayHud.surfaceA2ui] from. */
internal const val SURFACE_A2UI = "a2ui"
