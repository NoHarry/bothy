package cc.noharry.bothy.llm

import android.os.Build
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where llama-server runs the model: the Hexagon NPU first, the CPU when the NPU cannot be opened. */
enum class LlamaDevice(val label: String) {
    NPU("NPU"),
    CPU("CPU"),
}

/**
 * llama-server's command line: [model] on [device], one slot of [LocalModelSpec.contextTokens], OpenAI-compatible
 * tool calls from the model's own chat template (`--jinja`), listening only on 127.0.0.1:[port] and only for [apiKey].
 */
fun llamaServerArgs(
    executable: String,
    model: String,
    spec: LocalModelSpec,
    device: LlamaDevice,
    port: Int,
    apiKey: String,
    threads: Int = DEFAULT_THREADS,
): List<String> =
    listOf(
        executable, "-m", model, "--host", "127.0.0.1", "--port", "$port", "--api-key", apiKey,
        "-c", "${spec.contextTokens}", "-np", "1", "-t", "$threads", "--jinja", "--no-webui",
    ) + when (device) {
        LlamaDevice.NPU -> listOf("-ngl", "99", "--device", "HTP0")
        LlamaDevice.CPU -> listOf("-ngl", "0", "--device", "none")
    } + spec.serverArgs

/**
 * Runs llama.cpp's llama-server, packaged as `libllama_server.so` beside its libraries in [nativeLibraryDir], as a child
 * process of the app. It finds the NPU's FastRPC and the GPU's OpenCL libraries in /vendor/lib64 and the NPU-side
 * kernels (`libggml-htp-v*.so`) through `ADSP_LIBRARY_PATH`. The process dies with the app's; one that exits on its
 * own leaves [State.Failed], and the next [ensureRunning] starts it again. [log] gets the server's output lines.
 */
