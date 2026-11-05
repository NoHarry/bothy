package cc.noharry.agent.appserver.protocol.view

/** The on-screen reply: marked progress lines were already spoken and are not part of it. */
fun String.displayReplyText(): String {
    val kept = lines().filterNot { it.isTtsMarkedLine() }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    return kept.ifBlank { lines().joinToString("\n") { it.replace(TTS_LINE_PREFIX, "") }.trim() }
}

fun String.isTtsMarkedLine(): Boolean = TTS_LINE_PREFIX.containsMatchIn(this)

val PROGRESS_SENTENCE_ENDINGS = setOf('。', '！', '？', '.', '!', '?')

val TTS_LINE_PREFIX = Regex("""^\s*TTS:\s*""", RegexOption.IGNORE_CASE)
