package cc.noharry.bothy.app.overlay

import cc.noharry.bothy.app.runtime.append
import cc.noharry.bothy.app.session.CompactEntry
internal fun List<OverlayEntry>.overlayTranscriptSignature(): String =
    joinToString(separator = "|") { entry ->
        buildString {
            append(entry.id)
            append(':')
            append(entry.title)
            append(':')
            append(entry.body.hashCode())
            append(':')
            append(entry.isError)
            append(':')
            append(entry.isTool)
            append(':')
            append(entry.toolStatus)
            append(':')
            append(entry.running)
            append(':')
            append(entry.argumentsJson?.hashCode() ?: 0)
            append(':')
            append(entry.resultJson?.hashCode() ?: 0)
        }
    }

internal fun List<CompactEntry>.overlayCompactTranscriptSignature(): String =
    joinToString(separator = "|") { "${it.title}:${it.body.length}:${it.body.hashCode()}:${it.isError}" }

internal fun List<OverlayBoardSection>.overlayBoardSignature(): String =
    joinToString(separator = "|") { section ->
        val rows = section.rows.joinToString(separator = "\u001f") { "${it.text}:${it.planStatus}" }
        "${section.title}:$rows"
    }
