package cc.noharry.bothy.app.plans
import android.util.Log
import cc.noharry.bothy.app.a2a.describe
import cc.noharry.bothy.app.a2ui.valueText
import cc.noharry.bothy.app.console.saveFileAtomically
import cc.noharry.bothy.app.console.setAsideUnreadable
import cc.noharry.bothy.app.console.singleLine
import cc.noharry.bothy.app.memory.sensitiveTextError
import cc.noharry.bothy.app.model.describe
import cc.noharry.bothy.app.runtime.append
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** When a plan comes due. */
internal sealed interface PlanTrigger {
    /** Once, at [atMillis]. */
    data class Once(val atMillis: Long) : PlanTrigger

    /** At [minuteOfDay] (local time) on [days], ISO day numbers 1 (Monday) to 7 (Sunday). */
    data class Repeat(val minuteOfDay: Int, val days: Set<Int>) : PlanTrigger

    /**
     * When [watch] matches a reading: a number over or under its threshold for [holdSeconds], a value the field becomes,
     * or each event; [repeat] fires again later.
     */
    data class Watch(val watch: PlanWatch, val holdSeconds: Int, val repeat: Boolean) : PlanTrigger
}

/** The resource a trigger waits for, or null for a time. */
internal fun PlanTrigger.watch(): PlanWatch? = (this as? PlanTrigger.Watch)?.watch

/** Whether the plan goes on after it fired. */
internal fun PlanTrigger.repeats(): Boolean = when (this) {
    is PlanTrigger.Watch -> repeat
    else -> this is PlanTrigger.Repeat
}

/** How a plan ended: it did its one job, or its time passed while the device slept too long. */
internal enum class PlanEnd(val id: String, val label: String) {
    DONE("done", "已完成"),
    MISSED("missed", "已错过"),
    ;

    companion object {
        fun byId(id: String?): PlanEnd? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One plan: [message] is said when it comes due (a reminder), or, with [instruction], 小博 does that as a turn of its
 * own, and [actions] are car controls the app runs itself, without the model (an automation, owner 2026-10-16).
 * [lastDueAtMillis] is the last occurrence of a repeat handled (fired or skipped), so none is handled twice.
 */
internal data class Plan(
    val id: String,
    val title: String,
    val message: String,
    val instruction: String?,
    val trigger: PlanTrigger,
    val enabled: Boolean,
    val createdAtMillis: Long,
    val lastFiredAtMillis: Long? = null,
    val lastDueAtMillis: Long? = null,
    val firedCount: Int = 0,
    val end: PlanEnd? = null,
    val said: String? = null,
    val actions: List<PlanAction> = emptyList(),
) {
    /** Still waiting to come due: switched on and not ended. */
    val active: Boolean get() = enabled && end == null
}

/** A plan came due (or was missed), for the 计划 page's 最近触发. */
internal data class PlanFiring(val planId: String, val title: String, val atMillis: Long, val note: String)

/** The next time [this] comes due, or null for a condition and for a paused or ended plan. A past time is due now. */
internal fun Plan.nextDueMillis(now: Long, zone: ZoneId): Long? {
    if (!active) return null
    return when (val trigger = trigger) {
        is PlanTrigger.Once -> trigger.atMillis
        is PlanTrigger.Repeat -> nextRepeat(trigger, now, zone)
        is PlanTrigger.Watch -> null
    }
}

/** The first occurrence of [repeat] strictly after [after]. */
internal fun nextRepeat(repeat: PlanTrigger.Repeat, after: Long, zone: ZoneId): Long {
    val today = Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
    return (0L..WEEK_DAYS).asSequence().map(today::plusDays)
        .filter { it.dayOfWeek.value in repeat.days }
        .map { repeatAt(repeat, it, zone) }
        .first { it > after }
}

/** The latest occurrence of [repeat] at or before [now], or null when none is within a week. */
internal fun lastRepeat(repeat: PlanTrigger.Repeat, now: Long, zone: ZoneId): Long? {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return (0L..WEEK_DAYS).asSequence().map(today::minusDays)
        .filter { it.dayOfWeek.value in repeat.days }
        .map { repeatAt(repeat, it, zone) }
        .firstOrNull { it <= now }
}

private fun repeatAt(repeat: PlanTrigger.Repeat, day: LocalDate, zone: ZoneId): Long =
    day.atTime(repeat.minuteOfDay / MINUTES_PER_HOUR, repeat.minuteOfDay % MINUTES_PER_HOUR).atZone(zone).toInstant().toEpochMilli()

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
internal fun Plan.retimed(trigger: PlanTrigger, now: Long): Plan =
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
        require(repeat == null) { "in_minutes 只用于一次性的计划；重复的计划用 time 和 repeat" }
        require(minutes in 1..MAX_IN_MINUTES) { "in_minutes 要在 1 到 $MAX_IN_MINUTES 之间（最多 7 天）" }
        return@runCatching PlanTrigger.Once(now + minutes * MILLIS_PER_MINUTE)
    }
    val time = parseClock(ask.time) ?: throw IllegalArgumentException("需要 in_minutes，或者 HH:mm 格式的 time")
    val minuteOfDay = time.hour * MINUTES_PER_HOUR + time.minute
    if (repeat != null) return@runCatching PlanTrigger.Repeat(minuteOfDay, repeatDays(repeat, ask.days))
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val date = ask.date?.let(::parseDate) ?: today
    var at = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
    if (ask.date == null && at <= now) at = date.plusDays(1).atTime(time).atZone(zone).toInstant().toEpochMilli()
    require(at > now) { "这个时间已经过了" }
    require(date <= today.plusDays(MAX_DAYS_AHEAD)) { "最多安排一年以内的计划" }
    PlanTrigger.Once(at)
}

