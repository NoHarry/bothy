package cc.noharry.bothy.sample

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sidebar footer: a compact settings button next to the 连接应用 pill (the pill shows how many apps are connected).
 */
@Composable
internal fun DebugSidebarFooter(
    settingsSelected: Boolean,
    onShowSettings: () -> Unit,
    onShowA2aProfiles: () -> Unit,
) {
    val colors = DebugColors
    val context = LocalContext.current
    var showConnectors by remember { mutableStateOf(false) }
    val controller = remember { DebugConnectorController(context.applicationContext) }
    // The pill shows what is connected; with nothing connected, a few of the apps on offer. Read from what this car
    // knows at once, so the first frame does not show the wrong icons.
    val live = { DEBUG_CONNECTORS.filter { controller.status(it) is DebugConnectorStatus.Connected } }
    var connected by remember { mutableStateOf(live().size) }
    var shown by remember { mutableStateOf(live().ifEmpty { DEBUG_CONNECTORS }.take(PILL_AVATARS)) }
    fun count() {
        val now = live()
        connected = now.size
        shown = now.ifEmpty { DEBUG_CONNECTORS }.take(PILL_AVATARS)
    }
    // Check with the relay when the console shows and after the dialog closes: a device disconnected from the bot's
    // side loses its A2A profile here instead of keeping a delegation target that no longer answers.
    LaunchedEffect(showConnectors) {
        count()
        if (showConnectors) return@LaunchedEffect
        DEBUG_CONNECTORS.filter { controller.status(it) !is DebugConnectorStatus.Disconnected }
            .forEach { runCatching { controller.refresh(it) } }
        count()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(FOOTER_HEIGHT)
                .clip(CircleShape)
                .background(if (settingsSelected) colors.accentSoft else Color.Transparent)
                .clickable(onClick = onShowSettings)
                .semantics { role = Role.Button; contentDescription = "Settings" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = null,
                tint = if (settingsSelected) colors.accent else colors.muted,
                modifier = Modifier.size(FOOTER_ICON_SIZE),
            )
        }
        Spacer(Modifier.width(DebugDimens.spaceXs))
        Row(
            Modifier
                .weight(1f)
                .height(FOOTER_HEIGHT)
                .clip(RoundedCornerShape(DebugDimens.radiusFull))
                .border(1.dp, colors.border, RoundedCornerShape(DebugDimens.radiusFull))
                .background(colors.elevated)
                .clickable { showConnectors = true }
                .semantics { role = Role.Button }
                .padding(horizontal = DebugDimens.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("连接应用", style = DebugType.body, color = colors.text, fontWeight = FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.width(DebugDimens.spaceXsMd))
            Row(horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceXxs)) { shown.forEach { ConnectorAvatar(it, PILL_AVATAR_SIZE) } }
            if (connected > 0) {
                Spacer(Modifier.width(DebugDimens.spaceXs))
                Text("$connected", style = DebugType.meta, color = colors.success)
            }
        }
    }
    if (showConnectors) {
        DebugConnectorsDialog(
            controller = controller,
            onDismiss = { showConnectors = false },
            onShowA2aProfiles = {
                showConnectors = false
                onShowA2aProfiles()
            },
        )
    }
}

