package cc.noharry.bothy.app.model

import android.content.Context
import android.util.Log
import cc.noharry.bothy.app.session.ModelProvider
import cc.noharry.bothy.llm.LOCAL_MODELS
import cc.noharry.bothy.llm.LlamaServer
import cc.noharry.bothy.llm.LocalModelSpec
import cc.noharry.bothy.llm.ModelDownloader
import cc.noharry.bothy.llm.localModel
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The built-in on-device profile's id; it is not listed in model-provider-profiles.json, so every build has it. */
internal const val ON_DEVICE_PROFILE_ID = "on-device"

/**
 * The on-device profile: OpenAI chat to [OnDeviceModel]'s llama-server, running [model] (one of the offered models,
 * picked like any profile's model beside the composer). The base URL is a placeholder; each start of the server picks
 * its own port and key, which [OnDeviceModelClient] reads when it calls.
 */
internal fun onDeviceProfile(model: String? = null): ProviderProfile = ProviderProfile(
    id = ON_DEVICE_PROFILE_ID,
    label = "本机模型",
    provider = ModelProvider.OPENAI,
    apiKey = "",
    model = OnDeviceModel.spec(model).id,
    baseUrl = "http://127.0.0.1/v1",
    endpoint = ProviderEndpoint.CHAT_COMPLETIONS,
    streamEnabled = true,
    serverSideSearch = false,
    searchCapability = "none",
)

internal fun ProviderProfile.isOnDevice(): Boolean = id == ON_DEVICE_PROFILE_ID

/**
 * The list with the built-in on-device profile last. A saved override of it (choosing its model saves one, keyed
 * `on_device`, which comes back as a profile of that id) keeps only the model: the rest is fixed.
 */
internal fun List<ProviderProfile>.withOnDeviceProfile(): List<ProviderProfile> {
    val saved = filter { it.id.preferenceKey() == ON_DEVICE_PROFILE_ID.preferenceKey() }
    return (this - saved.toSet()) + onDeviceProfile(saved.firstOrNull()?.model)
}

/**
 * The on-device models: each GGUF ([ModelDownloader], in no-backup storage; 2–5 GB, fetched on request) and the one
 * llama-server that serves whichever is chosen ([LlamaServer]). Choosing the on-device profile or one of its models
 * starts the server so the first request does not wait for the load; choosing another profile stops it and gives the
 * model's memory back.
 */
internal object OnDeviceModel {
    val models: List<LocalModelSpec> = LOCAL_MODELS

    /** The offered model with [id], or the default (the first). */
    fun spec(id: String?): LocalModelSpec = localModel(id) ?: models.first()

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val starts = SupervisorJob()
    private val downloadJobs = mutableMapOf<String, Job>()
    private val mutableDownloads = MutableStateFlow<Map<String, ModelDownloader.Progress>>(emptyMap())
    private val mutableFileChanges = MutableStateFlow(0)

    /** Each model's latest download step while one runs or after it ended, by model id. */
    val downloads: StateFlow<Map<String, ModelDownloader.Progress>> = mutableDownloads.asStateFlow()

    /** Counts model files completed or deleted, so a view of what is on disk reads it again. */
    val fileChanges: StateFlow<Int> = mutableFileChanges.asStateFlow()

    val server: LlamaServer by lazy {
        LlamaServer(File(appContext.applicationInfo.nativeLibraryDir), log = { line -> Log.i(TAG, line) })
    }

    private val downloader: ModelDownloader by lazy { ModelDownloader(File(appContext.noBackupFilesDir, "models")) }

    fun init(context: Context) {
        appContext = context.applicationContext
        scope.launch { onProfileSelected(ProviderConfig.load(appContext).activeProfile) }
    }

    fun isDownloaded(spec: LocalModelSpec): Boolean = ::appContext.isInitialized && downloader.isReady(spec)

    fun downloadedBytes(spec: LocalModelSpec): Long = if (::appContext.isInitialized) downloader.downloadedBytes(spec) else 0L

    fun startDownload(spec: LocalModelSpec) {
        synchronized(downloadJobs) {
            val previous = downloadJobs[spec.id]
            if (previous?.isActive == true) return
            // A cancelled download can still be writing its last read; it ends before this one appends to the file.
            downloadJobs[spec.id] = scope.launch {
                previous?.join()
                downloader.download(spec).collect { step ->
                    mutableDownloads.update { it + (spec.id to step) }
                    if (step is ModelDownloader.Progress.Done || step is ModelDownloader.Progress.Failed) fileChanged()
                }
            }
        }
    }

    fun cancelDownload(spec: LocalModelSpec) {
        synchronized(downloadJobs) { downloadJobs[spec.id] }?.cancel()
        mutableDownloads.update { it - spec.id }
    }

    fun deleteModel(spec: LocalModelSpec) {
        cancelDownload(spec)
        val download = synchronized(downloadJobs) { downloadJobs[spec.id] }
        scope.launch {
            download?.join()
            if (servedModel() == spec.id) stopServer()
            downloader.delete(spec)
            mutableDownloads.update { it - spec.id }
            fileChanged()
        }
    }

    /** Starts the server for [spec] in the background when its file is there. */
    fun warmUp(spec: LocalModelSpec) {
        if (isDownloaded(spec)) scope.launch { runCatching { ensureRunning(spec) } }
    }

    fun stop() {
        scope.launch { stopServer() }
    }

    /**
     * The server running [spec], started (or switched to it) first; throws with what to do when the file is missing.
     * The start runs in this object's scope, so a request given up while the model loads (a barge-in, a first-event
     * timeout) leaves the load to finish for the next one; only [stop] ends it.
     */
    suspend fun ensureRunning(spec: LocalModelSpec): LlamaServer.State.Ready {
        check(isDownloaded(spec)) { missingMessage(spec) }
        val start = scope.async(starts) { server.ensureRunning(downloader.file(spec), spec) }
        return try {
            start.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException("本机模型已停止", e)
        }
    }

    /** The active profile changed, or its model did. */
    fun onProfileSelected(profile: ProviderProfile) {
        if (profile.isOnDevice()) warmUp(spec(profile.model)) else if (server.state.value !is LlamaServer.State.Stopped) stop()
    }

    fun missingMessage(spec: LocalModelSpec): String =
        "本机模型 ${spec.label} 还没下载：设置 → 模型与服务 → 本机模型 → ${spec.label} → 下载（约 ${"%.1f".format(spec.sizeBytes / 1e9)} GB）。"

    private suspend fun stopServer() {
        starts.cancelChildren()
        server.stop()
    }

    private fun fileChanged() = mutableFileChanges.update { it + 1 }

    private fun servedModel(): String? = when (val state = server.state.value) {
        is LlamaServer.State.Ready -> state.modelId
        is LlamaServer.State.Starting -> state.modelId
        else -> null
    }

    private const val TAG = "OnDeviceModel"
}
