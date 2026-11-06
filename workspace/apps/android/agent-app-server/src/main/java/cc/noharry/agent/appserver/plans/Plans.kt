package cc.noharry.agent.appserver.plans
import android.util.Log
import cc.noharry.agent.appserver.InternalAppServerApi
import cc.noharry.agent.appserver.console.saveFileAtomically
import cc.noharry.agent.appserver.console.setAsideUnreadable
import cc.noharry.agent.appserver.console.singleLine
import cc.noharry.agent.appserver.memory.sensitiveTextError
import cc.noharry.agent.appserver.protocol.DEFAULT_WATCH_POLL_MS
import cc.noharry.agent.appserver.protocol.Plan
import cc.noharry.agent.appserver.protocol.PlanAction
import cc.noharry.agent.appserver.protocol.PlanFiring
import cc.noharry.agent.appserver.protocol.PlanTrigger
import cc.noharry.agent.appserver.protocol.PlanWatch
import cc.noharry.agent.appserver.protocol.WatchMode
import cc.noharry.agent.appserver.protocol.WatchOp
import cc.noharry.agent.appserver.protocol.repeatAt
import cc.noharry.agent.appserver.protocol.view.valueText
import cc.noharry.agent.appserver.runtime.modelText
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The resource a trigger waits for, or null for a time. */
fun PlanTrigger.watch(): PlanWatch? = (this as? PlanTrigger.Watch)?.watch

/** Whether the plan goes on after it fired. */
@InternalAppServerApi
fun PlanTrigger.repeats(): Boolean = when (this) {
    is PlanTrigger.Watch -> repeat
    else -> this is PlanTrigger.Repeat
}

/** The latest occurrence of [repeat] at or before [now], or null when none is within a week. */
internal fun lastRepeat(repeat: PlanTrigger.Repeat, now: Long, zone: ZoneId): Long? {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return (0L..WEEK_DAYS).asSequence().map(today::minusDays)
        .filter { it.dayOfWeek.value in repeat.days }
        .map { repeatAt(repeat, it, zone) }
        .firstOrNull { it <= now }
}

/** What a time plan should do at [now]: come due (late or not), be given up as missed, or wait. */
internal sealed interface PlanDue {
    data class Fire(val dueAtMillis: Long, val late: Boolean) : PlanDue

    /** Given up: the device was off too long, or ([paused]) its time came while it was switched off. */
    data class Miss(val dueAtMillis: Long, val paused: Boolean = false) : PlanDue
}

/**
 * A one-off due within [ONCE_CATCH_UP_MS] fires (late when [LATE_AFTER_MS] past, so the words say when it was for);
 * older, the device was off too long and it is missed. A repeat fires an occurrence up to [REPEAT_CATCH_UP_MS] late and
 * skips older ones; each occurrence is handled once. Nothing at or before [Plan.lastDueAtMillis] comes due: a plan
 * switched back on or given a new time ([resumed], [retimed]) starts from then.
 */
internal fun Plan.dueAt(now: Long, zone: ZoneId): PlanDue? {
    if (!active) return null
    // A mark later than now came from a clock that ran ahead (a device before its time sync): it marks nothing,
    // and since the clock went back past it, nothing older is reported missed either (it may have fired early).
    val markAhead = lastDueAtMillis?.let { it > now } == true
    val handled = lastDueAtMillis?.takeIf { it <= now } ?: Long.MIN_VALUE
    return when (val trigger = trigger) {
        is PlanTrigger.Once -> when {
            trigger.atMillis > now -> null
            trigger.atMillis <= handled -> PlanDue.Miss(trigger.atMillis, paused = true)
            now - trigger.atMillis > ONCE_CATCH_UP_MS -> PlanDue.Miss(trigger.atMillis)
            else -> PlanDue.Fire(trigger.atMillis, late = now - trigger.atMillis > LATE_AFTER_MS)
        }
        is PlanTrigger.Repeat -> {
            val last = lastRepeat(trigger, now, zone) ?: return null
            if (last < createdAtMillis || last <= handled) return null
            when {
                now - last <= REPEAT_CATCH_UP_MS -> PlanDue.Fire(last, late = now - last > LATE_AFTER_MS)
                markAhead -> null
                else -> PlanDue.Miss(last)
            }
        }
        is PlanTrigger.Watch -> null
    }
}

/** Switched back on: what came due while it was paused is not reminded now (a repeat waits for its next time). */
internal fun Plan.resumed(now: Long): Plan = copy(enabled = true, lastDueAtMillis = now)

