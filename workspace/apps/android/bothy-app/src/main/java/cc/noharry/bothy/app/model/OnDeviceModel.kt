package cc.noharry.bothy.app.model

import android.content.Context
import android.util.Log
import cc.noharry.bothy.app.session.ModelProvider
import cc.noharry.bothy.llm.GEMMA_4_E4B
import cc.noharry.bothy.llm.LlamaServer
import cc.noharry.bothy.llm.ModelDownloader
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The built-in on-device profile's id; it is not listed in model-provider-profiles.json, so every build has it. */
internal const val ON_DEVICE_PROFILE_ID = "on-device"

/**
 * The on-device profile: OpenAI chat to [OnDeviceModel]'s llama-server. The base URL is a placeholder; each start of
 * the server picks its own port and key, which [OnDeviceModelClient] reads when it calls.
 */
internal fun onDeviceProfile(): ProviderProfile = ProviderProfile(
    id = ON_DEVICE_PROFILE_ID,
    label = "本机 ${OnDeviceModel.spec.label}",
    provider = ModelProvider.OPENAI,
    apiKey = "",
    model = OnDeviceModel.spec.id,
    baseUrl = "http://127.0.0.1/v1",
    endpoint = ProviderEndpoint.CHAT_COMPLETIONS,
    streamEnabled = true,
    serverSideSearch = false,
    searchCapability = "none",
)

internal fun ProviderProfile.isOnDevice(): Boolean = id == ON_DEVICE_PROFILE_ID

internal fun List<ProviderProfile>.withOnDeviceProfile(): List<ProviderProfile> =
    if (any { it.isOnDevice() }) this else this + onDeviceProfile()

/**
 * The on-device model: its GGUF ([ModelDownloader], in no-backup storage; about 5 GB, fetched on request) and the
 * llama-server that serves it ([LlamaServer]). Choosing the on-device profile starts the server so the first request
 * does not wait for the load; choosing another stops it and gives the model's memory back.
 */
internal object OnDeviceModel {
    val spec = GEMMA_4_E4B

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private val mutableDownload = MutableStateFlow<ModelDownloader.Progress?>(null)

    /** The latest download step while one runs or after it ended; null when none has run since the app started. */
    val download: StateFlow<ModelDownloader.Progress?> = mutableDownload.asStateFlow()

    val server: LlamaServer by lazy {
        LlamaServer(File(appContext.applicationInfo.nativeLibraryDir), log = { line -> Log.i(TAG, line) })
    }

    private val downloader: ModelDownloader by lazy { ModelDownloader(File(appContext.noBackupFilesDir, "models")) }

    fun init(context: Context) {
        appContext = context.applicationContext
        scope.launch { if (ProviderConfig.load(appContext).activeProfile.isOnDevice()) warmUp() }
    }

    fun isDownloaded(): Boolean = ::appContext.isInitialized && downloader.isReady(spec)

    fun downloadedBytes(): Long = if (::appContext.isInitialized) downloader.downloadedBytes(spec) else 0L

    fun startDownload() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch { downloader.download(spec).collect { mutableDownload.value = it } }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        mutableDownload.value = null
    }

    fun deleteModel() {
        cancelDownload()
        scope.launch {
            server.stop()
            downloader.delete(spec)
        }
    }

    /** Starts the server in the background when the model is there. */
    fun warmUp() {
        if (isDownloaded()) scope.launch { runCatching { ensureRunning() } }
    }

    fun stop() {
        scope.launch { server.stop() }
    }

    /** The running server, started first when it is not; throws with what to do when the model is missing. */
    suspend fun ensureRunning(): LlamaServer.State.Ready {
        check(isDownloaded()) { MISSING_MESSAGE }
        return server.ensureRunning(downloader.file(spec), spec)
    }

    fun onProfileSelected(profile: ProviderProfile) {
        if (profile.isOnDevice()) warmUp() else if (server.state.value !is LlamaServer.State.Stopped) stop()
    }

    val MISSING_MESSAGE: String
        get() = "本机模型还没下载：设置 → 模型与服务 → 本机 ${spec.label} → 下载（约 ${"%.1f".format(spec.sizeBytes / 1e9)} GB）。"

    private const val TAG = "OnDeviceModel"
}
