package cc.noharry.bothy.sample

import android.content.Context
import android.util.Log
import cc.noharry.bothy.a2a.A2aClient
import cc.noharry.bothy.a2a.A2aPart
import cc.noharry.bothy.a2a.A2aResult
import cc.noharry.bothy.a2a.A2aTask
import cc.noharry.bothy.a2a.A2aValue
import cc.noharry.bothy.a2a.ExperimentalA2aClientApi
import cc.noharry.bothy.a2a.RemoteAgentRef
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** A task handed to someone's own agent that answered "working": the device follows it until it settles. */
internal data class DebugA2aDelegation(
    val agentLabel: String,
    val taskId: String,
    val instruction: String,
    val startedAtEpochMillis: Long,
    /** The A2A profile it went through, so following can resume after the app restarts; empty when unknown. */
    val profileId: String = "",
)

/** How following ended: the settled task, or why the device stopped following without one. */
@OptIn(ExperimentalA2aClientApi::class)
internal data class DebugA2aFollowed(val task: A2aTask?, val problem: String? = null)

/** A question the user has not answered yet; the delegation tool's description carries it to the next turn. */
internal data class DebugA2aPendingQuestion(
    val agentLabel: String,
    val taskId: String,
    val question: String,
    val askedAtEpochMillis: Long,
)

/**
 * A settled delegation as one turn: [display] is what the transcript and overlay show, [model] what the agent reads
 * (what was asked, the card, the saved pictures, a question's task id).
 */
internal data class DebugA2aReport(val display: String, val model: String, val taskId: String = "")

/**
 * Where a followed delegation stands (owner 2026-10-12: 「被分配任务后后续结果来之前的可观测性不行」). The relay says
 * whether the bot has read the task, is reporting progress, or has not started 90 s after delivery.
 */
internal enum class DebugDelegationStage(val label: String, val settled: Boolean) {
    DELIVERED("已送达", false),
    STALLED("还没开始", false),
    STARTED("已开始", false),
    WORKING("处理中", false),
    /** Settled; its report waits to be spoken (the device is busy, or the user is reading a card). */
    REPORTING("有新结果", true),
    DONE("已完成", true),
    ASKING("等你回答", true),
    FAILED("没办成", true),
    CANCELLED("已取消", true),
    /** The device stopped following without an answer (gave up, or could not resume after a restart). */
    LOST("没跟上", true),
}

/** One step of the remote agent's plan; [status] is done, doing or todo. */
internal data class DebugDelegationStep(val title: String, val status: String)

/** A delegation as the console's 后台任务 and the overlay show it. */
internal data class DebugDelegationView(
    val profileId: String,
    val agentLabel: String,
    val taskId: String,
    val request: String,
    val startedAtEpochMillis: Long,
    val stage: DebugDelegationStage,
    val statusText: String = "",
    val steps: List<DebugDelegationStep> = emptyList(),
    val updatedAtEpochMillis: Long = startedAtEpochMillis,
    /** The report's short line once settled. */
    val outcome: String? = null,
)

internal interface DebugA2aDelegationSink {
    @OptIn(ExperimentalA2aClientApi::class)
    fun track(delegation: DebugA2aDelegation, client: A2aClient, remote: RemoteAgentRef)

    fun pendingQuestions(agentLabel: String): List<DebugA2aPendingQuestion> = emptyList()

    /** Delegations to [agentLabel] still being worked on, oldest first. */
    fun inFlight(agentLabel: String): List<DebugDelegationView> = emptyList()
}

/**
 * Follows delegated tasks in the background, outside any run. A settled one becomes a [DebugA2aReport] after its
 * pictures are saved, and the app turns it into a spoken turn (see [DebugRuntimeSession.deliverDelegationReport]).
 * Only polls: the user's agent cannot reach the device. [views] is what the console and overlay show; tasks still being
 * followed are kept in a no-backup file and followed again after the app restarts.
 */
internal object DebugA2aDelegations : DebugA2aDelegationSink {
    private class Host(val context: Context, val deliver: (DebugA2aReport) -> Unit)

    @OptIn(ExperimentalA2aClientApi::class)
    private class Follower(val client: A2aClient, val remote: RemoteAgentRef)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val followers = ConcurrentHashMap<String, Follower>()

    /** The first request of each task still open, so an answered question reports against what was asked. */
    private val requests = ConcurrentHashMap<String, String>()
    private val questions = ConcurrentHashMap<String, DebugA2aPendingQuestion>()
    private val mutableViews = MutableStateFlow<List<DebugDelegationView>>(emptyList())
    val views: StateFlow<List<DebugDelegationView>> = mutableViews

