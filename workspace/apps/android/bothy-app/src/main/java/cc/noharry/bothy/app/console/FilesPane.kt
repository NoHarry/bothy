package cc.noharry.bothy.app.console
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.FileListResult
import cc.noharry.agent.appserver.protocol.FileReadParams
import cc.noharry.agent.appserver.protocol.FileRequests
import cc.noharry.agent.appserver.protocol.PathParams
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ui.IconFile
import cc.noharry.bothy.app.console.ui.IconFolder
import cc.noharry.bothy.app.frontend.LocalServer

/**
 * Mounted-workspace file browser: mount shortcut chips on top, directory listing below.
 * HTML files open in the fullscreen viewer; other entries only navigate.
 */
@Composable
internal fun FilesPane(
    actions: ConsoleActions,
    modifier: Modifier = Modifier,
) {
    val colors = ConsoleColors
    val server = LocalServer.current
    var mountTargets by remember { mutableStateOf(listOf("/")) }
    LaunchedEffect(Unit) { server.send(FileRequests.MOUNTS, Empty) { mountTargets = it.targets } }
    var currentPath by rememberSaveable { mutableStateOf("/") }
    var refreshTick by remember { mutableIntStateOf(0) }
    var listing by remember { mutableStateOf<FileListResult?>(null) }
    LaunchedEffect(currentPath, refreshTick) {
        server.send(FileRequests.LIST, PathParams(currentPath), onRefused = { listing = FileListResult(error = it.message.orEmpty()) }) {
            listing = it
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
        ) {
            mountTargets.forEach { target ->
                MountChip(
                    target = target,
                    selected = currentPath == target || currentPath.startsWith(target.trimEnd('/') + "/"),
                    onClick = { currentPath = target },
                )
            }
        }
        Spacer(Modifier.height(Dimens.spaceXs))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = currentPath,
                style = ConsoleType.meta,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.common_refresh),
                style = ConsoleType.meta,
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(Dimens.radiusSm))
                    .clickable { refreshTick += 1 }
                    .padding(horizontal = Dimens.spaceXs, vertical = Dimens.spaceXxs),
            )
        }
        Spacer(Modifier.height(Dimens.spaceXs))
        val result = listing
        val error = result?.error
        when {
            result == null -> Text(stringResource(R.string.common_loading), style = ConsoleType.meta, color = colors.muted)
            error != null -> Text(
                stringResource(R.string.console_files_unavailable, error),
                style = ConsoleType.meta,
                color = colors.danger,
            )
            result.missing -> Text(stringResource(R.string.console_files_empty), style = ConsoleType.meta, color = colors.muted)
            else -> {
                val directories = result.entries.filter { it.directory }
                val files = result.entries.filterNot { it.directory }
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Dimens.spaceXxs),
                ) {
                    if (currentPath != "/") {
                        item(key = "..") {
                            FileEntryRow(
                                name = "..",
                                isDirectory = true,
                                sizeBytes = null,
                                onClick = { currentPath = parentVfsPath(currentPath) },
                            )
                        }
                    }
                    items(directories + files, key = { it.name }) { entry ->
                        FileEntryRow(
                            name = entry.name,
                            isDirectory = entry.directory,
                            sizeBytes = entry.sizeBytes,
                            onClick = {
                                val child = childVfsPath(currentPath, entry.name)
                                when {
                                    entry.directory -> currentPath = child
                                    child.isHtmlPath() -> server.send(FileRequests.READ, FileReadParams(child, MAX_HTML_PREVIEW_BYTES)) { read ->
                                        read.text?.let { actions.openHtmlViewer(child, it) }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MountChip(target: String, selected: Boolean, onClick: () -> Unit) {
    val colors = ConsoleColors
    Text(
        text = target,
        style = ConsoleType.meta,
        color = if (selected) colors.accent else colors.muted,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(Dimens.radiusFull))
            .background(if (selected) colors.accentSoft else colors.elevated)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.spaceSm, vertical = Dimens.spaceXs),
    )
}

@Composable
private fun FileEntryRow(
    name: String,
    isDirectory: Boolean,
    sizeBytes: Long?,
    onClick: () -> Unit,
) {
    val colors = ConsoleColors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.radiusSm))
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.spaceXs, vertical = Dimens.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isDirectory) IconFolder else IconFile,
            contentDescription = null,
            tint = if (isDirectory) colors.accent else colors.tertiary,
            modifier = Modifier.size(FILE_ENTRY_ICON_SIZE),
        )
        Spacer(Modifier.width(Dimens.spaceSm))
        Text(
            text = name,
            style = ConsoleType.bodySmall,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!isDirectory && sizeBytes != null) {
            Spacer(Modifier.width(Dimens.spaceSm))
            Text(
                text = sizeBytes.formatCount() + " B",
                style = ConsoleType.meta,
                color = colors.muted,
                maxLines = 1,
            )
        }
    }
}

private fun childVfsPath(parent: String, name: String): String =
    if (parent == "/") "/$name" else "$parent/$name"

private fun parentVfsPath(path: String): String {
    val trimmed = path.trimEnd('/')
    val index = trimmed.lastIndexOf('/')
    return if (index <= 0) "/" else trimmed.substring(0, index)
}

private fun String.isHtmlPath(): Boolean {
    val lower = lowercase()
    return lower.endsWith(".html") || lower.endsWith(".htm")
}

private val FILE_ENTRY_ICON_SIZE = 18.dp
private const val MAX_HTML_PREVIEW_BYTES = 512 * 1024
