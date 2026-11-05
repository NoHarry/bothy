package cc.noharry.bothy.app.mcp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.IdParams
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.BothyAlertDialog
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.outlinedTextFieldColors
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.app.frontend.ServerAccess
import cc.noharry.bothy.protocol.ConnectorCatalog
import cc.noharry.bothy.protocol.ConnectorPage
import cc.noharry.bothy.protocol.ConnectorRequests
import cc.noharry.bothy.protocol.ConnectorSignInParams
import cc.noharry.bothy.protocol.ConnectorStatus
import cc.noharry.bothy.protocol.ConnectorView
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Sidebar footer: a compact settings button next to the 连接应用 pill (the pill shows how many apps are connected).
 */
@Composable
internal fun SidebarFooter(
    settingsSelected: Boolean,
    onShowSettings: () -> Unit,
    onShowA2aProfiles: () -> Unit,
) {
    val colors = ConsoleColors
    val settingsLabel = stringResource(R.string.common_settings)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(FOOTER_HEIGHT)
                .clip(CircleShape)
                .background(if (settingsSelected) colors.accentSoft else Color.Transparent)
                .clickable(onClick = onShowSettings)
                .semantics { role = Role.Button; contentDescription = settingsLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = null,
                tint = if (settingsSelected) colors.accent else colors.muted,
                modifier = Modifier.size(FOOTER_ICON_SIZE),
            )
        }
        Spacer(Modifier.width(Dimens.spaceXs))
        ConnectAppsPill(onShowA2aProfiles, Modifier.weight(1f))
    }
}

/**
 * 连接应用's pill, which opens the market: what is connected, or with nothing connected a few of the apps on offer. The
 * sidebar's foot carries it, and on a phone also the empty conversation, which tells the user to start there.
 */
@Composable
internal fun ConnectAppsPill(onShowA2aProfiles: () -> Unit, modifier: Modifier = Modifier) {
    val server = LocalServer.current
    var showConnectors by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf(ConnectorCatalog()) }
    val connected = catalog.connectors.filter { catalog.statuses[it.id] is ConnectorStatus.Connected }
    val shown = connected.ifEmpty { catalog.connectors }.take(PILL_AVATARS)
    // Check with the relay when the console shows and after the dialog closes: a device disconnected from the bot's
    // side loses its A2A profile here instead of keeping a delegation target that no longer answers.
    LaunchedEffect(showConnectors) {
        val closed = !showConnectors
        server.send(ConnectorRequests.LIST, Empty) { listed ->
            catalog = listed
            if (closed) server.send(ConnectorRequests.REFRESH_ALL, Empty) { catalog = catalog.copy(statuses = it.statuses) }
        }
    }
    PillFace(shown, connected.size, modifier) { showConnectors = true }
    if (showConnectors) {
        ConnectorsDialog(
            onDismiss = { showConnectors = false },
            onShowA2aProfiles = {
                showConnectors = false
                onShowA2aProfiles()
            },
        )
    }
}

/** The pill itself: its label, a few app icons, and how many are connected. */
@Composable
private fun PillFace(shown: List<ConnectorView>, connected: Int, modifier: Modifier, onClick: () -> Unit) {
    val colors = ConsoleColors
    Row(
        modifier
            .height(FOOTER_HEIGHT)
            .clip(RoundedCornerShape(Dimens.radiusFull))
            .border(1.dp, colors.border, RoundedCornerShape(Dimens.radiusFull))
            .background(colors.elevated)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = Dimens.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.mcp_connect_apps),
            style = ConsoleType.body,
            color = colors.text,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Spacer(Modifier.width(Dimens.spaceXsMd))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXxs)) { shown.forEach { ConnectorAvatar(it, PILL_AVATAR_SIZE) } }
        if (connected > 0) {
            Spacer(Modifier.width(Dimens.spaceXs))
            Text("$connected", style = ConsoleType.meta, color = colors.success)
        }
    }
}

