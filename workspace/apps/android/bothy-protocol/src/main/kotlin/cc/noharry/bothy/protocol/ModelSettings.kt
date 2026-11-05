package cc.noharry.bothy.protocol

import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.IdParams
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.serialization.Serializable

/** The on-device profile's id: OpenAI chat to the llama-server the app runs itself. */
const val ON_DEVICE_PROFILE_ID = "on-device"

/** How a model's download stands. */
@Serializable
enum class DownloadPhase { DOWNLOADING, VERIFYING, DONE, FAILED }

/** Why a download ended without the file: no URL, every source failed, the checksum, or moving it into place. */
@Serializable
enum class DownloadFailure { NO_SOURCE, DOWNLOAD, CHECKSUM, RENAME }

@Serializable
data class ModelDownloadView(
    val phase: DownloadPhase,
    val bytes: Long = 0,
    val total: Long = 0,
    val source: String = "",
    val failure: DownloadFailure? = null,
    val detail: String = "",
)

/** One model the device can run: what it is (publisher and note in the screen's language) and what is on disk. */
@Serializable
data class OnDeviceModelView(
    val id: String,
    val label: String,
    val publisher: String = "",
    val quantization: String = "",
    val sizeBytes: Long = 0,
    val license: String = "",
    val note: String = "",
    val downloaded: Boolean = false,
    val onDiskBytes: Long = 0,
    val download: ModelDownloadView? = null,
)

@Serializable
enum class InferenceServerPhase { STOPPED, STARTING, READY, FAILED }

/** The llama-server: its phase, the model it loads or serves, on what, and where; [reason] when it failed. */
@Serializable
data class InferenceServerView(
    val phase: InferenceServerPhase = InferenceServerPhase.STOPPED,
    val modelId: String = "",
    val device: String = "",
    val baseUrl: String = "",
    val reason: String = "",
)

@Serializable
data class OnDeviceView(
    val models: List<OnDeviceModelView> = emptyList(),
    val server: InferenceServerView = InferenceServerView(),
)

/** The models the device can run and its inference server, as downloads and starts go on. */
@Serializable
data class OnDeviceSection(val onDevice: OnDeviceView = OnDeviceView())

/** Settings → 模型与服务 → 本机模型; each by model id. */
object OnDeviceRequests {
    val DOWNLOAD = RequestMethod("onDevice/download", IdParams.serializer(), Empty.serializer())
    val CANCEL = RequestMethod("onDevice/cancel", IdParams.serializer(), Empty.serializer())
    val DELETE = RequestMethod("onDevice/delete", IdParams.serializer(), Empty.serializer())

    /** Starts the server for the model when its file is there. */
    val START = RequestMethod("onDevice/start", IdParams.serializer(), Empty.serializer())
    val STOP = RequestMethod("onDevice/stop", Empty.serializer(), Empty.serializer())
}
