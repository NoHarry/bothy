package cc.noharry.bothy.app.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.llm.LlamaServer
import cc.noharry.bothy.llm.ModelDownloader

/** Settings → 模型与服务 → the on-device profile: the model file (download, resume, delete) and its server. */
@Composable
internal fun OnDeviceModelDetail(modifier: Modifier = Modifier) {
    val spec = OnDeviceModel.spec
    val download by OnDeviceModel.download.collectAsState()
    val server by OnDeviceModel.server.state.collectAsState()
    val downloaded = remember(download) { OnDeviceModel.isDownloaded() }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
        Text("本机 ${spec.label}", style = ConsoleType.panelTitle, color = ConsoleColors.text)
        Text(
            "在这台设备上运行，对话不出设备。模型是 Google ${spec.label}（QAT Q4_0，约 ${gigabytes(spec.sizeBytes)} GB，" +
                "Apache-2.0），用 llama.cpp 跑在 NPU 上，NPU 用不了时退回 CPU。选中这个服务时自动启动；换到别的服务时停下，释放内存。",
            style = ConsoleType.meta,
            color = ConsoleColors.muted,
        )
        HorizontalDivider(color = ConsoleColors.borderSubtle)
        OnDeviceModelFile(download, downloaded)
        HorizontalDivider(color = ConsoleColors.borderSubtle)
        OnDeviceModelServer(server, downloaded)
    }
}

@Composable
private fun OnDeviceModelFile(download: ModelDownloader.Progress?, downloaded: Boolean) {
    val total = OnDeviceModel.spec.sizeBytes
    val onDisk = remember(download) { OnDeviceModel.downloadedBytes() }
    val running = download is ModelDownloader.Progress.Downloading || download is ModelDownloader.Progress.Verifying
    val status = when {
        download is ModelDownloader.Progress.Downloading ->
            "下载中 ${gigabytes(download.bytes)} / ${gigabytes(total)} GB（${percent(download.bytes, total)}，${download.source}）"
        download is ModelDownloader.Progress.Verifying -> "校验中…（核对 SHA-256，约半分钟）"
        download is ModelDownloader.Progress.Failed -> download.reason
        downloaded -> "已就绪（${gigabytes(total)} GB）"
        onDisk > 0 -> "已下载 ${gigabytes(onDisk)} / ${gigabytes(total)} GB，可以继续"
        else -> "未下载"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        Column(Modifier.weight(1f)) {
            Text("模型文件", style = ConsoleType.body, color = ConsoleColors.text)
            Text(status, style = ConsoleType.meta, color = ConsoleColors.muted)
        }
        when {
            running -> OutlinedButton(onClick = OnDeviceModel::cancelDownload) { Text("取消") }
            !downloaded -> Button(onClick = OnDeviceModel::startDownload, shape = RoundedCornerShape(Dimens.radiusMd)) {
                Text(if (onDisk > 0) "继续下载" else "下载")
            }
        }
        if (!running && (downloaded || onDisk > 0)) {
            TextButton(onClick = OnDeviceModel::deleteModel) { Text("删除", color = ConsoleColors.danger) }
        }
    }
}

@Composable
private fun OnDeviceModelServer(server: LlamaServer.State, downloaded: Boolean) {
    val status = when (server) {
        LlamaServer.State.Stopped -> "未启动"
        is LlamaServer.State.Starting -> "启动中（${server.device.label}），加载模型约需十几秒…"
        is LlamaServer.State.Ready -> "运行中（${server.device.label}，${server.baseUrl.removeSuffix("/v1")}）"
        is LlamaServer.State.Failed -> "启动失败：${server.reason}"
    }
    val active = server is LlamaServer.State.Starting || server is LlamaServer.State.Ready
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        Column(Modifier.weight(1f)) {
            Text("推理服务", style = ConsoleType.body, color = ConsoleColors.text)
            Text(status, style = ConsoleType.meta, color = ConsoleColors.muted)
        }
        if (active) {
            OutlinedButton(onClick = OnDeviceModel::stop) { Text("停止") }
        } else {
            Button(onClick = OnDeviceModel::warmUp, enabled = downloaded, shape = RoundedCornerShape(Dimens.radiusMd)) {
                Text("启动")
            }
        }
    }
}

private fun gigabytes(bytes: Long): String = "%.2f".format(bytes / 1e9)

private fun percent(bytes: Long, total: Long): String = "${(bytes * 100 / total.coerceAtLeast(1))}%"
