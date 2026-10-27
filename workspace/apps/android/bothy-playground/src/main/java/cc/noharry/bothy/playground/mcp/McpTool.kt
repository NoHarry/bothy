package cc.noharry.bothy.playground.mcp
import cc.noharry.bothy.playground.ToolOutcome
import cc.noharry.bothy.playground.home.World
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * One tool of a playground server. [readOnly] and [destructive] become the MCP annotations, so a client can tell a
 * query (Bothy lets a plan poll it) from a change, and a change it should confirm first (unlocking the front door).
 */
class McpTool(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    val readOnly: Boolean = false,
    val destructive: Boolean = false,
    /** Runs outside the store's lock and changes the store itself: a call that waits on the map (route planning) would hold up every tick. */
    val direct: Boolean = false,
    val run: (World, ToolArgs) -> ToolOutcome,
)

/** A call the tool cannot carry out as asked; it answers as a tool error, in words the model can act on. */
class ToolRefusal(message: String) : Exception(message)

/**
 * A call's arguments, read leniently: a model may send `"主驾"` or `"driver"`, `"开"` or `true`, `"2"` or `2`. Numbers
 * outside their range are brought into it rather than refused.
 */
class ToolArgs(private val json: JsonObject) {
    fun has(name: String): Boolean = json[name] != null && json[name] !is kotlinx.serialization.json.JsonNull

    fun string(name: String): String? = (json[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

    /** The raw value of [name], for arguments that may be a string, a number, an object or a list. */
    fun raw(name: String): JsonElement? = json[name]?.takeUnless { it is kotlinx.serialization.json.JsonNull }

    fun double(name: String, range: ClosedFloatingPointRange<Double>): Double? {
        val raw = json[name] as? JsonPrimitive ?: return null
        val value = raw.doubleOrNull ?: raw.contentOrNull?.let(::leadingNumber) ?: throw ToolRefusal("$name 要填数字")
        return value.coerceIn(range)
    }

    fun int(name: String, range: IntRange): Int? =
        double(name, range.first.toDouble()..range.last.toDouble())?.let { kotlin.math.round(it).toInt() }

    fun bool(name: String): Boolean? {
        val raw = json[name] as? JsonPrimitive ?: return null
        raw.booleanOrNull?.let { return it }
        val text = raw.contentOrNull?.trim()?.lowercase() ?: return null
        return when (text) {
            in TRUE_WORDS -> true
            in FALSE_WORDS -> false
            else -> throw ToolRefusal("$name 要填 true 或 false")
        }
    }

    /** The [choices] entry [name] names, by id, by Chinese label or by an alias; null when [name] is not given. */
    fun <T> choice(name: String, choices: List<T>, names: (T) -> List<String>): T? {
        val said = string(name)?.lowercase() ?: return null
        return choices.firstOrNull { choice -> names(choice).any { it.lowercase() == said } }
            ?: choices.firstOrNull { choice -> names(choice).any { said.contains(it.lowercase()) } }
            ?: throw ToolRefusal("$name 不认识「$said」，可以用：${choices.joinToString("、") { names(it).first() }}")
    }

    private fun leadingNumber(text: String): Double? = NUMBER.find(text)?.value?.toDoubleOrNull()

    private companion object {
        val NUMBER = Regex("-?\\d+(\\.\\d+)?")
        val TRUE_WORDS = setOf("true", "on", "yes", "1", "开", "打开", "开启", "是")
        val FALSE_WORDS = setOf("false", "off", "no", "0", "关", "关闭", "关掉", "否")
    }
}

/** A JSON Schema object of [properties]; [required] lists the ones a call must give. */
internal fun objectSchema(vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", JsonObject(properties.toMap()))
    if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    put("additionalProperties", false)
}

internal fun property(type: String, description: String, choices: List<String> = emptyList(), min: Number? = null, max: Number? = null): JsonObject =
    buildJsonObject {
        put("type", type)
        put("description", description)
        if (choices.isNotEmpty()) put("enum", JsonArray(choices.map(::JsonPrimitive)))
        min?.let { put("minimum", it) }
        max?.let { put("maximum", it) }
    }

/** A tool's answer as MCP content: the words, plus the state they describe as `structuredContent`. */
internal fun toolResult(outcome: ToolOutcome, structured: JsonElement?): JsonObject = buildJsonObject {
    put("content", buildJsonArray {
        add(buildJsonObject {
            put("type", "text")
            put("text", outcome.text)
        })
    })
    if (structured is JsonObject) put("structuredContent", structured)
    put("isError", outcome.isError)
}