/** 连接应用: the market, and for one app its own page (an MCP app's key, an agent's pairing). */
@Composable
internal fun ConnectorsDialog(
    onDismiss: () -> Unit,
    onShowA2aProfiles: () -> Unit,
) {
    val colors = ConsoleColors
    val server = LocalServer.current
    var connectors by remember { mutableStateOf(emptyList<ConnectorView>()) }
    val statuses = remember { mutableStateMapOf<String, ConnectorStatus>() }
    var selected by remember { mutableStateOf<ConnectorView?>(null) }
    LaunchedEffect(Unit) {
        server.send(ConnectorRequests.LIST, Empty) { listed ->
            connectors = listed.connectors
            statuses.putAll(listed.statuses)
            listed.connectors.filter { listed.statuses[it.id] !is ConnectorStatus.Disconnected }.forEach { connector ->
                server.send(ConnectorRequests.REFRESH, IdParams(connector.id)) { statuses[connector.id] = it }
            }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            // Up to a comfortable reading size on a wide car display; nearly full screen on small ones.
            modifier = Modifier
                .padding(Dimens.spaceXl)
                .widthIn(max = DIALOG_MAX_WIDTH)
                .heightIn(max = DIALOG_MAX_HEIGHT)
                .fillMaxWidth()
                .fillMaxHeight(),
            shape = RoundedCornerShape(Dimens.radiusLg),
            color = colors.panel,
        ) {
            Column(Modifier.padding(Dimens.spaceXl)) {
                val current = selected
                if (current == null) {
                    ConnectorMarket(connectors, statuses, onOpen = { selected = it }, onShowA2aProfiles, onDismiss)
                } else if (current.app != null) {
                    McpConnectorDetail(
                        connector = current,
                        status = statuses[current.id] ?: ConnectorStatus.Disconnected,
                        onStatus = { statuses[current.id] = it },
                        onBack = { selected = null },
                    )
                } else {
                    ConnectorDetail(
                        connector = current,
                        status = statuses[current.id] ?: ConnectorStatus.Disconnected,
                        onStatus = { statuses[current.id] = it },
                        onBack = { selected = null },
                    )
                }
            }
        }
    }
}

/** 连接应用 as an app market: a card per app under its category, tapped for the app's own page. */
@Composable
private fun ColumnScope.ConnectorMarket(
    connectors: List<ConnectorView>,
    statuses: Map<String, ConnectorStatus>,
    onOpen: (ConnectorView) -> Unit,
    onShowA2aProfiles: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ConsoleColors
    var query by remember { mutableStateOf("") }
    val filterAll = stringResource(R.string.mcp_filter_all)
    val filterCustom = stringResource(R.string.mcp_filter_custom)
    // Everything, each category in catalog order, then 自定义; null is everything.
    var filter by remember { mutableStateOf<String?>(null) }
    val connected = statuses.values.count { it is ConnectorStatus.Connected }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.mcp_connect_apps),
            style = ConsoleType.hero,
            color = colors.text,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (connected > 0) {
                pluralStringResource(R.plurals.mcp_connected_count, connected, connected)
            } else {
                stringResource(R.string.mcp_none_connected)
            },
            style = ConsoleType.meta,
            color = if (connected > 0) colors.success else colors.muted,
        )
        Spacer(Modifier.width(Dimens.spaceSm))
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close), tint = colors.muted)
        }
    }
    Spacer(Modifier.height(Dimens.spaceMd))
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text(stringResource(R.string.mcp_search_placeholder)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = colors.muted) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = outlinedTextFieldColors(),
    )
    Spacer(Modifier.height(Dimens.spaceMd))
    val filters = listOf(filterAll) + connectors.map { it.category }.distinct() + filterCustom
    FilterChips(filters, filter ?: filterAll) { filter = it.takeIf { chosen -> chosen != filterAll } }
    val words = query.trim()
    val apps = connectors.filter { (filter == null || it.category == filter) && it.matches(words) }
    val customTitle = stringResource(R.string.mcp_custom_a2a_title)
    val customSummary = stringResource(R.string.mcp_custom_a2a_summary)
    val customShown = (filter == null || filter == filterCustom) &&
        (words.isEmpty() || customTitle.contains(words, true) || customSummary.contains(words, true))
    ConnectorGrid(
        apps, customShown, statuses, onOpen, onShowA2aProfiles,
        customTitle = filterCustom,
        emptyText = stringResource(R.string.mcp_no_results, words),
    )
}

@Composable
private fun FilterChips(filters: List<String>, selected: String, onSelect: (String) -> Unit) {
    val colors = ConsoleColors
    // One line that scrolls sideways: on a phone the chips would otherwise squeeze into letter-wide columns.
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        filters.forEach { entry ->
            val active = entry == selected
            Text(
                entry,
                style = ConsoleType.body,
                maxLines = 1,
                softWrap = false,
                color = if (active) colors.panel else colors.text,
                modifier = Modifier
                    .clip(RoundedCornerShape(Dimens.radiusFull))
                    .background(if (active) colors.ink else colors.elevated)
                    .clickable { onSelect(entry) }
                    .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceXsMd),
            )
        }
    }
}