/** 连接应用: the market, and for one app its own page (an MCP app's key, an agent's pairing). */
@Composable
internal fun DebugConnectorsDialog(
    controller: DebugConnectorController,
    onDismiss: () -> Unit,
    onShowA2aProfiles: () -> Unit,
) {
    val colors = DebugColors
    val statuses = remember { mutableStateMapOf<String, DebugConnectorStatus>() }
    var selected by remember { mutableStateOf<DebugConnector?>(null) }
    LaunchedEffect(Unit) {
        DEBUG_CONNECTORS.forEach { connector ->
            statuses[connector.id] = controller.status(connector)
            if (controller.status(connector) !is DebugConnectorStatus.Disconnected) {
                runCatching { controller.refresh(connector) }.onSuccess { statuses[connector.id] = it }
            }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            // Up to a comfortable reading size on a wide car display; nearly full screen on small ones.
            modifier = Modifier
                .padding(DebugDimens.spaceXl)
                .widthIn(max = DIALOG_MAX_WIDTH)
                .heightIn(max = DIALOG_MAX_HEIGHT)
                .fillMaxWidth()
                .fillMaxHeight(),
            shape = RoundedCornerShape(DebugDimens.radiusLg),
            color = colors.panel,
        ) {
            Column(Modifier.padding(DebugDimens.spaceXl)) {
                val current = selected
                if (current == null) {
                    ConnectorCatalog(statuses, onOpen = { selected = it }, onShowA2aProfiles, onDismiss)
                } else if (current.mcp != null) {
                    McpConnectorDetail(
                        connector = current,
                        status = statuses[current.id] ?: DebugConnectorStatus.Disconnected,
                        controller = controller,
                        onStatus = { statuses[current.id] = it },
                        onBack = { selected = null },
                    )
                } else {
                    ConnectorDetail(
                        connector = current,
                        status = statuses[current.id] ?: DebugConnectorStatus.Disconnected,
                        controller = controller,
                        onStatus = { statuses[current.id] = it },
                        onBack = { selected = null },
                    )
                }
            }
        }
    }
}

/** The chips over the market: everything, each category in catalog order, then 自定义. */
private val CONNECTOR_FILTERS: List<String> = listOf(FILTER_ALL) + DEBUG_CONNECTORS.map { it.category }.distinct() + FILTER_CUSTOM

/** 连接应用 as an app market: a card per app under its category, tapped for the app's own page. */
@Composable
private fun ColumnScope.ConnectorCatalog(
    statuses: Map<String, DebugConnectorStatus>,
    onOpen: (DebugConnector) -> Unit,
    onShowA2aProfiles: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DebugColors
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(FILTER_ALL) }
    val connected = statuses.values.count { it is DebugConnectorStatus.Connected }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("连接应用", style = DebugType.hero, color = colors.text, modifier = Modifier.weight(1f))
        Text(
            if (connected > 0) "已连接 $connected 个" else "还没有连接",
            style = DebugType.meta,
            color = if (connected > 0) colors.success else colors.muted,
        )
        Spacer(Modifier.width(DebugDimens.spaceSm))
        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "关闭", tint = colors.muted) }
    }
    Spacer(Modifier.height(DebugDimens.spaceMd))
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text("搜索应用，比如 咖啡、叫车、地图") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = colors.muted) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = debugOutlinedTextFieldColors(),
    )
    Spacer(Modifier.height(DebugDimens.spaceMd))
    FilterChips(filter) { filter = it }
    val words = query.trim()
    val apps = DEBUG_CONNECTORS.filter { (filter == FILTER_ALL || it.category == filter) && it.matches(words) }
    val customShown = (filter == FILTER_ALL || filter == FILTER_CUSTOM) &&
        (words.isEmpty() || CUSTOM_TITLE.contains(words, true) || CUSTOM_SUMMARY.contains(words, true))
    ConnectorGrid(apps, customShown, statuses, onOpen, onShowA2aProfiles, emptyText = "没有找到「$words」")
}

@Composable
private fun FilterChips(selected: String, onSelect: (String) -> Unit) {
    val colors = DebugColors
    Row(horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm)) {
        CONNECTOR_FILTERS.forEach { entry ->
            val active = entry == selected
            Text(
                entry,
                style = DebugType.body,
                color = if (active) colors.panel else colors.text,
                modifier = Modifier
                    .clip(RoundedCornerShape(DebugDimens.radiusFull))
                    .background(if (active) colors.ink else colors.elevated)
                    .clickable { onSelect(entry) }
                    .padding(horizontal = DebugDimens.spaceLg, vertical = DebugDimens.spaceXsMd),
            )
        }
    }
}

private fun DebugConnector.matches(words: String): Boolean =
    words.isEmpty() || name.contains(words, true) || summary.contains(words, true) || category.contains(words, true) ||
        mcp?.abilities?.any { it.contains(words, true) } == true

