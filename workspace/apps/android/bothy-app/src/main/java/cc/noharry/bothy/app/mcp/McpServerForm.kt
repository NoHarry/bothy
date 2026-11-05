package cc.noharry.bothy.app.mcp
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.McpSecretsView
import cc.noharry.agent.appserver.protocol.McpServer
import cc.noharry.agent.appserver.protocol.McpServerDraft
import cc.noharry.agent.appserver.protocol.McpServerKind
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.outlinedTextFieldColors
import cc.noharry.bothy.app.console.switchColors
import cc.noharry.bothy.app.console.ui.SelectableOptionRow

/** The fields of the add/edit form; secrets are never prefilled, so they are not saveable either. */
private class McpServerFields(existing: McpServer?) {
    var kind by mutableStateOf(existing?.kind ?: McpServerKind.HTTP)
    var label by mutableStateOf(existing?.label.orEmpty())
    var url by mutableStateOf(existing?.url.orEmpty())
    var token by mutableStateOf("")
    var headers by mutableStateOf("")
    var clearSecrets by mutableStateOf(false)

    fun draft(id: String?) = McpServerDraft(id, label, kind, url, token, headers, clearSecrets)
}

/**
 * Add or edit one MCP server: a standard server is its Streamable HTTP address, with a bearer token and request headers
 * that are stored on this device only and never shown again.
 */
@Composable
internal fun McpServerForm(
    existing: McpServer?,
    actions: ConsoleActions,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fields = remember(existing?.id) { McpServerFields(existing) }
    val status = rememberMcpStatus(emptyList())
    val stored = existing?.let { status.secrets[it.id] } ?: McpSecretsView()
    var saveError by rememberSaveable(existing?.id) { mutableStateOf<String?>(null) }
    Column(modifier.widthIn(max = MCP_FORM_MAX_WIDTH)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
        ) {
            if (McpServerKind.entries.size > 1 && existing?.deletable != false) McpKindPicker(fields.kind) { fields.kind = it }
            McpFormField(stringResource(R.string.mcp_form_name), fields.label) { fields.label = it }
            HttpFields(fields, stored)
            saveError?.let { Text(it, style = ConsoleType.meta, color = ConsoleColors.danger) }
        }
        Spacer(Modifier.height(Dimens.spaceSm))
        HorizontalDivider(color = ConsoleColors.border)
        Spacer(Modifier.height(Dimens.spaceXs))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDone) { Text(stringResource(R.string.common_cancel)) }
            Spacer(Modifier.width(Dimens.spaceSm))
            Button(
                onClick = { actions.saveMcpServer(fields.draft(existing?.id)) { error -> if (error == null) onDone() else saveError = error } },
                shape = RoundedCornerShape(Dimens.radiusMd),
            ) { Text(stringResource(R.string.common_save)) }
        }
    }
}

@Composable
private fun McpKindPicker(selected: McpServerKind, onSelect: (McpServerKind) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
        Text(stringResource(R.string.mcp_form_type), style = ConsoleType.meta, color = ConsoleColors.muted)
        McpServerKind.entries.forEach { kind ->
            SelectableOptionRow(
                label = stringResource(kind.screenName),
                selected = kind == selected,
                onClick = { onSelect(kind) },
                subtitle = stringResource(R.string.mcp_form_kind_http_subtitle),
            )
        }
    }
}

@Composable
private fun HttpFields(fields: McpServerFields, stored: McpSecretsView) {
    McpFormField(stringResource(R.string.mcp_form_url), fields.url, placeholder = "https://example.com/mcp") {
        fields.url = it
    }
    McpFormField(
        stringResource(R.string.mcp_form_token),
        fields.token,
        secret = true,
        placeholder = if (stored.token) stringResource(R.string.mcp_form_token_kept) else "",
    ) { fields.token = it }
    McpFormField(
        stringResource(R.string.mcp_form_headers),
        fields.headers,
        singleLine = false,
        placeholder = if (stored.headers > 0) {
            pluralStringResource(R.plurals.mcp_form_headers_kept, stored.headers, stored.headers)
        } else {
            "X-Api-Key: …"
        },
    ) { fields.headers = it }
    if (!stored.isEmpty) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.mcp_form_clear_secrets),
                style = ConsoleType.body,
                color = ConsoleColors.text,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = fields.clearSecrets, onCheckedChange = { fields.clearSecrets = it }, colors = switchColors())
        }
    }
}

@Composable
private fun McpFormField(
    label: String,
    value: String,
    secret: Boolean = false,
    singleLine: Boolean = true,
    placeholder: String = "",
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        colors = outlinedTextFieldColors(),
    )
}

private val MCP_FORM_MAX_WIDTH = 920.dp

/** The kind's name in 设置 → 连接 → MCP. */
@get:StringRes
private val McpServerKind.screenName: Int
    get() = when (this) {
        McpServerKind.HTTP -> R.string.mcp_kind_http
    }
