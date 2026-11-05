package cc.noharry.bothy.app.mcp
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.IdParams
import cc.noharry.agent.appserver.protocol.McpProviderState
import cc.noharry.agent.appserver.protocol.McpRequests
import cc.noharry.agent.appserver.protocol.McpSecretsView
import cc.noharry.agent.appserver.protocol.McpServer
import cc.noharry.agent.appserver.protocol.McpServerKind
import cc.noharry.agent.appserver.protocol.McpStatusView
import cc.noharry.agent.appserver.protocol.McpToolPreview
import cc.noharry.agent.appserver.protocol.McpToolsView
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.text
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.MessageField
import cc.noharry.bothy.app.console.SettingsChildPanel
import cc.noharry.bothy.app.console.SettingsNavBar
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.switchColors
import cc.noharry.bothy.app.console.ui.Disclosure
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.app.words

/**
 * 设置 → 连接 → MCP: the providers with their switches and tool previews, and the configured standard MCP servers,
 * which can be added, edited and deleted. `editing` is the form's server id, `""` for a
 * new one, null for the list.
 */
@Composable
internal fun McpProvidersPane(
    state: ServerState,
    actions: ConsoleActions,
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
                    editing == null -> stringResource(R.string.mcp_providers_title)
                    editedServer == null -> stringResource(R.string.mcp_add_server_title)
                    else -> stringResource(R.string.mcp_edit_server_title, editedServer.label)
                },
                breadcrumb = stringResource(
                    when {
                        editing == null -> R.string.mcp_breadcrumb
                        editedServer == null -> R.string.mcp_breadcrumb_add
                        else -> R.string.mcp_breadcrumb_edit
                    },
                ),
                backLabel = stringResource(R.string.common_back),
                onBack = { if (editing != null) editing = null else onBack() },
                hint = stringResource(R.string.mcp_providers_hint),
            )
            Spacer(Modifier.height(Dimens.spaceSm))
            HorizontalDivider(color = ConsoleColors.borderSubtle)
            Spacer(Modifier.height(Dimens.spaceMd))
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
    state: ServerState,
    actions: ConsoleActions,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ConsoleColors
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    val status = rememberMcpStatus(state.mcpProviders)
    Column(modifier) {
        // The add button leads the row: the 小博 bubble rests on the top-right corner of the screen and covered it there.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onAdd) { Text(stringResource(R.string.mcp_add_server)) }
            Text(
                stringResource(
                    R.string.mcp_loaded_count,
                    state.mcpProviders.count { it.enabled },
                    state.mcpProviders.size,
                ),
                style = ConsoleType.meta,
                color = colors.muted,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(Dimens.spaceSm))
        Surface(
            modifier = Modifier.weight(1f).widthIn(max = MCP_PROVIDERS_CONTENT_MAX_WIDTH),
            shape = RoundedCornerShape(Dimens.radiusMd),
            color = colors.panel,
            border = BorderStroke(1.dp, colors.borderSubtle),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            LazyColumn {
                state.mcpProviders.forEachIndexed { index, provider ->
                    item(key = provider.id) {
                        if (index > 0) HorizontalDivider(color = colors.borderSubtle, modifier = Modifier.padding(horizontal = Dimens.spaceMd))
                        ProviderToggleRow(
                            provider = provider,
                            status = status,
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
    provider: McpProviderState,
    status: McpStatusView,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    actions: ConsoleActions,
    onEdit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = MCP_ROW_HEIGHT)
                .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Disclosure(expanded = expanded)
            Spacer(Modifier.width(Dimens.spaceXs))
            ProviderRowInfo(provider, status, Modifier.weight(1f).clickable(onClick = onToggleExpanded))
            provider.server?.let { server ->
                TextButton(onClick = onEdit) { Text(stringResource(R.string.common_edit)) }
                if (server.deletable) {
                    TextButton(onClick = { actions.deleteMcpServer(server.id) }) {
                        Text(stringResource(R.string.common_delete), color = ConsoleColors.danger)
                    }
                }
            }
            Switch(
                checked = provider.enabled,
                onCheckedChange = { enabled -> actions.setMcpProviderEnabled(provider.id, enabled) },
                colors = switchColors(),
            )
        }
        if (provider.id == status.mediaProviderId) MediaAccessLine(status.mediaAccess)
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
private fun MediaAccessLine(granted: Boolean) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(start = Dimens.spaceLg * 2, end = Dimens.spaceLg, bottom = Dimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(if (granted) R.string.mcp_media_access_on else R.string.mcp_media_access_off),
            style = ConsoleType.meta,
            color = if (granted) ConsoleColors.success else ConsoleColors.muted,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { runCatching { context.startActivity(notificationAccessIntent()) } }) {
            Text(stringResource(if (granted) R.string.mcp_manage else R.string.mcp_turn_on))
        }
    }
}

@Composable
private fun ProviderRowInfo(provider: McpProviderState, status: McpStatusView, modifier: Modifier) {
    val colors = ConsoleColors
    val server = provider.server
    val secrets = server?.takeIf { it.kind == McpServerKind.HTTP }?.let { status.secrets[it.id] }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.spaceXxs)) {
        Text(
            status.titles[provider.id] ?: provider.label,
            style = ConsoleType.body,
            color = colors.text,
            fontWeight = FontWeight.Medium,
        )
        Text(
            stringResource(if (provider.enabled) R.string.mcp_loaded_in_runtime else R.string.mcp_not_loaded),
            style = ConsoleType.meta,
            color = if (provider.enabled) colors.success else colors.muted,
        )
        server?.let { Text(serverLine(it, secrets), style = ConsoleType.meta, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** Where the server is, and whether a token or headers are stored (never what they are). */
@Composable
private fun serverLine(server: McpServer, secrets: McpSecretsView?): String {
    val headers = secrets?.headers ?: 0
    return listOfNotNull(
        server.summary(),
        stringResource(R.string.mcp_token_set).takeIf { secrets?.token == true },
        stringResource(R.string.mcp_key_set).takeIf { secrets?.query == true },
        pluralStringResource(R.plurals.mcp_header_count, headers, headers).takeIf { headers > 0 },
    ).joinToString(" · ")
}

@Composable
private fun McpToolPreviewList(provider: McpProviderState, refreshKey: Any? = null) {
    val colors = ConsoleColors
    val server = LocalServer.current
    var result by remember(provider.id, refreshKey) { mutableStateOf<McpToolsView?>(null) }
    LaunchedEffect(provider.id, refreshKey) {
        server.send(
            McpRequests.TOOLS,
            IdParams(provider.id),
            onRefused = { result = McpToolsView(unavailable = text(it.message.orEmpty())) },
        ) { result = it }
    }
    var filter by rememberSaveable(provider.id) { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = Dimens.spaceLg, end = Dimens.spaceLg, bottom = Dimens.spaceMd),
    ) {
        if (!provider.enabled) {
            Text(
                stringResource(R.string.mcp_not_loaded_hint),
                style = ConsoleType.meta,
                color = colors.warning,
            )
            Spacer(Modifier.height(Dimens.spaceXs))
        }
        val loaded = result
        val tools = loaded?.tools
        when {
            loaded == null -> Text(stringResource(R.string.mcp_loading_tools), style = ConsoleType.meta, color = colors.muted)
            tools == null -> Text(loaded.unavailable?.let { words(it) }.orEmpty(), style = ConsoleType.meta, color = colors.danger)
            else -> LoadedMcpToolPreview(tools = tools, filter = filter, onFilterChange = { filter = it })
        }
    }
}

@Composable
private fun LoadedMcpToolPreview(
    tools: List<McpToolPreview>,
    filter: String,
    onFilterChange: (String) -> Unit,
) {
    val colors = ConsoleColors
    if (tools.isEmpty()) {
        Text(stringResource(R.string.mcp_no_tools_reported), style = ConsoleType.meta, color = colors.muted)
        return
    }
    val shown = tools.filter {
        filter.isBlank() ||
            it.name.contains(filter, ignoreCase = true) ||
            it.title.contains(filter, ignoreCase = true)
    }
    McpToolFilterHeader(
        shownCount = shown.size,
        totalCount = tools.size,
        filter = filter,
        onFilterChange = onFilterChange,
    )
    Spacer(Modifier.height(Dimens.spaceXs))
    Surface(
        shape = RoundedCornerShape(Dimens.radiusSm),
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
    val colors = ConsoleColors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.mcp_tools_shown, shownCount, totalCount),
            style = ConsoleType.meta,
            color = colors.muted,
            modifier = Modifier.weight(1f),
        )
        if (totalCount > MCP_FILTER_MIN_TOOLS) {
            MessageField(
                filter,
                onFilterChange,
                true,
                Modifier.widthIn(max = MCP_FILTER_FIELD_MAX_WIDTH),
                placeholder = stringResource(R.string.mcp_filter_tools),
            )
        }
    }
}

@Composable
private fun McpToolRow(tool: McpToolPreview) {
    val colors = ConsoleColors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.spaceMd, vertical = Dimens.spaceXs),
    ) {
        Text(
            tool.name,
            style = ConsoleType.code,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (tool.title != tool.name || tool.description.isNotBlank()) {
            Text(
                listOf(tool.title.takeIf { it != tool.name }, tool.description.takeIf { it.isNotBlank() })
                    .filterNotNull()
                    .joinToString(" · "),
                style = ConsoleType.meta,
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

/**
 * The MCP status (provider names, what each server stores, whether Device Media has notification access): asked again
 * when the rows change and on return from the system page that grants the access.
 */
@Composable
internal fun rememberMcpStatus(rows: List<McpProviderState>): McpStatusView {
    val server = LocalServer.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var status by remember { mutableStateOf(McpStatusView()) }
    var resumed by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumed += 1 }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(rows, resumed) { server.send(McpRequests.STATUS, Empty) { status = it } }
    return status
}

/** The system page that grants notification access (Device Media then sees what plays). */
private fun notificationAccessIntent(): Intent =
    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