/** Matches the app's saved name too, so a brand is found in either language. */
private fun ConnectorView.matches(words: String): Boolean =
    words.isEmpty() || name.contains(words, true) ||
        listOf(title, summary, category).any { it.contains(words, true) } ||
        app?.abilities?.any { it.contains(words, true) } == true

@Composable
private fun ColumnScope.ConnectorGrid(
    apps: List<ConnectorView>,
    customShown: Boolean,
    statuses: Map<String, ConnectorStatus>,
    onOpen: (ConnectorView) -> Unit,
    onShowA2aProfiles: () -> Unit,
    customTitle: String,
    emptyText: String,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(CARD_MIN_WIDTH),
        modifier = Modifier.weight(1f),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
    ) {
        apps.groupBy { it.category }.forEach { (category, members) ->
            item(key = "category-$category", span = { GridItemSpan(maxLineSpan) }) {
                ConnectorSectionTitle(category)
            }
            items(members, key = { it.id }) { connector ->
                ConnectorCard(
                    avatar = { ConnectorAvatar(connector, CARD_AVATAR_SIZE) },
                    title = connector.title,
                    summary = connector.summary,
                    status = { StatusChip(statuses[connector.id] ?: ConnectorStatus.Disconnected) },
                    onClick = { onOpen(connector) },
                )
            }
        }
        if (customShown) {
            item(key = "category-custom", span = { GridItemSpan(maxLineSpan) }) {
                ConnectorSectionTitle(customTitle)
            }
            item(key = "custom-a2a") {
                ConnectorCard(
                    avatar = { LetterAvatar("A2A", ConsoleColors.inkSoft, CARD_AVATAR_SIZE, 13.sp, APP_ICON_SHAPE) },
                    title = stringResource(R.string.mcp_custom_a2a_title),
                    summary = stringResource(R.string.mcp_custom_a2a_summary),
                    status = { ChipText(stringResource(R.string.mcp_manage), ConsoleColors.text) },
                    onClick = onShowA2aProfiles,
                )
            }
        }
        if (apps.isEmpty() && !customShown) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Text(emptyText, style = ConsoleType.body, color = ConsoleColors.muted, modifier = Modifier.padding(top = Dimens.spaceXl))
            }
        }
    }
}

/** One app in the market: icon, name and what it does, with where it stands at the bottom. */
@Composable
private fun ConnectorCard(
    avatar: @Composable () -> Unit,
    title: String,
    summary: String,
    status: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val colors = ConsoleColors
    Column(
        Modifier
            .fillMaxWidth()
            .height(CARD_HEIGHT)
            .clip(RoundedCornerShape(Dimens.radiusLg))
            .border(1.dp, colors.border, RoundedCornerShape(Dimens.radiusLg))
            .background(colors.elevated)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(Dimens.spaceLg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            avatar()
            Spacer(Modifier.width(Dimens.spaceMd))
            Text(title, style = ConsoleType.panelTitle, color = colors.text, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(Dimens.spaceSm))
        Text(summary, style = ConsoleType.bodySmall, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { status() }
    }
}

@Composable
private fun StatusChip(status: ConnectorStatus) {
    val colors = ConsoleColors
    when (status) {
        is ConnectorStatus.Connected -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = colors.success, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Dimens.spaceXs))
            Text(stringResource(R.string.mcp_status_connected), style = ConsoleType.body, color = colors.success)
        }
        ConnectorStatus.Paused -> ChipText(stringResource(R.string.mcp_status_paused), colors.muted)
        is ConnectorStatus.Pairing -> ChipText(stringResource(R.string.mcp_status_pairing), colors.accent)
        ConnectorStatus.Disconnected -> ChipText(stringResource(R.string.common_connect), colors.text)
    }
}

@Composable
private fun ChipText(text: String, color: Color) {
    Text(
        text,
        style = ConsoleType.body,
        color = color,
        modifier = Modifier
            .border(1.dp, ConsoleColors.border, RoundedCornerShape(Dimens.radiusFull))
            .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceXs),
    )
}

