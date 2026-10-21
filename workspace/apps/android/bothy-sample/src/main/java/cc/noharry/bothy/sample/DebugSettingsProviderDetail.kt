package cc.noharry.bothy.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ProviderDetail(
    initial: ProviderDraft,
    actions: DebugConsoleActions,
    isNew: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    onCreated: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val form = remember(initial.id) {
        ProviderFormState(
            initial,
            loadWindow = { model -> contextWindowOverride(context, initial.toEditor().copy(model = model))?.toString().orEmpty() },
            loadImageInput = { model -> loadDebugModelImageInput(context, initial.toEditor().copy(model = model)) },
        )
    }
    LaunchedEffect(initial.id, initial.modelOptions) { form.syncModelOptions(initial) }
    var notice by remember(initial.id) { mutableStateOf("") }
    Column(modifier.fillMaxSize()) {
        Text(if (isNew) "添加服务" else initial.label, style = DebugType.panelTitle, color = DebugColors.text)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceMd)) {
            item { ProviderConnectionFields(form, isNew, initial.id) }
            item {
                HorizontalDivider(color = DebugColors.borderSubtle)
                ProviderModelFields(form, actions)
            }
            items(form.modelOptions, key = { it }) { model -> ProviderModelRow(form, model) }
        }
        if (notice.isNotBlank()) Text(notice, style = DebugType.meta, color = DebugColors.accent)
        HorizontalDivider(color = DebugColors.borderSubtle)
        ProviderDetailActions(form.toDraft(initial), actions, isNew, form.canSave(isNew), onDone) {
            form.addPendingModel()
            val draft = form.toDraft(initial)
            val id = saveProviderConnection(draft, actions, isNew)
            val editor = draft.toEditor().copy(profileId = id)
            draft.modelOptions.forEach { model ->
                saveDebugContextWindow(context, editor.copy(model = model), parseContextWindow(form.windowInputs[model].orEmpty()))
                saveDebugModelImageInput(context, editor.copy(model = model), form.imageInputs[model] ?: true)
            }
            actions.saveProviderModels(editor, draft.modelOptions)
            notice = "已保存；使用的模型请在输入框旁选择。"
            if (isNew) onCreated(id)
        }
    }
}

private fun saveProviderConnection(draft: ProviderDraft, actions: DebugConsoleActions, isNew: Boolean): String {
    if (isNew) return actions.saveNewProfile(draft.label.trim(), draft.provider, draft.endpoint, draft.apiKey, "", draft.baseUrl.trim())
    actions.saveConfig(draft.provider, draft.apiKey, draft.model, draft.baseUrl.trim(), draft.endpoint)
    return draft.id
}

@Composable
private fun ProviderConnectionFields(form: ProviderFormState, isNew: Boolean, profileId: String) {
    Column(verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm)) {
        if (isNew) ConfigField("服务名称", form.label) { form.label = it }
        ProviderKindSelector(form.provider, form::selectProvider)
        EndpointSelector(form.provider, form.endpoint) { form.endpoint = it }
        ConfigField("Base URL", form.baseUrl, onValueChange = form::updateBaseUrl)
        ConfigField("API key", form.apiKey, secret = true, onValueChange = form::updateApiKey)
        when {
            form.baseUrl.isDeepSeekUrl() -> DeepSeekBalanceRow(form.apiKey)
            !isNew -> DeepSeekProbeRow(form, profileId)
        }
    }
}

/**
 * An official DeepSeek key's balance, asked on tap with the key in the form (saved or not); [url] is a relay's balance
 * path a check found ([DeepSeekProbeRow]).
 */
@Composable
private fun DeepSeekBalanceRow(apiKey: String, url: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    var shown by remember(apiKey, url) { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm)) {
        Text(
            shown.ifBlank { "DeepSeek 官方 key 可查询余额" },
            style = DebugType.meta,
            color = if (shown.isBlank()) DebugColors.muted else DebugColors.text,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(enabled = !asking && apiKey.isNotBlank(), onClick = {
            asking = true
            scope.launch {
                val balance = if (url == null) {
                    fetchDeepSeekBalance(apiKey)
                } else {
                    fetchDeepSeekBalance(apiKey, withContext(Dispatchers.IO) { deepSeekRelayClient(context) }, url)
                }
                shown = balance.fold({ "余额：${it.summary()}" }, { "查询失败：${it.message ?: it.javaClass.simpleName}" })
                asking = false
            }
        }) { Text(if (asking) "正在查询…" else "查询余额") }
    }
}

/**
 * A profile off api.deepseek.com that may have DeepSeek behind it (a bench relay, a gateway on an official key): 检测
 * checks the address and key in the form and, for this profile, unlocks what passed. The runtime goes by the saved
 * profile, so a check of unsaved values counts once they are saved; one made for other values shows as out of date.
 */
