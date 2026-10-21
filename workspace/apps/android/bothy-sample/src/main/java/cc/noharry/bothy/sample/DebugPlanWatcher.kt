package cc.noharry.bothy.sample

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Where one watched resource stands, for the 计划 page. */
internal enum class DebugSubscriptionState(val label: String) {
    OFFLINE("未连接"),
    PENDING("订阅中"),
    LIVE("实时"),
    /** The server answered a subscribe with an error: its owners read it on their own pace instead. */
    REFUSED("不支持推送"),
    /** Read on the plan's own pace. */
    NO_PUSH("定时读取"),
}

/**
 * Watches what the plans wait for, on whatever MCP servers are loaded: each watched resource is read on the plan's own
 * pace. Numbers go through [DebugConditionTracker] (hold, re-arm); values and events through [DebugChangeTracker];
 * [DebugFiringGuard] pauses a plan that fires in a loop. State lives on one thread, so reads and the plans' changes
 * never race. Only while the app runs.
 */
internal object DebugPlanWatcher {
    private val scope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor { Thread(it, "plan-watch") }.asCoroutineDispatcher())
    private val numbers = DebugConditionTracker()
    private val changes = DebugChangeTracker()
    private val guard = DebugFiringGuard()
    private val latest = mutableMapOf<Pair<String, String>, JsonObject>()
    private val readAt = mutableMapOf<String, Long>()
    private val readFailures = mutableMapOf<String, Int>()
    private val reading = mutableSetOf<String>()
    private var watched: List<DebugPlan> = emptyList()
    private var app: Context? = null
    private var ticker: Job? = null
    private val mutableStates = MutableStateFlow<Map<String, DebugSubscriptionState>>(emptyMap())
    private val trail = DebugWatchTrail()

    /** How each watched plan is fed (实时 / 定时读取 / 未连接…), by plan id, for the 计划 page. */
    val states: StateFlow<Map<String, DebugSubscriptionState>> = mutableStates

    /** The readings of what a watch reads: a resource, one per item where it has several, or a tool's answer (tests replace it). */
    internal var reader: suspend (Context, DebugPlanWatch) -> List<JsonObject>? = { context, watch ->
        val tool = watch.tool
        if (tool != null) readMcpTool(context, watch.server, tool, watch.arguments)?.let(::listOf) else readMcpResourceAll(context, watch.server, watch.uri)
    }

    /** Follows the plans: subscribes what the active ones watch and lets go of the rest. Cheap; any thread. */
    fun refresh(context: Context, plans: List<DebugPlan>) {
        val next = plans.filter { it.active && it.trigger is DebugPlanTrigger.Watch }
        val app = context.applicationContext
        scope.launch { follow(app, next) }
    }

    private fun follow(context: Context, next: List<DebugPlan>) {
        app = context
        val ids = next.mapTo(HashSet()) { it.id }
        watched = next
        numbers.retain(ids)
        changes.retain(ids)
        guard.retain(ids)
        readAt.keys.retainAll(ids)
        // A server's address may have changed with the plans (switched off, edited): looked up again.
        loopbackServers.clear()
        readFailures.keys.retainAll(ids)
        // A reading nobody watches now is stale: a rule made later on the same resource waits for a fresh one.
        latest.keys.retainAll(next.mapNotNullTo(HashSet()) { plan -> plan.trigger.watch()?.let { it.server to it.source } })
        publish()
        if (next.isEmpty()) {
            ticker?.cancel()
            ticker = null
        } else if (ticker?.isActive != true) {
            ticker = scope.launch { tick() }
        }
    }

    /**
     * Each second: reads each watched resource on its plan's pace, each read on its own (a slow server holds up no
     * other plan), longer apart after failures, judged against the plan as it is when the answer comes.
     */
    private suspend fun tick() {
        while (true) {
            val context = app ?: return
            val now = System.currentTimeMillis()
            watched.forEach { plan ->
                val watch = plan.trigger.watch() ?: return@forEach
                if (plan.id !in reading && now >= (readAt[plan.id] ?: 0L)) {
                    reading += plan.id
                    scope.launch { poll(context, plan, watch) }
                }
            }
            delay(TICK_MS)
        }
    }

    private suspend fun poll(context: Context, plan: DebugPlan, watch: DebugPlanWatch) {
        // A read answers every window at once: the watch takes the one its where names.
        val data = try {
            reader(context, watch)?.let { readings -> readings.firstOrNull(watch::concerns) ?: readings.firstOrNull() }
        } finally {
            reading -= plan.id
        }
        val failures = if (data == null) (readFailures[plan.id] ?: 0) + 1 else 0
        readFailures[plan.id] = failures
        readAt[plan.id] = System.currentTimeMillis() + minOf(pace(context, watch) * (1 + failures), MAX_READ_BACKOFF_MS)
        val current = watched.firstOrNull { it.id == plan.id && it.trigger == plan.trigger } ?: return
        data ?: return
        latest[watch.server to watch.source] = data
        evaluate(context, current, data, initial = false, System.currentTimeMillis(), pushed = false)
    }

    /**
     * How often [watch] is read: the plan's pace, or within [LOOPBACK_POLL_MS] on a server on this device (Bothy
     * Playground), where a read costs nothing; an arrival reminder then speaks as the car arrives, not half a minute on.
     */
    private fun pace(context: Context, watch: DebugPlanWatch): Long {
        val local = loopbackServers.getOrPut(watch.server) {
            loadDebugMcpServers(context).firstOrNull { it.id == watch.server }?.url?.let(::isLoopbackUrl) == true
        }
        return if (local) minOf(watch.pollMillis, LOOPBACK_POLL_MS) else watch.pollMillis
    }

    private val loopbackServers = mutableMapOf<String, Boolean>()

    private fun evaluate(context: Context, plan: DebugPlan, data: JsonObject, initial: Boolean, now: Long, pushed: Boolean = true) {
        val watch = plan.trigger.watch() ?: return
        if (!watch.concerns(data)) {
            trail.note("rule ${plan.id}", "rule ${plan.id} skips a reading of ${watch.sourceLabel}: not the one its where names (${watch.where})")
            return
        }
        val fires = if (watch.op.numeric) numbers.observe(plan, watch.number(data), now) else changes.observe(plan, data, initial, pushed)
        val reading = watch.reading(data)
        if (!fires) {
            trail.note("rule ${plan.id}", "rule ${plan.id} ${watch.field}=${reading.loggable()} ${watch.op}: not now")
            return
        }
        val verdict = guard.check(plan.id, now)
        trail.always("rule ${plan.id} ${watch.field}=${reading.loggable()} met: $verdict")
        when (verdict) {
            DebugFiringGuard.Verdict.FIRE -> DebugPlanRunner.conditionMet(context, plan.id, reading)
            DebugFiringGuard.Verdict.PAUSE -> DebugPlanRunner.pauseRunaway(context, plan.id)
            DebugFiringGuard.Verdict.SKIP -> Unit
        }
    }

    private fun publish() {
        mutableStates.value = watched.associate { plan -> plan.id to DebugSubscriptionState.NO_PUSH }
    }
}

