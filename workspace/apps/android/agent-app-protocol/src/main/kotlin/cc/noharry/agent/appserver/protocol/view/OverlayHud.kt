package cc.noharry.agent.appserver.protocol.view
import cc.noharry.agent.appserver.protocol.RunActivity
import cc.noharry.agent.appserver.protocol.RunOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.client.ServerState
import kotlinx.serialization.Serializable

/**
 * Demo-oriented floating panel projection.
 * Optimized for audience readability: clear phase, live elapsed, plan progress,
 * current tool, and final result — not for minimizing on-screen footprint.
 */
enum class OverlayPhase {
    LIVE,
    DONE,
    FAIL,
    STOP,
    IDLE,
}

data class OverlayToolLine(
    val id: Long,
    val name: String,
    val statusLabel: String,
    val durationLabel: String?,
    val isError: Boolean,
    val running: Boolean,
    val argumentsJson: String?,
    val resultJson: String?,
)

/** [reference] is null while the picture is still being drawn; [prompt] is what was asked for. */
@Serializable
data class OverlayPicture(val reference: String?, val prompt: String)

/** Card width follows what it must show, so content fits without scrolling. Widths are in the stage's dp. */
enum class OverlaySize(val landscapeWidthDp: Int, val screenShare: Float = MAP_KEEPING_SHARE) {
    /** Idle or a sentence or two. */
    COMPACT(560),
    /** Running, up to four picks, or a paragraph. */
    REGULAR(760),
    /** Four or more picks (two columns), a long answer, or open details. */
    WIDE(1080),
    /**
     * A trip: tickets, a column per day and the hotels (owner 2026-10-17: 旅行规划可以做一个更大的大卡). It takes most
     * of the screen; the plan is what the user reads then, not the map.
     */
    GRAND(1500, screenShare = 0.86f),
}

/** Every other size leaves the right ~40% of the map visible. */
private const val MAP_KEEPING_SHARE = 0.6f

/** Three columns of comparison, or a strip of five days or more, need the wide card to keep their text whole. */
private fun WidgetCard.isWide(): Boolean =
    (layout == CardLayout.COMPARE && items.size >= 3) || (layout == CardLayout.STRIP && items.size >= WIDE_STRIP_ITEMS)

private const val WIDE_STRIP_ITEMS = 5

/**
 * Two cards to a row on the wide card (深圳 and 广州 side by side); a trip, or a card that needs the wide card itself,
 * takes its own row.
 */
fun List<WidgetCard>.overlayCardRows(): List<List<WidgetCard>> =
    if (size > 1 && none { it.layout == CardLayout.TRIP || it.isWide() }) chunked(CARDS_PER_ROW) else map { listOf(it) }

const val CARDS_PER_ROW = 2

/** A finished run that left parts unconfirmed: 部分完成, not 没有完成. */
const val PARTLY_DONE_HEADLINE = "Partly done"

/** From this many picks the wide card lays them out in two columns (2×2 already saves a full tile row). */
const val OVERLAY_GRID_MIN_CHOICES = 4

/**
 * The latest image tool call of this turn: drawing while it runs, the saved picture once it succeeded, nothing
 * after a failure (the reply explains it). The reference is matched in the result text so a wrapped or escaped
 * result still yields it.
 */
fun List<ConversationItem>.overlayPicture(): OverlayPicture? =
    asReversed().firstNotNullOfOrNull { item -> (item as? ConversationItem.ToolCallItem)?.takeIf { it.isImageGenerateCall() } }
        ?.generatedPicture()

fun ConversationItem.ToolCallItem.isImageGenerateCall(): Boolean = shortToolName(toolName) == IMAGE_GENERATE_TOOL

