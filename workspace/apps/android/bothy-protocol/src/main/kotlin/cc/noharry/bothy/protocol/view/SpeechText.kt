package cc.noharry.bothy.protocol.view

import cc.noharry.agent.appserver.protocol.view.PROGRESS_SENTENCE_ENDINGS

/**
 * The progress lines in this text as they are spoken: from each `TTS:` marker, anywhere in a line, to the line end, the
 * first sentence end or the next marker; an unfinished last one runs to the end. The card shows only these when
 * 显示思考过程 is off.
 */
fun String.spokenProgress(): List<String> = buildList {
    var start = 0
    while (true) {
        val marker = TTS_PROGRESS_MARKER.find(this@spokenProgress, start) ?: break
        val contentStart = marker.range.last + 1
        val end = progressMessageEnd(contentStart) ?: length
        substring(contentStart, end).trim().takeIf(String::isNotEmpty)?.let(::add)
        start = maxOf(end, contentStart)
    }
}

fun String.progressMessageEnd(start: Int): Int? {
    val lineEnd = indexOfAny(charArrayOf('\n', '\r'), startIndex = start).takeIf { it >= 0 }
    val sentenceEnd = indexOfFirstAfter(start) { it in PROGRESS_SENTENCE_ENDINGS }
        ?.plus(1)
    // Next TTS: marker ends the previous progress line even without punctuation.
    val nextMarker = TTS_PROGRESS_MARKER.find(this, startIndex = start)?.range?.first
        ?.takeIf { it > start }
    return listOfNotNull(lineEnd, sentenceEnd, nextMarker).minOrNull()
}

fun String.consumeProgressEnd(end: Int): Int {
    var index = end
    while (index < length && (this[index] == '\n' || this[index] == '\r')) index += 1
    return index
}

private fun String.indexOfFirstAfter(start: Int, predicate: (Char) -> Boolean): Int? {
    var index = start
    while (index < length) {
        if (predicate(this[index])) return index
        index += 1
    }
    return null
}

val TTS_PROGRESS_MARKER = Regex("""(?i)(?<![A-Za-z0-9_])TTS:""")