@Composable
internal fun ConnectorSectionTitle(text: String) {
    Text(
        text,
        style = ConsoleType.panelTitle,
        color = ConsoleColors.text,
        modifier = Modifier.padding(top = Dimens.spaceXl, bottom = Dimens.spaceSm),
    )
}

/** An agent's page: pairing through the relay, the connection test, unpairing and the account. */
@Composable
private fun ColumnScope.ConnectorDetail(
    connector: ConnectorView,
    status: ConnectorStatus,
    onStatus: (ConnectorStatus) -> Unit,
    onBack: () -> Unit,
) {
    val server = LocalServer.current
    val title = connector.title
    val page = rememberAgentPage(connector.id, server, onStatus)
    val disconnecting = stringResource(R.string.mcp_disconnecting)
    var confirmDisconnect by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var info by remember { mutableStateOf<ConnectorPage?>(null) }
    LaunchedEffect(status, reload) { server.send(ConnectorRequests.PAGE, IdParams(connector.id)) { info = it } }
    page.onChanged = { reload += 1 }
    // While pairing, ask the relay every few seconds whether the phone finished; the page needs no car input.
    LaunchedEffect(connector.id, status is ConnectorStatus.Pairing) {
        while (status is ConnectorStatus.Pairing) {
            delay(PAIRING_POLL_MS)
            server.send(ConnectorRequests.REFRESH, IdParams(connector.id)) { if (it != status) onStatus(it) }
        }
    }
    AgentHeader(connector, info?.relayHost.orEmpty(), onBack)
    Spacer(Modifier.height(Dimens.spaceXl))
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        when (status) {
            ConnectorStatus.Disconnected -> {
                Text(stringResource(R.string.mcp_agent_intro, title), style = ConsoleType.body, color = ConsoleColors.text)
                Spacer(Modifier.height(Dimens.spaceLg))
                Button(onClick = { page.change(ConnectorRequests.CONNECT) }, enabled = !page.busy) {
                    Text(stringResource(R.string.mcp_start_connecting))
                }
            }
            ConnectorStatus.Paused -> Unit
            is ConnectorStatus.Pairing -> PairingPanel(
                status = status,
                busy = page.busy,
                onRefresh = { page.change(ConnectorRequests.NEW_CODE) },
                onCancel = { page.unpair(disconnecting) },
            )
            is ConnectorStatus.Connected -> ConnectedAgentPanel(title, status, page) { confirmDisconnect = true }
        }
        AgentProgress(page)
        AccountPanel(
            title = title,
            account = info?.account,
            connected = status is ConnectorStatus.Connected,
            accountPage = info?.accountPage.orEmpty(),
            busy = page.busy,
            onSignIn = { name, password, register -> page.signIn(name, password, register) },
            onSignOut = { page.signOut() },
        )
        ConnectionEvents(info?.events.orEmpty())
    }
    if (confirmDisconnect) {
        UnpairDialog(title, onDismiss = { confirmDisconnect = false }) {
            confirmDisconnect = false
            page.unpair(disconnecting)
        }
    }
}

/**
 * What an agent's page is doing: one change at a time ([busy]), its last error, the unpairing's progress and what it
 * said, and the connection test. [onChanged] hears that the page's events and account may have moved.
 */
private class AgentPageState(
    private val id: String,
    private val server: ServerAccess,
    private val onStatus: (ConnectorStatus) -> Unit,
) {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var progress by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var testing by mutableStateOf(false)
    var testResult by mutableStateOf<Pair<Boolean, String>?>(null)
    var onChanged: () -> Unit = {}

    fun change(method: RequestMethod<IdParams, ConnectorStatus>) = act { refused, answered ->
        server.send(method, IdParams(id), refused, answered)
    }

    fun signIn(name: String, password: String, register: Boolean) = act { refused, answered ->
        server.send(ConnectorRequests.SIGN_IN, ConnectorSignInParams(id, name, password, register), refused, answered)
    }

    fun signOut() {
        server.send(ConnectorRequests.SIGN_OUT, IdParams(id), onRefused = { error = it.message; onChanged() }) { onChanged() }
    }

    fun unpair(working: String? = null) {
        if (busy) return
        busy = true
        error = null
        progress = working
        server.send(ConnectorRequests.DISCONNECT, IdParams(id), onRefused = { finishUnpair(it.message.orEmpty()) }) {
            finishUnpair(it.text)
        }
    }

    fun test() {
        testing = true
        testResult = null
        server.send(ConnectorRequests.TEST, IdParams(id), onRefused = { tested(false, it.message.orEmpty()) }) {
            tested(true, it.text)
            onStatus(ConnectorStatus.Connected(verified = true))
        }
    }

    private fun tested(ok: Boolean, text: String) {
        testResult = ok to text
        testing = false
        onChanged()
    }

    private fun finishUnpair(said: String) {
        notice = said
        progress = null
        onStatus(ConnectorStatus.Disconnected)
        busy = false
        onChanged()
    }

    private fun act(request: (onRefused: (JsonRpcException) -> Unit, onAnswer: (ConnectorStatus) -> Unit) -> Unit) {
        if (busy) return
        busy = true
        error = null
        request({ error = it.message; busy = false; onChanged() }) { next ->
            onStatus(next)
            busy = false
            onChanged()
        }
    }
}