/**
 * A new time or condition: a fresh start, as if just made. An ended plan goes again, earlier occurrences of a repeat
 * are not reminded late, and a condition that reminded before is armed again.
 */
@InternalAppServerApi
fun Plan.retimed(trigger: PlanTrigger, now: Long): Plan =
    copy(trigger = trigger, enabled = true, end = null, lastDueAtMillis = now, lastFiredAtMillis = null, firedCount = 0)

/** What the model asked for in `when`, before it becomes a trigger. */
internal data class PlanWhen(
    val inMinutes: Int? = null,
    val date: String? = null,
    val time: String? = null,
    val repeat: String? = null,
    val days: List<Int>? = null,
)

/**
 * The trigger [ask] stands for at [now]: the host does the clock (the model never writes a date out of 「两小时后」).
 * A time with no date is today's, or tomorrow's once it has passed. Failures are words for the model to pass on.
 */
internal fun resolvePlanWhen(ask: PlanWhen, now: Long, zone: ZoneId): Result<PlanTrigger> = runCatching {
    val repeat = ask.repeat?.takeUnless { it == "none" }
    ask.inMinutes?.let { minutes ->
        require(repeat == null) { modelText("in_minutes 只用于一次性的计划；重复的计划用 time 和 repeat", "in_minutes is for a one-off plan; a repeating one takes time and repeat") }
        require(minutes in 1..MAX_IN_MINUTES) { modelText("in_minutes 要在 1 到 $MAX_IN_MINUTES 之间（最多 7 天）", "in_minutes is 1 to $MAX_IN_MINUTES (7 days at most)") }
        return@runCatching PlanTrigger.Once(now + minutes * MILLIS_PER_MINUTE)
    }
    val time = parseClock(ask.time) ?: throw IllegalArgumentException(modelText("需要 in_minutes，或者 HH:mm 格式的 time", "Needs in_minutes, or time as HH:mm"))
    val minuteOfDay = time.hour * MINUTES_PER_HOUR + time.minute
    if (repeat != null) return@runCatching PlanTrigger.Repeat(minuteOfDay, repeatDays(repeat, ask.days))
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val date = ask.date?.let(::parseDate) ?: today
    var at = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
    if (ask.date == null && at <= now) at = date.plusDays(1).atTime(time).atZone(zone).toInstant().toEpochMilli()
    require(at > now) { modelText("这个时间已经过了", "That time has passed") }
    require(date <= today.plusDays(MAX_DAYS_AHEAD)) { modelText("最多安排一年以内的计划", "A plan can be at most a year ahead") }
    PlanTrigger.Once(at)
}

private fun repeatDays(repeat: String, days: List<Int>?): Set<Int> = when (repeat) {
    "daily" -> (1..WEEK_DAYS.toInt()).toSet()
    "weekdays" -> (DayOfWeek.MONDAY.value..DayOfWeek.FRIDAY.value).toSet()
    "weekends" -> setOf(DayOfWeek.SATURDAY.value, DayOfWeek.SUNDAY.value)
    "weekly" -> days.orEmpty().toSet().also { picked ->
        require(picked.isNotEmpty() && picked.all { it in 1..WEEK_DAYS.toInt() }) { modelText("weekly 需要 days，1 是周一，7 是周日", "weekly needs days, 1 for Monday to 7 for Sunday") }
    }
    else -> throw IllegalArgumentException(modelText("repeat 只能是 none、daily、weekdays、weekends 或 weekly", "repeat is none, daily, weekdays, weekends or weekly"))
}

private fun parseClock(text: String?): LocalTime? =
    text?.trim()?.let { CLOCK.matchEntire(it) }?.destructured?.let { (hour, minute) ->
        runCatching { LocalTime.of(hour.toInt(), minute.toInt()) }.getOrNull()
    }

private fun parseDate(text: String): LocalDate = try {
    LocalDate.parse(text.trim())
} catch (_: DateTimeParseException) {
    throw IllegalArgumentException(modelText("date 要写成 YYYY-MM-DD，比如 2026-10-20", "date is YYYY-MM-DD, such as 2026-10-20"))
}

