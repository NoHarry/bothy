package cc.noharry.bothy.voice

/**
 * What the assistant said lately, to tell its own voice coming back through the microphone from the speaker's
 * ([looksLikeEcho]). Echo cancellation leaves some residue, and the recognizer writes it out late: the words of one line
 * come while the next line plays, and those of a reply's last second come after its speech ended (the host says it
 * ended once the player has taken the last sample, before the room has heard it). So the last [kept] lines of a stretch
 * of speech count while it plays and for [tailMillis] after; a stretch that starts after that begins afresh. Words that
 * first came in the tail and do not look like what was said are the speaker's. Any thread.
 */
class OwnSpeech(
    private val tailMillis: Long = OWN_SPEECH_TAIL_MILLIS,
    private val kept: Int = OWN_SPEECH_LINES_KEPT,
) {
    private val lines = ArrayDeque<String>()
    private var speaking = false
    private var endedAt: Long? = null

    /** The assistant is saying [line] at [now]; blank while it says one not known yet. */
    @Synchronized
    fun saying(line: String, now: Long) {
        if (!speaking && !inTail(now)) lines.clear()
        speaking = true
        endedAt = null
        val text = line.trim()
        if (text.isEmpty() || lines.lastOrNull() == text) return
        lines.addLast(text)
        while (lines.size > kept) lines.removeFirst()
    }

    /** Its speech ended (or was cut off) at [now]: the tail starts. */
    @Synchronized
    fun ended(now: Long) {
        if (!speaking) return
        speaking = false
        endedAt = now
    }

    /** Whether [words], whose first came at [heardAt], are the assistant's own: heard while it spoke or in the tail, alike to its lines. */
    @Synchronized
    fun echoes(words: String, heardAt: Long): Boolean =
        (speaking || inTail(heardAt)) && lines.isNotEmpty() && looksLikeEcho(words, lines.joinToString("\n"))

    private fun inTail(at: Long): Boolean = endedAt?.let { at - it <= tailMillis } == true
}

/**
 * How long after the assistant's speech ended words that look like it are still its own: the recognizer writes words up
 * to 1.2 s after they were said (a turn's loudest frame is looked for that far back, VoiceSession's RECOGNIZER_LAG_MILLIS),
 * and the on-device one closes a segment, its final included, after 1.0 s of quiet (SherpaOnnxAsrEngine's rule2), so the
 * reply's last words open a turn within this long of its end.
 */
const val OWN_SPEECH_TAIL_MILLIS = 1_200L

/** A queued reply plays a few lines in a row (progress, the answer, a question); the last three cover a line's residue. */
const val OWN_SPEECH_LINES_KEPT = 3
