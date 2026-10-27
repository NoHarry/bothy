package cc.noharry.bothy.app.a2ui

import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.console.ui.icon
import cc.noharry.bothy.app.plans.valueText
import cc.noharry.bothy.app.runtime.ANSWERABLE_STATUSES
import cc.noharry.bothy.app.runtime.PreviousExchange
/**
 * Options the model offered with `offer_options`, each optionally bound to one checked tool call.
 *
 * The reply still shows its numbered list as today; the offer only remembers what each item does. When the next
 * user turn picks an item (a tap sends "第N个", or the user says it or the item's label), the host hands the model
 * the bound call instead of a bare ordinal, so it acts in one round without searching again. An offer answers only
 * the turn that made it: any later turn leaves it behind.
 */
internal data class Offer(
    val sessionId: String,
    /** The turn whose reply shows the list: its request ID, the same as [PreviousExchange.turnId]. */
    val turnId: String,
    val options: List<OfferOption>,
    val createdAtMillis: Long,
    /** The panel (`offer_slider`, `offer_switch`, `offer_choice`), in the order offered and confirmed together. */
    val controls: List<Control> = emptyList(),
    /**
     * Information shown as cards (`show_card`), such as the weather; they ask nothing. One per thing the reply covers
     * (owner 2026-10-18: 查2个城市的活动，但是显示只显示了广州的), in the order shown.
     */
    val cards: List<WidgetCard> = emptyList(),
) {
    /** Option label to icon name, for rows that show one. */
    val icons: Map<String, String> get() = options.mapNotNull { option -> option.icon?.let { option.label to it } }.toMap()
}

internal data class OfferOption(val label: String, val call: BoundCall?, val icon: String? = null)

/** A tool call the host checked against the tool's input schema when the offer was made. */
internal data class BoundCall(
    val providerId: String,
    val toolName: String,
    val qualifiedName: String,
    val argumentsJson: String,
)

/** A pick resolved against the offer: what was picked, the checked calls it carries and the framed model input. */
internal data class OfferPick(val ordinal: Int, val label: String, val calls: List<BoundCall>, val input: String)

/** Latest offer per session, and the pick a starting run carries; in-process and bounded. */
internal object OfferStore {
    private val offers = boundedMap<Offer>()
    private val picks = boundedMap<OfferPick>()

    /** Adds to this turn's offer (options, slider and cards may come from separate calls); a new turn starts over. */
    @Synchronized fun update(sessionId: String, turnId: String, nowMillis: Long, change: (Offer) -> Offer): Offer {
        val current = offers[sessionId]?.takeIf { it.turnId == turnId } ?: Offer(sessionId, turnId, emptyList(), nowMillis)
        return change(current).also { offers[sessionId] = it }
    }

    /** A new turn in the session: what the last reply showed is no longer on offer. */
    @Synchronized fun endTurn(sessionId: String) {
        offers.remove(sessionId)
    }

    @Synchronized fun latest(sessionId: String): Offer? = offers[sessionId]

    /** The session's latest offered labels, or the newest offer's when the viewer does not know the session. */
    @Synchronized fun offeredLabels(sessionId: String?): List<String> {
        val offer = if (sessionId != null) offers[sessionId] else offers.values.maxByOrNull { it.createdAtMillis }
        return offer?.options?.map { it.label }.orEmpty()
    }

    /** Remembered so the bound tool's provider can offer it from the first round of the run that acts. */
    @Synchronized fun startPick(sessionId: String, pick: OfferPick) {
        picks[sessionId] = pick
    }

    @Synchronized fun pickFor(sessionId: String, input: String): OfferPick? =
        picks[sessionId]?.takeIf { it.input == input }

    @Synchronized fun clear() {
        offers.clear()
        picks.clear()
    }

    private fun <T> boundedMap(): LinkedHashMap<String, T> = object : LinkedHashMap<String, T>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?): Boolean = size > MAX_SESSIONS
    }

    private const val MAX_SESSIONS = 32
}

/**
 * The pick [text] makes from [offer], or null when the text is not a pick, the offer is not the previous turn's,
 * or the picked item carries no call (the ordinary short-reply frame then handles it).
 */
