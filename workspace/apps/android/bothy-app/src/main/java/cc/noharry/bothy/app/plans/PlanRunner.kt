package cc.noharry.bothy.app.plans
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import cc.noharry.bothy.app.a2a.describe
import cc.noharry.bothy.app.model.describe
import cc.noharry.bothy.app.runtime.RuntimeSession
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Runs the plans (owner 2026-10-15: 「可以开始计划的模块，这个模块要有可见性」): keeps them in a [PlanBook] under
 * `files/plans/`, wakes the app at the next due time with an exact alarm ([PlanAlarmReceiver]; inexact where the
 * system grants no exact alarms), fires or gives up what came due, and has [PlanWatcher] read the device for condition
 * plans. A plan coming due chimes and shows in [PlanNotice] for five seconds, then is spoken as written, without
 * the model (a reminder), or becomes a turn 小博 runs (a task); see [RuntimeSession.deliverPlan]. The log names
 * plans by id only.
 */
internal object PlanRunner {
    private val mutablePlans = MutableStateFlow<List<Plan>>(emptyList())
    private val mutableHistory = MutableStateFlow<List<PlanFiring>>(emptyList())
    val plans: StateFlow<List<Plan>> = mutablePlans
    val history: StateFlow<List<PlanFiring>> = mutableHistory

    /** The clock's zone; tests pin it. */
    internal var zone: () -> ZoneId = ZoneId::systemDefault
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    /** After a check that could not save, the next alarm waits until then (under [lock]). */
    private var retryAt = 0L
    @Volatile private var book: PlanBook? = null

    /** At app start, off the main thread: what came due while the app was gone is fired or given up, then the alarm. */
    fun init(context: Context) {
        val app = context.applicationContext
        scope.launch { check(app) }
    }

