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
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.llm.LlamaServer
import cc.noharry.bothy.llm.LocalModelSpec
import cc.noharry.bothy.llm.ModelDownloader

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
        Text("本机模型", style = ConsoleType.panelTitle, color = ConsoleColors.text)
        Text(
            "在这台设备上运行，对话不出设备：用 llama.cpp 跑在 NPU 上，NPU 用不了时退回 CPU。模型不随 App 安装，" +
                "下载哪个用哪个；在输入框旁或这里选用。选中「本机模型」时自动启动，换到别的服务时停下，释放内存。",
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
    val downloaded = remember(download) { OnDeviceModel.isDownloaded(spec) }
    val onDisk = remember(download) { OnDeviceModel.downloadedBytes(spec) }
    val running = download is ModelDownloader.Progress.Downloading || download is ModelDownloader.Progress.Verifying
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
            inUse -> Text("使用中", style = ConsoleType.meta, color = ConsoleColors.accent)
            downloaded -> OutlinedButton(onClick = onUse) { Text("使用") }
        }
        when {
            running -> OutlinedButton(onClick = { OnDeviceModel.cancelDownload(spec) }) { Text("取消") }
            !downloaded -> Button(onClick = { OnDeviceModel.startDownload(spec) }, shape = RoundedCornerShape(Dimens.radiusMd)) {
                Text(if (onDisk > 0) "继续下载" else "下载")
            }
        }
        if (!running && (downloaded || onDisk > 0)) {
            TextButton(onClick = { OnDeviceModel.deleteModel(spec) }) { Text("删除", color = ConsoleColors.danger) }
        }
    }
}

private fun fileStatus(spec: LocalModelSpec, download: ModelDownloader.Progress?, downloaded: Boolean, onDisk: Long): String {
    val total = spec.sizeBytes
    return when {
        download is ModelDownloader.Progress.Downloading ->
            "下载中 ${gigabytes(download.bytes)} / ${gigabytes(total)} GB（${percent(download.bytes, total)}，${download.source}）"
        download is ModelDownloader.Progress.Verifying -> "校验中…（核对 SHA-256，约半分钟）"
        download is ModelDownloader.Progress.Failed -> download.reason
        downloaded -> "已就绪"
        onDisk > 0 -> "已下载 ${gigabytes(onDisk)} / ${gigabytes(total)} GB，可以继续"
        else -> "未下载"
    }
}

@Composable
private fun OnDeviceModelServer(server: LlamaServer.State, chosen: LocalModelSpec) {
    val loaded = { id: String -> OnDeviceModel.spec(id).label }
    val status = when (server) {
        LlamaServer.State.Stopped -> "未启动"
        is LlamaServer.State.Starting -> "启动中（${loaded(server.modelId)}，${server.device.label}），加载模型约需十几秒…"
        is LlamaServer.State.Ready ->
            "运行中（${loaded(server.modelId)}，${server.device.label}，${server.baseUrl.removeSuffix("/v1")}）"
        is LlamaServer.State.Failed -> "启动失败：${server.reason}"
    }
    val active = server is LlamaServer.State.Starting || server is LlamaServer.State.Ready
    val downloaded = remember(server) { OnDeviceModel.isDownloaded(chosen) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        Column(Modifier.weight(1f)) {
            Text("推理服务", style = ConsoleType.body, color = ConsoleColors.text)
            Text(status, style = ConsoleType.meta, color = ConsoleColors.muted)
        }
        if (active) {
            OutlinedButton(onClick = OnDeviceModel::stop) { Text("停止") }
        } else {
            Button(onClick = { OnDeviceModel.warmUp(chosen) }, enabled = downloaded, shape = RoundedCornerShape(Dimens.radiusMd)) {
                Text("启动 ${chosen.label}")
            }
        }
    }
}

private fun gigabytes(bytes: Long): String = "%.2f".format(bytes / 1e9)

private fun percent(bytes: Long, total: Long): String = "${(bytes * 100 / total.coerceAtLeast(1))}%"