/** What the model asked for in `watch`, before it becomes a trigger. */
@InternalAppServerApi
data class PlanWatchAsk(
    val server: String?,
    val uri: String?,
    val field: String?,
    val op: String?,
    val value: JsonPrimitive?,
    val text: String?,
    val where: Map<String, JsonPrimitive> = emptyMap(),
    val mode: String? = null,
    val holdSeconds: Int? = null,
    val repeat: Boolean? = null,
    val pollSeconds: Int? = null,
    /** A read-only tool to call instead of reading [uri], with its [arguments]. */
    val tool: String? = null,
    val arguments: JsonObject? = null,
)

/**
 * A watch from the model's words: any resource of any MCP server, or a read-only tool of one, nothing fixed to a list
 * of signals (whether the server has it is the plan tool's check). A number is compared above or below; a value equals,
 * differs or contains; `changes` fires on any change. [repeatByDefault] is the plan's (every time for an automation).
 */
@InternalAppServerApi
fun resolvePlanWatch(ask: PlanWatchAsk, repeatByDefault: Boolean): Result<PlanTrigger> = runCatching {
    val tool = ask.tool?.trim()?.takeIf(String::isNotEmpty)?.let(::serverToolName)
    val uri = ask.uri?.trim()?.takeIf(String::isNotEmpty)
    require(uri == null || tool == null) { modelText("watch 的 uri 和 tool 只能写一个", "A watch takes uri or tool, not both") }
    require(uri != null || tool != null) {
        modelText("watch 需要 uri（资源，用 signals 查有哪些）或 tool（服务的只读查询工具，再加 arguments）", "A watch needs uri (a resource; signals lists them) or tool (a service's read-only query tool, with arguments)")
    }
    val field = ask.field?.trim()?.takeIf(String::isNotEmpty) ?: throw IllegalArgumentException(
        modelText("watch 需要 field（用 inspect 看读数里有哪些字段）", "A watch needs field (inspect shows the reading's fields)"),
    )
    val op = WatchOp.byId(ask.op) ?: throw IllegalArgumentException(
        modelText("op 只能是：${WatchOp.entries.joinToString("、") { it.id }}", "op is one of: ${WatchOp.entries.joinToString(", ") { it.id }}"),
    )
    val value = ask.value
    require(op == WatchOp.CHANGES || value != null) { modelText("op=${op.id} 需要 value", "op=${op.id} needs value") }
    val threshold = value?.let(::valueText)?.toDoubleOrNull()
    require(!op.numeric || threshold != null) { modelText("op=${op.id} 的 value 要是数字", "op=${op.id} needs a number as value") }
    val mode = ask.mode?.let { WatchMode.byId(it) ?: throw IllegalArgumentException(modelText("mode 只能是 enter 或 each", "mode is enter or each")) } ?: WatchMode.ENTER
    require(!(op.numeric && mode == WatchMode.EACH)) {
        modelText("数字比较用 enter：超过（低于）时触发一次，回落后才再触发", "A number comparison takes enter: it fires once on going above (below), and again only after going back")
    }
    val hold = if (op.numeric) ask.holdSeconds ?: 0 else 0
    require(hold in 0..MAX_HOLD_SECONDS) { modelText("hold_seconds 要在 0 到 $MAX_HOLD_SECONDS 之间", "hold_seconds is 0 to $MAX_HOLD_SECONDS") }
    val defaultPoll = if (tool != null) DEFAULT_TOOL_WATCH_POLL_MS else DEFAULT_WATCH_POLL_MS
    val poll = ask.pollSeconds ?: (defaultPoll / MILLIS_PER_SECOND).toInt()
    require(poll in 1..MAX_POLL_SECONDS) { modelText("poll_seconds 要在 1 到 $MAX_POLL_SECONDS 之间", "poll_seconds is 1 to $MAX_POLL_SECONDS") }
    require(tool == null || poll * MILLIS_PER_SECOND >= MIN_TOOL_WATCH_POLL_MS) {
        modelText(
            "调用工具的监听最快 ${MIN_TOOL_WATCH_POLL_MS / MILLIS_PER_SECOND} 秒一次（poll_seconds），别给别人的服务添负担",
            "A watch that calls a tool reads every ${MIN_TOOL_WATCH_POLL_MS / MILLIS_PER_SECOND} s at most (poll_seconds), to spare other people's services",
        )
    }
    val words = ask.text?.let(::singleLine)?.takeIf(String::isNotEmpty)?.take(MAX_WATCH_TEXT_CHARS)
        ?: modelText("${tool ?: uri} 的 $field ", "${tool ?: uri}'s $field ") + "${op.words}${value?.let { " " + valueText(it) }.orEmpty()}"
    val watch = PlanWatch(
        server = ask.server?.trim()?.takeIf(String::isNotEmpty) ?: throw IllegalArgumentException(modelText("watch 要写 server（MCP 服务器 id）", "A watch needs server (the MCP server's id)")),
        uri = uri.orEmpty(), field = field, op = op, value = value, text = words, where = ask.where, mode = mode,
        margin = threshold?.let { kotlin.math.abs(it) * MARGIN_SHARE } ?: 0.0,
        pollMillis = poll * MILLIS_PER_SECOND,
        tool = tool,
        arguments = ask.arguments ?: JsonObject(emptyMap()),
    )
    PlanTrigger.Watch(watch, hold, ask.repeat ?: repeatByDefault)
}

