package cc.noharry.bothy.server.server

import android.content.Context
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.server.RouteModule
import cc.noharry.agent.appserver.server.RouteTable
import cc.noharry.bothy.llm.GEMMA_4_E4B
import cc.noharry.bothy.llm.LlamaServer
import cc.noharry.bothy.llm.LocalModelSpec
import cc.noharry.bothy.llm.MINICPM5_2B
import cc.noharry.bothy.llm.ModelDownloader
import cc.noharry.bothy.llm.QWEN3_4B_2507
import cc.noharry.bothy.protocol.DownloadFailure
import cc.noharry.bothy.protocol.DownloadPhase
import cc.noharry.bothy.protocol.InferenceServerPhase
import cc.noharry.bothy.protocol.InferenceServerView
import cc.noharry.bothy.protocol.ModelDownloadView
import cc.noharry.bothy.protocol.OnDeviceModelView
import cc.noharry.bothy.protocol.OnDeviceRequests
import cc.noharry.bothy.protocol.OnDeviceView
import cc.noharry.bothy.server.R
import cc.noharry.bothy.server.model.OnDeviceModel

/** Settings → 模型与服务 → 本机模型: downloads and the inference server, by model id. */
internal class OnDeviceRoutes : RouteModule {
    override fun RouteTable.install() {
        on(OnDeviceRequests.DOWNLOAD) { done { OnDeviceModel.startDownload(spec(it.id)) } }
        on(OnDeviceRequests.CANCEL) { done { OnDeviceModel.cancelDownload(spec(it.id)) } }
        on(OnDeviceRequests.DELETE) { done { OnDeviceModel.deleteModel(spec(it.id)) } }
        on(OnDeviceRequests.START) { done { OnDeviceModel.warmUp(spec(it.id)) } }
        on(OnDeviceRequests.STOP) { done { OnDeviceModel.stop() } }
    }

    private fun spec(id: String): LocalModelSpec = OnDeviceModel.spec(id)

    private inline fun done(block: () -> Unit): Empty {
        block()
        return Empty
    }
}

/** The offered models with what is on disk and how their downloads stand, and the server; read on the main thread. */
internal fun onDeviceView(context: Context): OnDeviceView {
    val downloads = OnDeviceModel.downloads.value
    return OnDeviceView(
        models = OnDeviceModel.models.map { spec ->
            OnDeviceModelView(
                id = spec.id,
                label = spec.label,
                publisher = publisherLabel(context, spec),
                quantization = spec.quantization,
                sizeBytes = spec.sizeBytes,
                license = spec.license,
                note = noteLabel(context, spec),
                downloaded = OnDeviceModel.isDownloaded(spec),
                onDiskBytes = OnDeviceModel.downloadedBytes(spec),
                download = downloads[spec.id]?.view(),
            )
        },
        server = OnDeviceModel.server.state.value.view(),
    )
}

private fun ModelDownloader.Progress.view(): ModelDownloadView = when (this) {
    is ModelDownloader.Progress.Downloading -> ModelDownloadView(DownloadPhase.DOWNLOADING, bytes, total, source)
    ModelDownloader.Progress.Verifying -> ModelDownloadView(DownloadPhase.VERIFYING)
    is ModelDownloader.Progress.Done -> ModelDownloadView(DownloadPhase.DONE)
    is ModelDownloader.Progress.Failed -> ModelDownloadView(
        DownloadPhase.FAILED,
        failure = DownloadFailure.valueOf(failure.name),
        detail = detail,
    )
}

/** The server without its key: a client only needs what it runs and where. */
private fun LlamaServer.State.view(): InferenceServerView = when (this) {
    LlamaServer.State.Stopped -> InferenceServerView(InferenceServerPhase.STOPPED)
    is LlamaServer.State.Starting -> InferenceServerView(InferenceServerPhase.STARTING, modelId, device.label)
    is LlamaServer.State.Ready -> InferenceServerView(InferenceServerPhase.READY, modelId, device.label, baseUrl)
    is LlamaServer.State.Failed -> InferenceServerView(InferenceServerPhase.FAILED, reason = reason)
}

/** The publisher in the screen's language; the spec's own for a model this list does not know. */
private fun publisherLabel(context: Context, spec: LocalModelSpec): String = when (spec.id) {
    QWEN3_4B_2507.id -> context.getString(R.string.model_publisher_qwen)
    MINICPM5_2B.id -> context.getString(R.string.model_publisher_minicpm)
    else -> spec.publisher
}

/** What the tests found about the model, in the screen's language; the spec's own note for one not listed here. */
private fun noteLabel(context: Context, spec: LocalModelSpec): String = when (spec.id) {
    GEMMA_4_E4B.id -> context.getString(R.string.model_note_gemma_4_e4b)
    QWEN3_4B_2507.id -> context.getString(R.string.model_note_qwen3_4b)
    MINICPM5_2B.id -> context.getString(R.string.model_note_minicpm5_2b)
    else -> spec.note
}
