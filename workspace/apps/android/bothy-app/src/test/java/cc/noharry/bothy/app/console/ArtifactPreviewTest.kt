package cc.noharry.bothy.app.console
import cc.noharry.bothy.app.runtime.append
import cc.noharry.bothy.app.session.ConversationItem
import cc.noharry.bothy.app.session.ToolCallStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtifactPreviewTest {
    @Test
    fun findsLatestSuccessfulHtmlWriteArtifact() {
        val older = toolResult(1, "/scratch/old.html", content = "<html>old</html>")
        val latest = toolResult(2, "/scratch/latest.html", content = "<html>latest</html>", bytes = 42)

        val preview = latestHtmlArtifact(listOf(older, latest))

        requireNotNull(preview)
        assertEquals("/scratch/latest.html", preview.path)
        assertEquals("<html>latest</html>", preview.html)
        assertEquals(42L, preview.bytes)
    }

    @Test
    fun acceptsRawHookLineResultFormat() {
        val item = toolResult(1, "/scratch/page.html", bytes = 7).copy(
            resultJson = """
                tool=write_file
                ok=true
                result={"ok":true,"path":"/scratch/page.html","bytes":7,"mode":"create"}
            """.trimIndent(),
        )

        val preview = latestHtmlArtifact(listOf(item))

        requireNotNull(preview)
        assertEquals("/scratch/page.html", preview.path)
        assertEquals(7L, preview.bytes)
    }

    @Test
    fun samePathRewriteChangesRefreshToken() {
        val first = toolResult(1, "/scratch/live.html", content = "<html>v1</html>")
        val second = toolResult(2, "/scratch/live.html", content = "<html>v2</html>")

        val firstPreview = latestHtmlArtifact(listOf(first))
        val secondPreview = latestHtmlArtifact(listOf(first, second))

        requireNotNull(firstPreview)
        requireNotNull(secondPreview)
        assertEquals(firstPreview.path, secondPreview.path)
        assertNotEquals(firstPreview.refreshToken, secondPreview.refreshToken)
    }

    @Test
    fun ignoresNonHtmlAndFailedWrites() {
        val failed = toolResult(1, "/scratch/page.html", ok = false)
        val text = toolResult(2, "/scratch/page.txt")

        assertNull(latestHtmlArtifact(listOf(failed, text)))
    }

    @Test
    fun skipsWritesWithoutReadableContent() {
        val placeholderArgs = toolResult(1, "/scratch/page.html").copy(
            argumentsJson = "arguments=(payload hidden)",
        )
        val truncatedArgs = toolResult(2, "/scratch/page.html").copy(
            argumentsJson = "arguments={\"path\":\"/scratch/page.html\",\"content\":\"<html><body>unfinish",
        )
        val blankContent = toolResult(3, "/scratch/page.html", content = "")

        assertNull(latestHtmlArtifact(listOf(placeholderArgs, truncatedArgs, blankContent)))
    }

    @Test
    fun ignoresEditFileArtifacts() {
        val edit = toolResult(1, "/scratch/page.html").copy(toolName = "edit_file")

        assertNull(latestHtmlArtifact(listOf(edit)))
    }
}

private fun toolResult(
    id: Long,
    path: String,
    ok: Boolean = true,
    bytes: Long = 1L,
    content: String = "<html><body>hello</body></html>",
): ConversationItem.ToolCallItem =
    ConversationItem.ToolCallItem(
        id = id,
        toolName = "write_file",
        argumentsJson = """{"path":"$path","content":${content.jsonStringLiteral()},"mode":"create"}""",
        // Same plain-JSON shape the public tool-result event stores in the transcript.
        resultJson = """{"ok":$ok,"path":"$path","bytes":$bytes,"mode":"create"}""",
        status = if (ok) ToolCallStatus.DONE else ToolCallStatus.FAILED,
        startedAt = 0L,
        finishedAt = 1L,
    )

private fun String.jsonStringLiteral(): String =
    buildString {
        append('"')
        for (char in this@jsonStringLiteral) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }
