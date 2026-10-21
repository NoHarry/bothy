package cc.noharry.bothy.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * An MCP app's own page in 连接应用: how to get its key (a QR code to open on the phone), the key, and once connected
 * what it can do, a check, a new key or disconnecting. Nothing here says MCP: the user only sees the app.
 */
@Composable
internal fun ColumnScope.McpConnectorDetail(
    connector: DebugConnector,
    status: DebugConnectorStatus,
    controller: DebugConnectorController,
    onStatus: (DebugConnectorStatus) -> Unit,
    onBack: () -> Unit,
) {
    val spec = connector.mcp ?: return
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tools by remember { mutableStateOf<List<String>?>(null) }
    var replacing by remember(status) { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var events by remember(status) { mutableStateOf(controller.events(connector)) }
    fun act(doing: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = doing
        error = null
        scope.launch {
            try {
                block()
            } catch (failure: DebugConnectorException) {
                error = failure.message
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = "出错了：${failure.message ?: failure.javaClass.simpleName}"
            } finally {
                busy = null
                events = controller.events(connector)
            }
        }
    }
    // Opening a connected app's page checks it quietly, so an expired key shows here and not mid-drive.
    LaunchedEffect(connector.id, status is DebugConnectorStatus.Connected) {
        if (status is DebugConnectorStatus.Connected) act("正在检查…") { tools = controller.checkApp(connector, record = false) }
    }
    AppHeader(connector, onBack)
    Spacer(Modifier.height(DebugDimens.spaceXl))
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        when {
            status is DebugConnectorStatus.Connected && !replacing -> ConnectedPanel(
                spec = spec,
                sandbox = controller.usesSandbox(connector),
                tools = tools,
                busy = busy != null,
                onCheck = { act("正在检查…") { tools = controller.checkApp(connector) } },
                onReplace = { replacing = true },
                onDisconnect = { confirmDisconnect = true },
            )
            status == DebugConnectorStatus.Paused && !replacing -> PausedPanel(
                busy = busy != null,
                onResume = { act("正在启用…") { onStatus(controller.resumeApp(connector)) } },
                onDisconnect = { confirmDisconnect = true },
            )
            else -> ConnectForm(spec, busy != null, replacing, onCancel = { replacing = false }) { credential, sandbox ->
                act("正在连接${connector.name}…") {
                    tools = controller.connectApp(connector, credential, sandbox)
                    replacing = false
                    onStatus(DebugConnectorStatus.Connected(verified = true))
                }
            }
        }
        BusyAndError(busy, error)
        Abilities(spec)
        ConnectionEvents(events)
    }
    if (confirmDisconnect) {
        DisconnectDialog(connector, onDismiss = { confirmDisconnect = false }) {
            confirmDisconnect = false
            act("正在断开…") {
                controller.disconnect(connector)
                tools = null
                onStatus(DebugConnectorStatus.Disconnected)
            }
        }
    }
}

@Composable
private fun AppHeader(connector: DebugConnector, onBack: () -> Unit) {
    val colors = DebugColors
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = colors.muted) }
        ConnectorAvatar(connector, HEADER_AVATAR_SIZE)
        Spacer(Modifier.width(DebugDimens.spaceMd))
        Column(Modifier.weight(1f)) {
            Text(connector.name, style = DebugType.hero, color = colors.text)
            Text(connector.summary, style = DebugType.bodySmall, color = colors.muted)
            Text(connector.category, style = DebugType.meta, color = colors.tertiary)
        }
    }
}

@Composable
private fun ConnectedPanel(
    spec: DebugMcpConnectorSpec,
    sandbox: Boolean,
    tools: List<String>?,
    busy: Boolean,
    onCheck: () -> Unit,
    onReplace: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val colors = DebugColors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = colors.success)
        Spacer(Modifier.width(DebugDimens.spaceSm))
        Text(if (sandbox) "已连接 · 沙箱" else "已连接", style = DebugType.panelTitle, color = colors.text)
    }
    Spacer(Modifier.height(DebugDimens.spaceSm))
    tools?.let { names ->
        val shown = names.take(SHOWN_TOOLS).joinToString("、") + if (names.size > SHOWN_TOOLS) " 等" else ""
        Text("可以用 ${names.size} 个功能：$shown", style = DebugType.body, color = colors.muted)
    }
    Text("直接对小博说「${spec.example}」就行。", style = DebugType.body, color = colors.text)
    if (sandbox) {
        Text("现在连的是沙箱，数据是模拟的。要真用，点「更换${spec.credentialLabel}」选正式环境。", style = DebugType.bodySmall, color = colors.muted)
    }
    if (spec.note.isNotEmpty() && !spec.needsCredential) Text(spec.note, style = DebugType.bodySmall, color = colors.muted)
    Spacer(Modifier.height(DebugDimens.spaceLg))
    Row(horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm)) {
        Button(onClick = onCheck, enabled = !busy) { Text("检查连接") }
        if (spec.needsCredential) OutlinedButton(onClick = onReplace, enabled = !busy) { Text("更换${spec.credentialLabel}") }
        OutlinedButton(onClick = onDisconnect, enabled = !busy) { Text("断开", color = colors.danger) }
    }
}

@Composable
private fun PausedPanel(busy: Boolean, onResume: () -> Unit, onDisconnect: () -> Unit) {
    val colors = DebugColors
    Text("已在 设置 → 连接 → MCP 里停用，小博暂时不会用它。", style = DebugType.body, color = colors.text)
    Spacer(Modifier.height(DebugDimens.spaceLg))
    Row(horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm)) {
        Button(onClick = onResume, enabled = !busy) { Text("重新启用") }
        OutlinedButton(onClick = onDisconnect, enabled = !busy) { Text("断开", color = colors.danger) }
    }
}

