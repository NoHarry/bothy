package cc.noharry.bothy.app.mcp
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.BothyAlertDialog
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.outlinedTextFieldColors
import cc.noharry.bothy.app.console.singleLine
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.model.summary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * An MCP app's own page in 连接应用: how to get its key (a QR code to open on the phone), the key, and once connected
 * what it can do, a check, a new key or disconnecting. Nothing here says MCP: the user only sees the app.
 */
@Composable
internal fun ColumnScope.McpConnectorDetail(
    connector: Connector,
    status: ConnectorStatus,
    controller: ConnectorController,
    onStatus: (ConnectorStatus) -> Unit,
    onBack: () -> Unit,
) {
    val spec = connector.mcp ?: return
    val context = LocalContext.current
    val resources = LocalResources.current
    val title = stringResource(connector.title)
    val checking = stringResource(R.string.mcp_checking)
    val enabling = stringResource(R.string.mcp_enabling)
    val connecting = stringResource(R.string.mcp_connecting_app, title)
    val disconnecting = stringResource(R.string.mcp_disconnecting)
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
            } catch (failure: ConnectorException) {
                error = failure.message
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = resources.getString(R.string.mcp_error_generic, failure.message ?: failure.javaClass.simpleName)
            } finally {
                busy = null
                events = controller.events(connector)
            }
        }
    }
    // Opening a connected app's page checks it quietly, so an expired key shows here and not mid-drive.
    LaunchedEffect(connector.id, status is ConnectorStatus.Connected) {
        if (status is ConnectorStatus.Connected) {
            act(checking) { tools = controller.checkApp(connector, record = false) }
        }
    }
    AppHeader(connector, onBack)
    Spacer(Modifier.height(Dimens.spaceXl))
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        when {
            status is ConnectorStatus.Connected && !replacing -> ConnectedPanel(
                spec = spec,
                sandbox = controller.usesSandbox(connector),
                tools = tools,
                busy = busy != null,
                onCheck = { act(checking) { tools = controller.checkApp(connector) } },
                onReplace = { replacing = true },
                onDisconnect = { confirmDisconnect = true },
            )
            status == ConnectorStatus.Paused && !replacing -> PausedPanel(
                busy = busy != null,
                onResume = { act(enabling) { onStatus(controller.resumeApp(connector)) } },
                onDisconnect = { confirmDisconnect = true },
            )
            else -> ConnectForm(spec, busy != null, replacing, onCancel = { replacing = false }) { credential, sandbox ->
                act(connecting) {
                    tools = controller.connectApp(connector, credential, sandbox)
                    replacing = false
                    onStatus(ConnectorStatus.Connected(verified = true))
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
            act(disconnecting) {
                controller.disconnect(connector)
                tools = null
                onStatus(ConnectorStatus.Disconnected)
            }
        }
    }
}

@Composable
private fun AppHeader(connector: Connector, onBack: () -> Unit) {
    val colors = ConsoleColors
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_back),
                tint = colors.muted,
            )
        }
        ConnectorAvatar(connector, HEADER_AVATAR_SIZE)
        Spacer(Modifier.width(Dimens.spaceMd))
        Column(Modifier.weight(1f)) {
            Text(stringResource(connector.title), style = ConsoleType.hero, color = colors.text)
            Text(stringResource(connector.summary), style = ConsoleType.bodySmall, color = colors.muted)
            Text(stringResource(connector.category), style = ConsoleType.meta, color = colors.tertiary)
        }
    }
}

