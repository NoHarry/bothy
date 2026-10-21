package cc.noharry.bothy.sample

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.vfs.VfsEntry
import cc.noharry.bothy.vfs.VfsFileSystem
import cc.noharry.bothy.vfs.VfsListResult
import cc.noharry.bothy.vfs.VfsReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Mounted-workspace file browser: mount shortcut chips on top, directory listing below.
 * HTML files open in the fullscreen viewer; other entries only navigate.
 */
@Composable
internal fun DebugFilesPane(
    actions: DebugConsoleActions,
    modifier: Modifier = Modifier,
) {
    val colors = DebugColors
    val context = LocalContext.current.applicationContext
    val vfs by produceState<VfsFileSystem?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) {
            val workspaceRoot = debugWorkspaceRoot(context)
            debugWorkspaceVfs(context, workspaceRoot)
        }
    }
    val mountTargets by produceState(initialValue = listOf("/"), context) {
        value = withContext(Dispatchers.IO) { debugWorkspaceMountTargets(context) }
    }
    var currentPath by rememberSaveable { mutableStateOf("/") }
    var refreshTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val listing by produceState<VfsListResult?>(initialValue = null, vfs, currentPath, refreshTick) {
        val workspace = vfs ?: return@produceState
        value = withContext(Dispatchers.IO) { workspace.list(currentPath) }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceXs),
        ) {
            mountTargets.forEach { target ->
                MountChip(
                    target = target,
                    selected = currentPath == target || currentPath.startsWith(target.trimEnd('/') + "/"),
                    onClick = { currentPath = target },
                )
            }
        }
        Spacer(Modifier.height(DebugDimens.spaceXs))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = currentPath,
                style = DebugType.meta,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Refresh",
                style = DebugType.meta,
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(DebugDimens.radiusSm))
                    .clickable { refreshTick += 1 }
                    .padding(horizontal = DebugDimens.spaceXs, vertical = DebugDimens.spaceXxs),
            )
        }
        Spacer(Modifier.height(DebugDimens.spaceXs))
        when (val result = listing) {
            null -> Text("Loading…", style = DebugType.meta, color = colors.muted)
            is VfsListResult.Error -> Text(
                "Unavailable: ${result.message}",
                style = DebugType.meta,
                color = colors.danger,
            )
            VfsListResult.InvalidPath,
            VfsListResult.NotFound,
            -> Text("Nothing here.", style = DebugType.meta, color = colors.muted)
            is VfsListResult.Entries -> {
                val directories = result.entries.filter { it.type == VFS_DIR_TYPE }
                val files = result.entries.filter { it.type != VFS_DIR_TYPE }
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceXxs),
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
                            isDirectory = entry.type == VFS_DIR_TYPE,
                            sizeBytes = entry.sizeBytes,
                            onClick = {
                                val child = childVfsPath(currentPath, entry.name)
                                when {
                                    entry.type == VFS_DIR_TYPE -> currentPath = child
                                    child.isHtmlPath() && vfs != null -> scope.launch {
                                        val read = withContext(Dispatchers.IO) {
                                            vfs?.read(child, 0L, MAX_HTML_PREVIEW_BYTES)
                                        }
                                        when (read) {
                                            is VfsReadResult.Found -> actions.openHtmlViewer(child, read.text)
                                            else -> Unit
                                        }
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
    val colors = DebugColors
    Text(
        text = target,
        style = DebugType.meta,
        color = if (selected) colors.accent else colors.muted,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(DebugDimens.radiusFull))
            .background(if (selected) colors.accentSoft else colors.elevated)
            .clickable(onClick = onClick)
            .padding(horizontal = DebugDimens.spaceSm, vertical = DebugDimens.spaceXs),
    )
}

@Composable
private fun FileEntryRow(
    name: String,
    isDirectory: Boolean,
    sizeBytes: Long?,
    onClick: () -> Unit,
) {
    val colors = DebugColors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DebugDimens.radiusSm))
            .clickable(onClick = onClick)
            .padding(horizontal = DebugDimens.spaceXs, vertical = DebugDimens.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isDirectory) DebugIconFolder else DebugIconFile,
            contentDescription = null,
            tint = if (isDirectory) colors.accent else colors.tertiary,
            modifier = Modifier.size(FILE_ENTRY_ICON_SIZE),
        )
        Spacer(Modifier.width(DebugDimens.spaceSm))
        Text(
            text = name,
            style = DebugType.bodySmall,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!isDirectory && sizeBytes != null) {
            Spacer(Modifier.width(DebugDimens.spaceSm))
            Text(
                text = sizeBytes.formatCount() + " B",
                style = DebugType.meta,
                color = colors.muted,
                maxLines = 1,
            )
        }
    }
}

/** Directory mount targets from the workspace manifest (file mounts excluded). */
private fun debugWorkspaceMountTargets(context: android.content.Context): List<String> =
    runCatching {
        val text = context.assets.open("$WORKSPACE_ASSET_ROOT_PATH/$WORKSPACE_MANIFEST_FILE")
            .use { it.readBytes().toString(Charsets.UTF_8) }
        val mounts = MountsJson.parseToJsonElement(text).jsonObject["mounts"]?.jsonArray.orEmpty()
        mounts.mapNotNull { element ->
            val target = element.jsonObject["target"]?.jsonPrimitive?.content ?: return@mapNotNull null
            target.takeIf { path -> path.split('/').last().none { it == '.' } || path == "/" }
        }.distinct().sorted()
    }.getOrDefault(listOf("/"))

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

private val MountsJson = Json { ignoreUnknownKeys = true }
private val FILE_ENTRY_ICON_SIZE = 18.dp
private const val WORKSPACE_ASSET_ROOT_PATH = "runtime-workspace"
private const val WORKSPACE_MANIFEST_FILE = "workspace.json"
private const val VFS_DIR_TYPE = "dir"
private const val MAX_HTML_PREVIEW_BYTES = 512 * 1024