@Composable
private fun rememberAgentPage(id: String, server: ServerAccess, onStatus: (ConnectorStatus) -> Unit): AgentPageState {
    val current by rememberUpdatedState(onStatus)
    return remember(id) { AgentPageState(id, server) { current(it) } }
}

@Composable
private fun AgentHeader(connector: ConnectorView, relayHost: String, onBack: () -> Unit) {
    val colors = ConsoleColors
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_back),
                tint = colors.muted,
            )
        }
        ConnectorAvatar(connector, ROW_AVATAR_SIZE)
        Spacer(Modifier.width(Dimens.spaceMd))
        Column(Modifier.weight(1f)) {
            Text(connector.title, style = ConsoleType.hero, color = colors.text)
            Text(connector.summary, style = ConsoleType.bodySmall, color = colors.muted)
            Text(stringResource(R.string.mcp_via_relay, relayHost), style = ConsoleType.meta, color = colors.tertiary)
        }
    }
}

@Composable
private fun ConnectedAgentPanel(
    title: String,
    status: ConnectorStatus.Connected,
    page: AgentPageState,
    onDisconnect: () -> Unit,
) {
    val colors = ConsoleColors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = colors.success)
        Spacer(Modifier.width(Dimens.spaceSm))
        Text(stringResource(R.string.mcp_connected_to_your, title), style = ConsoleType.panelTitle, color = colors.text)
    }
    Spacer(Modifier.height(Dimens.spaceSm))
    Text(
        stringResource(if (status.verified) R.string.mcp_agent_verified else R.string.mcp_agent_unverified, title),
        style = ConsoleType.body,
        color = colors.muted,
    )
    Spacer(Modifier.height(Dimens.spaceLg))
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { page.test() }, enabled = !page.busy && !page.testing) {
            Text(stringResource(R.string.mcp_test_connection))
        }
        OutlinedButton(onClick = onDisconnect, enabled = !page.busy && !page.testing) {
            Text(stringResource(R.string.mcp_unpair), color = colors.danger)
        }
    }
    if (page.testing) {
        Spacer(Modifier.height(Dimens.spaceSm))
        Text(stringResource(R.string.mcp_test_waiting, title), style = ConsoleType.body, color = colors.muted)
    }
    page.testResult?.let { (ok, text) ->
        Spacer(Modifier.height(Dimens.spaceSm))
        Text(
            if (ok) stringResource(R.string.mcp_test_reply, title, text) else text,
            style = ConsoleType.body,
            color = if (ok) colors.success else colors.danger,
        )
    }
}

/** The spinner (with what unpairing is doing), what the last change said, and its error. */
@Composable
private fun AgentProgress(page: AgentPageState) {
    val colors = ConsoleColors
    if (page.busy) {
        Spacer(Modifier.height(Dimens.spaceMd))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            page.progress?.let {
                Spacer(Modifier.width(Dimens.spaceSm))
                Text(it, style = ConsoleType.body, color = colors.muted)
            }
        }
    }
    page.notice?.let {
        Spacer(Modifier.height(Dimens.spaceMd))
        Text(it, style = ConsoleType.body, color = colors.text)
    }
    page.error?.let {
        Spacer(Modifier.height(Dimens.spaceMd))
        Text(it, style = ConsoleType.body, color = colors.danger)
    }
}