private fun repeatDays(repeat: String, days: List<Int>?): Set<Int> = when (repeat) {
    "daily" -> (1..WEEK_DAYS.toInt()).toSet()
    "weekdays" -> (DayOfWeek.MONDAY.value..DayOfWeek.FRIDAY.value).toSet()
    "weekends" -> setOf(DayOfWeek.SATURDAY.value, DayOfWeek.SUNDAY.value)
    "weekly" -> days.orEmpty().toSet().also { picked ->
        require(picked.isNotEmpty() && picked.all { it in 1..WEEK_DAYS.toInt() }) { "weekly 需要 days，1 是周一，7 是周日" }
    }
    else -> throw IllegalArgumentException("repeat 只能是 none、daily、weekdays、weekends 或 weekly")
}

private fun parseClock(text: String?): LocalTime? =
    text?.trim()?.let { CLOCK.matchEntire(it) }?.destructured?.let { (hour, minute) ->
        runCatching { LocalTime.of(hour.toInt(), minute.toInt()) }.getOrNull()
    }

private fun parseDate(text: String): LocalDate = try {
    LocalDate.parse(text.trim())
} catch (_: DateTimeParseException) {
    throw IllegalArgumentException("date 要写成 YYYY-MM-DD，比如 2026-10-20")
}

