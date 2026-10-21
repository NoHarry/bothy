package cc.noharry.bothy.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DebugArtifactStoreTest {
    @Test
    fun persistsHtmlWritesIntoSessionFolder() {
        val root = tempWorkspace()
        val tools = listOf(
            writeTool(1, "/scratch/hello.html", "<html>hello</html>"),
            writeTool(2, "/scratch/site/index.html", "<html>site</html>"),
        )

        val written = DebugArtifactStore.persistHtmlArtifacts(root, "session-a", tools)

        assertEquals(2, written)
        val dir = DebugArtifactStore.sessionArtifactsDir(root, "session-a")
        assertEquals("<html>hello</html>", File(dir, "scratch/hello.html").readText())
        assertEquals("<html>site</html>", File(dir, "scratch/site/index.html").readText())
    }

    @Test
    fun skipsNonHtmlFailedAndNonWriteTools() {
        val root = tempWorkspace()
        val tools = listOf(
            writeTool(1, "/scratch/notes.txt", "plain text"),
            writeTool(2, "/scratch/failed.html", "<html>x</html>", status = "FAILED"),
            writeTool(3, "/scratch/page.html", "<html>x</html>").copy(toolName = "edit_file"),
            writeTool(4, "/scratch/result-failed.html", "<html>x</html>", resultOk = false),
        )

        val written = DebugArtifactStore.persistHtmlArtifacts(root, "session-b", tools)

        assertEquals(0, written)
        assertFalse(DebugArtifactStore.sessionArtifactsDir(root, "session-b").exists())
    }

    @Test
    fun rewriteOverwritesSamePath() {
        val root = tempWorkspace()
        DebugArtifactStore.persistHtmlArtifacts(
            root, "session-c",
            listOf(writeTool(1, "/scratch/live.html", "<html>v1</html>")),
        )

        DebugArtifactStore.persistHtmlArtifacts(
            root, "session-c",
            listOf(writeTool(2, "/scratch/live.html", "<html>v2</html>")),
        )

        val file = File(DebugArtifactStore.sessionArtifactsDir(root, "session-c"), "scratch/live.html")
        assertEquals("<html>v2</html>", file.readText())
    }

    @Test
    fun rejectsEscapingPaths() {
        val root = tempWorkspace()
        val written = DebugArtifactStore.persistHtmlArtifacts(
            root, "session-d",
            listOf(writeTool(1, "/../outside.html", "<html>x</html>")),
        )

        assertEquals(0, written)
        assertFalse(File(root, "outside.html").exists())
    }

    @Test
    fun deleteSessionArtifactsRemovesOnlyThatSession() {
        val root = tempWorkspace()
        DebugArtifactStore.persistHtmlArtifacts(root, "keep", listOf(writeTool(1, "/scratch/a.html", "<html>a</html>")))
        DebugArtifactStore.persistHtmlArtifacts(root, "drop", listOf(writeTool(2, "/scratch/b.html", "<html>b</html>")))

        assertTrue(DebugArtifactStore.deleteSessionArtifacts(root, "drop"))

        assertFalse(DebugArtifactStore.sessionArtifactsDir(root, "drop").exists())
        assertTrue(DebugArtifactStore.sessionArtifactsDir(root, "keep").exists())
    }

    @Test
    fun redactedWritePayloadStillPersistsSafeHtmlArtifact() {
        val root = tempWorkspace()
        val rawHtml = """<html><body>hello driver@example.com password=hunter2</body></html>"""
        val raw = writeTool(1, "/scratch/safe.html", rawHtml).copy(
            resultPreview = """{"ok":true,"path":"/scratch/safe.html","secret":"home-address"}""",
        )
        val safe = raw.copy(
            argumentsPreview = redactDebugToolPayload(raw.argumentsPreview),
            resultPreview = redactDebugToolPayload(raw.resultPreview),
        )

        assertEquals(1, DebugArtifactStore.persistHtmlArtifacts(root, "session-safe", listOf(safe)))

        val persisted = File(
            DebugArtifactStore.sessionArtifactsDir(root, "session-safe"),
            "scratch/safe.html",
        ).readText()
        assertTrue(persisted.contains("hello"))
        assertFalse(persisted.contains("driver@example.com"))
        assertFalse(persisted.contains("hunter2"))
        assertFalse(safe.resultPreview.contains("home-address"))
    }
}

private fun writeTool(
    sequence: Int,
    path: String,
    content: String,
    status: String = "OK",
    resultOk: Boolean = true,
): DebugSessionToolCall =
    DebugSessionToolCall(
        sequence = sequence,
        toolName = "write_file",
        argumentsPreview = """{"path":"$path","content":${content.jsonLiteral()},"mode":"create"}""",
        resultPreview = """{"ok":$resultOk,"path":"$path"}""",
        status = status,
    )

private fun String.jsonLiteral(): String =
    buildString {
        append('"')
        for (char in this@jsonLiteral) {
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

private fun tempWorkspace(): File =
    Files.createTempDirectory("artifact-store-").toFile().canonicalFile
