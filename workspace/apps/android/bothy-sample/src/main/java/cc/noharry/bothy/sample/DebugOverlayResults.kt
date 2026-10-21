package cc.noharry.bothy.sample

/** What the card shows for the session's state, and its chips (see [OverlayResultMemory]). */
internal data class OverlayShownResult(
    val hud: DebugOverlayHud,
    val fresh: Boolean,
    val lastResult: String?,
    val previousResult: String?,
    val showingPrevious: Boolean,
)

/**
 * When the current result arrived, whether its card was folded (so it was seen), and when the user last touched
 * the card: what decides if a card opens fresh and how long delegation reports wait. Main thread only.
 */
internal class OverlayReading(private val clock: () -> Long = System::currentTimeMillis) {
    private var resultAt = 0L
    private var seen = false
    private var touchedAt = 0L

    /** A result is stale once seen, over [FRESH_AFTER_MS] old, or of unknown age (it came before the overlay ran). */
    val stale: Boolean get() = seen || resultAt == 0L || clock() - resultAt > FRESH_AFTER_MS

    /** The current result arrived while the overlay ran and its card has not folded since. */
    val unread: Boolean get() = !seen && resultAt != 0L

    fun arrived() {
        resultAt = clock()
        seen = false
    }

    fun seen() {
        seen = true
    }

    fun touched() {
        touchedAt = clock()
    }

    /**
     * Until when reports wait for [shown] to be read (epoch ms), or 0: its reading time since it arrived, or
     * [INTERACTION_HOLD_MS] since the last touch, while it is a result on the open card.
     */
    fun holdUntil(shown: OverlayShownResult, cardOpen: Boolean): Long {
        if (!cardOpen || shown.fresh || shown.hud.phase !in RESULT_PHASES) return 0L
        val reading = if (resultAt > 0L) resultAt + shown.hud.readingMillis() else 0L
        return maxOf(reading, touchedAt + INTERACTION_HOLD_MS).takeIf { it > clock() } ?: 0L
    }
}

/**
 * What the overlay card remembers about results (owner 2026-10-12): 「没看完页面就由于有别的话进来就关了」 and
 * 「唤醒或者点击小博的时候卡片会带着上次的结果」. A wake always opens the card fresh, and a tap on a stale result does, the
 * result behind a 上次 chip; a new request that replaces an unread result keeps it behind a 上一个结果 chip until the card
 * folds; while a result is read, [reading] holds delegation reports. Main thread only.
 */
internal class OverlayResultMemory(val reading: OverlayReading = OverlayReading()) {
    var fresh = false
        private set
    private val previous = OverlayPreviousChip()
    private val wake = OverlayWakeHold()

    /** A tap on 小博 opens the card for the user to talk while the session shows [phase]. */
    fun openToTalk(phase: DebugOverlayPhase?) {
        if (phase in RESULT_PHASES) fresh = reading.stale
    }

    /** Woken (你好，小博, a long press) while the session shows [phase]: the card opens fresh, see [OverlayWakeHold]. */
    fun woken(phase: DebugOverlayPhase?) {
        fresh = true
        previous.clear()
        wake.woken(phase, reading.unread)
    }

    fun showLast() {
        fresh = false
        wake.clear()
    }

    fun togglePrevious() = previous.toggle()

    /** The session's phase moved from [before] to [phase]: a result that arrives is new and unread. */
    fun phaseChanged(before: DebugOverlayPhase?, phase: DebugOverlayPhase) {
        if (phase in RESULT_PHASES && before != phase) reading.arrived()
        wake.phaseChanged(before, phase)
    }

    /** The card folded while showing [shownPhase]: a result on it has been seen; the chips go. */
    fun folded(shownPhase: DebugOverlayPhase?) {
        if (shownPhase in RESULT_PHASES && !fresh) reading.seen()
        fresh = false
        wake.clear()
        previous.clear()
    }

    /** The card for the session's [next] state, given what it showed [before] and whether it is open. */
    fun show(next: DebugOverlayHud, before: DebugOverlayHud?, cardOpen: Boolean): OverlayShownResult {
        val live = next.phase == DebugOverlayPhase.LIVE
        if (live && cardOpen && before?.phase in RESULT_PHASES && !fresh && !previous.showing && reading.unread) previous.result = before
        if (fresh && wake.freshEnds(next, previous)) fresh = false
        val back = previous.result?.takeIf { previous.showing }
        val hud = back ?: if (fresh) next.freshView() else next
        val last = next.takeIf { fresh && it.phase in RESULT_PHASES }?.resultLabel()
        return OverlayShownResult(hud, fresh, last, previous.result?.takeIf { live || back != null }?.resultLabel(), back != null)
    }
}

/** The 上一个结果 chip: an unread result a new request replaced, kept until the card folds; tapped, the card shows it. */
internal class OverlayPreviousChip {
    var result: DebugOverlayHud? = null
    var showing = false
        private set

    fun toggle() {
        showing = !showing
    }

    fun clear() {
        result = null
        showing = false
    }
}

