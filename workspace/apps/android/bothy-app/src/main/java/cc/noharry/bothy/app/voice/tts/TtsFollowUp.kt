package cc.noharry.bothy.app.voice.tts
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.bothy.app.console.status

/** Only explicit user-input requests are questions; arbitrary failures/approvals are not. */
internal fun AgentEvent.followUpQuestion(): Pair<String, String>? = when (this) {
    is AgentEvent.RuntimeBlocked -> if (blocker.code == RuntimeErrorCode.WAITING_USER_INPUT.wireCode) {
        blocker.runId to blocker.message
    } else null
    is AgentEvent.ResultAvailable -> if (result.status == RuntimeRunStatus.WAITING_USER_INPUT) {
        result.runId to result.response
    } else null
    else -> null
}

/** Keep model-authored question sentences even when the spoken summary uses only sentence one. */
internal fun String.withFollowUpQuestions(source: String): String {
    val parts = mutableListOf(this)
    for (question in source.followUpQuestions()) {
        if (parts.none { it.contains(question) }) parts += question
    }
    return parts.withRecommendation(source).joinToString(" ").take(MAX_FOLLOW_UP_SPEECH_CHARS)
}

/** Options stay on screen; a spoken question keeps its own lines plus the recommended option. */
internal fun String.spokenQuestion(): String {
    val spoken = lines().filterNot { TTS_LIST_ITEM_PREFIX.containsMatchIn(it) }
        .map { it.replace(INLINE_OPTIONS, "") }
        .filter { it.isNotBlank() }
    if (spoken.isEmpty()) return this
    return spoken.withRecommendation(this).joinToString("\n")
}

/** The model marks its pick on screen ("2. 西广场（推荐，车位多）"); say it before the question. */
private fun List<String>.withRecommendation(source: String, at: Int = minOf(1, size)): List<String> {
    if (any { "推荐" in it }) return this
    val (name, reason) = source.recommendedOption() ?: return this
    // Already named, even by an alias ("新能汽车充电站(炭步大田充电站)" said as "炭步大田充电站"): no repeat.
    val aliases = name.split(NAME_ALIAS_SEPARATORS).map { it.trim() }.filter { it.length >= MIN_ALIAS_CHARS }
    if (aliases.any { alias -> any { alias in it } }) return this
    val recommendation = if (reason.isEmpty()) "我推荐$name。" else "我推荐$name，$reason。"
    return toMutableList().apply { add(at, recommendation) }
}

/** Spoken, "东广场（推荐，离进站口近）" reads as "东广场，离进站口近". */
internal fun String.withSpokenRecommendationNotes(): String =
    RECOMMENDATION_NOTE.replace(this) { note -> note.groupValues[1].trim().let { if (it.isEmpty()) "" else "，$it" } }

private fun String.recommendedOption(): Pair<String, String>? {
    val item = lines().firstOrNull { TTS_LIST_ITEM_PREFIX.containsMatchIn(it) && "推荐" in it }
        ?.replace(TTS_LIST_ITEM_PREFIX, "") ?: return null
    // "（推荐，最近）" or "（约1.5公里，推荐，最近）"; reason is what follows 推荐 inside the brackets.
    val note = RECOMMENDATION_NOTE.find(item) ?: INNER_RECOMMENDATION_NOTE.find(item)
    // The name only; details after a dash or colon ("——约740米，快充12个") stay on screen.
    val name = (if (note != null) item.removeRange(note.range) else item.replace(BARE_RECOMMENDATION, " "))
        .split(OPTION_DETAIL_SEPARATOR).first().trim().trimEnd('，', ',')
    if (name.isBlank()) return null
    return name to note?.groupValues?.get(1)?.trim().orEmpty()
}

/**
 * Partial/blocked: a reply that asks the user (a question or an options list) makes the pending part
 * explicit, so its lead is spoken with the question; one that asks nothing stays silent. A lead that
 * claims everything is done ("都已经完成") contradicts the outcome and is never spoken.
 */
internal fun String.partialReplyTts(lead: String?): String? {
    val questions = followUpQuestions()
    val asks = questions.isNotEmpty() || ASKING_PHRASE.containsMatchIn(this) ||
        lines().any { TTS_LIST_ITEM_PREFIX.containsMatchIn(it) }
    if (!asks) return null
    val keptLead = lead?.takeUnless { BLANKET_DONE.containsMatchIn(it) }
    val parts = listOfNotNull(keptLead) + questions.filter { keptLead?.contains(it) != true }
    // The pick goes right after the lead, or before the question when there is no lead.
    return parts.withRecommendation(this, at = if (keptLead != null) 1 else 0)
        .joinToString(" ").take(MAX_FOLLOW_UP_SPEECH_CHARS).takeIf { it.isNotBlank() }
}

/** A `.` followed by a non-space (decimal, version) stays inside the question; list bullets are dropped. */
private fun String.followUpQuestions(): List<String> =
    Regex("""(?:[^。！？.!?\r\n]|\.(?=\S))+[？?]""").findAll(this)
        .map { it.value.trim().replace(Regex("""^[-*•]\s+"""), "") }.distinct().toList()

/** Numbered or bulleted option line: "1. ", "2、", "（3）", "- ". A decimal like "12.5" is not a marker. */
internal val TTS_LIST_ITEM_PREFIX = Regex("""^(?:\d{1,2}[.、)）](?!\d)|[（(]\d{1,2}[）)]|[-*•])\s*""")
private val INLINE_OPTIONS = Regex("""\s*[（(]1[）)].*$""")
private val RECOMMENDATION_NOTE = Regex("""[（(]推荐[，,：:、\s]*([^）)]*)[）)]""")
private val INNER_RECOMMENDATION_NOTE = Regex("""[（(][^（）()]*?[，,、\s]推荐[，,：:、\s]*([^（）()]*)[）)]""")
private val BARE_RECOMMENDATION = Regex("""[，,、\s]*推荐[，,、\s]*""")
private val OPTION_DETAIL_SEPARATOR = Regex("""\s*(?:——|—|–|：|:|\s-\s)""")
private val NAME_ALIAS_SEPARATORS = Regex("""[（()）]""")
private const val MIN_ALIAS_CHARS = 4
/** Asking without a question mark: "你可以告诉我大概想在哪停""得你定一下". */
private val ASKING_PHRASE = Regex("""告诉我|跟我说|说一声|你定一下|得你定|选一个|挑一个|选哪个""")
private val BLANKET_DONE = Regex("""(?:都|全都|全部)(?:已经)?(?:完成|处理好了|办好了|搞定了|好了)""")
private const val MAX_FOLLOW_UP_SPEECH_CHARS = 600
