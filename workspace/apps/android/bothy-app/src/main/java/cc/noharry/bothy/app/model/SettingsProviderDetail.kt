package cc.noharry.bothy.app.model
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import cc.noharry.agent.appserver.protocol.deepseek.DeepSeekBalanceParams
import cc.noharry.agent.appserver.protocol.deepseek.DeepSeekCheckView
import cc.noharry.agent.appserver.protocol.deepseek.DeepSeekProbeView
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ModelSettingsParams
import cc.noharry.agent.appserver.protocol.ModelSettingsRequests
import cc.noharry.agent.appserver.protocol.deepseek.DeepSeekRequests
import cc.noharry.agent.appserver.protocol.ModelSettingsSaveParams
import cc.noharry.agent.appserver.protocol.ProviderEditState
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.protocol.deepseek.isDeepSeekUrl
import cc.noharry.agent.appserver.protocol.parseContextWindow
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.balanceWords
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.outlinedTextFieldColors
import cc.noharry.bothy.app.console.switchColors
import cc.noharry.bothy.app.console.ui.PopupMenu
import cc.noharry.bothy.app.console.ui.PopupMenuItem
import cc.noharry.bothy.app.console.ui.PopupSectionLabel
import cc.noharry.bothy.app.console.ui.SelectorChip
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.app.words
import java.text.NumberFormat
import java.util.Locale

@Composable
internal fun ProviderDetail(
    initial: ProviderDraft,
    actions: ConsoleActions,
    isNew: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    onCreated: (String) -> Unit = {},
) {
    val resources = LocalResources.current
    val server = LocalServer.current
    val form = remember(initial.id) { ProviderFormState(initial) }
    LaunchedEffect(initial.id, initial.modelOptions) { form.syncModelOptions(initial) }
    LaunchedEffect(form, form.modelOptions) {
        val missing = form.modelsToLoad()
        if (missing.isNotEmpty()) {
            server.send(ModelSettingsRequests.MODELS_READ, ModelSettingsParams(initial.toEditor(), missing)) { form.applySettings(it.models) }
        }
    }
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
            saveProviderConnection(draft, actions, isNew) { id ->
                val editor = draft.toEditor().copy(profileId = id)
                server.send(ModelSettingsRequests.MODELS_SAVE, ModelSettingsSaveParams(editor, form.modelSettings()))
                actions.saveProviderModels(editor, draft.modelOptions)
                notice = resources.getString(R.string.model_saved_choose_model)
                if (isNew) onCreated(id)
            }
        }
    }
}

/** Saves the connection, then gives the profile's id (a new profile's once the server made it). */
private fun saveProviderConnection(draft: ProviderDraft, actions: ConsoleActions, isNew: Boolean, saved: (String) -> Unit) {
    if (isNew) return actions.saveNewProfile(draft.label.trim(), draft.provider, draft.endpoint, draft.apiKey, "", draft.baseUrl.trim(), saved)
    actions.saveConfig(draft.provider, draft.apiKey, draft.model, draft.baseUrl.trim(), draft.endpoint)
    saved(draft.id)
}

@Composable
private fun ProviderConnectionFields(form: ProviderFormState, isNew: Boolean, profileId: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        if (isNew) ConfigField(stringResource(R.string.model_service_name), form.label) { form.label = it }
        ProviderKindSelector(form.provider, form::selectProvider)
        EndpointSelector(form.provider, form.endpoint) { form.endpoint = it }
        ConfigField("Base URL", form.baseUrl, onValueChange = form::updateBaseUrl)
        ConfigField(
            "API key", form.apiKey, secret = true, onValueChange = form::updateApiKey,
            placeholder = stringResource(R.string.model_api_key_saved).takeIf { form.hasSavedKey },
        )
        when {
            form.baseUrl.isDeepSeekUrl() -> DeepSeekBalanceRow(
                DeepSeekBalanceParams(apiKey = form.apiKey, connection = form.probeConnection(profileId).takeIf { form.hasSavedKey }),
            )
            !isNew -> DeepSeekProbeRow(form, profileId)
        }
    }
}

/**
 * An official DeepSeek key's balance, asked on tap with the key typed in the form, else the one saved for the profile;
 * or a relay's, at the balance path a check found ([DeepSeekProbeRow]), with the key the server has for the connection.
 */