    // The page's switch and delete, the alarm and the watcher run on threads with nobody to catch: a full disk is
    // logged (by class only) and the plans stay as they were, instead of the app crashing at every start.
    private inline fun logFailure(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
        }
    }

    fun book(context: Context): PlanBook = book ?: synchronized(this) {
        book ?: PlanBook(File(context.applicationContext.filesDir, PLAN_FILE)).also {
            book = it
            publish(it)
        }
    }

    /**
     * A change through the book; the page, the alarm and the watcher then follow it, inside the same lock, so a
     * slower writer cannot set the alarm or the watcher from plans older than the last change. Any thread but main.
     */
    fun <T> write(context: Context, change: (PlanBook) -> T): T {
        val app = context.applicationContext
        val target = book(app)
        return synchronized(lock) {
            change(target).also { settle(app, target) }
        }
    }

    /** The page's switch for one plan; switched back on, it does not remind what came due while it was off. */
    fun setEnabled(context: Context, id: String, enabled: Boolean) = logFailure("switch $id") {
        val now = System.currentTimeMillis()
        write(context) { it.update(id) { plan -> if (enabled) plan.resumed(now) else plan.copy(enabled = false) } }
        Log.i(TAG, "${if (enabled) "resumed" else "paused"} $id")
    }

    fun delete(context: Context, id: String) = logFailure("delete $id") {
        write(context) { it.remove(id) }
        Log.i(TAG, "deleted $id")
    }

    /** The alarm went off, the app started or the clock changed: fire or give up what is due, then set the next alarm. */
    fun check(context: Context) = logFailure("check") {
        val app = context.applicationContext
        val target = book(app)
        synchronized(lock) {
            val now = System.currentTimeMillis()
            try {
                target.plans().forEach { plan ->
                    when (val due = plan.dueAt(now, zone())) {
                        is PlanDue.Fire -> fire(app, target, plan, due.dueAtMillis, due.late, reading = null)
                        is PlanDue.Miss -> miss(target, plan, due)
                        null -> Unit
                    }
                }
                retryAt = 0L
            } catch (e: Exception) {
                // A full disk: what is due stays due, and is tried again in a minute, not by an alarm every second.
                retryAt = now + CHECK_RETRY_MS
                throw e
            } finally {
                settle(app, target)
            }
        }
    }

    /** A watch plan's reading matched ([PlanWatcher]); [reading] is the field's value in words. */
    fun conditionMet(context: Context, planId: String, reading: String?) = logFailure("condition $planId") {
        val app = context.applicationContext
        val target = book(app)
        synchronized(lock) {
            val plan = target.plans().firstOrNull { it.id == planId && it.active } ?: return@synchronized
            try {
                fire(app, target, plan, System.currentTimeMillis(), late = false, reading = reading)
            } finally {
                settle(app, target)
            }
        }
    }

    private fun fire(app: Context, target: PlanBook, plan: Plan, dueAt: Long, late: Boolean, reading: String?) {
        val now = System.currentTimeMillis()
        val ends = plan.trigger is PlanTrigger.Once || (plan.trigger is PlanTrigger.Watch && !plan.trigger.repeats())
        target.update(plan.id) {
            it.copy(lastFiredAtMillis = now, lastDueAtMillis = dueAt, firedCount = it.firedCount + 1, end = if (ends) PlanEnd.DONE else it.end)
        }
        Log.i(TAG, "fired ${plan.id} (${plan.trigger.kind()}, late=$late, actions=${plan.actions.size})")
        val delivery = if (plan.message.isBlank() && plan.instruction == null) null else planDelivery(plan, dueAt, late, reading, now, zone())
        PlanNotice.show(app, planNoticeEntry(plan, delivery?.reply))
        // Its own controls run without the model; their outcome is a history line of its own when they are done.
        if (plan.actions.isNotEmpty()) {
            PlanActionRunner.run(app, plan) { lines ->
                PlanNotice.ran(plan.id, lines)
                logFailure("record ${plan.id}") { write(app) { it.record(PlanFiring(plan.id, plan.title, now, lines.joinToString("；"))) } }
            }
        }
        if (delivery == null) return
        // Delivered once marked fired, before the history line: a history that cannot be saved loses only its line.
        RuntimeSession.deliverPlan(app, delivery)
        val note = when {
            plan.instruction != null -> "交给小博去办"
            late -> "晚了 ${(now - dueAt) / MILLIS_PER_MINUTE} 分钟提醒（设备当时没开）"
            else -> "已提醒"
        }
        target.record(PlanFiring(plan.id, plan.title, now, note))
    }

    /** [FiringGuard] caught a plan firing in a loop (its action sets off its own trigger): it is switched off. */
    fun pauseRunaway(context: Context, planId: String) = logFailure("pause $planId") {
        write(context) { book ->
            val plan = book.update(planId) { it.copy(enabled = false) } ?: return@write
            book.record(PlanFiring(planId, plan.title, System.currentTimeMillis(), "触发太频繁，已暂停：看看是不是它的动作又触发了它自己"))
        }
        Log.w(TAG, "paused runaway $planId")
    }

    private fun miss(target: PlanBook, plan: Plan, due: PlanDue.Miss) {
        val dueAt = due.dueAtMillis
        target.update(plan.id) {
            if (it.trigger is PlanTrigger.Once) it.copy(end = PlanEnd.MISSED, lastDueAtMillis = dueAt) else it.copy(lastDueAtMillis = dueAt)
        }
        target.record(PlanFiring(plan.id, plan.title, dueAt, if (due.paused) "暂停期间到点，没有提醒" else "设备没开，错过了"))
        Log.i(TAG, "missed ${plan.id} (${plan.trigger.kind()})")
    }

    private fun settle(app: Context, target: PlanBook) {
        publish(target)
        schedule(app, target.plans())
        PlanWatcher.refresh(app, target.plans())
    }

    private fun publish(target: PlanBook) {
        mutablePlans.value = target.plans()
        mutableHistory.value = target.history()
    }

    /**
     * One alarm, for the soonest due plan; a past one goes off at once. Exact unless the system grants no exact alarms
     * (Android 12+ with the permission taken away); then within [INEXACT_WINDOW_MS], which a repeat's catch-up covers.
     */
    private fun schedule(app: Context, plans: List<Plan>) {
        val now = System.currentTimeMillis()
        val next = plans.mapNotNull { it.nextDueMillis(now, zone()) }.minOrNull()
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            app, 0, Intent(app, PlanAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (next == null) {
            alarms.cancel(pending)
            return
        }
        val at = maxOf(next, now + MIN_ALARM_DELAY_MS, retryAt)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) else alarms.setWindow(AlarmManager.RTC_WAKEUP, at, INEXACT_WINDOW_MS, pending)
        Log.i(TAG, "next alarm in ${(at - now) / MILLIS_PER_SECOND} s (exact=$exact)")
    }
}