@Composable
private fun UnpairDialog(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    BothyAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mcp_unpair_title, title)) },
        text = { Text(stringResource(R.string.mcp_unpair_message, title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.mcp_unpair), color = ConsoleColors.danger) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * 账号 (owner 2026-10-12): signed in, the account name and where to manage it; signed out, a name and password to sign
 * in or register. Signing in on a second car joins the account's connection, so it needs no QR code or MCP token.
 */
@Composable
private fun AccountPanel(
    title: String,
    account: String?,
    connected: Boolean,
    accountPage: String,
    busy: Boolean,
    onSignIn: (name: String, password: String, register: Boolean) -> Unit,
    onSignOut: () -> Unit,
) {
    val colors = ConsoleColors
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    // Typed on a device screen it is easy to mistype; owner 2026-10-12: 「密码加个按钮可以看见」.
    var showPassword by remember { mutableStateOf(false) }
    ConnectorSectionTitle(stringResource(R.string.mcp_account))
    if (account != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.mcp_signed_in_as, account),
                style = ConsoleType.panelTitle,
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onSignOut, enabled = !busy) { Text(stringResource(R.string.mcp_sign_out)) }
        }
        Spacer(Modifier.height(Dimens.spaceSm))
        Text(
            stringResource(R.string.mcp_account_signed_in_hint, title, accountPage),
            style = ConsoleType.body,
            color = colors.muted,
        )
        return
    }
    Text(
        if (connected) {
            stringResource(R.string.mcp_account_register_hint)
        } else {
            stringResource(R.string.mcp_account_sign_in_hint, title)
        },
        style = ConsoleType.body,
        color = colors.muted,
    )
    Spacer(Modifier.height(Dimens.spaceSm))
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.mcp_username)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            colors = outlinedTextFieldColors(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.mcp_password)) },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showPassword = !showPassword }) {
                    Text(stringResource(if (showPassword) R.string.mcp_hide else R.string.mcp_show))
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.weight(1f),
            colors = outlinedTextFieldColors(),
        )
    }
    Spacer(Modifier.height(Dimens.spaceSm))
    val filled = name.isNotBlank() && password.isNotEmpty()
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        Button(onClick = { onSignIn(name, password, false); password = "" }, enabled = !busy && filled) {
            Text(stringResource(R.string.mcp_sign_in))
        }
        OutlinedButton(onClick = { onSignIn(name, password, true); password = "" }, enabled = !busy && filled) {
            Text(stringResource(R.string.mcp_register))
        }
    }
}

@Composable
private fun PairingPanel(status: ConnectorStatus.Pairing, busy: Boolean, onRefresh: () -> Unit, onCancel: () -> Unit) {
    val colors = ConsoleColors
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status.expiresAtMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(CLOCK_TICK_MS)
        }
    }
    val usable = status.code.isNotEmpty() && status.expiresAtMillis > now
    Row(verticalAlignment = Alignment.Top) {
        PairingQr(status.connectUrl, usable)
        Spacer(Modifier.width(Dimens.spaceXl))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.mcp_pairing_scan), style = ConsoleType.panelTitle, color = colors.text)
            Spacer(Modifier.height(Dimens.spaceSm))
            Text(
                stringResource(R.string.mcp_pairing_manual, status.connectUrl.substringBefore('?')),
                style = ConsoleType.body,
                color = colors.muted,
            )
            Text(
                status.code.ifEmpty { "————" },
                fontFamily = FontFamily.Monospace,
                fontSize = CODE_FONT_SIZE,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 4.sp,
                color = if (usable) colors.text else colors.tertiary,
                modifier = Modifier.padding(vertical = Dimens.spaceSm),
            )
            val validity = if (usable) stringResource(R.string.mcp_pairing_valid_until, clockTime(status.expiresAtMillis))
            else stringResource(R.string.mcp_pairing_expired)
            Text(validity, style = ConsoleType.bodySmall, color = colors.muted)
            Spacer(Modifier.height(Dimens.spaceLg))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
                OutlinedButton(onClick = onRefresh, enabled = !busy) { Text(stringResource(R.string.mcp_refresh_code)) }
                TextButton(onClick = onCancel, enabled = !busy) {
                    Text(stringResource(R.string.common_cancel), color = colors.danger)
                }
            }
            Spacer(Modifier.height(Dimens.spaceXl))
            PairingSteps()
        }
    }
}

