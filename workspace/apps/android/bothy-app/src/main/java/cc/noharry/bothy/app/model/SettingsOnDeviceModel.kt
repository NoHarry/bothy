package cc.noharry.bothy.app.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.llm.GEMMA_4_E4B
import cc.noharry.bothy.llm.LlamaServer
import cc.noharry.bothy.llm.LocalModelSpec
import cc.noharry.bothy.llm.MINICPM5_2B
import cc.noharry.bothy.llm.ModelDownloader
import cc.noharry.bothy.llm.QWEN3_4B_2507

/**
 * Settings → 模型与服务 → 本机模型: each offered model (download, resume, delete, use) and the server that runs the
 * chosen one. [chosenModel] is the profile's model; [profileActive] whether 本机模型 is the active profile.
 */
@Composable
internal fun OnDeviceModelDetail(
    chosenModel: String,
    profileActive: Boolean,
    actions: ConsoleActions,
    modifier: Modifier = Modifier,
) {
    val downloads by OnDeviceModel.downloads.collectAsState()
    val server by OnDeviceModel.server.state.collectAsState()
    val chosen = OnDeviceModel.spec(chosenModel)
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
        Text(stringResource(R.string.model_on_device), style = ConsoleType.panelTitle, color = ConsoleColors.text)
        Text(
            stringResource(R.string.model_on_device_intro),
            style = ConsoleType.meta,
            color = ConsoleColors.muted,
        )
        HorizontalDivider(color = ConsoleColors.borderSubtle)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
            items(OnDeviceModel.models, key = { it.id }) { spec ->
                val use = { selectOnDeviceModel(actions, spec, profileActive) }
                OnDeviceModelRow(spec, downloads[spec.id], inUse = profileActive && spec == chosen, onUse = use)
            }
        }
        HorizontalDivider(color = ConsoleColors.borderSubtle)
        OnDeviceModelServer(server, chosen)
    }
}

private fun selectOnDeviceModel(actions: ConsoleActions, spec: LocalModelSpec, profileActive: Boolean) {
    if (!profileActive) actions.selectProfile(ON_DEVICE_PROFILE_ID)
    actions.selectModel(spec.id)
}

@Composable
private fun OnDeviceModelRow(spec: LocalModelSpec, download: ModelDownloader.Progress?, inUse: Boolean, onUse: () -> Unit) {
    val files by OnDeviceModel.fileChanges.collectAsState()
    val downloaded = remember(download, files) { OnDeviceModel.isDownloaded(spec) }
    val onDisk = remember(download, files) { OnDeviceModel.downloadedBytes(spec) }
    val running = download is ModelDownloader.Progress.Downloading || download is ModelDownloader.Progress.Verifying
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        Column(Modifier.weight(1f)) {
            Text(spec.label, style = ConsoleType.body, color = ConsoleColors.text)
            Text(
                listOf(publisherLabel(spec), spec.quantization, "${gigabytes(spec.sizeBytes)} GB", spec.license)
                    .filter(String::isNotBlank).joinToString(" · "),
                style = ConsoleType.meta,
                color = ConsoleColors.muted,
            )
            Text(noteLabel(spec), style = ConsoleType.meta, color = ConsoleColors.muted)
            Text(fileStatus(spec, download, downloaded, onDisk), style = ConsoleType.meta, color = ConsoleColors.accent)
        }
        when {
            inUse -> Text(stringResource(R.string.model_in_use), style = ConsoleType.meta, color = ConsoleColors.accent)
            downloaded -> OutlinedButton(onClick = onUse) { Text(stringResource(R.string.model_use)) }
        }
        when {
            running -> OutlinedButton(onClick = { OnDeviceModel.cancelDownload(spec) }) {
                Text(stringResource(R.string.common_cancel))
            }
            !downloaded -> Button(onClick = { OnDeviceModel.startDownload(spec) }, shape = RoundedCornerShape(Dimens.radiusMd)) {
                Text(stringResource(if (onDisk > 0) R.string.model_resume_download else R.string.model_download))
            }
        }
        if (!running && (downloaded || onDisk > 0)) {
            TextButton(onClick = { OnDeviceModel.deleteModel(spec) }) {
                Text(stringResource(R.string.common_delete), color = ConsoleColors.danger)
            }
        }
    }
}

