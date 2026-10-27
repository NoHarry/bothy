package cc.noharry.bothy.app.trajectory
import cc.noharry.bothy.app.model.summary
import cc.noharry.bothy.app.runtime.append
import cc.noharry.bothy.app.usage.tokens
import java.util.Locale

/**
 * Pure search over the display-safe fields of the current snapshot.
 *
 * The index only sees what the ledger and inspector are already allowed to render: title,
 * summary, safe metadata and redacted rich previews. Hashes and hidden raw data are never
 * indexed, so a query can never surface something the UI would refuse to show.
 */
internal fun TrajectoryRecord.searchIndexText(): String =
    buildString {
        append(title)
        append(' ')
        append(summary)
        safeMetadata.forEach { (key, value) ->
            append(' ')
            append(key)
            append('=')
            append(value)
        }
        rich?.let { details ->
            listOfNotNull(
                details.inputPreview,
                details.payloadPreview,
                details.resultPreview,
                details.schemaPreview,
                details.requestText,
                details.thinkingText,
                details.outputText,
                details.usageText,
            ).forEach { preview ->
                append(' ')
                append(preview)
            }
        }
    }.lowercase(Locale.ROOT)

/** Whitespace-separated tokens with AND semantics; a blank query matches everything. */
internal fun trajectorySearchTokens(query: String): List<String> =
    query.lowercase(Locale.ROOT).split(WHITESPACE).filter { it.isNotBlank() }

internal fun TrajectoryRecord.matchesTrajectorySearch(tokens: List<String>): Boolean {
    if (tokens.isEmpty()) return true
    val indexed = searchIndexText()
    return tokens.all { indexed.contains(it) }
}

/**
 * Hit ids in ledger order. Non-matching rows keep their place in the list: search highlights
 * and lets the user step between hits, it never destructively filters the trajectory.
 */
internal fun trajectorySearchHits(
    groups: List<TrajectoryRunGroup>,
    query: String,
): List<TrajectoryRecordId> {
    val tokens = trajectorySearchTokens(query)
    if (tokens.isEmpty()) return emptyList()
    return groups.flatMap { group -> group.records }
        .filter { it.matchesTrajectorySearch(tokens) }
        .map { it.id }
}

/** Cyclic next/previous stepping so long trajectories stay navigable from either end. */
internal fun nextTrajectoryHit(
    hits: List<TrajectoryRecordId>,
    current: TrajectoryRecordId?,
    forward: Boolean = true,
): TrajectoryRecordId? {
    if (hits.isEmpty()) return null
    val index = current?.let(hits::indexOf) ?: -1
    if (index < 0) return if (forward) hits.first() else hits.last()
    val next = if (forward) index + 1 else index - 1
    return hits[(next + hits.size) % hits.size]
}

private val WHITESPACE = Regex("""\s+""")