@Composable
private fun DeepSeekBalanceRow(ask: DeepSeekBalanceParams) {
    val server = LocalServer.current
    val resources = LocalResources.current
    var asking by remember { mutableStateOf(false) }
    var shown by remember(ask) { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        Text(
            shown.ifBlank { stringResource(R.string.model_deepseek_balance_hint) },
            style = ConsoleType.meta,
            color = if (shown.isBlank()) ConsoleColors.muted else ConsoleColors.text,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(enabled = !asking && (ask.connection != null || ask.apiKey.isNotBlank()), onClick = {
            asking = true
            server.send(
                DeepSeekRequests.BALANCE,
                ask,
                onRefused = { shown = it.message.orEmpty(); asking = false },
            ) { answer ->
                shown = answer.balance?.let { resources.getString(R.string.model_balance, resources.balanceWords(it)) }
                    ?: answer.failure?.let(resources::words).orEmpty()
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
    val server = LocalServer.current
    var checking by remember { mutableStateOf(false) }
    var probe by remember(profileId) { mutableStateOf<DeepSeekProbeView?>(null) }
    val connection = form.probeConnection(profileId)
    // The stored check, measured against the connection as now typed.
    LaunchedEffect(connection) { server.send(DeepSeekRequests.PROBE, connection) { probe = it } }
    val current = probe?.takeIf { it.current }
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
                        probe?.checked != true -> stringResource(R.string.model_deepseek_probe_intro)
                        current == null -> stringResource(R.string.model_deepseek_probe_stale)
                        else -> stringResource(R.string.model_deepseek_probe_current)
                    },
                    style = ConsoleType.meta,
                    color = ConsoleColors.muted,
                )
            }
            OutlinedButton(enabled = !checking && form.baseUrl.isNotBlank() && probe?.hasKey == true, onClick = {
                checking = true
                server.send(
                    DeepSeekRequests.CHECK,
                    connection,
                    onRefused = { checking = false },
                ) { probe = it; checking = false }
            }) { Text(stringResource(if (checking) R.string.model_probe_checking else R.string.model_probe_check)) }
        }
        current?.let { result ->
            result.balance?.let { ProbeLine(stringResource(R.string.model_probe_balance), it) }
            result.tools?.let { ProbeLine(stringResource(R.string.model_probe_tools), it) }
            result.passthrough?.let { ProbeLine(stringResource(R.string.model_probe_passthrough), it) }
                ?: Text(
                    stringResource(R.string.model_probe_passthrough_messages_only),
                    style = ConsoleType.meta,
                    color = ConsoleColors.muted,
                )
            result.balance?.url?.let { DeepSeekBalanceRow(DeepSeekBalanceParams(connection = connection, url = it)) }
        }
    }
}

@Composable
private fun ProbeLine(name: String, check: DeepSeekCheckView) {
    Text(
        stringResource(
            if (check.passed) R.string.model_probe_line_passed else R.string.model_probe_line_failed, name, words(check.note),
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
                actions.fetchModels(form.id, form.provider, form.endpoint, form.apiKey, form.model, form.baseUrl.trim()) { result ->
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
    val defaultWindow = form.defaultWindows[model]?.let(::formatTokens).orEmpty()
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
    placeholder: String? = null,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
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
    /** A key typed to replace the saved one; blank keeps it ([hasSavedKey]). The saved key never reaches the app. */
    val apiKey: String,
    val model: String,
    val baseUrl: String,
    val canDelete: Boolean,
    val modelOptions: List<String>,
    val hasSavedKey: Boolean = false,
) {
    companion object {
        fun from(editor: ProviderEditState): ProviderDraft =
            ProviderDraft(
                id = editor.profileId,
                label = editor.profileLabel,
                provider = editor.provider,
                endpoint = editor.endpoint,
                apiKey = "",
                model = editor.model,
                baseUrl = editor.baseUrl,
                canDelete = editor.profileOptions.firstOrNull { it.id == editor.profileId }?.canDelete == true,
                modelOptions = editor.modelOptions,
                hasSavedKey = editor.hasApiKey,
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
    model = model.trim(), variant = "", baseUrl = baseUrl.trim(), profileId = id, provider = provider, endpoint = endpoint,
)

/** A token count as the usage page writes it: 128,000. */
private fun formatTokens(tokens: Long): String = NumberFormat.getIntegerInstance(Locale.US).format(tokens)
