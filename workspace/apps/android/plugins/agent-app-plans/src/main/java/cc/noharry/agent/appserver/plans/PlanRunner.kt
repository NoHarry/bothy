package cc.noharry.agent.appserver.plans
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.Plan
import cc.noharry.agent.appserver.protocol.PlanDraft
import cc.noharry.agent.appserver.protocol.PlanEnd
import cc.noharry.agent.appserver.protocol.PlanFiring
import cc.noharry.agent.appserver.protocol.PlanTrigger
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.protocol.listing
import cc.noharry.agent.appserver.protocol.nextDueMillis
import cc.noharry.agent.appserver.runtime.modelText
import cc.noharry.agent.appserver.server.Delivery
import cc.noharry.agent.appserver.server.PluginContext
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
 * `files/plans/`, wakes the app at the next due time with an exact alarm ([PlanAlarmReceiver]; inexact where the system
 * grants no exact alarms), fires or gives up what came due, and has [PlanWatcher] read the device for condition plans.
 * A plan coming due chimes and shows in [PlanNotice] for five seconds, then is spoken as written, without the model (a
 * reminder), or becomes a turn the assistant runs (a task), handed to the conversation through the plugin
 * ([PluginContext.deliver]). Only while [PlansPlugin] runs ([start], [stop]). The log names plans by id only.
 */
internal object PlanRunner {
    private val mutablePlans = MutableStateFlow<List<Plan>>(emptyList())
    private val mutableHistory = MutableStateFlow<List<PlanFiring>>(emptyList())
    val plans: StateFlow<List<Plan>> = mutablePlans
    val history: StateFlow<List<PlanFiring>> = mutableHistory

    /** The clock's zone; tests pin it. */
    var zone: () -> ZoneId = ZoneId::systemDefault
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    /** After a check that could not save, the next alarm waits until then (under [lock]). */
    private var retryAt = 0L
    @Volatile private var book: PlanBook? = null

    /** The running plugin's context: where a plan coming due goes, the voice, the approval mode; null while it is off. */
    @Volatile private var plugin: PluginContext? = null

    /** Whether the plans plugin runs: only then does a plan come due, an alarm wait or a watch read. */
    val running: Boolean get() = plugin != null

    /** The plugin started: what came due while plans did not run is fired or given up, then the alarm and the watches. */
    fun start(ctx: PluginContext) {
        synchronized(lock) { plugin = ctx }
        init(ctx.app)
    }