@Composable
private fun DeepSeekProbeRow(form: ProviderFormState, profileId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var probe by remember(profileId) { mutableStateOf(DebugDeepSeekProbes.saved(context, profileId)) }
    val profile = form.probeProfile(profileId)
    val key = remember(form.apiKey, form.baseUrl) { DebugProviderConfig.load(context.applicationContext).effectiveApiKey(profile) }
    val current = probe?.takeIf { it.fingerprint == deepSeekProbeFingerprint(form.endpoint, form.baseUrl, key) }
    Column(verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceXs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm)) {
            Column(Modifier.weight(1f)) {
                Text("DeepSeek 官方能力", style = DebugType.body, color = DebugColors.text)
                Text(
                    when {
                        probe == null -> "中转后面是 DeepSeek 官方接口时，检测通过的余额、Search/Fetch、原生延迟工具会走这个服务的地址和 key（检测搜索一次 ¥0.005）"
                        current == null -> "地址、端点或 key 和上次检测时不同，需要重新检测"
                        else -> "按当前填写的地址和 key 检测；有改动要保存后才生效"
                    },
                    style = DebugType.meta,
                    color = DebugColors.muted,
                )
            }
            OutlinedButton(enabled = !checking && form.baseUrl.isNotBlank() && key.isNotBlank(), onClick = {
                checking = true
                scope.launch {
                    probe = DebugDeepSeekProbes.check(context, profile, key)
                    checking = false
                }
            }) { Text(if (checking) "正在检测…" else "检测") }
        }
        current?.let { result ->
            ProbeLine("余额接口", result.balance)
            ProbeLine("Search/Fetch 接口", result.tools)
            result.passthrough?.let { ProbeLine("原生延迟工具", it) }
                ?: Text("原生延迟工具：只有 Messages 端点有", style = DebugType.meta, color = DebugColors.muted)
            result.balance.url?.let { DeepSeekBalanceRow(key, it) }
        }
    }
}

@Composable
private fun ProbeLine(name: String, check: DeepSeekCheck) {
    Text(
        "$name：${if (check.passed) "✓ ${check.note}" else "✗ ${check.note}"}",
        style = DebugType.meta,
        color = if (check.passed) DebugColors.success else DebugColors.danger,
    )
}

@Composable
private fun ProviderModelFields(form: ProviderFormState, actions: DebugConsoleActions) {
    var fetching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("模型列表 · ${form.modelOptions.size}", style = DebugType.body, color = DebugColors.text, modifier = Modifier.weight(1f))
            OutlinedButton(enabled = !fetching && form.baseUrl.isNotBlank(), onClick = {
                fetching = true
                val connection = form.connectionIdentity()
                actions.fetchModels("catalog", form.provider, form.endpoint, form.apiKey, form.model, form.baseUrl.trim()) { result ->
                    fetching = false
                    if (connection == form.connectionIdentity()) {
                        result.onSuccess { fetched ->
                            val unselected = form.useFetchedModels(fetched)
                            message = when {
                                fetched.none { it.isNotBlank() } -> "上游没有列出模型，已有列表保留，仍可手动添加。"
                                unselected != null -> "已拉取 ${form.modelOptions.size} 个模型，替换了原来的列表；原来选的 $unselected 上游已不再提供，保存后请在输入框旁重新选择模型。"
                                else -> "已拉取 ${form.modelOptions.size} 个模型，替换了原来的列表，保存后可在输入框旁选择。"
                            }
                        }.onFailure { message = "拉取失败，已有列表保留，仍可手动添加。" }
                    } else message = "连接已修改，请重新拉取模型。"
                }
            }) { Text(if (fetching) "正在拉取…" else "从上游拉取模型") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm)) {
            Box(Modifier.weight(1f)) { ConfigField("手动添加模型名", form.pendingModel) { form.pendingModel = it } }
            TextButton(enabled = form.pendingModel.isNotBlank(), onClick = form::addPendingModel) { Text("添加模型") }
        }
        Text(message.ifBlank { "在此管理模型列表和上下文上限；使用的模型在输入框旁选择。" }, style = DebugType.meta, color = DebugColors.muted)
    }
}

@Composable
private fun ProviderModelRow(form: ProviderFormState, model: String) {
    val text = form.windowInputs[model].orEmpty()
    val valid = text.isBlank() || parseContextWindow(text) != null
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceMd)) {
            Text(model, modifier = Modifier.weight(1f), style = DebugType.body, color = DebugColors.text)
            OutlinedTextField(
                value = text, onValueChange = { form.windowInputs[model] = it },
                label = { Text("上下文上限（tokens）") },
                placeholder = { Text("默认 ${formatContextTokens(defaultDebugContextWindow(model))}") },
                supportingText = { Text(if (valid) "默认 ${formatContextTokens(defaultDebugContextWindow(model))} · 80% 自动压缩" else "请输入正整数或 K / M 数值") },
                modifier = Modifier.width(260.dp), singleLine = true, isError = !valid,
                colors = debugOutlinedTextFieldColors(),
            )
            ModelImageInputSwitch(form, model)
        }
        HorizontalDivider(color = DebugColors.borderSubtle)
    }
}