@Composable
private fun ColumnScope.ConnectorGrid(
    apps: List<DebugConnector>,
    customShown: Boolean,
    statuses: Map<String, DebugConnectorStatus>,
    onOpen: (DebugConnector) -> Unit,
    onShowA2aProfiles: () -> Unit,
    emptyText: String,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(CARD_MIN_WIDTH),
        modifier = Modifier.weight(1f),
        horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceMd),
    ) {
        apps.groupBy { it.category }.forEach { (category, members) ->
            item(key = "category-$category", span = { GridItemSpan(maxLineSpan) }) { ConnectorSectionTitle(category) }
            items(members, key = { it.id }) { connector ->
                ConnectorCard(
                    avatar = { ConnectorAvatar(connector, CARD_AVATAR_SIZE) },
                    title = connector.name,
                    summary = connector.summary,
                    status = { StatusChip(statuses[connector.id] ?: DebugConnectorStatus.Disconnected) },
                    onClick = { onOpen(connector) },
                )
            }
        }
        if (customShown) {
            item(key = "category-custom", span = { GridItemSpan(maxLineSpan) }) { ConnectorSectionTitle(FILTER_CUSTOM) }
            item(key = "custom-a2a") {
                ConnectorCard(
                    avatar = { LetterAvatar("A2A", DebugColors.inkSoft, CARD_AVATAR_SIZE, 13.sp, APP_ICON_SHAPE) },
                    title = CUSTOM_TITLE,
                    summary = CUSTOM_SUMMARY,
                    status = { ChipText("管理", DebugColors.text) },
                    onClick = onShowA2aProfiles,
                )
            }
        }
        if (apps.isEmpty() && !customShown) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Text(emptyText, style = DebugType.body, color = DebugColors.muted, modifier = Modifier.padding(top = DebugDimens.spaceXl))
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
    val colors = DebugColors
    Column(
        Modifier
            .fillMaxWidth()
            .height(CARD_HEIGHT)
            .clip(RoundedCornerShape(DebugDimens.radiusLg))
            .border(1.dp, colors.border, RoundedCornerShape(DebugDimens.radiusLg))
            .background(colors.elevated)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(DebugDimens.spaceLg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            avatar()
            Spacer(Modifier.width(DebugDimens.spaceMd))
            Text(title, style = DebugType.panelTitle, color = colors.text, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(DebugDimens.spaceSm))
        Text(summary, style = DebugType.bodySmall, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { status() }
    }
}

@Composable
private fun StatusChip(status: DebugConnectorStatus) {
    val colors = DebugColors
    when (status) {
        is DebugConnectorStatus.Connected -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = colors.success, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(DebugDimens.spaceXs))
            Text("已连接", style = DebugType.body, color = colors.success)
        }
        DebugConnectorStatus.Paused -> ChipText("已停用", colors.muted)
        is DebugConnectorStatus.Pairing -> ChipText("配对中", colors.accent)
        DebugConnectorStatus.Disconnected -> ChipText("连接", colors.text)
    }
}

@Composable
private fun ChipText(text: String, color: Color) {
    Text(
        text,
        style = DebugType.body,
        color = color,
        modifier = Modifier
            .border(1.dp, DebugColors.border, RoundedCornerShape(DebugDimens.radiusFull))
            .padding(horizontal = DebugDimens.spaceLg, vertical = DebugDimens.spaceXs),
    )
}

@Composable
internal fun ConnectorSectionTitle(text: String) {
    Text(
        text,
        style = DebugType.panelTitle,
        color = DebugColors.text,
        modifier = Modifier.padding(top = DebugDimens.spaceXl, bottom = DebugDimens.spaceSm),
    )
}