    /**
     * The plugin stopped: the alarm and the watches are let go, under the lock, so a check still running cannot set
     * them again; nothing comes due until it starts again, which sets them from the book.
     */
    fun stop(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            plugin = null
            letGo(app)
        }
    }

    /** The app started, the plugin started or the clock changed, off the main thread: what is due, then the alarm. */
    fun init(context: Context) {
        val app = context.applicationContext
        scope.launch { check(app) }
    }

    /** No plans run: an alarm or a watch left from before is let go. */
    private fun letGo(app: Context) {
        schedule(app, emptyList())
        PlanWatcher.refresh(app, emptyList())
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

    /** Forgets the book read, so the next use reads the app's file again (a test's new sandbox). */
    internal fun forgetBook() {
        book = null
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

    /**
     * A plan made on the plans page; refused when its time cannot be one ([pageTriggerError]: checked before it is
     * kept, as one it could not schedule would fail every start after) or the book will not keep it. Any thread but
     * main.
     */
    fun create(context: Context, draft: PlanDraft): Result<Plan> {
        pageTriggerError(draft.trigger, System.currentTimeMillis())?.let { return Result.failure(IllegalArgumentException(it)) }
        return runCatching { write(context) { it.add(draft.title, draft.message, draft.instruction, draft.trigger, said = null) } }
            .getOrElse { Result.failure(it) }
            .onSuccess { Log.i(TAG, "made ${it.id} on the page") }
    }

    fun delete(context: Context, id: String) = logFailure("delete $id") {
        write(context) { it.remove(id) }
        Log.i(TAG, "deleted $id")
    }

    /** The alarm went off, the app started or the clock changed: fire or give up what is due, then set the next alarm. */
    fun check(context: Context) = logFailure("check") {
        val app = context.applicationContext
        if (!running) return@logFailure letGo(app)
        val target = book(app)
        synchronized(lock) {
            val now = System.currentTimeMillis()
            try {
                target.plans().forEach { plan ->
                    when (val due = plan.dueAt(now, zone())) {
                        is PlanDue.Fire -> fire(app, target, plan, due.dueAtMillis, due.late, reading = null)
                        is PlanDue.Miss -> miss(app, target, plan, due)
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
        if (!running) return@logFailure
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
        val ctx = plugin ?: return
        PlanNotice.show(planNoticeEntry(plan, delivery?.reply), ctx.voice, ctx.assistant)
        // Its own controls run without the model, in the approval mode of the moment; their outcome is a history line
        // of its own when they are done.
        if (plan.actions.isNotEmpty()) {
            PlanActionRunner.run(app, plan, ctx.approvalMode) { lines ->
                val outcome = listing(lines)
                PlanNotice.ran(plan.id, outcome, ctx.assistant)
                logFailure("record ${plan.id}") { write(app) { it.record(PlanFiring(plan.id, plan.title, now, outcome)) } }
            }
        }
        if (delivery == null) return
        // Delivered once marked fired, before the history line: a history that cannot be saved loses only its line.
        ctx.deliver(delivery.toConversation(app.getString(R.string.plans_label)))
        val lateMinutes = ((now - dueAt) / MILLIS_PER_MINUTE).toInt()
        val note = when {
            plan.instruction != null -> finding(FindingCode.PLANS_NOTE_HANDED)
            late -> Finding(FindingCode.PLANS_NOTE_LATE, listOf(lateMinutes.toString()), count = lateMinutes)
            else -> finding(FindingCode.PLANS_NOTE_REMINDED)
        }
        target.record(PlanFiring(plan.id, plan.title, now, note))
    }

    /** [FiringGuard] caught a plan firing in a loop (its action sets off its own trigger): it is switched off. */
    fun pauseRunaway(context: Context, planId: String) = logFailure("pause $planId") {
        write(context) { book ->
            val plan = book.update(planId) { it.copy(enabled = false) } ?: return@write
            val note = finding(FindingCode.PLANS_NOTE_RUNAWAY)
            book.record(PlanFiring(planId, plan.title, System.currentTimeMillis(), note))
        }
        Log.w(TAG, "paused runaway $planId")
    }

    private fun miss(app: Context, target: PlanBook, plan: Plan, due: PlanDue.Miss) {
        val dueAt = due.dueAtMillis
        target.update(plan.id) {
            if (it.trigger is PlanTrigger.Once) it.copy(end = PlanEnd.MISSED, lastDueAtMillis = dueAt) else it.copy(lastDueAtMillis = dueAt)
        }
        val note = finding(if (due.paused) FindingCode.PLANS_NOTE_PAUSED_DUE else FindingCode.PLANS_NOTE_MISSED)
        target.record(PlanFiring(plan.id, plan.title, dueAt, note))
        Log.i(TAG, "missed ${plan.id} (${plan.trigger.kind()})")
    }

    /** The page follows the book; the alarm and the watches only while the plugin runs. */
    private fun settle(app: Context, target: PlanBook) {
        publish(target)
        val running = if (running) target.plans() else emptyList()
        schedule(app, running)
        PlanWatcher.refresh(app, running)
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
internal fun planReminderSteer(reply: String): String = modelText(
    "【计划提醒】用户之前安排的提醒现在到点了。在这一轮的回复里把它原样告诉用户：「$reply」",
    "[Plan reminder] A reminder the user set is due now. In this turn's reply, tell the user exactly: \"$reply\"",
)

/**
 * What a plan coming due shows and says: [reply] spoken as written, or, with [modelText], a turn the assistant runs.
 */
internal data class PlanDelivery(val display: String, val reply: String, val modelText: String?) {
    /**
     * As the conversation takes it, named [label] on the card: a task opens the card the assistant works on and is a
     * turn; a reminder already showed in the plan popup and is only spoken, or passed on by a turn still running.
     */
    fun toConversation(label: String): Delivery = if (modelText != null) {
        Delivery(shown = display, title = label, forModel = modelText, showCard = true)
    } else {
        Delivery(shown = display, title = label, forModel = planReminderSteer(reply), said = reply)
    }
}

/**
 * The words for [plan] coming due: the user line 「计划 · 拿快递」; a reminder says its message (「你之前让我 15:00 提醒你：…」
 * when late); a task tells the model what was planned and that nobody just spoke.
 */
internal fun planDelivery(plan: Plan, dueAt: Long, late: Boolean, reading: String?, now: Long, zone: ZoneId): PlanDelivery {
    val watch = plan.trigger.watch()
    val lead = when {
        late -> Instant.ofEpochMilli(dueAt).atZone(zone).let { at ->
            modelText("你之前让我${at.format(CLOCK_TEXT)}提醒你：", "You asked me to remind you at ${at.format(CLOCK_PLAIN)}: ")
        }
        // A number's words say what was reached unless the model already did (「续航低于50公里了，该充电了」): an old
        // plan's 「该充电了」 alone would not say why.
        watch != null && watch.op.numeric -> if (plan.message.namesReading(watch.text)) "" else modelText("${watch.text}了，", "${watch.text}: ")
        watch != null || plan.message.startsWith("提醒") || plan.message.startsWith("remind", ignoreCase = true) -> ""
        else -> modelText("提醒你：", "Reminder: ")
    }
    val reply = if (plan.message.isNotBlank()) {
        lead + plan.message
    } else {
        modelText("到点了，我来办：${plan.instruction.orEmpty()}", "It is time; I am on it: ${plan.instruction.orEmpty()}")
    }
    val forModel = plan.instruction?.let { task ->
        val current = if (watch != null && reading != null) modelText("（现在 ${watch.field} 是 $reading）", " (${watch.field} is $reading now)") else ""
        val trigger = plan.trigger.describe(now, zone)
        modelText(
            "${PLAN_DUE_MARK_ZH}用户之前安排的计划「${plan.title}」（$trigger）到了$current，现在去办：$task。" +
                "办完用一两句话告诉用户；用户此刻没有说话，不要追问。",
            "$PLAN_DUE_MARK_EN The plan \"${plan.title}\" the user set ($trigger) is due$current. Do it now: $task. " +
                "Then tell the user in a sentence or two; the user did not just speak, so ask nothing.",
        )
    }
    return PlanDelivery(display = modelText("计划 · ", "Plan · ") + plan.title, reply = reply, modelText = forModel)
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
const val PLAN_LOG_TAG = "DebugPlans"
private const val TAG = PLAN_LOG_TAG
private val CLOCK_TEXT: DateTimeFormatter = DateTimeFormatter.ofPattern(" HH:mm ")
private val CLOCK_PLAIN: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** The mark a plan's own run starts with, which the action gate lets order what the plan was made to. */
private const val PLAN_DUE_MARK_ZH = "【计划到点】"
private const val PLAN_DUE_MARK_EN = "[Plan due]"

/** Whether [this] run input is a plan's own run coming due: it carries what the user approved when making the plan. */
fun String.isPlanRun(): Boolean = startsWith(PLAN_DUE_MARK_ZH) || startsWith(PLAN_DUE_MARK_EN)

/** Whether a reminder's message already says what its watch reached: 「续航低于50公里了」 names 「续航低于50公里」. */
private fun String.namesReading(watchText: String): Boolean =
    if (watchText.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) {
        watchText.take(2) in this
    } else {
        watchText.split(' ').firstOrNull { it.length > 2 }?.let { contains(it, ignoreCase = true) } == true
    }
