package cc.noharry.bothy.sample

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** `files/plans/plans.json`: `{version, plans:[…], history:[…]}`. */
internal fun encodePlanFile(plans: List<DebugPlan>, history: List<DebugPlanFiring>): String = buildJsonObject {
    put("version", 1)
    put("plans", buildJsonArray { plans.forEach { add(encodePlan(it)) } })
    put(
        "history",
        buildJsonArray {
            history.forEach { firing ->
                add(
                    buildJsonObject {
                        put("planId", firing.planId)
                        put("title", firing.title)
                        put("at", firing.atMillis)
                        put("note", firing.note)
                    },
                )
            }
        },
    )
}.toString()

private fun encodePlan(plan: DebugPlan): JsonObject = buildJsonObject {
    put("id", plan.id)
    put("title", plan.title)
    put("message", plan.message)
    plan.instruction?.let { put("instruction", it) }
    put("trigger", encodeTrigger(plan.trigger))
    put("enabled", plan.enabled)
    put("createdAt", plan.createdAtMillis)
    plan.lastFiredAtMillis?.let { put("lastFiredAt", it) }
    plan.lastDueAtMillis?.let { put("lastDueAt", it) }
    put("firedCount", plan.firedCount)
    plan.end?.let { put("end", it.id) }
    plan.said?.let { put("said", it) }
    if (plan.actions.isNotEmpty()) put("actions", buildJsonArray { plan.actions.forEach { add(encodeAction(it)) } })
}

private fun encodeAction(action: DebugPlanAction): JsonObject = buildJsonObject {
    put("server", action.server)
    put("tool", action.tool)
    put("arguments", action.arguments)
    put("label", action.label)
}

private fun decodeAction(item: JsonObject): DebugPlanAction? = DebugPlanAction(
    server = item.string("server") ?: return null,
    tool = item.string("tool")?.takeIf(String::isNotBlank) ?: return null,
    arguments = item["arguments"] as? JsonObject ?: JsonObject(emptyMap()),
    label = item.string("label").orEmpty(),
)

private fun encodeTrigger(trigger: DebugPlanTrigger): JsonObject = buildJsonObject {
    when (trigger) {
        is DebugPlanTrigger.Once -> {
            put("type", "once")
            put("at", trigger.atMillis)
        }
        is DebugPlanTrigger.Repeat -> {
            put("type", "repeat")
            put("minute", trigger.minuteOfDay)
            put("days", buildJsonArray { trigger.days.sorted().forEach { add(JsonPrimitive(it)) } })
        }
        is DebugPlanTrigger.Watch -> {
            val watch = trigger.watch
            put("type", "watch")
            put("server", watch.server)
            put("uri", watch.uri)
            watch.tool?.let {
                put("tool", it)
                put("arguments", watch.arguments)
            }
            put("field", watch.field)
            put("op", watch.op.id)
            watch.value?.let { put("value", it) }
            put("text", watch.text)
            if (watch.where.isNotEmpty()) put("where", JsonObject(watch.where))
            put("mode", watch.mode.id)
            put("margin", watch.margin)
            put("poll", watch.pollMillis)
            put("hold", trigger.holdSeconds)
            put("repeat", trigger.repeat)
        }
    }
}

/** The plans and firings in [text]; a plan with an unknown trigger or no title is dropped, the rest stay. */
internal fun decodePlanFile(text: String): Pair<List<DebugPlan>, List<DebugPlanFiring>> {
    val root = Json.parseToJsonElement(text) as? JsonObject ?: return emptyList<DebugPlan>() to emptyList()
    val plans = (root["plans"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::decodePlan) }
    val history = (root["history"] as? JsonArray).orEmpty().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        DebugPlanFiring(item.string("planId") ?: return@mapNotNull null, item.string("title").orEmpty(), item.long("at") ?: 0L, item.string("note").orEmpty())
    }
    return plans to history
}

private fun decodePlan(item: JsonObject): DebugPlan? {
    val trigger = (item["trigger"] as? JsonObject)?.let(::decodeTrigger) ?: return null
    return DebugPlan(
        id = item.string("id")?.takeIf(String::isNotBlank) ?: return null,
        title = item.string("title")?.takeIf(String::isNotBlank) ?: return null,
        message = item.string("message").orEmpty(),
        instruction = item.string("instruction"),
        trigger = trigger,
        enabled = (item["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
        createdAtMillis = item.long("createdAt") ?: 0L,
        lastFiredAtMillis = item.long("lastFiredAt"),
        lastDueAtMillis = item.long("lastDueAt"),
        firedCount = (item["firedCount"] as? JsonPrimitive)?.intOrNull ?: 0,
        end = DebugPlanEnd.byId(item.string("end")),
        said = item.string("said"),
        actions = (item["actions"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::decodeAction) },
    )
}

private fun decodeTrigger(item: JsonObject): DebugPlanTrigger? = when (item.string("type")) {
    "once" -> item.long("at")?.let(DebugPlanTrigger::Once)
    "repeat" -> {
        val minute = (item["minute"] as? JsonPrimitive)?.intOrNull
        val days = (item["days"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }.filter { it in 1..7 }.toSet()
        if (minute == null || minute !in 0 until MINUTES_PER_DAY || days.isEmpty()) null else DebugPlanTrigger.Repeat(minute, days)
    }
    "watch" -> decodeWatch(item)?.let { DebugPlanTrigger.Watch(it, item.int("hold") ?: 0, item.bool("repeat") ?: false) }
    else -> null
}

private fun decodeWatch(item: JsonObject): DebugPlanWatch? = DebugPlanWatch(
    server = item.string("server") ?: return null,
    uri = item.string("uri")?.takeIf { it.isNotBlank() || item.string("tool") != null } ?: return null,
    field = item.string("field")?.takeIf(String::isNotBlank) ?: return null,
    op = DebugWatchOp.byId(item.string("op")) ?: return null,
    value = item["value"] as? JsonPrimitive,
    text = item.string("text").orEmpty(),
    where = (item["where"] as? JsonObject).orEmpty().mapNotNull { (key, value) -> (value as? JsonPrimitive)?.let { key to it } }.toMap(),
    mode = DebugWatchMode.byId(item.string("mode")) ?: DebugWatchMode.ENTER,
    margin = (item["margin"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
    pollMillis = item.long("poll") ?: DEFAULT_WATCH_POLL_MS,
    tool = item.string("tool")?.takeIf(String::isNotBlank),
    arguments = item["arguments"] as? JsonObject ?: JsonObject(emptyMap()),
)

private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

private const val MINUTES_PER_DAY = 24 * 60