@Composable
private fun ConnectedPanel(
    spec: McpConnectorSpec,
    sandbox: Boolean,
    tools: List<String>?,
    busy: Boolean,
    onCheck: () -> Unit,
    onReplace: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val colors = ConsoleColors
    val label = spec.credentialLabel?.let { stringResource(it) }.orEmpty()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = colors.success)
        Spacer(Modifier.width(Dimens.spaceSm))
        Text(
            stringResource(if (sandbox) R.string.mcp_connected_sandbox else R.string.mcp_status_connected),
            style = ConsoleType.panelTitle,
            color = colors.text,
        )
    }
    Spacer(Modifier.height(Dimens.spaceSm))
    tools?.let { names ->
        val listed = names.take(SHOWN_TOOLS).joinToString(stringResource(R.string.mcp_list_separator))
        val shown = if (names.size > SHOWN_TOOLS) stringResource(R.string.mcp_tools_more, listed) else listed
        Text(
            pluralStringResource(R.plurals.mcp_tools_available, names.size, names.size, shown),
            style = ConsoleType.body,
            color = colors.muted,
        )
    }
    Text(
        stringResource(R.string.mcp_say_example, stringResource(spec.example)),
        style = ConsoleType.body,
        color = colors.text,
    )
    if (sandbox) {
        Text(stringResource(R.string.mcp_sandbox_hint, label), style = ConsoleType.bodySmall, color = colors.muted)
    }
    if (spec.note != null && !spec.needsCredential) {
        Text(stringResource(spec.note), style = ConsoleType.bodySmall, color = colors.muted)
    }
    Spacer(Modifier.height(Dimens.spaceLg))
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        Button(onClick = onCheck, enabled = !busy) { Text(stringResource(R.string.mcp_check_connection)) }
        if (spec.needsCredential) {
            OutlinedButton(onClick = onReplace, enabled = !busy) {
                Text(stringResource(R.string.mcp_replace_credential, label))
            }
        }
        OutlinedButton(onClick = onDisconnect, enabled = !busy) {
            Text(stringResource(R.string.common_disconnect), color = colors.danger)
        }
    }
}

@Composable
private fun PausedPanel(busy: Boolean, onResume: () -> Unit, onDisconnect: () -> Unit) {
    val colors = ConsoleColors
    Text(stringResource(R.string.mcp_paused_hint), style = ConsoleType.body, color = colors.text)
    Spacer(Modifier.height(Dimens.spaceLg))
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        Button(onClick = onResume, enabled = !busy) { Text(stringResource(R.string.mcp_turn_back_on)) }
        OutlinedButton(onClick = onDisconnect, enabled = !busy) {
            Text(stringResource(R.string.common_disconnect), color = colors.danger)
        }
    }
}

/** Where to get the key (scan to open it on the phone), the key, and for 滴滴 which environment. */
@Composable
private fun ConnectForm(
    spec: McpConnectorSpec,
    busy: Boolean,
    replacing: Boolean,
    onCancel: () -> Unit,
    onConnect: (credential: String, sandbox: Boolean) -> Unit,
) {
    var credential by remember { mutableStateOf("") }
    var sandbox by remember { mutableStateOf(true) }
    if (!spec.needsCredential) {
        val local = spec.url.startsWith("http://127.0.0.1:")
        val hint = stringResource(if (local) R.string.mcp_open_local_hint else R.string.mcp_open_public_hint)
        Text(hint, style = ConsoleType.body, color = ConsoleColors.text)
        Spacer(Modifier.height(Dimens.spaceMd))
        Button(onClick = { onConnect("", false) }, enabled = !busy) { Text(stringResource(R.string.common_connect)) }
        return
    }
    Row(verticalAlignment = Alignment.Top) {
        PortalQr(spec.portal)
        Spacer(Modifier.width(Dimens.spaceXl))
        Column(Modifier.weight(1f)) { HowToSteps(spec) }
    }
    Spacer(Modifier.height(Dimens.spaceLg))
    CredentialField(spec.credentialLabel?.let { stringResource(it) }.orEmpty(), credential) { credential = it }
    if (spec.sandboxUrl != null) SandboxChoice(sandbox) { sandbox = it }
    Spacer(Modifier.height(Dimens.spaceMd))
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        Button(onClick = { onConnect(credential, sandbox) }, enabled = !busy && credential.isNotBlank()) {
            Text(stringResource(if (replacing) R.string.mcp_save_and_connect else R.string.common_connect))
        }
        if (replacing) TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.common_cancel)) }
    }
}