/**
 * When a number plan fires: once its reading has met the threshold for the plan's hold time. Then it waits to be
 * re-armed: the reading must go back past the threshold by the watch's margin, and [cooldownMillis] must pass, so a
 * speed hovering around 120 reminds once. A plan that reminded before starts disarmed, from its last firing: an app
 * restart while the range is still low (the bench, 2026-10-15) must not remind again. A reading that could not be taken
 * is not 0 and restarts the hold. A clock set back since the firing counts as cooled down.
 */
internal class DebugConditionTracker(private val cooldownMillis: Long = REPEAT_COOLDOWN_MS) {
    private class State(val trigger: DebugPlanTrigger, var since: Long? = null, var armed: Boolean = true, var firedAt: Long = 0L)

    private val states = mutableMapOf<String, State>()

    @Synchronized
    fun observe(plan: DebugPlan, value: Double?, now: Long): Boolean {
        val trigger = plan.trigger as? DebugPlanTrigger.Watch ?: return false
        val watch = trigger.watch
        val threshold = watch.value?.let(::valueText)?.toDoubleOrNull() ?: return false
        // A plan given a new watch starts over (its last firing was cleared with it, see [retimed]).
        val state = states[plan.id]?.takeIf { it.trigger == trigger }
            ?: (plan.lastFiredAtMillis?.let { State(trigger, armed = false, firedAt = it) } ?: State(trigger)).also { states[plan.id] = it }
        if (value == null) {
            state.since = null
            return false
        }
        val above = watch.op == DebugWatchOp.ABOVE
        if (!(if (above) value > threshold else value < threshold)) {
            state.since = null
            val cleared = if (above) value <= threshold - watch.margin else value >= threshold + watch.margin
            if (!state.armed && cleared && (now - state.firedAt >= cooldownMillis || now < state.firedAt)) state.armed = true
            return false
        }
        if (!state.armed) return false
        val since = state.since ?: now.also { state.since = it }
        if (now - since < trigger.holdSeconds * MILLIS_PER_SECOND) return false
        state.armed = false
        state.firedAt = now
        state.since = null
        return true
    }

    @Synchronized
    fun retain(ids: Set<String>) {
        states.keys.retainAll(ids)
    }
}

/**
 * When a value or event plan fires. `enter`: when the field becomes the value (or stops being it, for not_equals); the
 * first reading, and the snapshot a subscription starts with, only set where it stands (the adapter's music snapshot
 * always says false, 2026-10-16). `changes`: when the field differs from the last reading. `each`: every matching
 * pushed reading but the snapshot (an arrival is not a state, the next one looks the same); a read one (a server that
 * does not push) only when the whole reading differs from the last read, or the last arrival would fire on every read.
 */
internal class DebugChangeTracker {
    private class State(val watch: DebugPlanWatch, var matched: Boolean? = null, var reading: String? = null, var whole: String? = null)

    private val states = mutableMapOf<String, State>()

