package cc.noharry.bothy.sample

internal fun List<DebugOverlayEntry>.overlayTranscriptSignature(): String =
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

internal fun List<DebugCompactEntry>.overlayCompactTranscriptSignature(): String =
    joinToString(separator = "|") { "${it.title}:${it.body.length}:${it.body.hashCode()}:${it.isError}" }

internal fun List<DebugOverlayBoardSection>.overlayBoardSignature(): String =
    joinToString(separator = "|") { section ->
        val rows = section.rows.joinToString(separator = "\u001f") { "${it.text}:${it.planStatus}" }
        "${section.title}:$rows"
    }