/** What the model asked for in `watch`, before it becomes a trigger. */
internal data class PlanWatchAsk(
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
internal fun resolvePlanWatch(ask: PlanWatchAsk, repeatByDefault: Boolean): Result<PlanTrigger> = runCatching {
    val tool = ask.tool?.trim()?.takeIf(String::isNotEmpty)?.let(::serverToolName)
    val uri = ask.uri?.trim()?.takeIf(String::isNotEmpty)
    require(uri == null || tool == null) { "watch 的 uri 和 tool 只能写一个" }
    require(uri != null || tool != null) { "watch 需要 uri（资源，用 signals 查有哪些）或 tool（服务的只读查询工具，再加 arguments）" }
    val field = ask.field?.trim()?.takeIf(String::isNotEmpty) ?: throw IllegalArgumentException("watch 需要 field（用 inspect 看读数里有哪些字段）")
    val op = WatchOp.byId(ask.op) ?: throw IllegalArgumentException("op 只能是：${WatchOp.entries.joinToString("、") { it.id }}")
    val value = ask.value
    require(op == WatchOp.CHANGES || value != null) { "op=${op.id} 需要 value" }
    val threshold = value?.let(::valueText)?.toDoubleOrNull()
    require(!op.numeric || threshold != null) { "op=${op.id} 的 value 要是数字" }
    val mode = ask.mode?.let { WatchMode.byId(it) ?: throw IllegalArgumentException("mode 只能是 enter 或 each") } ?: WatchMode.ENTER
    require(!(op.numeric && mode == WatchMode.EACH)) { "数字比较用 enter：超过（低于）时触发一次，回落后才再触发" }
    val hold = if (op.numeric) ask.holdSeconds ?: 0 else 0
    require(hold in 0..MAX_HOLD_SECONDS) { "hold_seconds 要在 0 到 $MAX_HOLD_SECONDS 之间" }
    val defaultPoll = if (tool != null) DEFAULT_TOOL_WATCH_POLL_MS else DEFAULT_WATCH_POLL_MS
    val poll = ask.pollSeconds ?: (defaultPoll / MILLIS_PER_SECOND).toInt()
    require(poll in 1..MAX_POLL_SECONDS) { "poll_seconds 要在 1 到 $MAX_POLL_SECONDS 之间" }
    require(tool == null || poll * MILLIS_PER_SECOND >= MIN_TOOL_WATCH_POLL_MS) {
        "调用工具的监听最快 ${MIN_TOOL_WATCH_POLL_MS / MILLIS_PER_SECOND} 秒一次（poll_seconds），别给别人的服务添负担"
    }
    val words = ask.text?.let(::singleLine)?.takeIf(String::isNotEmpty)?.take(MAX_WATCH_TEXT_CHARS)
        ?: "${tool ?: uri} 的 $field ${op.words}${value?.let { " " + valueText(it) }.orEmpty()}"
    val watch = PlanWatch(
        server = ask.server?.trim()?.takeIf(String::isNotEmpty) ?: throw IllegalArgumentException("watch 要写 server（MCP 服务器 id）"),
        uri = uri.orEmpty(), field = field, op = op, value = value, text = words, where = ask.where, mode = mode,
        margin = threshold?.let { kotlin.math.abs(it) * MARGIN_SHARE } ?: 0.0,
        pollMillis = poll * MILLIS_PER_SECOND,
        tool = tool,
        arguments = ask.arguments ?: JsonObject(emptyMap()),
    )
    PlanTrigger.Watch(watch, hold, ask.repeat ?: repeatByDefault)
}

/** The trigger in words, as the page and the model see it: 「今天 16:30」「工作日 08:00」「最低价低于 800 元」. */
internal fun PlanTrigger.describe(now: Long, zone: ZoneId): String = when (this) {
    is PlanTrigger.Once -> describeMoment(atMillis, now, zone)
    is PlanTrigger.Repeat -> "${describeDays(days)} ${clock(minuteOfDay)}"
    is PlanTrigger.Watch -> buildString {
        append(watch.text)
        if (holdSeconds > 0) append(" 持续 $holdSeconds 秒")
        append(if (repeat) "，每次" else "，一次")
    }
}

/** 「今天 16:30」「明天 08:00」「后天 08:00」「10月20日 周二 08:00」. */
internal fun describeMoment(atMillis: Long, now: Long, zone: ZoneId): String {
    val at = Instant.ofEpochMilli(atMillis).atZone(zone)
    val days = at.toLocalDate().toEpochDay() - Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
    val day = when (days) {
        0L -> "今天"
        1L -> "明天"
        2L -> "后天"
        -1L -> "昨天"
        else -> "${at.monthValue}月${at.dayOfMonth}日 ${PLAN_WEEKDAYS[at.dayOfWeek.value - 1]}"
    }
    return "$day ${at.format(CLOCK_FORMAT)}"
}

private fun describeDays(days: Set<Int>): String = when (days) {
    (1..WEEK_DAYS.toInt()).toSet() -> "每天"
    (1..5).toSet() -> "工作日"
    setOf(6, 7) -> "周末"
    else -> "每" + days.sorted().joinToString("、") { PLAN_WEEKDAYS[it - 1] }
}

private fun clock(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / MINUTES_PER_HOUR, minuteOfDay % MINUTES_PER_HOUR)

/** Why [title], [message] or [instruction] cannot be a plan's, or null; a plan with [actions] needs no words. */
internal fun planTextError(title: String, message: String, instruction: String?, actions: Boolean = false): String? = when {
    title.isBlank() -> "需要 title（计划的名字，比如「拿快递」）"
    title.length > MAX_PLAN_TITLE_CHARS -> "title 不超过 $MAX_PLAN_TITLE_CHARS 个字"
    message.isBlank() && instruction.isNullOrBlank() && !actions ->
        "需要 message（到点时说的话）、instruction（到点让小博去办的事）或 actions（到点直接执行的操作）"
    message.length > MAX_PLAN_MESSAGE_CHARS -> "message 不超过 $MAX_PLAN_MESSAGE_CHARS 个字"
    (instruction?.length ?: 0) > MAX_PLAN_INSTRUCTION_CHARS -> "instruction 不超过 $MAX_PLAN_INSTRUCTION_CHARS 个字"
    else -> null
}

/**
 * The plans and the latest firings in one file, written whole on each change. At most [MAX_PLANS]: a new plan makes
 * room by dropping the oldest ended one, and is refused when every plan is still going.
 */
internal class PlanBook(
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
                ?: return Result.failure(IllegalStateException("已经有 $MAX_PLANS 个计划了：先在「计划」页删掉一些"))
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
private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
internal val PLAN_WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** A server's own tool name from the runtime's 「mcp__<server>__<tool>」, or the name as given. */
internal fun serverToolName(name: String): String =
    if (name.startsWith("mcp__")) name.removePrefix("mcp__").substringAfter("__", name) else name
