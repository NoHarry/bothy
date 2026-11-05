package cc.noharry.agent.appserver.pages

import android.content.Context
import cc.noharry.agent.appserver.image.generatedImageName
import cc.noharry.agent.appserver.protocol.view.DRAWN_PAGE
import cc.noharry.agent.appserver.protocol.view.DRAWN_PAGES_DIR
import cc.noharry.agent.appserver.runtime.encodeSessionId
import java.io.File

/**
 * Pages the agent drew (`show_html`), as the pictures are kept: `filesDir/drawn-pages/<session>/`, so deleting a
 * session (or clearing runtime data) deletes its pages. A cache for the screen: a session keeps its newest
 * [MAX_KEPT_PAGES].
 */
internal fun saveDrawnPage(context: Context, sessionId: String, html: String, nowMillis: Long = System.currentTimeMillis()): String {
    val directory = sessionPagesDir(context, sessionId).apply { mkdirs() }
    val file = File(directory, "${generatedImageName(nowMillis, prefix = PAGE_NAME_PREFIX)}.html")
    file.writeText(html)
    directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_KEPT_PAGES)?.forEach { it.delete() }
    return "$DRAWN_PAGES_DIR/${directory.name}/${file.name}"
}

/** The page behind [reference], or null when it is gone or the reference points anywhere but a session's pages. */
internal fun readDrawnPage(context: Context, reference: String): String? {
    if (!DRAWN_PAGE.matches(reference)) return null
    val root = File(context.filesDir, DRAWN_PAGES_DIR).canonicalFile
    val file = File(context.filesDir, reference).canonicalFile
    return file.takeIf { it.parentFile?.parentFile == root && it.isFile }?.readText()
}

/** Deletes the pages of one session; true when nothing of it is left. */
internal fun deleteSessionDrawnPages(context: Context, sessionId: String): Boolean {
    val directory = sessionPagesDir(context, sessionId)
    return !directory.exists() || runCatching { directory.deleteRecursively() }.getOrDefault(false)
}

private fun sessionPagesDir(context: Context, sessionId: String): File =
    File(File(context.filesDir, DRAWN_PAGES_DIR), encodeSessionId(sessionId.ifBlank { UNKNOWN_SESSION }))

private const val PAGE_NAME_PREFIX = "page"
private const val UNKNOWN_SESSION = "unknown-session"
internal const val MAX_KEPT_PAGES = 30
