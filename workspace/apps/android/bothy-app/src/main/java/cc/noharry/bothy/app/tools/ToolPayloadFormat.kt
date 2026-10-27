package cc.noharry.bothy.app.tools

import cc.noharry.bothy.app.console.ui.prettyJsonString
/**
 * Shared pretty-print for tool Arguments/Result (fullscreen + overlay).
 * Pure-Kotlin so JVM unit tests and device share the same path.
 */
internal fun prettyToolPayload(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return raw
    if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return raw
    return runCatching { prettyJsonString(trimmed) }.getOrDefault(raw)
}