internal fun offerPick(
    text: String,
    previous: PreviousExchange?,
    offer: Offer?,
    nowMillis: Long = System.currentTimeMillis(),
): OfferPick? {
    if (previous == null || offer == null || offer.turnId != previous.turnId) return null
    if (previous.status !in ANSWERABLE_STATUSES || nowMillis - offer.createdAtMillis > OFFER_TTL_MILLIS) return null
    val shown = previous.assistantText.displayChoiceReply(offer.options.map { it.label }).choices.map { it.label }
    pickedOption(text, shown, offer.options)?.let { (ordinal, option) ->
        val call = option.call ?: return null
        val said = "你上一条回复的选项里选了第${ordinal}项「${option.label}」"
        return OfferPick(ordinal, option.label, listOf(call), boundPickInput(said, text, listOf(call)))
    }
    val set = controlPicks(text, offer.controls) ?: return null
    val calls = set.flatMap { (control, value) -> control.callsAt(value) }.takeIf { it.isNotEmpty() } ?: return null
    val values = set.joinToString("、") { (control, value) -> control.valueText(value) }
    return OfferPick(0, set.joinToString("、") { it.first.label }, calls, boundPickInput("你上一条回复的控件上设了$values", text, calls))
}

/** By ordinal ("第2个", "第二个", "2") through the list as shown, else by the item's own label ("蓝色"). */
private fun pickedOption(text: String, shown: List<String>, options: List<OfferOption>): Pair<Int, OfferOption>? {
    val spoken = text.offerKey()
    if (spoken.isEmpty()) return null
    pickOrdinal(spoken)?.let { ordinal ->
        // The list the user saw decides what "第N个" means, in case the model listed it in another order.
        val option = if (shown.isEmpty()) options.getOrNull(ordinal - 1) else shown.getOrNull(ordinal - 1)?.let { matchOption(it, options) }
        return option?.let { ordinal to it }
    }
    // "1档" picks "1 档（最弱）": a bracketed note is shown, not said.
    val index = options.indexOfFirst { spoken == it.label.offerKey() || spoken == it.label.substringBefore('（').substringBefore('(').offerKey() }
    return if (index < 0) null else (index + 1) to options[index]
}

/** The option whose label the shown line starts with; the longest wins ("打开座椅加热" over "打开"). */
private fun matchOption(shownLine: String, options: List<OfferOption>): OfferOption? {
    val line = shownLine.offerKey()
    return options.filter { it.label.offerKey().let { label -> label.isNotEmpty() && line.startsWith(label) } }
        .maxByOrNull { it.label.length }
}

internal fun pickOrdinal(spoken: String): Int? {
    val match = PICK_ORDINAL.matchEntire(spoken) ?: return null
    val token = match.groupValues[1].ifEmpty { match.groupValues[2] }
    return (token.toIntOrNull() ?: CHINESE_ORDINALS.indexOf(token).takeIf { it >= 0 }?.plus(1))
        ?.takeIf { it in 1..MAX_OFFER_OPTIONS }
}

private fun boundPickInput(said: String, text: String, calls: List<BoundCall>): String =
    "用户在${said}（原话：${text.trim()}）。\n" +
        "绑定的操作已由宿主按工具定义核对：" + calls.joinToString("；") { "调用 ${it.qualifiedName}，参数 ${it.argumentsJson}" } + "。\n" +
        "直接按这些参数各调用一次，不要再搜索或追问；完成后用一句话告诉用户结果。"

/** Spaces, punctuation and polite particles dropped, so "第二个吧。" and "蓝色！" compare by their words. */
internal fun String.offerKey(): String =
    trim().replace(OFFER_KEY_NOISE, "").removeSuffix("吧").removeSuffix("啊").removeSuffix("呀")

/** "第2个", "选第二个", "就第三项", or a bare digit as a tap or keyboard sends it. */
private val PICK_ORDINAL = Regex("""^(?:选|要|就|用)?(?:第([1-8一二三四五六七八])(?:个|项|条|家|首|种)?|([1-8]))$""")
private val OFFER_KEY_NOISE = Regex("""[\s，,。.！!？?、~～“”"'：:；;]""")
private const val CHINESE_ORDINALS = "一二三四五六七八"
internal const val MAX_OFFER_OPTIONS = 8
private const val OFFER_TTL_MILLIS = 30 * 60 * 1000L
