package cc.noharry.agent.appserver.runtime

/**
 * This reply as screens, the transcript and other agents get it: without the lines the host's voice spoke as progress
 * ([RunAddOns.progressWords]), or only their words when it has nothing else.
 */
internal fun String.shownReply(): String {
    val kept = lines().filterNot { it.isProgressLine() }.joinToString("\n").replace(BLANK_LINES, "\n\n").trim()
    return kept.ifBlank { lines().joinToString("\n") { AppHost.runs.progressWords(it) ?: it }.trim() }
}

/** Whether [this] line of a reply is progress the host's voice speaks ([RunAddOns.progressWords]). */
internal fun String.isProgressLine(): Boolean = AppHost.runs.progressWords(this) != null

private val BLANK_LINES = Regex("\n{3,}")
