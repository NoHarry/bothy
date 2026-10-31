package cc.noharry.bothy.llm

import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The server's lifecycle against a stand-in `libllama_server.so`: a shell script that serves `/health` with Python's
 * http.server on the port it is given, and exits at once when asked for the NPU if `npu-fails` is beside it. With
 * `never-ready` beside it, it writes its pid to `pid` and never answers, like a model still loading.
 */
class LlamaServerTest {
    private lateinit var dir: File
    private val uncaught = mutableListOf<Throwable>()
    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("llama").toFile()
        File(dir, "www/health").apply { parentFile!!.mkdirs() }.writeText("""{"status":"ok"}""")
        File(dir, LlamaServer.EXECUTABLE).apply {
            writeText(
                """
                |#!/bin/sh
                |here=${'$'}(dirname "${'$'}0")
                |port=""; prev=""
                |for a in "${'$'}@"; do
                |  [ "${'$'}prev" = "--port" ] && port="${'$'}a"
                |  [ "${'$'}a" = "HTP0" ] && [ -f "${'$'}here/npu-fails" ] && { echo "ggml-hex: failed to open session"; exit 1; }
                |  prev="${'$'}a"
                |done
                |[ -f "${'$'}here/never-ready" ] && { echo ${'$'}${'$'} > "${'$'}here/pid"; exec sleep 60; }
                |echo "fake llama-server on ${'$'}port"
                |cd "${'$'}here/www" && exec python3 -m http.server "${'$'}port" --bind 127.0.0.1
                """.trimMargin(),
            )
            setExecutable(true)
        }
        Thread.setDefaultUncaughtExceptionHandler { _, e -> synchronized(uncaught) { uncaught += e } }
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        dir.deleteRecursively()
    }

    @Test
    fun startsOnceAndReusesTheRunningServer() = runBlocking {
        val server = LlamaServer(dir, readyTimeoutMillis = 20_000)
        val model = File(dir, "m.gguf").apply { writeText("x") }

        val first = server.ensureRunning(model, GEMMA_4_E4B)
        val second = server.ensureRunning(model, GEMMA_4_E4B)

        assertSame(first, second)
        assertEquals(LlamaDevice.NPU, first.device)
        assertTrue(first.baseUrl.startsWith("http://127.0.0.1:"))
        assertEquals(48, first.apiKey.length)
        server.stop()
    }

    @Test
    fun stoppingDoesNotThrowOnTheLogThread() = runBlocking {
        val server = LlamaServer(dir, readyTimeoutMillis = 20_000)
        server.ensureRunning(File(dir, "m.gguf").apply { writeText("x") }, GEMMA_4_E4B)

        server.stop()
        Thread.sleep(500)

        assertEquals(LlamaServer.State.Stopped, server.state.value)
        assertEquals(emptyList<Throwable>(), synchronized(uncaught) { uncaught.toList() })
    }

    @Test
    fun aStreamClosedUnderTheReadEndsTheLinesQuietly() {
        val lines = mutableListOf<String>()
        val text = "model loaded\nlistening\n".toByteArray()
        var position = 0
        // What Android does to the output of a destroyed process: the next read fails.
        val closedUnderRead = object : InputStream() {
            override fun read(): Int =
                if (position < text.size) text[position++].toInt() else throw InterruptedIOException("read interrupted by close() on another thread")
        }

        pumpLines(closedUnderRead) { lines += it }

        assertEquals(listOf("model loaded", "listening"), lines)
    }

    @Test
    fun aStartGivenUpWhileTheModelLoadsLeavesNoProcessAndNoStart() = runBlocking {
        File(dir, "never-ready").writeText("")
        val server = LlamaServer(dir, readyTimeoutMillis = 20_000)
        val start = launch(Dispatchers.Default) { server.ensureRunning(File(dir, "m.gguf").apply { writeText("x") }, GEMMA_4_E4B) }
        val pid = File(dir, "pid")
        withTimeout(10_000) { while (pid.readTextOrBlank().isBlank()) delay(50) }

        start.cancelAndJoin()

        assertEquals(LlamaServer.State.Stopped, server.state.value)
        withTimeout(5_000) { while (alive(pid.readText().trim())) delay(50) }
    }

    @Test
    fun fallsBackToTheCpuWhenTheNpuCannotStart() = runBlocking {
        File(dir, "npu-fails").writeText("")
        val lines = mutableListOf<String>()
        val server = LlamaServer(dir, log = { synchronized(lines) { lines += it } }, readyTimeoutMillis = 20_000)

        val ready = server.ensureRunning(File(dir, "m.gguf").apply { writeText("x") }, GEMMA_4_E4B)

        assertEquals(LlamaDevice.CPU, ready.device)
        assertTrue(synchronized(lines) { lines.any { "failed to open session" in it } })
        server.stop()
    }

    private fun File.readTextOrBlank(): String = if (isFile) readText() else ""

    private fun alive(pid: String): Boolean = ProcessBuilder("kill", "-0", pid).start().waitFor() == 0
}
