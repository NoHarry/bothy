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
import cc.noharry.agent.vfs.VfsEntry
import cc.noharry.agent.vfs.VfsFileSystem
import cc.noharry.agent.vfs.VfsListResult
import cc.noharry.agent.vfs.VfsReadResult
import cc.noharry.bothy.app.a2ui.initialValue
import cc.noharry.bothy.app.console.ui.IconFile
import cc.noharry.bothy.app.console.ui.IconFolder
import cc.noharry.bothy.app.runtime.appWorkspaceRoot
import cc.noharry.bothy.app.runtime.workspaceVfs
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
internal fun FilesPane(
    actions: ConsoleActions,
    modifier: Modifier = Modifier,
) {
    val colors = ConsoleColors
    val context = LocalContext.current.applicationContext
    val vfs by produceState<VfsFileSystem?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) {
            val workspaceRoot = appWorkspaceRoot(context)
            workspaceVfs(context, workspaceRoot)
        }
    }
    val mountTargets by produceState(initialValue = listOf("/"), context) {
        value = withContext(Dispatchers.IO) { workspaceMountTargets(context) }
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
                text = "Refresh",
                style = ConsoleType.meta,
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(Dimens.radiusSm))
                    .clickable { refreshTick += 1 }
                    .padding(horizontal = Dimens.spaceXs, vertical = Dimens.spaceXxs),
            )
        }
        Spacer(Modifier.height(Dimens.spaceXs))
        when (val result = listing) {
            null -> Text("Loading…", style = ConsoleType.meta, color = colors.muted)
            is VfsListResult.Error -> Text(
                "Unavailable: ${result.message}",
                style = ConsoleType.meta,
                color = colors.danger,
            )
            VfsListResult.InvalidPath,
            VfsListResult.NotFound,
            -> Text("Nothing here.", style = ConsoleType.meta, color = colors.muted)
            is VfsListResult.Entries -> {
                val directories = result.entries.filter { it.type == VFS_DIR_TYPE }
                val files = result.entries.filter { it.type != VFS_DIR_TYPE }
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

/** Directory mount targets from the workspace manifest (file mounts excluded). */
private fun workspaceMountTargets(context: android.content.Context): List<String> =
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