@Composable
private fun ColumnScope.ConnectorDetail(
    connector: DebugConnector,
    status: DebugConnectorStatus,
    controller: DebugConnectorController,
    onStatus: (DebugConnectorStatus) -> Unit,
    onBack: () -> Unit,
) {
    val colors = DebugColors
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var events by remember(status) { mutableStateOf(controller.events(connector)) }
    var account by remember(status) { mutableStateOf(controller.account(connector)) }
    fun unpair() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            notice = controller.disconnect(connector) { progress = it }
            progress = null
            events = controller.events(connector)
            onStatus(DebugConnectorStatus.Disconnected)
            busy = false
        }
    }
    fun run(action: suspend () -> DebugConnectorStatus) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                onStatus(action())
            } catch (failure: DebugConnectorException) {
                error = failure.message
            } finally {
                busy = false
            }
        }
    }
    // While pairing, ask the relay every few seconds whether the phone finished; the page needs no car input.
    LaunchedEffect(connector.id, status is DebugConnectorStatus.Pairing) {
        while (status is DebugConnectorStatus.Pairing) {
            delay(PAIRING_POLL_MS)
            runCatching { controller.refresh(connector) }.onSuccess { if (it != status) onStatus(it) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = colors.muted) }
        ConnectorAvatar(connector, ROW_AVATAR_SIZE)
        Spacer(Modifier.width(DebugDimens.spaceMd))
        Column(Modifier.weight(1f)) {
            Text(connector.name, style = DebugType.hero, color = colors.text)
            Text(connector.summary, style = DebugType.bodySmall, color = colors.muted)
            Text("通过预置中转 ${controller.relayHost(connector)} 连接", style = DebugType.meta, color = colors.tertiary)
        }
    }
    Spacer(Modifier.height(DebugDimens.spaceXl))
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        when (status) {
            DebugConnectorStatus.Disconnected -> {
                Text(
                    "连接后，小博会把需要你个人账号的事交给你自己的 ${connector.name}，办完再回到这里播报。" +
                        "需要一部能扫码的手机，和你的 ${connector.name} 账号；手机上的页面会一步步带你配置。",
                    style = DebugType.body,
                    color = colors.text,
                )
                Spacer(Modifier.height(DebugDimens.spaceLg))
                Button(onClick = { run { controller.connect(connector) } }, enabled = !busy) { Text("开始连接") }
            }
            DebugConnectorStatus.Paused -> Unit
            is DebugConnectorStatus.Pairing -> PairingPanel(
                status = status,
                busy = busy,
                onRefresh = { run { controller.refreshCode(connector) } },
                onCancel = { unpair() },
            )
            is DebugConnectorStatus.Connected -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = colors.success)
                    Spacer(Modifier.width(DebugDimens.spaceSm))
                    Text("已连接到你的 ${connector.name}", style = DebugType.panelTitle, color = colors.text)
                }
                Spacer(Modifier.height(DebugDimens.spaceSm))
                Text(
                    if (status.verified) {
                        "已经走通过一次完整流程。直接对小博说「让 ${connector.name} 帮我……」就行。"
                    } else {
                        "还没有走通过完整流程：可以在手机连接页点「测试连接」，或直接对小博说「让 ${connector.name} 帮我……」。"
                    },
                    style = DebugType.body,
                    color = colors.muted,
                )
                Spacer(Modifier.height(DebugDimens.spaceLg))
                Row(horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            testing = true
                            testResult = null
                            scope.launch {
                                testResult = try {
                                    true to controller.test(connector)
                                } catch (failure: DebugConnectorException) {
                                    false to failure.message.orEmpty()
                                }
                                if (testResult?.first == true) onStatus(DebugConnectorStatus.Connected(verified = true))
                                testing = false
                            }
                        },
                        enabled = !busy && !testing,
                    ) { Text("测试连接") }
                    OutlinedButton(onClick = { confirmDisconnect = true }, enabled = !busy && !testing) { Text("解除绑定", color = colors.danger) }
                }
                if (testing) {
                    Spacer(Modifier.height(DebugDimens.spaceSm))
                    Text("已交给 ${connector.name}，等它汇报（一般半分钟到两分钟）…", style = DebugType.body, color = colors.muted)
                }
                testResult?.let { (ok, text) ->
                    Spacer(Modifier.height(DebugDimens.spaceSm))
                    Text(if (ok) "${connector.name}：$text" else text, style = DebugType.body, color = if (ok) colors.success else colors.danger)
                }
            }
        }
        if (busy) {
            Spacer(Modifier.height(DebugDimens.spaceMd))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                progress?.let {
                    Spacer(Modifier.width(DebugDimens.spaceSm))
                    Text(it, style = DebugType.body, color = colors.muted)
                }
            }
        }
        notice?.let {
            Spacer(Modifier.height(DebugDimens.spaceMd))
            Text(it, style = DebugType.body, color = colors.text)
        }
        error?.let {
            Spacer(Modifier.height(DebugDimens.spaceMd))
            Text(it, style = DebugType.body, color = colors.danger)
        }
        AccountPanel(
            connector = connector,
            account = account,
            connected = status is DebugConnectorStatus.Connected,
            accountPage = controller.accountPage(connector),
            busy = busy,
            onSignIn = { name, password, register ->
                run {
                    controller.signIn(connector, name, password, register).also {
                        account = controller.account(connector)
                        events = controller.events(connector)
                    }
                }
            },
            onSignOut = {
                scope.launch {
                    runCatching { controller.signOut(connector) }.onFailure { error = it.message }
                    account = controller.account(connector)
                    events = controller.events(connector)
                }
            },
        )
        LaunchedEffect(testResult) { events = controller.events(connector) }
        ConnectionEvents(events)
    }
    if (confirmDisconnect) {
        DebugAlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("解除和 ${connector.name} 的绑定？") },
            text = {
                Text(
                    "解除绑定后小博不能再把事交给 ${connector.name}。会先让 ${connector.name} 删掉它那边的例行任务和 car-relay 连接器" +
                        "并回报结果（最多等三分钟），两边都会留下记录。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    unpair()
                }) { Text("解除绑定", color = colors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("取消") } },
        )
    }
}

