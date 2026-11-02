package cc.noharry.bothy.app.model
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.outlinedTextFieldColors
import cc.noharry.bothy.app.console.singleLine
import cc.noharry.bothy.app.console.switchColors
import cc.noharry.bothy.app.console.ui.PopupMenu
import cc.noharry.bothy.app.console.ui.PopupMenuItem
import cc.noharry.bothy.app.console.ui.PopupSectionLabel
import cc.noharry.bothy.app.console.ui.SelectorChip
import cc.noharry.bothy.app.session.ModelProvider
import cc.noharry.bothy.app.usage.formatContextTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ProviderDetail(
    initial: ProviderDraft,
    actions: ConsoleActions,
    isNew: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    onCreated: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val form = remember(initial.id) {
        ProviderFormState(
            initial,
            loadWindow = { model -> contextWindowOverride(context, initial.toEditor().copy(model = model))?.toString().orEmpty() },
            loadImageInput = { model -> loadModelImageInput(context, initial.toEditor().copy(model = model)) },
        )
    }
    LaunchedEffect(initial.id, initial.modelOptions) { form.syncModelOptions(initial) }
    var notice by remember(initial.id) { mutableStateOf("") }
    Column(modifier.fillMaxSize()) {
        Text(
            if (isNew) stringResource(R.string.model_add_service) else initial.label,
            style = ConsoleType.panelTitle,
            color = ConsoleColors.text,
        )
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
            item { ProviderConnectionFields(form, isNew, initial.id) }
            item {
                HorizontalDivider(color = ConsoleColors.borderSubtle)
                ProviderModelFields(form, actions)
            }
            items(form.modelOptions, key = { it }) { model -> ProviderModelRow(form, model) }
        }
        if (notice.isNotBlank()) Text(notice, style = ConsoleType.meta, color = ConsoleColors.accent)
        HorizontalDivider(color = ConsoleColors.borderSubtle)
        ProviderDetailActions(form.toDraft(initial), actions, isNew, form.canSave(isNew), onDone) {
            form.addPendingModel()
            val draft = form.toDraft(initial)
            val id = saveProviderConnection(draft, actions, isNew)
            val editor = draft.toEditor().copy(profileId = id)
            draft.modelOptions.forEach { model ->
                saveContextWindow(context, editor.copy(model = model), parseContextWindow(form.windowInputs[model].orEmpty()))
                saveModelImageInput(context, editor.copy(model = model), form.imageInputs[model] ?: true)
            }
            actions.saveProviderModels(editor, draft.modelOptions)
            notice = resources.getString(R.string.model_saved_choose_model)
            if (isNew) onCreated(id)
        }
    }
}

private fun saveProviderConnection(draft: ProviderDraft, actions: ConsoleActions, isNew: Boolean): String {
    if (isNew) return actions.saveNewProfile(draft.label.trim(), draft.provider, draft.endpoint, draft.apiKey, "", draft.baseUrl.trim())
    actions.saveConfig(draft.provider, draft.apiKey, draft.model, draft.baseUrl.trim(), draft.endpoint)
    return draft.id
}