    @Volatile
    private var host: Host? = null

    fun install(context: Context, deliver: (DebugA2aReport) -> Unit) {
        host = Host(context.applicationContext, deliver)
        scope.launch { resume(context.applicationContext) }
    }

    override fun pendingQuestions(agentLabel: String): List<DebugA2aPendingQuestion> {
        val cutoff = System.currentTimeMillis() - QUESTION_TTL_MS
        questions.values.removeIf { it.askedAtEpochMillis < cutoff }
        return questions.values.filter { it.agentLabel == agentLabel }.sortedBy { it.askedAtEpochMillis }
    }

    override fun inFlight(agentLabel: String): List<DebugDelegationView> =
        views.value.filter { it.agentLabel == agentLabel && !it.stage.settled }.sortedBy { it.startedAtEpochMillis }

    @OptIn(ExperimentalA2aClientApi::class)
    override fun track(delegation: DebugA2aDelegation, client: A2aClient, remote: RemoteAgentRef) {
        questions.remove(delegation.taskId)
        val followed = delegation.copy(instruction = requests.getOrPut(delegation.taskId) { delegation.instruction })
        jobs.remove(followed.taskId)?.cancel()
        followers[followed.taskId] = Follower(client, remote)
        show(followed, DebugDelegationStage.DELIVERED)
        jobs[followed.taskId] = scope.launch { settle(followed, client, remote) }
    }

    /** Stops following and asks the remote agent's relay to stop waiting (Grok itself cannot be stopped). */
    @OptIn(ExperimentalA2aClientApi::class)
    fun cancel(taskId: String) {
        jobs.remove(taskId)?.cancel()
        val follower = followers.remove(taskId)
        requests.remove(taskId)
        update(taskId) { it.copy(stage = DebugDelegationStage.CANCELLED, outcome = "已停止跟踪", updatedAtEpochMillis = System.currentTimeMillis()) }
        if (follower != null) scope.launch { runCatching { follower.client.cancelTask(follower.remote, taskId) } }
    }

    /** The settled task's report was spoken as a turn: its stage becomes the outcome's. */
    fun markDelivered(taskId: String) {
        update(taskId) { view -> if (view.stage == DebugDelegationStage.REPORTING) view.copy(stage = reportedStage(view)) else view }
    }

    @OptIn(ExperimentalA2aClientApi::class)
    private suspend fun settle(delegation: DebugA2aDelegation, client: A2aClient, remote: RemoteAgentRef) {
        val followed = followDelegation(delegation, onPoll = { task -> polled(delegation.taskId, task) }) {
            client.getTask(remote, delegation.taskId)
        }
        jobs.remove(delegation.taskId)
        followers.remove(delegation.taskId)
        val parts = followed.task?.let(::reportParts)
        val pictures = host?.let { fetchReportPictures(it.context, remote, delegation.taskId, parts?.pictures.orEmpty()) }.orEmpty()
        val report = delegationReport(delegation, followed, pictures)
        if (followed.task?.status == A2aTask.Status.INPUT_REQUIRED) {
            questions[delegation.taskId] = DebugA2aPendingQuestion(
                delegation.agentLabel, delegation.taskId, parts?.text.orEmpty(), System.currentTimeMillis(),
            )
        } else {
            requests.remove(delegation.taskId)
        }
        val stage = if (followed.task == null) DebugDelegationStage.LOST else DebugDelegationStage.REPORTING
        val completed = followed.task?.status == A2aTask.Status.COMPLETED
        update(delegation.taskId) { view ->
            view.copy(
                stage = stage, outcome = report.display, statusText = parts?.text.orEmpty(), updatedAtEpochMillis = System.currentTimeMillis(),
                // The last progress report was before the final steps; a completed task finished them all.
                steps = if (completed) view.steps.map { it.copy(status = "done") } else view.steps,
            )
        }
        settledStatus[delegation.taskId] = followed.task?.status
        Log.i(TAG, "delegation ${delegation.taskId} settled: ${report.display.substringBefore('】')}】 pictures=${pictures.size}")
        host?.deliver?.invoke(report)
    }

    /** The status each settled task ended with, for the stage its view takes once its report is spoken. */
    @OptIn(ExperimentalA2aClientApi::class)
    private val settledStatus = ConcurrentHashMap<String, A2aTask.Status?>()

    @OptIn(ExperimentalA2aClientApi::class)
    private fun reportedStage(view: DebugDelegationView): DebugDelegationStage = when (settledStatus.remove(view.taskId)) {
        A2aTask.Status.COMPLETED -> DebugDelegationStage.DONE
        A2aTask.Status.INPUT_REQUIRED -> DebugDelegationStage.ASKING
        A2aTask.Status.CANCELLED -> DebugDelegationStage.CANCELLED
        else -> DebugDelegationStage.FAILED
    }

