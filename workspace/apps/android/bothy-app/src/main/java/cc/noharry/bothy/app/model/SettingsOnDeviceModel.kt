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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.IdParams
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.protocol.DownloadFailure
import cc.noharry.bothy.protocol.DownloadPhase
import cc.noharry.bothy.protocol.InferenceServerPhase
import cc.noharry.bothy.protocol.InferenceServerView
import cc.noharry.bothy.protocol.ModelDownloadView
import cc.noharry.bothy.protocol.ON_DEVICE_PROFILE_ID
import cc.noharry.bothy.protocol.OnDeviceModelView
import cc.noharry.bothy.protocol.OnDeviceRequests
import cc.noharry.bothy.protocol.onDevice

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
    val server = LocalServer.current
    val onDevice = server.state.onDevice
    // As the server picks a model: the profile's, else the first offered.
    val chosen = onDevice.models.firstOrNull { it.id == chosenModel.trim() } ?: onDevice.models.firstOrNull()
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
        Text(stringResource(R.string.model_on_device), style = ConsoleType.panelTitle, color = ConsoleColors.text)
        Text(
            stringResource(R.string.model_on_device_intro),
            style = ConsoleType.meta,
            color = ConsoleColors.muted,
        )
        HorizontalDivider(color = ConsoleColors.borderSubtle)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
            items(onDevice.models, key = { it.id }) { model ->
                val use = { selectOnDeviceModel(actions, model, profileActive) }
                OnDeviceModelRow(model, inUse = profileActive && model.id == chosen?.id, onUse = use)
            }
        }
        HorizontalDivider(color = ConsoleColors.borderSubtle)
        if (chosen != null) OnDeviceModelServer(onDevice.server, chosen, onDevice.models)
    }
}

private fun selectOnDeviceModel(actions: ConsoleActions, model: OnDeviceModelView, profileActive: Boolean) {
    if (!profileActive) actions.selectProfile(ON_DEVICE_PROFILE_ID)
    actions.selectModel(model.id)
}

@Composable
private fun OnDeviceModelRow(spec: OnDeviceModelView, inUse: Boolean, onUse: () -> Unit) {
    val server = LocalServer.current
    val download = spec.download
    val downloaded = spec.downloaded
    val onDisk = spec.onDiskBytes
    val running = download?.phase == DownloadPhase.DOWNLOADING || download?.phase == DownloadPhase.VERIFYING
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        Column(Modifier.weight(1f)) {
            Text(spec.label, style = ConsoleType.body, color = ConsoleColors.text)
            Text(
                listOf(spec.publisher, spec.quantization, "${gigabytes(spec.sizeBytes)} GB", spec.license)
                    .filter(String::isNotBlank).joinToString(" · "),
                style = ConsoleType.meta,
                color = ConsoleColors.muted,
            )
            Text(spec.note, style = ConsoleType.meta, color = ConsoleColors.muted)
            Text(fileStatus(spec, download, downloaded, onDisk), style = ConsoleType.meta, color = ConsoleColors.accent)
        }
        when {
            inUse -> Text(stringResource(R.string.model_in_use), style = ConsoleType.meta, color = ConsoleColors.accent)
            downloaded -> OutlinedButton(onClick = onUse) { Text(stringResource(R.string.model_use)) }
        }
        when {
            running -> OutlinedButton(onClick = { server.send(OnDeviceRequests.CANCEL, IdParams(spec.id)) }) {
                Text(stringResource(R.string.common_cancel))
            }
            !downloaded -> Button(
                onClick = { server.send(OnDeviceRequests.DOWNLOAD, IdParams(spec.id)) },
                shape = RoundedCornerShape(Dimens.radiusMd),
            ) {
                Text(stringResource(if (onDisk > 0) R.string.model_resume_download else R.string.model_download))
            }
        }
        if (!running && (downloaded || onDisk > 0)) {
            TextButton(onClick = { server.send(OnDeviceRequests.DELETE, IdParams(spec.id)) }) {
                Text(stringResource(R.string.common_delete), color = ConsoleColors.danger)
            }
        }
    }
}

@Composable
private fun fileStatus(spec: OnDeviceModelView, download: ModelDownloadView?, downloaded: Boolean, onDisk: Long): String {
    val total = spec.sizeBytes
    return when {
        download?.phase == DownloadPhase.DOWNLOADING -> stringResource(
            R.string.model_downloading,
            gigabytes(download.bytes), gigabytes(total), percent(download.bytes, total), download.source,
        )
        download?.phase == DownloadPhase.VERIFYING -> stringResource(R.string.model_verifying)
        download?.phase == DownloadPhase.FAILED -> downloadFailure(download)
        downloaded -> stringResource(R.string.model_ready)
        onDisk > 0 -> stringResource(R.string.model_partly_downloaded, gigabytes(onDisk), gigabytes(total))
        else -> stringResource(R.string.model_not_downloaded)
    }
}

@Composable
private fun downloadFailure(failed: ModelDownloadView): String = when (failed.failure) {
    DownloadFailure.NO_SOURCE -> stringResource(R.string.model_download_no_source)
    DownloadFailure.CHECKSUM -> stringResource(R.string.model_download_checksum_failed)
    DownloadFailure.RENAME -> stringResource(R.string.model_download_rename_failed, failed.detail)
    DownloadFailure.DOWNLOAD, null -> stringResource(R.string.model_download_failed, failed.detail)
}

@Composable
private fun OnDeviceModelServer(server: InferenceServerView, chosen: OnDeviceModelView, models: List<OnDeviceModelView>) {
    val access = LocalServer.current
    // The model's name as offered; the first offered for an id this list does not know, as the server reads it.
    val loaded = { id: String -> (models.firstOrNull { it.id == id.trim() } ?: models.first()).label }
    val status = when (server.phase) {
        InferenceServerPhase.STOPPED -> stringResource(R.string.model_server_stopped)
        InferenceServerPhase.STARTING -> stringResource(R.string.model_server_starting, loaded(server.modelId), server.device)
        InferenceServerPhase.READY -> stringResource(
            R.string.model_server_running,
            loaded(server.modelId), server.device, server.baseUrl.removeSuffix("/v1"),
        )
        InferenceServerPhase.FAILED -> stringResource(R.string.model_server_failed, server.reason)
    }
    val active = server.phase == InferenceServerPhase.STARTING || server.phase == InferenceServerPhase.READY
    val downloaded = chosen.downloaded
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.model_inference_server), style = ConsoleType.body, color = ConsoleColors.text)
            Text(status, style = ConsoleType.meta, color = ConsoleColors.muted)
        }
        if (active) {
            OutlinedButton(onClick = { access.send(OnDeviceRequests.STOP, Empty) }) { Text(stringResource(R.string.model_stop)) }
        } else {
            Button(
                onClick = { access.send(OnDeviceRequests.START, IdParams(chosen.id)) },
                enabled = downloaded,
                shape = RoundedCornerShape(Dimens.radiusMd),
            ) {
                Text(stringResource(R.string.model_start, chosen.label))
            }
        }
    }
}

private fun gigabytes(bytes: Long): String = "%.2f".format(bytes / 1e9)

private fun percent(bytes: Long, total: Long): String = "${(bytes * 100 / total.coerceAtLeast(1))}%"