    @Synchronized
    fun observe(plan: DebugPlan, data: JsonObject, initial: Boolean, pushed: Boolean = true): Boolean {
        val watch = plan.trigger.watch() ?: return false
        val state = states[plan.id]?.takeIf { it.watch == watch } ?: State(watch).also { states[plan.id] = it }
        val reading = watch.reading(data)
        val matched = watch.matches(data)
        val before = state.matched
        val previous = state.reading
        val whole = data.toString()
        val newRead = state.whole != null && state.whole != whole
        state.matched = matched
        state.reading = reading
        state.whole = whole
        if (initial) return false
        return when {
            watch.mode == DebugWatchMode.EACH && !pushed -> newRead && (matched || watch.op == DebugWatchOp.CHANGES)
            watch.mode == DebugWatchMode.EACH -> if (watch.op == DebugWatchOp.CHANGES) reading != null else matched
            watch.op == DebugWatchOp.CHANGES -> previous != null && reading != previous
            else -> before == false && matched
        }
    }

    @Synchronized
    fun retain(ids: Set<String>) {
        states.keys.retainAll(ids)
    }
}

/**
 * Keeps a plan from firing in a loop: [Verdict.SKIP] within [minGapMillis] of its last firing (a burst of equal pushes),
 * [Verdict.PAUSE] past [maxFirings] firings in [windowMillis] (a rule whose action sets off its own trigger).
 */
internal class DebugFiringGuard(
    private val minGapMillis: Long = MIN_FIRING_GAP_MS,
    private val maxFirings: Int = MAX_FIRINGS,
    private val windowMillis: Long = FIRING_WINDOW_MS,
) {
    enum class Verdict { FIRE, SKIP, PAUSE }

    private val firings = mutableMapOf<String, ArrayDeque<Long>>()

    @Synchronized
    fun check(id: String, now: Long): Verdict {
        val recent = firings.getOrPut(id) { ArrayDeque() }
        if (recent.isNotEmpty() && now - recent.last() in 0 until minGapMillis) return Verdict.SKIP
        while (recent.isNotEmpty() && now - recent.first() > windowMillis) recent.removeFirst()
        recent.addLast(now)
        return if (recent.size > maxFirings) Verdict.PAUSE else Verdict.FIRE
    }

    @Synchronized
    fun retain(ids: Set<String>) {
        firings.keys.retainAll(ids)
    }
}

private const val TICK_MS = 1_000L
private const val LOOPBACK_POLL_MS = 3_000L

/** An address on this device: 127.0.0.1, localhost or [::1]. */
internal fun isLoopbackUrl(url: String): Boolean =
    Regex("""^https?://(127\.0\.0\.1|localhost|\[::1])(:\d+)?(/|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url.trim())

/** A read that keeps failing is tried at most this far apart. */
private const val MAX_READ_BACKOFF_MS = 5 * 60 * 1000L
private const val MILLIS_PER_SECOND = 1_000L
/** A repeating number plan reminds at most this often, even when the reading keeps crossing back. */
internal const val REPEAT_COOLDOWN_MS = 5 * 60 * 1000L
private const val MIN_FIRING_GAP_MS = 3_000L
private const val MAX_FIRINGS = 10
private const val FIRING_WINDOW_MS = 10 * 60 * 1000L

/**
 * What the rules saw, for a device's log (2026-10-19: a 「空调到 20 度就开窗」 rule was subscribed but never
 * fired, and nothing showed whether a push came or why it did not match). A key logs once per [WATCH_LOG_EVERY_MS]
 * with how many it held back, so a signal that pushes many times a second does not fill the log; a firing always logs.
 */
internal class DebugWatchTrail(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { Log.i(WATCH_TAG, it) },
) {
    private val lastAt = mutableMapOf<String, Long>()
    private val held = mutableMapOf<String, Int>()

    fun note(key: String, line: String) {
        val now = nowMillis()
        val last = lastAt[key]
        if (last != null && now - last < WATCH_LOG_EVERY_MS) {
            held[key] = (held[key] ?: 0) + 1
            return
        }
        lastAt[key] = now
        val skipped = held.remove(key)?.let { " (+$it since)" }.orEmpty()
        log(line + skipped)
    }

    fun always(line: String) = log(line)
}

/**
 * A reading as the device's log may keep it: numbers and switches as they are, text as 「‹text›」 (a destination's name,
 * a POI, a plan's title stay out; DebugPlanRunner logs plans by id too).
 */
internal fun JsonObject.loggable(): String =
    entries.joinToString(" ", "{", "}") { (key, value) -> "$key=${(value as? JsonPrimitive)?.loggableValue() ?: "‹…›"}" }
        .take(MAX_LOGGED_DATA)

internal fun String?.loggable(): String =
    if (this == null || toDoubleOrNull() != null || this == "true" || this == "false") toString() else TEXT_MASK

private fun JsonPrimitive.loggableValue(): String = if (isString) TEXT_MASK else content

private const val WATCH_TAG = "DebugPlanWatch"
private const val WATCH_LOG_EVERY_MS = 10_000L
private const val MAX_LOGGED_DATA = 160
private const val TEXT_MASK = "‹text›"