/** Where to get the key (scan to open it on the phone), the key, and for 滴滴 which environment. */
@Composable
private fun ConnectForm(
    spec: DebugMcpConnectorSpec,
    busy: Boolean,
    replacing: Boolean,
    onCancel: () -> Unit,
    onConnect: (credential: String, sandbox: Boolean) -> Unit,
) {
    var credential by remember { mutableStateOf("") }
    var sandbox by remember { mutableStateOf(true) }
    if (!spec.needsCredential) {
        val local = spec.url.startsWith("http://127.0.0.1:")
        val hint = if (local) "本机的演示服务，不需要 key：先打开 Bothy Playground，再点「连接」。" else "公开服务，不需要 key：点「连接」测一下，就能直接用。"
        Text(hint, style = DebugType.body, color = DebugColors.text)
        Spacer(Modifier.height(DebugDimens.spaceMd))
        Button(onClick = { onConnect("", false) }, enabled = !busy) { Text("连接") }
        return
    }
    Row(verticalAlignment = Alignment.Top) {
        PortalQr(spec.portal)
        Spacer(Modifier.width(DebugDimens.spaceXl))
        Column(Modifier.weight(1f)) { HowToSteps(spec) }
    }
    Spacer(Modifier.height(DebugDimens.spaceLg))
    CredentialField(spec.credentialLabel, credential) { credential = it }
    if (spec.sandboxUrl != null) SandboxChoice(sandbox) { sandbox = it }
    Spacer(Modifier.height(DebugDimens.spaceMd))
    Row(horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm)) {
        Button(onClick = { onConnect(credential, sandbox) }, enabled = !busy && credential.isNotBlank()) {
            Text(if (replacing) "保存并连接" else "连接")
        }
        if (replacing) TextButton(onClick = onCancel, enabled = !busy) { Text("取消") }
    }
}

@Composable
private fun HowToSteps(spec: DebugMcpConnectorSpec) {
    val colors = DebugColors
    Text("怎么获取${spec.credentialLabel}", style = DebugType.panelTitle, color = colors.text)
    (spec.steps + "把${spec.credentialLabel}粘贴到下面，点「连接」").forEachIndexed { index, step ->
        Text("${index + 1}. $step", style = DebugType.body, color = colors.text, modifier = Modifier.padding(top = DebugDimens.spaceXs))
    }
    Spacer(Modifier.height(DebugDimens.spaceSm))
    Text("扫不了码时，用手机浏览器打开 ${spec.portal}", style = DebugType.bodySmall, color = colors.muted)
}

@Composable
private fun PortalQr(link: String) {
    Box(
        Modifier
            .size(QR_SIZE)
            .clip(RoundedCornerShape(DebugDimens.radiusMd))
            .background(Color.White)
            .padding(DebugDimens.spaceMd),
        contentAlignment = Alignment.Center,
    ) {
        DebugQrCode(link, Modifier.fillMaxSize())
    }
}

/** Typed on a device screen, so it can be shown to check; never shown once saved. */
@Composable
private fun CredentialField(label: String, value: String, onValue: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "隐藏" else "显示") } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
        colors = debugOutlinedTextFieldColors(),
    )
}

@Composable
private fun SandboxChoice(sandbox: Boolean, onChoose: (Boolean) -> Unit) {
    Spacer(Modifier.height(DebugDimens.spaceSm))
    ChoiceRow("沙箱：模拟数据，不会真的叫车（先用它试）", sandbox) { onChoose(true) }
    ChoiceRow("正式：会真实派单、扣费", !sandbox) { onChoose(false) }
}

@Composable
private fun ChoiceRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(DebugDimens.radiusMd)).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text, style = DebugType.body, color = DebugColors.text)
    }
}

@Composable
private fun BusyAndError(busy: String?, error: String?) {
    if (busy != null) {
        Spacer(Modifier.height(DebugDimens.spaceMd))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(DebugDimens.spaceSm))
            Text(busy, style = DebugType.body, color = DebugColors.muted)
        }
    }
    error?.let {
        Spacer(Modifier.height(DebugDimens.spaceMd))
        Text(it, style = DebugType.body, color = DebugColors.danger)
    }
}

@Composable
private fun Abilities(spec: DebugMcpConnectorSpec) {
    val colors = DebugColors
    ConnectorSectionTitle("能做什么")
    spec.abilities.forEach { Text("• $it", style = DebugType.body, color = colors.text, modifier = Modifier.padding(top = DebugDimens.spaceXs)) }
    if (spec.note.isNotEmpty()) {
        Spacer(Modifier.height(DebugDimens.spaceSm))
        Text(spec.note, style = DebugType.bodySmall, color = colors.muted)
    }
}

@Composable
private fun DisconnectDialog(connector: DebugConnector, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    DebugAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("断开${connector.name}？") },
        text = {
            val spec = connector.mcp
            Text(if (spec?.needsCredential == false) "断开后小博不能再用${connector.name}。" else "断开后小博不能再用${connector.name}，这台设备上保存的${spec?.credentialLabel ?: "令牌"}也会删掉。")
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("断开", color = DebugColors.danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private const val SHOWN_TOOLS = 8
private val HEADER_AVATAR_SIZE = 52.dp
private val QR_SIZE = 200.dp
