package cc.noharry.bothy.sample

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** The fields of the add/edit form; secrets are never prefilled, so they are not saveable either. */
private class McpServerFields(existing: DebugMcpServer?) {
    var kind by mutableStateOf(existing?.kind ?: DebugMcpServerKind.HTTP)
    var label by mutableStateOf(existing?.label.orEmpty())
    var url by mutableStateOf(existing?.url.orEmpty())
    var token by mutableStateOf("")
    var headers by mutableStateOf("")
    var clearSecrets by mutableStateOf(false)

    fun draft(id: String?) = DebugMcpServerDraft(id, label, kind, url, token, headers, clearSecrets)
}

/**
 * Add or edit one MCP server: a standard server is its Streamable HTTP address, with a bearer token and request headers
 * that are stored on this device only and never shown again.
 */
@Composable
internal fun McpServerForm(
    existing: DebugMcpServer?,
    actions: DebugConsoleActions,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fields = remember(existing?.id) { McpServerFields(existing) }
    val stored = remember(existing?.id) { existing?.let { DebugMcpSecrets.read(context, it.id) } ?: DebugMcpSecrets.Stored() }
    var saveError by rememberSaveable(existing?.id) { mutableStateOf<String?>(null) }
    Column(modifier.widthIn(max = MCP_FORM_MAX_WIDTH)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceMd),
        ) {
            if (DebugMcpServerKind.entries.size > 1 && existing?.deletable != false) McpKindPicker(fields.kind) { fields.kind = it }
            McpFormField("名称", fields.label) { fields.label = it }
            HttpFields(fields, stored)
            saveError?.let { Text(it, style = DebugType.meta, color = DebugColors.danger) }
        }
        Spacer(Modifier.height(DebugDimens.spaceSm))
        HorizontalDivider(color = DebugColors.border)
        Spacer(Modifier.height(DebugDimens.spaceXs))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDone) { Text("取消") }
            Spacer(Modifier.width(DebugDimens.spaceSm))
            Button(
                onClick = { actions.saveMcpServer(fields.draft(existing?.id)).let { error -> if (error == null) onDone() else saveError = error } },
                shape = RoundedCornerShape(DebugDimens.radiusMd),
            ) { Text("保存") }
        }
    }
}

@Composable
private fun McpKindPicker(selected: DebugMcpServerKind, onSelect: (DebugMcpServerKind) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceXs)) {
        Text("类型", style = DebugType.meta, color = DebugColors.muted)
        DebugMcpServerKind.entries.forEach { kind ->
            DebugSelectableOptionRow(
                label = kind.label,
                selected = kind == selected,
                onClick = { onSelect(kind) },
                subtitle = "服务器地址，可带令牌和请求头",
            )
        }
    }
}

@Composable
private fun HttpFields(fields: McpServerFields, stored: DebugMcpSecrets.Stored) {
    McpFormField("地址（Streamable HTTP）", fields.url, placeholder = "https://example.com/mcp") { fields.url = it }
    McpFormField(
        "令牌（可不填，作为 Authorization: Bearer 发送，只存在本机）",
        fields.token,
        secret = true,
        placeholder = if (stored.token.isNotEmpty()) "已设置，留空不变" else "",
    ) { fields.token = it }
    McpFormField(
        "其他请求头（可不填，一行一个「名称: 值」，只存在本机）",
        fields.headers,
        singleLine = false,
        placeholder = if (stored.headers.isNotEmpty()) "已设置 ${stored.headers.size} 个，留空不变" else "X-Api-Key: …",
    ) { fields.headers = it }
    if (!stored.isEmpty) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("清除已存的令牌和请求头", style = DebugType.body, color = DebugColors.text, modifier = Modifier.weight(1f))
            Switch(checked = fields.clearSecrets, onCheckedChange = { fields.clearSecrets = it }, colors = debugSwitchColors())
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
        colors = debugOutlinedTextFieldColors(),
    )
}

private val MCP_FORM_MAX_WIDTH = 920.dp