    @OptIn(ExperimentalA2aClientApi::class)
    private fun polled(taskId: String, task: A2aTask) {
        if (task.status in SETTLED) return
        val progress = relayProgress(task)
        update(taskId) { view ->
            view.copy(
                stage = progress.stage,
                statusText = task.statusMessage?.parts.orEmpty().text().ifBlank { view.statusText },
                steps = progress.steps.ifEmpty { view.steps },
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
        }
    }

    private fun show(delegation: DebugA2aDelegation, stage: DebugDelegationStage) {
        val view = DebugDelegationView(
            delegation.profileId, delegation.agentLabel, delegation.taskId, delegation.instruction.shortened(),
            delegation.startedAtEpochMillis, stage,
        )
        mutableViews.update { views -> (views.filterNot { it.taskId == delegation.taskId } + view).kept() }
        persist()
    }

    private fun update(taskId: String, change: (DebugDelegationView) -> DebugDelegationView) {
        mutableViews.update { views -> views.map { if (it.taskId == taskId) change(it) else it }.kept() }
        persist()
    }

    /** In-flight delegations, and settled ones from the last half hour, at most [MAX_VIEWS]. */
    private fun List<DebugDelegationView>.kept(): List<DebugDelegationView> {
        val cutoff = System.currentTimeMillis() - SETTLED_KEPT_MS
        return filter { !it.stage.settled || it.updatedAtEpochMillis >= cutoff }.sortedBy { it.startedAtEpochMillis }.takeLast(MAX_VIEWS)
    }

    /** Tasks still being followed, so following resumes after a restart; written on every change. */
    private fun persist() {
        val context = host?.context ?: return
        val open = mutableViews.value.filterNot { it.stage.settled }
        runCatching {
            val json = JsonArray(open.map { view ->
                JsonObject(mapOf(
                    "profileId" to JsonPrimitive(view.profileId), "agentLabel" to JsonPrimitive(view.agentLabel),
                    "taskId" to JsonPrimitive(view.taskId), "request" to JsonPrimitive(requests[view.taskId] ?: view.request),
                    "startedAt" to JsonPrimitive(view.startedAtEpochMillis),
                ))
            })
            File(context.noBackupFilesDir, PERSIST_FILE).writeText(json.toString())
        }.onFailure { Log.w(TAG, "delegations not saved: ${it.message}") }
    }

    /** Follows again what was being followed when the app stopped; a profile that is gone ends as [DebugDelegationStage.LOST]. */
    @OptIn(ExperimentalA2aClientApi::class)
    private fun resume(context: Context) {
        val saved = runCatching { File(context.noBackupFilesDir, PERSIST_FILE).takeIf { it.exists() }?.readText() }.getOrNull() ?: return
        val entries = runCatching { Json.parseToJsonElement(saved) as? JsonArray }.getOrNull().orEmpty()
        val config = runCatching { DebugA2aModuleConfig.load(context) }.getOrNull()
        for (entry in entries.mapNotNull { it as? JsonObject }) {
            val delegation = DebugA2aDelegation(
                agentLabel = entry.string("agentLabel"), taskId = entry.string("taskId"), instruction = entry.string("request"),
                startedAtEpochMillis = (entry["startedAt"] as? JsonPrimitive)?.longOrNull ?: 0L, profileId = entry.string("profileId"),
            )
            if (delegation.taskId.isEmpty() || jobs.containsKey(delegation.taskId)) continue
            val profile = config?.state?.profiles?.firstOrNull { it.id == delegation.profileId }
            val client = profile?.let { runCatching { config.createClient(it.id) }.getOrNull() }
            if (profile == null || client == null) {
                show(delegation, DebugDelegationStage.LOST)
                update(delegation.taskId) { it.copy(outcome = "App 重启后没能接着跟踪（${delegation.agentLabel} 的配置不在了）") }
                continue
            }
            Log.i(TAG, "delegation ${delegation.taskId} followed again after a restart")
            track(delegation, client, RemoteAgentRef(id = profile.id, endpoint = profile.endpoint))
        }
    }
}

/** Where the relay says the open round stands, and the bot's step list, from the status message's data part. */
internal data class DebugRelayProgress(val stage: DebugDelegationStage, val steps: List<DebugDelegationStep>)

@OptIn(ExperimentalA2aClientApi::class)
internal fun relayProgress(task: A2aTask): DebugRelayProgress {
    val relay = task.statusMessage?.parts.orEmpty().filterIsInstance<A2aPart.Data>()
        .firstNotNullOfOrNull { ((it.value as? A2aValue.ObjectValue)?.values?.get("relay")) as? A2aValue.ObjectValue }?.values.orEmpty()
    val stage = when ((relay["stage"] as? A2aValue.StringValue)?.value) {
        "stalled" -> DebugDelegationStage.STALLED
        "started" -> DebugDelegationStage.STARTED
        "working" -> DebugDelegationStage.WORKING
        else -> DebugDelegationStage.DELIVERED
    }
    val steps = (relay["steps"] as? A2aValue.ListValue)?.values.orEmpty().mapNotNull { step ->
        val fields = (step as? A2aValue.ObjectValue)?.values ?: return@mapNotNull null
        val title = (fields["title"] as? A2aValue.StringValue)?.value?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        DebugDelegationStep(title, (fields["status"] as? A2aValue.StringValue)?.value ?: "todo")
    }
    return DebugRelayProgress(stage, steps)
}

private fun JsonObject.string(name: String): String = (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()

/**
 * Polls [getTask] until the task completes, fails, is canceled or asks something. Gives up after [FOLLOW_LIMIT_MS] or
 * [MAX_POLL_FAILURES] failed polls in a row and says why; never guesses success.
 */
@OptIn(ExperimentalA2aClientApi::class)
internal suspend fun followDelegation(
    delegation: DebugA2aDelegation,
    now: () -> Long = System::currentTimeMillis,
    pause: suspend (Long) -> Unit = { delay(it) },
    /** Each answer while the task is still open, for its progress. */
    onPoll: (A2aTask) -> Unit = {},
    getTask: suspend () -> A2aResult<A2aTask>,
): DebugA2aFollowed {
    var failures = 0
    while (true) {
        val elapsed = now() - delegation.startedAtEpochMillis
        if (elapsed >= FOLLOW_LIMIT_MS) return DebugA2aFollowed(null, "过了 ${FOLLOW_LIMIT_MS / 60_000} 分钟还没有结果，这里已停止跟踪")
        pause(delegationPollMillis(elapsed))
        when (val result = getTask()) {
            is A2aResult.Success -> {
                failures = 0
                if (result.value.status in SETTLED) return DebugA2aFollowed(result.value)
                onPoll(result.value)
            }
            is A2aResult.Failure -> if (++failures >= MAX_POLL_FAILURES) {
                return DebugA2aFollowed(null, "连续 $MAX_POLL_FAILURES 次查不到进度（${result.error.reason}）")
            }
        }
    }
}

/** Every 3 s for the first minute, every 6 s up to five minutes, then every 15 s. */
internal fun delegationPollMillis(elapsedMillis: Long): Long = when {
    elapsedMillis < 60_000 -> 3_000
    elapsedMillis < 300_000 -> 6_000
    else -> 15_000
}

/** What a settled round carries: its spoken text, the card to show, other data, and relay paths of its pictures. */
internal data class DebugA2aReportParts(
    val text: String,
    val card: String?,
    val details: String?,
    val pictures: List<Pair<String, String>>,
)

/** The round's result artifact, else the status message; `data.card` and `data.images` are taken apart. */
@OptIn(ExperimentalA2aClientApi::class)
internal fun reportParts(task: A2aTask): DebugA2aReportParts {
    val artifact = task.artifacts.lastOrNull()?.parts.orEmpty()
    val text = artifact.text().ifBlank { task.statusMessage?.parts.orEmpty().text() }
    val data = artifact.filterIsInstance<A2aPart.Data>().firstOrNull()?.value as? A2aValue.ObjectValue
    val values = data?.values.orEmpty()
    val card = (values[DATA_CARD] as? A2aValue.ObjectValue)?.toJson()?.toString()?.clipped(MAX_CARD_CHARS)
    val details = JsonObject(values.filterKeys { it !in STRUCTURED_KEYS }.mapValues { it.value.toJson() })
        .takeIf { it.isNotEmpty() }?.toString()?.clipped(MAX_DATA_CHARS)
    val pictures = (values[DATA_IMAGES] as? A2aValue.ListValue)?.values.orEmpty().mapNotNull { image ->
        val fields = (image as? A2aValue.ObjectValue)?.values ?: return@mapNotNull null
        val path = (fields["path"] as? A2aValue.StringValue)?.value ?: return@mapNotNull null
        path to ((fields["caption"] as? A2aValue.StringValue)?.value.orEmpty())
    }
    return DebugA2aReportParts(text, card, details, pictures)
}

/** One settled delegation as a turn the model reads as a background report, never as a user request. */
@OptIn(ExperimentalA2aClientApi::class)
internal fun delegationReport(
    delegation: DebugA2aDelegation,
    followed: DebugA2aFollowed,
    pictures: List<DebugA2aReportPicture> = emptyList(),
): DebugA2aReport {
    val agent = delegation.agentLabel
    val asked = "「${delegation.instruction.shortened()}」"
    val parts = followed.task?.let(::reportParts)
    val said = parts?.text.orEmpty().ifBlank { "（没有附带说明）" }
    val problem = "处理${asked}时，${followed.problem ?: "状态不明"}。"
    val (mark, lead) = when (followed.task?.status) {
        A2aTask.Status.COMPLETED -> REPORT_MARK to "$agent 完成了$asked：$said"
        A2aTask.Status.INPUT_REQUIRED ->
            QUESTION_MARK to "$agent 关于${asked}问：$said（用户回答后，用 $agent 的 a2a_message_send 带 taskId=${delegation.taskId} 把回答发回去）"
        A2aTask.Status.CANCELLED -> FAILURE_MARK to "$agent 那边取消了$asked：$said"
        A2aTask.Status.FAILED -> FAILURE_MARK to "$agent 没能完成$asked：$said"
        else -> FAILURE_MARK to "$agent $problem"
    }
    val extras = listOfNotNull(
        parts?.card?.let { "卡片：$it" },
        pictures.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = "图片：") { picture ->
            picture.reference + picture.caption.takeIf(String::isNotEmpty)?.let { "（$it）" }.orEmpty()
        },
        parts?.details?.let { "详细：$it" },
    )
    val shown = if (followed.task == null) problem else said
    return DebugA2aReport(display = "$mark$agent：$shown", model = (listOf(mark + lead) + extras).joinToString("\n"), taskId = delegation.taskId)
}

/** A turn opened by a delegation report rather than by the user. */
internal fun String.isDelegationReport(): Boolean = startsWith(REPORT_MARK) || startsWith(QUESTION_MARK) || startsWith(FAILURE_MARK)

/** Who opened a turn, for its header: the user, or a delegated agent reporting back. */
internal fun debugTurnLabel(text: String): String = if (text.isDelegationReport()) DELEGATION_TURN_LABEL else USER_TURN_LABEL

private const val USER_TURN_LABEL = "You"
private const val DELEGATION_TURN_LABEL = "委托回报"

@OptIn(ExperimentalA2aClientApi::class)
private fun List<A2aPart>.text(): String = filterIsInstance<A2aPart.Text>().joinToString("\n") { it.text }.trim()

@OptIn(ExperimentalA2aClientApi::class)
private fun A2aValue.toJson(): JsonElement = when (this) {
    A2aValue.Null -> JsonNull
    is A2aValue.BooleanValue -> JsonPrimitive(value)
    is A2aValue.NumberValue -> if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
    is A2aValue.StringValue -> JsonPrimitive(value)
    is A2aValue.ListValue -> JsonArray(values.map { it.toJson() })
    is A2aValue.ObjectValue -> JsonObject(values.mapValues { it.value.toJson() })
}

private fun String.clipped(limit: Int): String = if (length > limit) take(limit) + "…" else this

private fun String.shortened(): String = trim().replace(Regex("\\s+"), " ").clipped(MAX_INSTRUCTION_CHARS)

@OptIn(ExperimentalA2aClientApi::class)
private val SETTLED = setOf(
    A2aTask.Status.COMPLETED, A2aTask.Status.FAILED, A2aTask.Status.CANCELLED, A2aTask.Status.INPUT_REQUIRED,
)

internal const val FOLLOW_LIMIT_MS = 30 * 60_000L
internal const val MAX_POLL_FAILURES = 5
internal const val REPORT_MARK = "【委托回报】"
internal const val QUESTION_MARK = "【委托追问】"
internal const val FAILURE_MARK = "【委托失败】"
private const val QUESTION_TTL_MS = 30 * 60_000L
private const val MAX_INSTRUCTION_CHARS = 40
private const val MAX_DATA_CHARS = 300
private const val MAX_CARD_CHARS = 1_200
private const val DATA_CARD = "card"
private const val DATA_IMAGES = "images"
private val STRUCTURED_KEYS = setOf(DATA_CARD, DATA_IMAGES, "images_failed")
private const val TAG = "DebugA2aDelegation"
private const val PERSIST_FILE = "a2a-delegations.json"
private const val SETTLED_KEPT_MS = 30 * 60_000L
private const val MAX_VIEWS = 12