/**
 * 账号 (owner 2026-10-12): signed in, the account name and where to manage it; signed out, a name and password to sign
 * in or register. Signing in on a second car joins the account's connection, so it needs no QR code or MCP token.
 */
@Composable
private fun AccountPanel(
    connector: DebugConnector,
    account: String?,
    connected: Boolean,
    accountPage: String,
    busy: Boolean,
    onSignIn: (name: String, password: String, register: Boolean) -> Unit,
    onSignOut: () -> Unit,
) {
    val colors = DebugColors
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    // Typed on a device screen it is easy to mistype; owner 2026-10-12: 「密码加个按钮可以看见」.
    var showPassword by remember { mutableStateOf(false) }
    ConnectorSectionTitle("账号")
    if (account != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("已登录：$account", style = DebugType.panelTitle, color = colors.text, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onSignOut, enabled = !busy) { Text("退出账号") }
        }
        Spacer(Modifier.height(DebugDimens.spaceSm))
        Text(
            "在别的平板或手机上登录这个账号，就会加入同一个 ${connector.name} 连接，不用扫码。" +
                "手机打开 $accountPage 可以管理设备、看委托记录、更新例行任务的 Webhook。",
            style = DebugType.body,
            color = colors.muted,
        )
        return
    }
    Text(
        if (connected) {
            "注册一个账号接管这个连接：以后别的设备登录同一个账号就能直接用，例行任务的 Webhook 换了也能在网页上改。"
        } else {
            "已经有账号？直接登录，这台设备会加入账号的 ${connector.name} 连接，不用扫码。"
        },
        style = DebugType.body,
        color = colors.muted,
    )
    Spacer(Modifier.height(DebugDimens.spaceSm))
    Row(horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            colors = debugOutlinedTextFieldColors(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码（至少 8 位）") },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "隐藏" else "显示") }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.weight(1f),
            colors = debugOutlinedTextFieldColors(),
        )
    }
    Spacer(Modifier.height(DebugDimens.spaceSm))
    val filled = name.isNotBlank() && password.isNotEmpty()
    Row(horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm)) {
        Button(onClick = { onSignIn(name, password, false); password = "" }, enabled = !busy && filled) { Text("登录") }
        OutlinedButton(onClick = { onSignIn(name, password, true); password = "" }, enabled = !busy && filled) { Text("注册") }
    }
}