/**
 * What a wake changes about the card besides opening it fresh (owner 2026-10-12: 「我唤醒后他还是显示上一次的结果」).
 * Woken over a running request, which the wake stops, the card stays fresh until that run has ended; woken over an
 * unread result, the result moves to the 上一个结果 chip once the new request starts.
 */
internal class OverlayWakeHold {
    var overRun = false
        private set
    private var keepUnread = false
    private var unread: DebugOverlayHud? = null

    fun woken(phase: DebugOverlayPhase?, resultUnread: Boolean) {
        overRun = phase == DebugOverlayPhase.LIVE
        keepUnread = phase in RESULT_PHASES && resultUnread
        unread = null
    }

    fun phaseChanged(before: DebugOverlayPhase?, phase: DebugOverlayPhase) {
        if (before == DebugOverlayPhase.LIVE && phase != DebugOverlayPhase.LIVE) overRun = false
    }

    /**
     * The fresh card stands over [next]: true when it gives way, as a new request starts (not the run the wake is
     * stopping), and an unread result kept meanwhile goes behind [previous].
     */
    fun freshEnds(next: DebugOverlayHud, previous: OverlayPreviousChip): Boolean {
        if (keepUnread && next.phase in RESULT_PHASES) unread = next
        if (next.phase != DebugOverlayPhase.LIVE || overRun) return false
        unread?.let { previous.result = it }
        clear()
        return true
    }

    fun clear() {
        overRun = false
        keepUnread = false
        unread = null
    }
}

/**
 * How long an open card waits before folding back into 小博, or null while it should stay: something is running,
 * or the user is asked, has picks to make, is typing, reading details, or looking at the picture large.
 */
internal fun DebugOverlayHud.quietCollapseMillis(ui: DebugOverlayUi): Long? {
    val act = mascotAction(ui)
    val waiting = act == MascotAction.ASKING || act == MascotAction.PICKING || ui.pendingQuestion
    val busy = ui.showInput || ui.showDetails || ui.pictureExpanded
    return when {
        phase == DebugOverlayPhase.LIVE || waiting || busy -> null
        phase == DebugOverlayPhase.IDLE -> IDLE_COLLAPSE_MS
        else -> readingMillis()
    }
}

/**
 * How long a finished result stays up to be read: 10 s plus about five characters a second of what it shows, at least
 * [QUIET_COLLAPSE_MS] and at most [MAX_READING_MS] (owner 2026-10-12: a news summary folded before it was read).
 */
internal fun DebugOverlayHud.readingMillis(): Long {
    val shown = resultText.length + cards.size * READING_CARD_CHARS + choices.sumOf { it.label.length }
    return (READING_BASE_MS + shown * READING_MS_PER_CHAR).coerceIn(QUIET_COLLAPSE_MS, MAX_READING_MS)
}

/** The card opened fresh to listen: nothing of the last result but its 上次 chip. */
internal fun DebugOverlayHud.freshView(): DebugOverlayHud = copy(
    phase = DebugOverlayPhase.IDLE, phaseLabel = "READY", headline = "Agent ready", requestPreview = null, resultText = "",
    currentStep = null, planRows = emptyList(), toolLines = emptyList(), isRunning = false, choices = emptyList(),
    choiceIcons = emptyMap(), cards = emptyList(), controls = emptyList(), picture = null,
)

/** A result in a few words for its chip: what was asked (a report without its 【委托回报】 mark), else the reply's first line. */
internal fun DebugOverlayHud.resultLabel(): String =
    (requestPreview?.takeIf(String::isNotBlank) ?: resultText.lineSequence().firstOrNull(String::isNotBlank) ?: headline)
        .trim().replace(REPORT_MARK_PATTERN, "").let { if (it.length > RESULT_LABEL_CHARS) it.take(RESULT_LABEL_CHARS) + "…" else it }

private val REPORT_MARK_PATTERN = Regex("^【[^】]*】")

/** A new request starting, or a reply arriving, is news worth opening the card for. */
internal fun overlayAnnounces(previous: DebugOverlayPhase, next: DebugOverlayPhase): Boolean =
    (next == DebugOverlayPhase.LIVE && previous != DebugOverlayPhase.LIVE) ||
        (previous == DebugOverlayPhase.LIVE && (next == DebugOverlayPhase.DONE || next == DebugOverlayPhase.FAIL))

internal val RESULT_PHASES = setOf(DebugOverlayPhase.DONE, DebugOverlayPhase.FAIL, DebugOverlayPhase.STOP)

/** A finished reply stays readable at least this long after the user last touched the card. */
internal const val QUIET_COLLAPSE_MS = 20_000L
/** A card with nothing to show folds back almost at once. */
internal const val IDLE_COLLAPSE_MS = 4_000L
internal const val MAX_READING_MS = 120_000L
private const val READING_BASE_MS = 10_000L
private const val READING_MS_PER_CHAR = 200L
/** A card (weather, a list) reads like this many characters. */
private const val READING_CARD_CHARS = 120
/** A touch on the card keeps reports waiting this long. */
internal const val INTERACTION_HOLD_MS = 15_000L
/** A result older than this, or already folded away, no longer opens with the card. */
internal const val FRESH_AFTER_MS = 60_000L
private const val RESULT_LABEL_CHARS = 24