/** 图片输入: on sends screenshots and pictures to this model; off (仅文本) folds them into text. */
@Composable
private fun ModelImageInputSwitch(form: ProviderFormState, model: String) {
    val enabled = form.imageInputs[model] ?: true
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Switch(
            checked = enabled,
            onCheckedChange = { form.imageInputs[model] = it },
            modifier = Modifier.semantics { contentDescription = "$model 图片输入" },
            colors = debugSwitchColors(),
        )
        Text(if (enabled) "文本+图片" else "仅文本", style = DebugType.meta, color = DebugColors.muted)
    }
}

@Composable
private fun ProviderKindSelector(selected: DebugProvider, onSelected: (DebugProvider) -> Unit) {
    SettingsChoiceMenu(
        label = "Type",
        selected = selected,
        options = DebugProvider.entries,
        optionLabel = { it.label },
        onSelected = onSelected,
    )
}

@Composable
private fun EndpointSelector(
    provider: DebugProvider,
    selected: DebugProviderEndpoint,
    onSelected: (DebugProviderEndpoint) -> Unit,
) {
    SettingsChoiceMenu(
        label = "Endpoint",
        selected = selected,
        options = DebugProviderEndpoint.options(provider),
        optionLabel = { it.label },
        onSelected = onSelected,
    )
}

@Composable
private fun <T> SettingsChoiceMenu(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = DebugColors
    Column(verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceXs)) {
        Text(label, style = DebugType.meta, color = colors.muted)
        Box(Modifier.fillMaxWidth()) {
            DebugSelectorChip(
                label = optionLabel(selected),
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            )
            DebugPopupMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                minWidth = SETTINGS_MENU_MIN_WIDTH,
                maxWidth = SETTINGS_MENU_MAX_WIDTH,
            ) {
                DebugPopupSectionLabel(label)
                options.forEach { option ->
                    DebugPopupMenuItem(
                        label = optionLabel(option),
                        selected = option == selected,
                        onClick = {
                            expanded = false
                            onSelected(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderDetailActions(
    draft: ProviderDraft,
    actions: DebugConsoleActions,
    isNew: Boolean,
    canSave: Boolean,
    onDone: () -> Unit,
    onSave: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        if (!isNew) TextButton(onClick = { actions.deleteProfile(draft.id); onDone() }, enabled = draft.canDelete) {
            Text("删除服务", color = if (draft.canDelete) DebugColors.danger else DebugColors.muted)
        }
        Spacer(Modifier.weight(1f))
        if (isNew) TextButton(onClick = onDone) { Text("取消") }
        Button(onClick = onSave, enabled = canSave, shape = RoundedCornerShape(DebugDimens.radiusMd)) { Text("保存") }
    }
}

@Composable
private fun ConfigField(
    label: String,
    value: String,
    secret: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        colors = debugOutlinedTextFieldColors(),
    )
}

internal data class ProviderDraft(
    val id: String,
    val label: String,
    val provider: DebugProvider,
    val endpoint: DebugProviderEndpoint,
    val apiKey: String,
    val model: String,
    val baseUrl: String,
    val canDelete: Boolean,
    val modelOptions: List<String>,
) {
    companion object {
        fun from(editor: DebugProviderEditState): ProviderDraft =
            ProviderDraft(
                id = editor.profileId,
                label = editor.profileLabel,
                provider = editor.provider,
                endpoint = editor.endpoint,
                apiKey = editor.apiKey,
                model = editor.model,
                baseUrl = editor.baseUrl,
                canDelete = editor.profileOptions.firstOrNull { it.id == editor.profileId }?.canDelete == true,
                modelOptions = editor.modelOptions,
            )

        fun new(): ProviderDraft =
            ProviderDraft(
                id = "new",
                label = "",
                provider = DebugProvider.OPENAI,
                endpoint = DebugProviderEndpoint.defaultFor(DebugProvider.OPENAI),
                apiKey = "",
                model = "",
                baseUrl = "",
                canDelete = false,
                modelOptions = emptyList(),
            )
    }
}

private val SETTINGS_MENU_MIN_WIDTH = 220.dp
private val SETTINGS_MENU_MAX_WIDTH = 320.dp

internal fun ProviderDraft.toEditor(): DebugProviderEditState = DebugProviderEditState(
    apiKey = apiKey, model = model.trim(), variant = "", baseUrl = baseUrl.trim(), profileId = id,
    provider = provider, endpoint = endpoint,
)
