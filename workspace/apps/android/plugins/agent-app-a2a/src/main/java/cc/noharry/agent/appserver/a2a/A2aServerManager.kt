package cc.noharry.agent.appserver.a2a
import android.content.Context
import android.util.Log
import cc.noharry.agent.a2a.server.A2aServer
import cc.noharry.agent.a2a.server.A2aServerConfig
import cc.noharry.agent.a2a.server.A2aAuthorizer
import cc.noharry.agent.appserver.runtime.modelText
import cc.noharry.agent.appserver.server.OutsideTurns
import cc.noharry.agent.a2a.server.A2aServerMessages

/**
 * The loopback endpoint while the A2A plugin runs and its host asks for it ([A2aHost.localServer]), separate from the ASR
 * and outbound A2A client paths. Stopping it closes the socket and stops the inbound tasks still running.
 */
internal object A2aServerManager {
    private var server: A2aServer? = null
    private var handler: AppA2aRequestHandler? = null

    @Synchronized
    fun start(context: Context, host: A2aHost, turns: OutsideTurns) {
        if (server != null) return
        val requests = AppA2aRequestHandler(context, host, turns)
        var candidate: A2aServer? = null
        try {
            // Its token first: one that cannot be kept leaves the endpoint off, logged, and the plugin running.
            val started = A2aServer(A2aServerConfig(authorizer = A2aAuthorizer.bearer(serverToken(context)), messages = serverMessages()), requests)
            candidate = started
            val port = started.start()
            server = started
            handler = requests
            Log.i(TAG, "A2A text server listening on 127.0.0.1:$port/a2a")
        } catch (error: Exception) {
            candidate?.close()
            Log.e(TAG, "A2A text server could not start", error)
        }
    }

    @Synchronized
    fun stop() {
        server?.close()
        server = null
        handler?.stopAll()
        handler = null
    }

    @Synchronized
    internal fun serverToken(context: Context): String {
        val file = java.io.File(context.noBackupFilesDir, "a2a/server-token")
        val saved = runCatching { file.readText(Charsets.US_ASCII).trim() }.getOrNull()
        if (saved != null && saved.length == 64 && saved.all { it in "0123456789abcdef" }) return saved
        val bytes = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        file.parentFile?.mkdirs()
        val atomic = android.util.AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(token.toByteArray(Charsets.US_ASCII))
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
        check(file.setReadable(false, false) && file.setWritable(false, false)) { "Unable to restrict A2A credential access" }
        check(file.setReadable(true, true) && file.setWritable(true, true)) { "Unable to set A2A credential ownership" }
        return token
    }

    /** What a caller is told about its task, in the persona's language. */
    private fun serverMessages() = A2aServerMessages(
        accepted = modelText("已接收文字请求", "Text request received"),
        started = modelText("开始执行", "Started"),
        timedOut = modelText("任务超时或输出接收过慢", "The task timed out, or its output was read too slowly"),
        canceled = modelText("任务已取消", "The task was canceled"),
        interrupted = modelText("已被新任务打断", "Interrupted by a new task"),
        failed = modelText("执行失败，请查看设备日志", "The task failed; the device's log has why"),
        noResult = modelText("运行结束但没有终态结果", "The run ended without a final result"),
        deliveryFailed = modelText("输出连接不可用或输出超限", "The output connection closed, or the output grew too large"),
    )

    private const val TAG = "DebugA2aServer"
}