/** The picture of one image tool call, for the overlay and the console alike; null for other tools. */
fun ConversationItem.ToolCallItem.generatedPicture(): OverlayPicture? {
    if (!isImageGenerateCall()) return null
    // Before the raw transcript arrives the console holds a placeholder, not JSON; the prompt is then unknown.
    // The raw transcript stores arguments as `arguments={...}`.
    val arguments = argumentsJson.orEmpty().let { it.substringAfter("arguments=", it) }.trim()
    val prompt = runCatching {
        Json.parseToJsonElement(arguments).jsonObject["prompt"]?.jsonPrimitive?.contentOrNull
    }.getOrNull().orEmpty()
    return when (status) {
        ToolCallStatus.RUNNING -> OverlayPicture(null, prompt)
        ToolCallStatus.DONE ->
            resultJson?.let { PICTURE_REFERENCE.find(it)?.groupValues?.get(1) }?.let { OverlayPicture(it, prompt) }
        ToolCallStatus.FAILED, ToolCallStatus.UNKNOWN -> null
    }
}

/** A whole file name with its extension: a redacted name (`img-[REDACTED_PHONE].jpg`) matches nothing. */
private val PICTURE_REFERENCE =
    Regex("""\\?"reference\\?"\s*:\s*\\?"($GENERATED_IMAGES_DIR/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+\.(?:jpg|png|webp))\\?"""")

/**
 * What the current turn's finished reply shows beyond its text: picks (with their icons), a panel, cards. [reply]
 * holds the text without the pick lines; it is the whole text when there are no picks.
 */
data class PickableReply(
    val messageId: Long,
    val reply: DisplayReply,
    val cards: List<WidgetCard> = emptyList(),
    val controls: List<Control> = emptyList(),
    val icons: Map<String, String> = emptyMap(),
)

/** The reply's widgets from this turn's offer; null when the reply shows nothing but text. */
fun appPickableReply(messageId: Long, text: String, offer: Offer?): PickableReply? {
    // Beside a trip card a numbered list is no pick (owner 2026-10-17: 下面又一个酒店选择，这种选择有什么意义): the plan
    // already holds the hotel, and a tap would only send 「第1个」 back while the reply asks something else. Beside any
    // other card the list is the card's items again (owner 2026-10-18: 还是出现了选一个这种控件: a hotel card, the same
    // hotels registered with offer_options without a call, and marked 推荐): a pick only when the reply asks which one
    // or a registered option does something when picked.
    val cards = offer?.cards.orEmpty()
    val options = offer?.options.orEmpty()
    val listsOnly = cards.any { it.layout == CardLayout.TRIP } && options.isEmpty()
    val labels = if (cards.isEmpty() || options.any { it.call != null }) options.map { it.label } else emptyList()
    val reply = if (listsOnly) DisplayReply(text, emptyList()) else text.displayChoiceReply(labels, markedPicks = cards.isEmpty())
    val widgets = PickableReply(messageId, reply, cards, offer?.controls.orEmpty(), offer?.icons.orEmpty())
    return widgets.takeIf { reply.choices.isNotEmpty() || it.cards.isNotEmpty() || it.controls.isNotEmpty() }
}

/**
 * The current turn's finished reply when it offers picks; the overlay card and the console show the same list.
 * A partial/blocked reply that asks for a pick shows FAIL yet is still answerable, so only a live or stopped run
 * closes it. Only this turn's reply counts: an older offer is never pending.
 */
fun ServerState.pickableReply(): PickableReply? =
    if (isRunning || overlayPhase() == OverlayPhase.STOP) {
        null
    } else {
        items.currentTurnItems().finishedReplyChoices(offers[activeSessionId])
    }

private fun List<ConversationItem>.finishedReplyChoices(offer: Offer?): PickableReply? {
    val reply = asReversed().firstOrNull { item ->
        item is ConversationItem.MessageItem &&
            item.role == TranscriptRole.ASSISTANT &&
            item.content.isNotBlank() &&
            !item.content.trimStart().startsWith("TTS:", ignoreCase = true) &&
            !item.content.contains("MODEL_FINISHED_WITHOUT_RUNTIME_FINISH")
    } as? ConversationItem.MessageItem ?: return null
    return appPickableReply(reply.id, reply.content.overlayHudMultiline(Int.MAX_VALUE).displayReplyText(), offer)
}

private fun ServerState.overlayPhase(): OverlayPhase =
    when {
        isRunning -> OverlayPhase.LIVE
        // How the last run ended wins over what the run said last (an intermediate event may have said it failed).
        lastRun != null -> when (lastRun.outcome) {
            RunOutcome.FAILED, RunOutcome.REPLAY_FAILED -> OverlayPhase.FAIL
            RunOutcome.STOPPED -> OverlayPhase.STOP
            RunOutcome.COMPLETED, RunOutcome.PARTIAL -> OverlayPhase.DONE
        }
        activity == RunActivity.STOPPED -> OverlayPhase.STOP
        activity == RunActivity.FAILED -> OverlayPhase.FAIL
        else -> OverlayPhase.IDLE
    }

/** Items belonging to the latest user turn (after the last TurnHeader). */
fun List<ConversationItem>.currentTurnItems(): List<ConversationItem> {
    val turnStart = indexOfLast { it is ConversationItem.TurnHeader }
    return if (turnStart >= 0) drop(turnStart + 1) else this
}

private fun shortToolName(name: String): String {
    val trimmed = name.substringAfterLast('.').substringAfterLast("__")
    return trimmed.ifBlank { name }.overlayHudLine(HUD_TOOL_NAME_MAX)
}

/** A plan step the person has finished. Failed and cancelled steps still count as unfinished. */
fun String.isCompletedPlanStatus(): Boolean = lowercase() in COMPLETED_PLAN_STATUS

private val COMPLETED_PLAN_STATUS = setOf("completed", "done", "success")

fun String.toOverlayPlanStatus(): OverlayPlanStatus =
    when {
        isOverlayPlanActive() -> OverlayPlanStatus.ACTIVE
        isCompletedPlanStatus() -> OverlayPlanStatus.DONE
        lowercase().contains("fail") -> OverlayPlanStatus.FAILED
        else -> OverlayPlanStatus.WAITING
    }

private fun String.isOverlayPlanActive(): Boolean =
    lowercase() in setOf("in_progress", "running", "active", "doing")

fun String.overlayHudLine(maxChars: Int): String {
    val single = lineSequence().joinToString(" ").trim().replace(Regex("""\s+"""), " ")
    return if (single.length <= maxChars) single else single.take(maxChars - 1) + "…"
}

fun String.overlayHudMultiline(maxChars: Int): String {
    // Models often emit literal "\n" / markdown; normalize for audience reading.
    val text = trim()
        .replace("\\r\\n", "\n")
        .replace("\\\\n", "\n")
        .replace("\\n", "\n")
        .replace("\\t", " ")
        .replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
        // The voice skill names its parts 说/看; a model sometimes writes the label itself on its own line.
        .lines().filterNot { it.trim() in SKILL_SECTION_LABELS }.joinToString("\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
    if (text.length <= maxChars) return text
    return text.take(maxChars - 1) + "…"
}

private val SKILL_SECTION_LABELS = setOf("说", "看", "说：", "看：", "说:", "看:")

// Anywhere in a line, as the announcer finds it: streamed rounds run together ("…temperature.TTS: 先查…").
val TTS_PROGRESS_PREFIX = Regex("""(?i)(?<![A-Za-z0-9_])TTS:\s*""")

/** The step while 显示思考过程 is off; the card shows it in its own language ([stepPhrase]). */
const val HIDDEN_THINKING_STEP = "正在处理"

/** The idle card's text in the projection; the card shows it in its own language ([overlayShownText]). */
const val WAITING_FOR_RUN_TEXT = "Waiting for a run — send a prompt to start the demo"

const val HUD_MAX_PLAN_ROWS = 8

const val HUD_MAX_TOOL_LINES = 10

private const val HUD_TOOL_NAME_MAX = 40