/**
 * Why [trigger] cannot be the time of a plan made on the page at [now], or null: once, ahead and within a year, or
 * weekly at a time of day on ISO days 1 to 7. Watching is the agent's own to set up, after it has checked the tool.
 */
internal fun pageTriggerError(trigger: PlanTrigger, now: Long): String? = when (trigger) {
    is PlanTrigger.Once -> when {
        trigger.atMillis <= now -> "the time has passed"
        trigger.atMillis > now + MAX_PAGE_AHEAD_MS -> "more than a year ahead"
        else -> null
    }
    is PlanTrigger.Repeat -> when {
        trigger.minuteOfDay !in 0 until MINUTES_PER_DAY -> "the time of day is out of range"
        trigger.days.isEmpty() || trigger.days.any { it !in 1..7 } -> "the days must be 1 to 7"
        else -> null
    }
    is PlanTrigger.Watch -> "a watch is set up by the agent"
}

private const val MAX_PAGE_AHEAD_MS = 366L * 24 * 60 * 60 * 1000
private const val MINUTES_PER_DAY = 24 * 60

/** Why [title], [message] or [instruction] cannot be a plan's, or null; a plan with [actions] needs no words. */
internal fun planTextError(title: String, message: String, instruction: String?, actions: Boolean = false): String? = when {
    title.isBlank() -> modelText("需要 title（计划的名字，比如「拿快递」）", "Needs title (the plan's name, such as 'parcel')")
    title.length > MAX_PLAN_TITLE_CHARS -> modelText("title 不超过 $MAX_PLAN_TITLE_CHARS 个字", "title has at most $MAX_PLAN_TITLE_CHARS characters")
    message.isBlank() && instruction.isNullOrBlank() && !actions ->
        modelText(
            "需要 message（到点时说的话）、instruction（到点让你去办的事）或 actions（到点直接执行的操作）",
            "Needs message (what is said when it fires), instruction (what you are to do then) or actions (what runs then)",
        )
    message.length > MAX_PLAN_MESSAGE_CHARS -> modelText("message 不超过 $MAX_PLAN_MESSAGE_CHARS 个字", "message has at most $MAX_PLAN_MESSAGE_CHARS characters")
    (instruction?.length ?: 0) > MAX_PLAN_INSTRUCTION_CHARS ->
        modelText("instruction 不超过 $MAX_PLAN_INSTRUCTION_CHARS 个字", "instruction has at most $MAX_PLAN_INSTRUCTION_CHARS characters")
    else -> null
}

/**
 * The plans and the latest firings in one file, written whole on each change. At most [MAX_PLANS]: a new plan makes
 * room by dropping the oldest ended one, and is refused when every plan is still going.
 */