/** The plans' alarm: checks what is due off the main thread. */
class PlanAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread({
            try {
                PlanRunner.check(context)
            } finally {
                pending.finish()
            }
        }, "plan-alarm").start()
    }
}

/** A reminder for a device still running a turn: that turn's agent passes it on. */
internal fun planReminderSteer(reply: String): String = "【计划提醒】用户之前安排的提醒现在到点了。在这一轮的回复里把它原样告诉用户：「$reply」"

/** What a plan coming due shows and says: [reply] spoken as written, or, with [modelText], a turn 小博 runs. */
internal data class PlanDelivery(val display: String, val reply: String, val modelText: String?)

/**
 * The words for [plan] coming due: the user line 「计划 · 拿快递」; a reminder says its message (「你之前让我 15:00 提醒你：…」
 * when late); a task tells the model what was planned and that nobody just spoke.
 */
internal fun planDelivery(plan: Plan, dueAt: Long, late: Boolean, reading: String?, now: Long, zone: ZoneId): PlanDelivery {
    val watch = plan.trigger.watch()
    val lead = when {
        late -> "你之前让我${Instant.ofEpochMilli(dueAt).atZone(zone).format(CLOCK_TEXT)}提醒你："
        // A number's words say what was reached unless the model already did (「续航低于50公里了，该充电了」): an old
        // plan's 「该充电了」 alone would not say why.
        watch != null && watch.op.numeric -> if (watch.text.take(2) in plan.message) "" else "${watch.text}了，"
        watch != null || plan.message.startsWith("提醒") -> ""
        else -> "提醒你："
    }
    val reply = if (plan.message.isNotBlank()) lead + plan.message else "到点了，我来办：${plan.instruction.orEmpty()}"
    val modelText = plan.instruction?.let { task ->
        val current = if (watch != null && reading != null) "（现在 ${watch.field} 是 $reading）" else ""
        "【计划到点】用户之前安排的计划「${plan.title}」（${plan.trigger.describe(now, zone)}）到了$current，现在去办：$task。" +
            "办完用一两句话告诉用户；用户此刻没有说话，不要追问。"
    }
    return PlanDelivery(display = "计划 · ${plan.title}", reply = reply, modelText = modelText)
}

private fun PlanTrigger.kind(): String = when (this) {
    is PlanTrigger.Once -> "once"
    is PlanTrigger.Repeat -> "repeat"
    is PlanTrigger.Watch -> "watch:${watch.server}"
}

private const val PLAN_FILE = "plans/plans.json"
/** An alarm for a time already past still waits this long, so a plan never fires inside the write that made it. */
private const val MIN_ALARM_DELAY_MS = 1_000L
/** After a check that could not save (a full disk), the next one waits this long. */
private const val CHECK_RETRY_MS = 60_000L
/** Android 12+ stretches a shorter window to this anyway. */
private const val INEXACT_WINDOW_MS = 10 * 60 * 1000L
private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_MINUTE = 60_000L
internal const val PLAN_LOG_TAG = "DebugPlans"
private const val TAG = PLAN_LOG_TAG
internal const val PLAN_SCENE = "plan"
internal const val PLAN_LABEL = "计划"
private val CLOCK_TEXT: DateTimeFormatter = DateTimeFormatter.ofPattern(" HH:mm ")