@Composable
private fun HowToSteps(spec: McpConnectorSpec) {
    val colors = ConsoleColors
    val label = spec.credentialLabel?.let { stringResource(it) }.orEmpty()
    Text(stringResource(R.string.mcp_how_to_get, label), style = ConsoleType.panelTitle, color = colors.text)
    val steps = spec.steps.map { stringResource(it) } + stringResource(R.string.mcp_paste_step, label)
    steps.forEachIndexed { index, step ->
        Text("${index + 1}. $step", style = ConsoleType.body, color = colors.text, modifier = Modifier.padding(top = Dimens.spaceXs))
    }
    Spacer(Modifier.height(Dimens.spaceSm))
    Text(stringResource(R.string.mcp_portal_manual, spec.portal), style = ConsoleType.bodySmall, color = colors.muted)
}

@Composable
private fun PortalQr(link: String) {
    Box(
        Modifier
            .size(QR_SIZE)
            .clip(RoundedCornerShape(Dimens.radiusMd))
            .background(Color.White)
            .padding(Dimens.spaceMd),
        contentAlignment = Alignment.Center,
    ) {
        QrCode(link, Modifier.fillMaxSize())
    }
}

/** Typed on a device screen, so it can be shown to check; never shown once saved. */
@Composable
private fun CredentialField(label: String, value: String, onValue: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        // English labels are written for the middle of a sentence ("access token"); a label starts with a capital.
        label = { Text(label.replaceFirstChar { it.uppercase() }) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) {
                Text(stringResource(if (visible) R.string.mcp_hide else R.string.mcp_show))
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
        colors = outlinedTextFieldColors(),
    )
}

@Composable
private fun SandboxChoice(sandbox: Boolean, onChoose: (Boolean) -> Unit) {
    Spacer(Modifier.height(Dimens.spaceSm))
    ChoiceRow(stringResource(R.string.mcp_sandbox_choice), sandbox) { onChoose(true) }
    ChoiceRow(stringResource(R.string.mcp_production_choice), !sandbox) { onChoose(false) }
}

@Composable
private fun ChoiceRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.radiusMd)).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text, style = ConsoleType.body, color = ConsoleColors.text)
    }
}

@Composable
private fun BusyAndError(busy: String?, error: String?) {
    if (busy != null) {
        Spacer(Modifier.height(Dimens.spaceMd))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(Dimens.spaceSm))
            Text(busy, style = ConsoleType.body, color = ConsoleColors.muted)
        }
    }
    error?.let {
        Spacer(Modifier.height(Dimens.spaceMd))
        Text(it, style = ConsoleType.body, color = ConsoleColors.danger)
    }
}

@Composable
private fun Abilities(spec: McpConnectorSpec) {
    val colors = ConsoleColors
    ConnectorSectionTitle(stringResource(R.string.mcp_abilities))
    spec.abilities.forEach {
        Text(
            "• ${stringResource(it)}",
            style = ConsoleType.body,
            color = colors.text,
            modifier = Modifier.padding(top = Dimens.spaceXs),
        )
    }
    spec.note?.let {
        Spacer(Modifier.height(Dimens.spaceSm))
        Text(stringResource(it), style = ConsoleType.bodySmall, color = colors.muted)
    }
}

@Composable
private fun DisconnectDialog(connector: Connector, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    BothyAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mcp_disconnect_title, stringResource(connector.title))) },
        text = {
            val spec = connector.mcp
            val title = stringResource(connector.title)
            Text(
                if (spec?.needsCredential == false) {
                    stringResource(R.string.mcp_disconnect_open, title)
                } else {
                    val label = stringResource(spec?.credentialLabel ?: R.string.mcp_token)
                    stringResource(R.string.mcp_disconnect_keyed, title, label)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.common_disconnect), color = ConsoleColors.danger)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

private const val SHOWN_TOOLS = 8
private val HEADER_AVATAR_SIZE = 52.dp
private val QR_SIZE = 200.dp