@Composable
private fun PairingPanel(status: DebugConnectorStatus.Pairing, busy: Boolean, onRefresh: () -> Unit, onCancel: () -> Unit) {
    val colors = DebugColors
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
        Spacer(Modifier.width(DebugDimens.spaceXl))
        Column(Modifier.weight(1f)) {
            Text("用手机扫码，按页面提示完成连接", style = DebugType.panelTitle, color = colors.text)
            Spacer(Modifier.height(DebugDimens.spaceSm))
            Text(
                "扫不了码时，用手机浏览器打开 ${status.connectUrl.substringBefore('?')}，输入配对码：",
                style = DebugType.body,
                color = colors.muted,
            )
            Text(
                status.code.ifEmpty { "————" },
                fontFamily = FontFamily.Monospace,
                fontSize = CODE_FONT_SIZE,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 4.sp,
                color = if (usable) colors.text else colors.tertiary,
                modifier = Modifier.padding(vertical = DebugDimens.spaceSm),
            )
            Text(
                if (usable) "有效期到 ${clockTime(status.expiresAtMillis)}，连好后这里会自动显示「已连接」。" else "配对码已过期，请刷新。",
                style = DebugType.bodySmall,
                color = colors.muted,
            )
            Spacer(Modifier.height(DebugDimens.spaceLg))
            Row(horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm)) {
                OutlinedButton(onClick = onRefresh, enabled = !busy) { Text("刷新配对码") }
                TextButton(onClick = onCancel, enabled = !busy) { Text("取消", color = colors.danger) }
            }
            Spacer(Modifier.height(DebugDimens.spaceXl))
            PairingSteps()
        }
    }
}

/** The latest connection events on this car (connected, tests, disconnects), newest first. */
@Composable
internal fun ConnectionEvents(events: List<String>) {
    if (events.isEmpty()) return
    val colors = DebugColors
    Spacer(Modifier.height(DebugDimens.spaceXl))
    Text("最近记录", style = DebugType.meta, color = colors.muted)
    events.forEach { Text(it, style = DebugType.bodySmall, color = colors.text, modifier = Modifier.padding(top = DebugDimens.spaceXs)) }
}

@Composable
private fun PairingQr(link: String, usable: Boolean) {
    Box(
        Modifier
            .size(QR_SIZE)
            .clip(RoundedCornerShape(DebugDimens.radiusMd))
            .background(Color.White)
            .padding(DebugDimens.spaceMd),
        contentAlignment = Alignment.Center,
    ) {
        if (usable) DebugQrCode(link, Modifier.fillMaxWidth().fillMaxHeight())
        else Text("配对码已失效", style = DebugType.body, color = Color(0xFF6B6B66))
    }
}

/** What the phone page walks the user through; the bot does the routine, the claim and the connector itself. */
@Composable
private fun PairingSteps() {
    val colors = DebugColors
    Text("手机上要做的事", style = DebugType.meta, color = colors.muted)
    listOf(
        "复制页面上的第一段话发给 Grok Bot，它会建好例行任务，发来两个链接",
        "点开链接复制 Webhook 地址和密钥，粘到页面上，点「连接」",
        "把页面给的第二段话发给 Grok Bot，并在 Auto-review 里放行 car-relay 的两个汇报工具",
    ).forEachIndexed { index, step ->
        Text("${index + 1}. $step", style = DebugType.bodySmall, color = colors.text, modifier = Modifier.padding(top = DebugDimens.spaceXs))
    }
}

/** The QR code of [text], drawn module by module (no quiet zone: the caller pads it on white). */
@Composable
internal fun DebugQrCode(text: String, modifier: Modifier = Modifier) {
    val modules = remember(text) { debugQrModules(text) }
    Canvas(modifier.semantics { contentDescription = "配对二维码" }) {
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
internal fun debugQrModules(text: String): List<BooleanArray> = runCatching {
    val matrix = Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")).matrix
    List(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix.get(x, y).toInt() == 1 } }
}.getOrDefault(emptyList())

/** An app's icon: its mark on its colour, a rounded square like an app icon (a dot in the sidebar pill). */
@Composable
internal fun ConnectorAvatar(connector: DebugConnector, size: androidx.compose.ui.unit.Dp) {
    val small = size < ROW_AVATAR_SIZE
    LetterAvatar(connector.mark, Color(connector.color), size, if (small) 10.sp else 22.sp, if (small) CircleShape else APP_ICON_SHAPE)
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

private const val CUSTOM_TITLE = "自定义 A2A 智能体"
private const val CUSTOM_SUMMARY = "填写地址和访问令牌，接入任何支持 A2A 协议的智能体。"
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
private const val FILTER_ALL = "全部"
private const val FILTER_CUSTOM = "自定义"
private val QR_SIZE = 240.dp
private val CODE_FONT_SIZE = 30.sp