class LlamaServer(
    private val nativeLibraryDir: File,
    private val log: (String) -> Unit = {},
    private val readyTimeoutMillis: Long = READY_TIMEOUT_MS,
) {
    sealed interface State {
        data object Stopped : State
        data class Starting(val device: LlamaDevice, val modelId: String = "") : State
        data class Ready(val baseUrl: String, val apiKey: String, val device: LlamaDevice, val modelId: String = "") : State
        data class Failed(val reason: String) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val mutex = Mutex()
    private var process: Process? = null
    private var servedModel: File? = null
    private val recentLines = ArrayDeque<String>()

    /** Starts the server for [model] unless it already serves it, and returns once it answers `/health`. */
    suspend fun ensureRunning(model: File, spec: LocalModelSpec): State.Ready = mutex.withLock {
        val current = mutableState.value
        if (current is State.Ready && process?.alive() == true && servedModel == model) return current
        withContext(Dispatchers.IO) { stopLocked() }
        val executable = File(nativeLibraryDir, EXECUTABLE)
        check(executable.isFile) { "本机模型只支持 arm64 设备（没有找到 $EXECUTABLE）" }
        var reason = ""
        for (device in LlamaDevice.entries) {
            mutableState.value = State.Starting(device, spec.id)
            val ready = withContext(Dispatchers.IO) { startOn(executable, model, spec, device) }
            if (ready != null) {
                servedModel = model
                mutableState.value = ready
                return ready
            }
            reason = failureReason()
            log("llama-server on ${device.label} failed: $reason")
        }
        mutableState.value = State.Failed(reason)
        throw IllegalStateException("本机模型启动失败：$reason")
    }

    /** Stops the server; the model's memory is freed with the process. */
    suspend fun stop() = mutex.withLock {
        withContext(Dispatchers.IO) { stopLocked() }
        mutableState.value = State.Stopped
    }

    private suspend fun startOn(executable: File, model: File, spec: LocalModelSpec, device: LlamaDevice): State.Ready? {
        val port = freeLoopbackPort()
        val apiKey = randomKey()
        val args = llamaServerArgs(executable.absolutePath, model.absolutePath, spec, device, port, apiKey)
        val started = ProcessBuilder(args).redirectErrorStream(true).apply {
            environment()["LD_LIBRARY_PATH"] = "${nativeLibraryDir.absolutePath}:/vendor/lib64"
            environment()["ADSP_LIBRARY_PATH"] = nativeLibraryDir.absolutePath
        }.start()
        synchronized(recentLines) { recentLines.clear() }
        process = started
        readOutput(started)
        val deadline = System.currentTimeMillis() + readyTimeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (!started.alive()) return null
            if (healthy(port)) {
                watchExit(started)
                return State.Ready("http://127.0.0.1:$port/v1", apiKey, device, spec.id)
            }
            delay(HEALTH_POLL_MS)
        }
        synchronized(recentLines) { recentLines.addLast("not ready after ${readyTimeoutMillis / 1000} s") }
        started.kill()
        return null
    }

    private fun readOutput(started: Process) {
        Thread({
            pumpLines(started.inputStream) { line ->
                log(line)
                synchronized(recentLines) {
                    recentLines.addLast(line)
                    while (recentLines.size > RECENT_LINES) recentLines.removeFirst()
                }
            }
        }, "llama-server-log").apply { isDaemon = true }.start()
    }

    /** A server that exits on its own (killed for memory, crashed) leaves [State.Failed] for the next start. */
    private fun watchExit(started: Process) {
        Thread({
            val code = runCatching { started.waitFor() }.getOrNull()
            if (process === started) {
                process = null
                mutableState.value = State.Failed("llama-server exited ($code): ${failureReason()}")
            }
        }, "llama-server-exit").apply { isDaemon = true }.start()
    }

    private fun stopLocked() {
        val running = process ?: return
        process = null
        servedModel = null
        running.destroy()
        if (!running.waitForExit(STOP_GRACE_MS)) running.kill()
    }

    /** The server's last error-looking output line, or its last line. */
    private fun failureReason(): String = synchronized(recentLines) {
        recentLines.lastOrNull { line -> ERROR_HINTS.any { line.contains(it, ignoreCase = true) } }
            ?: recentLines.lastOrNull()
            ?: "no output"
    }.take(MAX_REASON)

    private fun healthy(port: Int): Boolean = runCatching {
        val connection = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
        connection.connectTimeout = HEALTH_TIMEOUT_MS
        connection.readTimeout = HEALTH_TIMEOUT_MS
        try {
            connection.responseCode == HttpURLConnection.HTTP_OK
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    companion object {
        const val EXECUTABLE = "libllama_server.so"
        private const val READY_TIMEOUT_MS = 180_000L
        private const val HEALTH_POLL_MS = 300L
        private const val HEALTH_TIMEOUT_MS = 1_000
        private const val STOP_GRACE_MS = 2_000L
        private const val RECENT_LINES = 60
        private const val MAX_REASON = 300
        private val ERROR_HINTS = listOf("error", "failed", "exiting", "abort", "not found")
    }
}

private const val DEFAULT_THREADS = 6

private fun freeLoopbackPort(): Int =
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

private fun randomKey(): String =
    ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

/**
 * Hands each line of [input] to [onLine] until it ends. Stopping the server makes Android close the stream under the
 * read (InterruptedIOException); that ends the lines and must not end the app, as an uncaught exception would.
 */
internal fun pumpLines(input: InputStream, onLine: (String) -> Unit) {
    try {
        input.bufferedReader().useLines { lines -> lines.forEach(onLine) }
    } catch (_: IOException) {
    }
}

// Process.isAlive, waitFor(timeout) and destroyForcibly are API 26; the app's minSdk is lower.
private fun Process.alive(): Boolean =
    try {
        exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

private fun Process.waitForExit(millis: Long): Boolean {
    val deadline = System.currentTimeMillis() + millis
    while (alive() && System.currentTimeMillis() < deadline) Thread.sleep(EXIT_POLL_MS)
    return !alive()
}

private fun Process.kill() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) destroyForcibly() else destroy()
}

private const val EXIT_POLL_MS = 50L