@InternalAppServerApi
class PlanBook(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var plans: List<Plan>
    private var history: List<PlanFiring>

    init {
        val loaded = runCatching { if (file.isFile) decodePlanFile(file.readText()) else null }
            .onFailure {
                Log.w(TAG, "plans unreadable, set aside: ${it.javaClass.simpleName}")
                setAsideUnreadable(file)
            }
            .getOrNull()
        plans = loaded?.first.orEmpty()
        history = loaded?.second.orEmpty()
    }

    @Synchronized fun plans(): List<Plan> = plans

    @Synchronized fun history(): List<PlanFiring> = history

    @Synchronized
    fun add(
        title: String,
        message: String,
        instruction: String?,
        trigger: PlanTrigger,
        said: String?,
        actions: List<PlanAction> = emptyList(),
    ): Result<Plan> {
        planTextError(singleLine(title), singleLine(message), instruction?.let(::singleLine), actions.isNotEmpty())
            ?.let { return Result.failure(IllegalArgumentException(it)) }
        var kept = plans
        if (kept.size >= MAX_PLANS) {
            val oldestEnded = kept.filter { it.end != null }.minByOrNull { it.lastFiredAtMillis ?: it.createdAtMillis }
                ?: return Result.failure(IllegalStateException(modelText("已经有 $MAX_PLANS 个计划了：先在「计划」页删掉一些", "There are $MAX_PLANS plans already: delete some first")))
            kept = kept - oldestEnded
        }
        val plan = Plan(
            id = newId(), title = singleLine(title), message = singleLine(message), instruction = instruction?.let(::singleLine)?.ifEmpty { null },
            trigger = trigger, enabled = true, createdAtMillis = now(),
            // The words as said, unless they hold a password or a card number (kept out like 记忆 keeps them out).
            said = said?.let(::singleLine)?.takeIf { sensitiveTextError(it) == null }?.take(MAX_SAID_CHARS)?.ifEmpty { null },
            actions = actions,
        )
        save(kept + plan, history)
        return Result.success(plan)
    }

    /** [change] applied to the plan [id]; null when there is none. */
    @Synchronized
    fun update(id: String, change: (Plan) -> Plan): Plan? {
        val old = plans.firstOrNull { it.id == id } ?: return null
        val next = change(old).let { it.copy(title = singleLine(it.title), message = singleLine(it.message), instruction = it.instruction?.let(::singleLine)) }
        planTextError(next.title, next.message, next.instruction, next.actions.isNotEmpty())?.let { throw IllegalArgumentException(it) }
        save(plans.map { if (it.id == id) next else it }, history)
        return next
    }

    @Synchronized
    fun remove(id: String): Plan? {
        val old = plans.firstOrNull { it.id == id } ?: return null
        save(plans - old, history)
        return old
    }

    @Synchronized
    fun record(firing: PlanFiring) = save(plans, (listOf(firing) + history).take(MAX_HISTORY))

    /** Throws IOException when the disk takes nothing; the plans in memory then stay as they were. */
    private fun save(nextPlans: List<Plan>, nextHistory: List<PlanFiring>) {
        saveFileAtomically(file, encodePlanFile(nextPlans, nextHistory))
        plans = nextPlans
        history = nextHistory
    }

    private fun newId(): String {
        while (true) {
            val id = "p" + UUID.randomUUID().toString().replace("-", "").take(ID_HEX_CHARS)
            if (plans.none { it.id == id }) return id
        }
    }
}

internal const val MAX_PLANS = 20
internal const val MAX_PLAN_TITLE_CHARS = 20
internal const val MAX_PLAN_MESSAGE_CHARS = 120
internal const val MAX_PLAN_INSTRUCTION_CHARS = 200
/** A one-off missed by up to this much (the device was asleep) still reminds, saying when it was for. */
internal const val ONCE_CATCH_UP_MS = 2 * 60 * 60 * 1000L
/** A repeat occurrence up to this late still reminds: more than the inexact alarm's window ([PlanRunner]). */
internal const val REPEAT_CATCH_UP_MS = 15 * 60 * 1000L
internal const val LATE_AFTER_MS = 2 * 60 * 1000L
internal const val MAX_IN_MINUTES = 7 * 24 * 60
private const val MAX_HOLD_SECONDS = 600
private const val MAX_POLL_SECONDS = 3600
private const val MAX_WATCH_TEXT_CHARS = 40
private const val MILLIS_PER_SECOND = 1_000L
/**
 * A repeating number plan re-arms once the reading is back past the threshold by 5 % of it: 「雨量高于 0」 re-arms at 0
 * (a floor of 1 would ask for -1, which a rain level never reads).
 */
private const val MARGIN_SHARE = 0.05
private const val MAX_HISTORY = 20
private const val MAX_SAID_CHARS = 200
private const val MAX_DAYS_AHEAD = 366L
private const val ID_HEX_CHARS = 8
private const val WEEK_DAYS = 7L
private const val MINUTES_PER_HOUR = 60
private const val MILLIS_PER_MINUTE = 60_000L
private const val TAG = "DebugPlans"
private val CLOCK = Regex("""(\d{1,2})[:：](\d{2})""")

/** A server's own tool name from the runtime's 「mcp__<server>__<tool>」, or the name as given. */
internal fun serverToolName(name: String): String =
    if (name.startsWith("mcp__")) name.removePrefix("mcp__").substringAfter("__", name) else name