/** The latest connection events on this car (connected, tests, disconnects), newest first. */
@Composable
internal fun ConnectionEvents(events: List<String>) {
    if (events.isEmpty()) return
    val colors = ConsoleColors
    Spacer(Modifier.height(Dimens.spaceXl))
    Text(stringResource(R.string.mcp_recent_events), style = ConsoleType.meta, color = colors.muted)
    events.forEach { Text(it, style = ConsoleType.bodySmall, color = colors.text, modifier = Modifier.padding(top = Dimens.spaceXs)) }
}

@Composable
private fun PairingQr(link: String, usable: Boolean) {
    Box(
        Modifier
            .size(QR_SIZE)
            .clip(RoundedCornerShape(Dimens.radiusMd))
            .background(Color.White)
            .padding(Dimens.spaceMd),
        contentAlignment = Alignment.Center,
    ) {
        if (usable) QrCode(link, Modifier.fillMaxWidth().fillMaxHeight())
        else Text(
            stringResource(R.string.mcp_pairing_code_invalid),
            style = ConsoleType.body,
            color = Color(0xFF6B6B66),
        )
    }
}

/** What the phone page walks the user through; the bot does the routine, the claim and the connector itself. */
@Composable
private fun PairingSteps() {
    val colors = ConsoleColors
    Text(stringResource(R.string.mcp_phone_steps), style = ConsoleType.meta, color = colors.muted)
    listOf(
        R.string.mcp_phone_step_1,
        R.string.mcp_phone_step_2,
        R.string.mcp_phone_step_3,
    ).forEachIndexed { index, step ->
        Text(
            "${index + 1}. ${stringResource(step)}",
            style = ConsoleType.bodySmall,
            color = colors.text,
            modifier = Modifier.padding(top = Dimens.spaceXs),
        )
    }
}

/** The QR code of [text], drawn module by module (no quiet zone: the caller pads it on white). */
@Composable
internal fun QrCode(text: String, modifier: Modifier = Modifier) {
    val modules = remember(text) { qrModules(text) }
    val description = stringResource(R.string.mcp_pairing_qr)
    Canvas(modifier.semantics { contentDescription = description }) {
        if (modules.isEmpty()) return@Canvas
        val cell = minOf(size.width, size.height) / modules.size
        val origin = Offset((size.width - cell * modules.size) / 2, (size.height - cell * modules.size) / 2)
        modules.forEachIndexed { y, row ->
            row.forEachIndexed { x, dark ->
                if (dark) drawRect(Color.Black, origin + Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/** Rows of dark (true) and light modules for [text], error correction M; empty when it does not fit a QR code. */
internal fun qrModules(text: String): List<BooleanArray> = runCatching {
    val matrix = Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")).matrix
    List(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix.get(x, y).toInt() == 1 } }
}.getOrDefault(emptyList())

/** An app's icon: its mark on its colour, a rounded square like an app icon (a dot in the sidebar pill). */
@Composable
internal fun ConnectorAvatar(connector: ConnectorView, size: androidx.compose.ui.unit.Dp) {
    val small = size < ROW_AVATAR_SIZE
    LetterAvatar(
        connector.mark, Color(connector.color), size, if (small) 10.sp else 22.sp, if (small) CircleShape else APP_ICON_SHAPE,
    )
}

@Composable
private fun LetterAvatar(
    letters: String,
    background: Color,
    size: androidx.compose.ui.unit.Dp,
    fontSize: androidx.compose.ui.unit.TextUnit,
    shape: androidx.compose.ui.graphics.Shape = CircleShape,
) {
    Box(Modifier.size(size).clip(shape).background(background), contentAlignment = Alignment.Center) {
        Text(letters, color = Color.White, fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

private fun clockTime(millis: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(millis))

private const val PAIRING_POLL_MS = 3_000L
private const val CLOCK_TICK_MS = 15_000L
private val DIALOG_MAX_WIDTH = 1280.dp
private val DIALOG_MAX_HEIGHT = 1000.dp
private val FOOTER_HEIGHT = 36.dp
private val FOOTER_ICON_SIZE = 18.dp
private val PILL_AVATAR_SIZE = 16.dp
private val ROW_AVATAR_SIZE = 44.dp
private val CARD_AVATAR_SIZE = 48.dp
private val CARD_MIN_WIDTH = 300.dp
private val CARD_HEIGHT = 150.dp
private val APP_ICON_SHAPE = RoundedCornerShape(12.dp)
private const val PILL_AVATARS = 3
private val QR_SIZE = 240.dp
private val CODE_FONT_SIZE = 30.sp