@Composable
private fun ProviderConnectionFields(form: ProviderFormState, isNew: Boolean, profileId: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        if (isNew) ConfigField(stringResource(R.string.model_service_name), form.label) { form.label = it }
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
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    var shown by remember(apiKey, url) { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        Text(
            shown.ifBlank { stringResource(R.string.model_deepseek_balance_hint) },
            style = ConsoleType.meta,
            color = if (shown.isBlank()) ConsoleColors.muted else ConsoleColors.text,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(enabled = !asking && apiKey.isNotBlank(), onClick = {
            asking = true
            scope.launch {
                val balance = if (url == null) {
                    fetchDeepSeekBalance(context, apiKey)
                } else {
                    val relay = withContext(Dispatchers.IO) { deepSeekRelayClient(context) }
                    fetchDeepSeekBalance(context, apiKey, relay, url)
                }
                shown = balance.fold(
                    { resources.getString(R.string.model_balance, it.summary(context)) },
                    { resources.getString(R.string.model_balance_failed, it.message ?: it.javaClass.simpleName) },
                )
                asking = false
            }
        }) { Text(stringResource(if (asking) R.string.model_balance_checking else R.string.model_balance_check)) }
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
    var probe by remember(profileId) { mutableStateOf(DeepSeekProbes.saved(context, profileId)) }
    val profile = form.probeProfile(profileId)
    val key = remember(form.apiKey, form.baseUrl) { ProviderConfig.load(context.applicationContext).effectiveApiKey(profile) }
    val current = probe?.takeIf { it.fingerprint == deepSeekProbeFingerprint(form.endpoint, form.baseUrl, key) }
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.model_deepseek_features),
                    style = ConsoleType.body,
                    color = ConsoleColors.text,
                )
                Text(
                    when {
                        probe == null -> stringResource(R.string.model_deepseek_probe_intro)
                        current == null -> stringResource(R.string.model_deepseek_probe_stale)
                        else -> stringResource(R.string.model_deepseek_probe_current)
                    },
                    style = ConsoleType.meta,
                    color = ConsoleColors.muted,
                )
            }
            OutlinedButton(enabled = !checking && form.baseUrl.isNotBlank() && key.isNotBlank(), onClick = {
                checking = true
                scope.launch {
                    probe = DeepSeekProbes.check(context, profile, key)
                    checking = false
                }
            }) { Text(stringResource(if (checking) R.string.model_probe_checking else R.string.model_probe_check)) }
        }
        current?.let { result ->
            ProbeLine(stringResource(R.string.model_probe_balance), result.balance)
            ProbeLine(stringResource(R.string.model_probe_tools), result.tools)
            result.passthrough?.let { ProbeLine(stringResource(R.string.model_probe_passthrough), it) }
                ?: Text(
                    stringResource(R.string.model_probe_passthrough_messages_only),
                    style = ConsoleType.meta,
                    color = ConsoleColors.muted,
                )
            result.balance.url?.let { DeepSeekBalanceRow(key, it) }
        }
    }
}

@Composable
private fun ProbeLine(name: String, check: DeepSeekCheck) {
    Text(
        stringResource(
            if (check.passed) R.string.model_probe_line_passed else R.string.model_probe_line_failed, name, check.note,
        ),
        style = ConsoleType.meta,
        color = if (check.passed) ConsoleColors.success else ConsoleColors.danger,
    )
}

@Composable
private fun ProviderModelFields(form: ProviderFormState, actions: ConsoleActions) {
    val resources = LocalContext.current.resources
    var fetching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.model_models_count, form.modelOptions.size),
                style = ConsoleType.body, color = ConsoleColors.text, modifier = Modifier.weight(1f),
            )
            OutlinedButton(enabled = !fetching && form.baseUrl.isNotBlank(), onClick = {
                fetching = true
                val connection = form.connectionIdentity()
                actions.fetchModels("catalog", form.provider, form.endpoint, form.apiKey, form.model, form.baseUrl.trim()) { result ->
                    fetching = false
                    if (connection == form.connectionIdentity()) {
                        result.onSuccess { fetched ->
                            val unselected = form.useFetchedModels(fetched)
                            val count = form.modelOptions.size
                            message = when {
                                fetched.none { it.isNotBlank() } -> resources.getString(R.string.model_fetch_empty)
                                unselected != null -> resources.getQuantityString(
                                    R.plurals.model_fetched_unselected, count, count, unselected,
                                )
                                else -> resources.getQuantityString(R.plurals.model_fetched, count, count)
                            }
                        }.onFailure { message = resources.getString(R.string.model_fetch_failed) }
                    } else message = resources.getString(R.string.model_fetch_connection_changed)
                }
            }) { Text(stringResource(if (fetching) R.string.model_fetching else R.string.model_fetch)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
            Box(Modifier.weight(1f)) {
                ConfigField(stringResource(R.string.model_add_by_hand), form.pendingModel) { form.pendingModel = it }
            }
            TextButton(enabled = form.pendingModel.isNotBlank(), onClick = form::addPendingModel) {
                Text(stringResource(R.string.model_add_model))
            }
        }
        Text(
            message.ifBlank { stringResource(R.string.model_list_hint) },
            style = ConsoleType.meta,
            color = ConsoleColors.muted,
        )
    }
}