@Composable
private fun fileStatus(spec: LocalModelSpec, download: ModelDownloader.Progress?, downloaded: Boolean, onDisk: Long): String {
    val total = spec.sizeBytes
    return when {
        download is ModelDownloader.Progress.Downloading -> stringResource(
            R.string.model_downloading,
            gigabytes(download.bytes), gigabytes(total), percent(download.bytes, total), download.source,
        )
        download is ModelDownloader.Progress.Verifying -> stringResource(R.string.model_verifying)
        download is ModelDownloader.Progress.Failed -> downloadFailure(download)
        downloaded -> stringResource(R.string.model_ready)
        onDisk > 0 -> stringResource(R.string.model_partly_downloaded, gigabytes(onDisk), gigabytes(total))
        else -> stringResource(R.string.model_not_downloaded)
    }
}

@Composable
private fun downloadFailure(failed: ModelDownloader.Progress.Failed): String = when (failed.failure) {
    ModelDownloader.Failure.NO_SOURCE -> stringResource(R.string.model_download_no_source)
    ModelDownloader.Failure.DOWNLOAD -> stringResource(R.string.model_download_failed, failed.detail)
    ModelDownloader.Failure.CHECKSUM -> stringResource(R.string.model_download_checksum_failed)
    ModelDownloader.Failure.RENAME -> stringResource(R.string.model_download_rename_failed, failed.detail)
}

/** The publisher in the screen's language; the spec's own for a model this list does not know. */
@Composable
private fun publisherLabel(spec: LocalModelSpec): String = when (spec.id) {
    QWEN3_4B_2507.id -> stringResource(R.string.model_publisher_qwen)
    MINICPM5_2B.id -> stringResource(R.string.model_publisher_minicpm)
    else -> spec.publisher
}

/** What the tests found about the model, in the screen's language; the spec's own note for one not listed here. */
@Composable
private fun noteLabel(spec: LocalModelSpec): String = when (spec.id) {
    GEMMA_4_E4B.id -> stringResource(R.string.model_note_gemma_4_e4b)
    QWEN3_4B_2507.id -> stringResource(R.string.model_note_qwen3_4b)
    MINICPM5_2B.id -> stringResource(R.string.model_note_minicpm5_2b)
    else -> spec.note
}

@Composable
private fun OnDeviceModelServer(server: LlamaServer.State, chosen: LocalModelSpec) {
    val loaded = { id: String -> OnDeviceModel.spec(id).label }
    val status = when (server) {
        LlamaServer.State.Stopped -> stringResource(R.string.model_server_stopped)
        is LlamaServer.State.Starting ->
            stringResource(R.string.model_server_starting, loaded(server.modelId), server.device.label)
        is LlamaServer.State.Ready -> stringResource(
            R.string.model_server_running,
            loaded(server.modelId), server.device.label, server.baseUrl.removeSuffix("/v1"),
        )
        is LlamaServer.State.Failed -> stringResource(R.string.model_server_failed, server.reason)
    }
    val active = server is LlamaServer.State.Starting || server is LlamaServer.State.Ready
    val files by OnDeviceModel.fileChanges.collectAsState()
    val downloaded = remember(server, chosen, files) { OnDeviceModel.isDownloaded(chosen) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.model_inference_server), style = ConsoleType.body, color = ConsoleColors.text)
            Text(status, style = ConsoleType.meta, color = ConsoleColors.muted)
        }
        if (active) {
            OutlinedButton(onClick = OnDeviceModel::stop) { Text(stringResource(R.string.model_stop)) }
        } else {
            Button(onClick = { OnDeviceModel.warmUp(chosen) }, enabled = downloaded, shape = RoundedCornerShape(Dimens.radiusMd)) {
                Text(stringResource(R.string.model_start, chosen.label))
            }
        }
    }
}

private fun gigabytes(bytes: Long): String = "%.2f".format(bytes / 1e9)

private fun percent(bytes: Long, total: Long): String = "${(bytes * 100 / total.coerceAtLeast(1))}%"
