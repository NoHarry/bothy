package cc.noharry.bothy.sample

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.noharry.agent.device.DeviceMediaMcpProvider

/**
 * 设置 → 连接 → MCP: the providers with their switches and tool previews, and the configured standard MCP servers,
 * which can be added, edited and deleted. `editing` is the form's server id, `""` for a
 * new one, null for the list.
 */
@Composable
internal fun DebugMcpProvidersPane(
    state: DebugUiState,
    actions: DebugConsoleActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val editedServer = editing?.let { id -> state.mcpProviders.firstOrNull { it.id == id }?.server }
    SettingsChildPanel(modifier, embedded) {
        if (!embedded || editing != null) {
            SettingsNavBar(
                title = when {
                    editing == null -> "MCP providers"
                    editedServer == null -> "添加 MCP"
                    else -> "编辑 ${editedServer.label}"
                },
                breadcrumb = if (editing == null) "Settings › MCP" else "Settings › MCP › ${if (editedServer == null) "添加" else "编辑"}",
                backLabel = "Back",
                onBack = { if (editing != null) editing = null else onBack() },
                hint = "Toggles apply immediately · tap a row to preview tools.",
            )
            Spacer(Modifier.height(DebugDimens.spaceSm))
            HorizontalDivider(color = DebugColors.borderSubtle)
            Spacer(Modifier.height(DebugDimens.spaceMd))
        }
        if (editing != null) {
            McpServerForm(editedServer, actions, onDone = { editing = null }, modifier = Modifier.weight(1f))
        } else {
            McpProviderList(state, actions, onAdd = { editing = "" }, onEdit = { editing = it }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun McpProviderList(
    state: DebugUiState,
    actions: DebugConsoleActions,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DebugColors
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier) {
        // The add button leads the row: the 小博 bubble rests on the top-right corner of the screen and covered it there.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onAdd) { Text("+ 添加 MCP") }
            Text(
                "${state.mcpProviders.count { it.enabled }} of ${state.mcpProviders.size} loaded",
                style = DebugType.meta,
                color = colors.muted,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(DebugDimens.spaceSm))
        Surface(
            modifier = Modifier.weight(1f).widthIn(max = MCP_PROVIDERS_CONTENT_MAX_WIDTH),
            shape = RoundedCornerShape(DebugDimens.radiusMd),
            color = colors.panel,
            border = BorderStroke(1.dp, colors.borderSubtle),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            LazyColumn {
                state.mcpProviders.forEachIndexed { index, provider ->
                    item(key = provider.id) {
                        if (index > 0) HorizontalDivider(color = colors.borderSubtle, modifier = Modifier.padding(horizontal = DebugDimens.spaceMd))
                        ProviderToggleRow(
                            provider = provider,
                            expanded = expandedId == provider.id,
                            onToggleExpanded = { expandedId = if (expandedId == provider.id) null else provider.id },
                            actions = actions,
                            onEdit = { onEdit(provider.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Toggle row aligned with the Settings row family: body title, meta subtitle,
 * trailing switch. The title area expands a tool-catalog preview; the switch
 * only flips enablement. A configured server also shows where it is and offers 编辑 and 删除.
 */
@Composable
private fun ProviderToggleRow(
    provider: DebugMcpProviderState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    actions: DebugConsoleActions,
    onEdit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = MCP_ROW_HEIGHT)
                .padding(horizontal = DebugDimens.spaceLg, vertical = DebugDimens.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DebugDisclosure(expanded = expanded)
            Spacer(Modifier.width(DebugDimens.spaceXs))
            ProviderRowInfo(provider, Modifier.weight(1f).clickable(onClick = onToggleExpanded))
            provider.server?.let { server ->
                TextButton(onClick = onEdit) { Text("编辑") }
                if (server.deletable) TextButton(onClick = { actions.deleteMcpServer(server.id) }) { Text("删除", color = DebugColors.danger) }
            }
            Switch(
                checked = provider.enabled,
                onCheckedChange = { enabled -> actions.setMcpProviderEnabled(provider.id, enabled) },
                colors = debugSwitchColors(),
            )
        }
        if (provider.id == DEBUG_MCP_PROVIDER_DEVICE_MEDIA) MediaAccessLine()
        if (expanded) {
            McpToolPreviewList(provider, refreshKey = provider.server)
        }
    }
}

/**
 * Device Media's notification access, looked at again on return from the system page that grants it: with it 小博 sees
 * what plays and in which app; without it only play, pause, skipping and the volume reach whatever plays.
 */
@Composable
private fun MediaAccessLine() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var granted by remember { mutableStateOf(DeviceMediaMcpProvider.notificationAccessGranted(context)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = DeviceMediaMcpProvider.notificationAccessGranted(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = DebugDimens.spaceLg * 2, end = DebugDimens.spaceLg, bottom = DebugDimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (granted) "通知使用权已开：能看到在放什么、指定控制哪个 App" else "通知使用权未开：只能播放、暂停、切歌和调音量，看不到在放什么",
            style = DebugType.meta,
            color = if (granted) DebugColors.success else DebugColors.muted,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { runCatching { context.startActivity(DeviceMediaMcpProvider.notificationAccessIntent().addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
            Text(if (granted) "管理" else "去开启")
        }
    }
}

@Composable
private fun ProviderRowInfo(provider: DebugMcpProviderState, modifier: Modifier) {
    val colors = DebugColors
    val context = LocalContext.current
    val server = provider.server
    val secrets = remember(server) { server?.takeIf { it.kind == DebugMcpServerKind.HTTP }?.let { DebugMcpSecrets.read(context, it.id) } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceXxs)) {
        Text(provider.label, style = DebugType.body, color = colors.text, fontWeight = FontWeight.Medium)
        Text(
            if (provider.enabled) "Loaded in runtime" else "Not loaded",
            style = DebugType.meta,
            color = if (provider.enabled) colors.success else colors.muted,
        )
        server?.let { Text(serverLine(it, secrets), style = DebugType.meta, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** Where the server is, and whether a token or headers are stored (never what they are). */
private fun serverLine(server: DebugMcpServer, secrets: DebugMcpSecrets.Stored?): String =
    listOfNotNull(
        server.summary(),
        "令牌已设置".takeIf { secrets?.token?.isNotEmpty() == true },
        "密钥已设置".takeIf { secrets?.query?.isNotEmpty() == true },
        secrets?.headers?.size?.takeIf { it > 0 }?.let { "$it 个请求头" },
    ).joinToString(" · ")

@Composable
private fun McpToolPreviewList(provider: DebugMcpProviderState, refreshKey: Any? = null) {
    val colors = DebugColors
    val context = LocalContext.current
    val result by produceState<DebugMcpToolsResult?>(initialValue = null, provider.id, refreshKey) {
        value = loadMcpProviderTools(context, provider)
    }
    var filter by rememberSaveable(provider.id) { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = DebugDimens.spaceLg, end = DebugDimens.spaceLg, bottom = DebugDimens.spaceMd),
    ) {
        if (!provider.enabled) {
            Text(
                "Not loaded — enable to use these tools.",
                style = DebugType.meta,
                color = colors.warning,
            )
            Spacer(Modifier.height(DebugDimens.spaceXs))
        }
        when (val tools = result) {
            null -> Text("Loading tools…", style = DebugType.meta, color = colors.muted)
            is DebugMcpToolsResult.Unavailable -> Text(
                tools.reason,
                style = DebugType.meta,
                color = colors.danger,
            )
            is DebugMcpToolsResult.Loaded -> {
                LoadedMcpToolPreview(tools = tools, filter = filter, onFilterChange = { filter = it })
            }
        }
    }
}

@Composable
private fun LoadedMcpToolPreview(
    tools: DebugMcpToolsResult.Loaded,
    filter: String,
    onFilterChange: (String) -> Unit,
) {
    val colors = DebugColors
    if (tools.tools.isEmpty()) {
        Text("No tools reported.", style = DebugType.meta, color = colors.muted)
        return
    }
    val shown = tools.tools.filter {
        filter.isBlank() ||
            it.name.contains(filter, ignoreCase = true) ||
            it.title.contains(filter, ignoreCase = true)
    }
    McpToolFilterHeader(
        shownCount = shown.size,
        totalCount = tools.tools.size,
        filter = filter,
        onFilterChange = onFilterChange,
    )
    Spacer(Modifier.height(DebugDimens.spaceXs))
    Surface(
        shape = RoundedCornerShape(DebugDimens.radiusSm),
        color = colors.codeBg,
        border = BorderStroke(1.dp, colors.borderSubtle),
    ) {
        LazyColumn(Modifier.heightIn(max = MCP_TOOL_LIST_MAX_HEIGHT)) {
            items(shown, key = { it.name }) { tool -> McpToolRow(tool) }
        }
    }
}

@Composable
private fun McpToolFilterHeader(
    shownCount: Int,
    totalCount: Int,
    filter: String,
    onFilterChange: (String) -> Unit,
) {
    val colors = DebugColors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$shownCount / $totalCount tools",
            style = DebugType.meta,
            color = colors.muted,
            modifier = Modifier.weight(1f),
        )
        if (totalCount > MCP_FILTER_MIN_TOOLS) {
            DebugMessageField(
                filter,
                onFilterChange,
                true,
                Modifier.widthIn(max = MCP_FILTER_FIELD_MAX_WIDTH),
                placeholder = "Filter tools…",
            )
        }
    }
}

@Composable
private fun McpToolRow(tool: DebugMcpToolPreview) {
    val colors = DebugColors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DebugDimens.spaceMd, vertical = DebugDimens.spaceXs),
    ) {
        Text(
            tool.name,
            style = DebugType.code,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (tool.title != tool.name || tool.description.isNotBlank()) {
            Text(
                listOf(tool.title.takeIf { it != tool.name }, tool.description.takeIf { it.isNotBlank() })
                    .filterNotNull()
                    .joinToString(" · "),
                style = DebugType.meta,
                color = colors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val MCP_ROW_HEIGHT = 56.dp
private val MCP_PROVIDERS_CONTENT_MAX_WIDTH = 920.dp
private val MCP_TOOL_LIST_MAX_HEIGHT = 360.dp
private val MCP_FILTER_FIELD_MAX_WIDTH = 260.dp
private const val MCP_FILTER_MIN_TOOLS = 8