@Composable
private fun ProviderModelRow(form: ProviderFormState, model: String) {
    val text = form.windowInputs[model].orEmpty()
    val valid = text.isBlank() || parseContextWindow(text) != null
    val defaultWindow = formatContextTokens(defaultContextWindow(model))
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
            Text(model, modifier = Modifier.weight(1f), style = ConsoleType.body, color = ConsoleColors.text)
            OutlinedTextField(
                value = text, onValueChange = { form.windowInputs[model] = it },
                label = { Text(stringResource(R.string.model_context_limit)) },
                placeholder = { Text(stringResource(R.string.model_context_default, defaultWindow)) },
                supportingText = {
                    Text(
                        if (valid) {
                            stringResource(R.string.model_context_default_compacts, defaultWindow)
                        } else {
                            stringResource(R.string.model_context_invalid)
                        },
                    )
                },
                modifier = Modifier.width(260.dp), singleLine = true, isError = !valid,
                colors = outlinedTextFieldColors(),
            )
            ModelImageInputSwitch(form, model)
        }
        HorizontalDivider(color = ConsoleColors.borderSubtle)
    }
}

/** 图片输入: on sends screenshots and pictures to this model; off (仅文本) folds them into text. */
@Composable
private fun ModelImageInputSwitch(form: ProviderFormState, model: String) {
    val enabled = form.imageInputs[model] ?: true
    val description = stringResource(R.string.model_image_input_description, model)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Switch(
            checked = enabled,
            onCheckedChange = { form.imageInputs[model] = it },
            modifier = Modifier.semantics { contentDescription = description },
            colors = switchColors(),
        )
        Text(
            stringResource(if (enabled) R.string.model_text_and_images else R.string.model_text_only),
            style = ConsoleType.meta,
            color = ConsoleColors.muted,
        )
    }
}

@Composable
private fun ProviderKindSelector(selected: ModelProvider, onSelected: (ModelProvider) -> Unit) {
    SettingsChoiceMenu(
        label = stringResource(R.string.model_type),
        selected = selected,
        options = ModelProvider.entries,
        optionLabel = { it.label },
        onSelected = onSelected,
    )
}

@Composable
private fun EndpointSelector(
    provider: ModelProvider,
    selected: ProviderEndpoint,
    onSelected: (ProviderEndpoint) -> Unit,
) {
    SettingsChoiceMenu(
        label = stringResource(R.string.model_endpoint),
        selected = selected,
        options = ProviderEndpoint.options(provider),
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
    val colors = ConsoleColors
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
        Text(label, style = ConsoleType.meta, color = colors.muted)
        Box(Modifier.fillMaxWidth()) {
            SelectorChip(
                label = optionLabel(selected),
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            )
            PopupMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                minWidth = SETTINGS_MENU_MIN_WIDTH,
                maxWidth = SETTINGS_MENU_MAX_WIDTH,
            ) {
                PopupSectionLabel(label)
                options.forEach { option ->
                    PopupMenuItem(
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
    actions: ConsoleActions,
    isNew: Boolean,
    canSave: Boolean,
    onDone: () -> Unit,
    onSave: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        if (!isNew) TextButton(onClick = { actions.deleteProfile(draft.id); onDone() }, enabled = draft.canDelete) {
            Text(
                stringResource(R.string.model_delete_service),
                color = if (draft.canDelete) ConsoleColors.danger else ConsoleColors.muted,
            )
        }
        Spacer(Modifier.weight(1f))
        if (isNew) TextButton(onClick = onDone) { Text(stringResource(R.string.common_cancel)) }
        Button(onClick = onSave, enabled = canSave, shape = RoundedCornerShape(Dimens.radiusMd)) {
            Text(stringResource(R.string.common_save))
        }
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
        colors = outlinedTextFieldColors(),
    )
}

internal data class ProviderDraft(
    val id: String,
    val label: String,
    val provider: ModelProvider,
    val endpoint: ProviderEndpoint,
    val apiKey: String,
    val model: String,
    val baseUrl: String,
    val canDelete: Boolean,
    val modelOptions: List<String>,
) {
    companion object {
        fun from(editor: ProviderEditState): ProviderDraft =
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
                provider = ModelProvider.OPENAI,
                endpoint = ProviderEndpoint.defaultFor(ModelProvider.OPENAI),
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

internal fun ProviderDraft.toEditor(): ProviderEditState = ProviderEditState(
    apiKey = apiKey, model = model.trim(), variant = "", baseUrl = baseUrl.trim(), profileId = id,
    provider = provider, endpoint = endpoint,
)
