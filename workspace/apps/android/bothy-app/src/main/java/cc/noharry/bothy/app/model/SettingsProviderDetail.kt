package cc.noharry.bothy.app.model
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ModelSettingsParams
import cc.noharry.agent.appserver.protocol.ModelSettingsRequests
import cc.noharry.agent.appserver.protocol.ModelSettingsSaveParams
import cc.noharry.agent.appserver.protocol.ProviderEditState
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.protocol.ServiceView
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.outlinedTextFieldColors
import cc.noharry.bothy.app.console.switchColors
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.app.finding
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
    val services = rememberServices()
    val form = remember(initial.id) { ProviderFormState(initial) }
    LaunchedEffect(initial.id, initial.modelOptions) { form.syncModelOptions(initial) }
    ServiceChoiceEffect(form, services, isNew)
    ModelSettingsEffect(form, initial, isNew)
    var notice by remember(initial.id) { mutableStateOf("") }
    Column(modifier.fillMaxSize()) {
        Text(
            if (isNew) stringResource(R.string.model_add_service) else initial.label,
            style = ConsoleType.panelTitle,
            color = ConsoleColors.text,
        )
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
            item { ProviderConnectionFields(form, isNew, initial.id, services) }
            // A new profile shows the rest once its provider is chosen.
            if (form.service != null) {
                item {
                    HorizontalDivider(color = ConsoleColors.borderSubtle)
                    ProviderModelFields(form, actions)
                }
                items(form.modelOptions, key = { it }) { model -> ProviderModelRow(form, model) }
            }
        }
        if (notice.isNotBlank()) Text(notice, style = ConsoleType.meta, color = ConsoleColors.accent)
        HorizontalDivider(color = ConsoleColors.borderSubtle)
        ProviderDetailActions(form.toDraft(initial), actions, isNew, form.service != null && form.canSave(isNew), onDone) {
            form.addPendingModel()
            val draft = form.toDraft(initial)
            saveProviderConnection(form, draft, actions, isNew, refused = { notice = resources.words(it) }) { id ->
                val editor = draft.toEditor().copy(profileId = id)
                server.send(ModelSettingsRequests.MODELS_SAVE, ModelSettingsSaveParams(editor, form.modelSettings()))
                actions.saveProviderModels(editor, draft.modelOptions)
                notice = resources.getString(R.string.model_saved_choose_model)
                if (isNew) onCreated(id)
            }
        }
    }
}

/**
 * Once the services are known: a new profile with none to choose from (the models plugin off) is Custom at once; a
 * saved one takes the service the server found for it, whose endpoints its address follows.
 */
@Composable
private fun ServiceChoiceEffect(form: ProviderFormState, services: List<ServiceView>?, isNew: Boolean) {
    LaunchedEffect(form, services) {
        when {
            services == null -> Unit
            !isNew -> form.knownAs(services.named(form.service))
            services.isEmpty() && form.service == null -> form.chooseCustom()
        }
    }
}

/** Each listed model's settings, read once: a new profile's at the address filled in, a saved one's as saved. */
@Composable
private fun ModelSettingsEffect(form: ProviderFormState, initial: ProviderDraft, isNew: Boolean) {
    val server = LocalServer.current
    LaunchedEffect(form, form.modelOptions) {
        val missing = form.modelsToLoad()
        if (missing.isEmpty()) return@LaunchedEffect
        val profile = if (isNew) form.toDraft(initial).toEditor() else initial.toEditor()
        server.send(ModelSettingsRequests.MODELS_READ, ModelSettingsParams(profile, missing)) { form.applySettings(it.models) }
    }
}

/**
 * Saves the connection, then gives the profile's id (a new profile's once the server made it); or why the server refused
 * it (a saved key kept for another host, which the person types again).
 */
private fun saveProviderConnection(
    form: ProviderFormState,
    draft: ProviderDraft,
    actions: ConsoleActions,
    isNew: Boolean,
    refused: (Finding) -> Unit,
    saved: (String) -> Unit,
) {
    if (isNew) return actions.saveNewProfile(form.createParams(), saved)
    actions.saveConfig(draft.provider, draft.apiKey, draft.model, draft.baseUrl.trim(), draft.endpoint) { why -> if (why == null) saved(draft.id) else refused(why) }
}

/**
 * The provider (a new profile's choice, or the service a saved one is on), then the connection: name, protocol,
 * endpoint, base URL and key, with where to get a key and what the service answers beyond chat.
 */
@Composable
private fun ProviderConnectionFields(form: ProviderFormState, isNew: Boolean, profileId: String, services: List<ServiceView>?) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        when {
            !isNew -> ServiceLine(services, form)
            !services.isNullOrEmpty() -> ServicePicker(services, form)
        }
        if (form.service == null) return@Column
        if (isNew) ConfigField(stringResource(R.string.model_service_name), form.label) { form.label = it }
        ProtocolFields(form)
        ConfigField("Base URL", form.baseUrl, onValueChange = form::updateBaseUrl)
        val hint = services.orEmpty().named(form.service)?.let { LocalResources.current.keyHintText(it, form.region) }
        ConfigField(
            "API key", form.apiKey, secret = true, onValueChange = form::updateApiKey,
            placeholder = stringResource(R.string.model_api_key_saved).takeIf { form.hasSavedKey },
            supporting = hint?.let { text -> @Composable { KeyHint(text) } },
        )
        ServiceExtras(form, isNew, profileId, services.orEmpty())
    }
}

/** The wire protocol and its endpoint; on a service's own address, the address follows the endpoint chosen. */
@Composable
internal fun ProtocolFields(form: ProviderFormState) {
    val openAi = stringResource(R.string.model_protocol_openai)
    SettingsChoiceMenu(
        label = stringResource(R.string.model_type),
        selected = form.provider,
        options = ModelProvider.entries,
        optionLabel = { if (it == ModelProvider.OPENAI) openAi else it.label },
        onSelected = form::selectProvider,
    )
    SettingsChoiceMenu(
        label = stringResource(R.string.model_endpoint),
        selected = form.endpoint,
        options = ProviderEndpoint.options(form.provider),
        optionLabel = { it.label },
        onSelected = form::selectEndpoint,
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
                    message = if (connection == form.connectionIdentity()) {
                        resources.fetchedWords(form, result)
                    } else {
                        resources.getString(R.string.model_fetch_connection_changed)
                    }
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

/** The fetched list taken into [form], in words: how many, the selected model it dropped, or why none came. */
private fun android.content.res.Resources.fetchedWords(form: ProviderFormState, result: Result<List<String>>): String =
    result.fold(
        onSuccess = { fetched ->
            val unselected = form.useFetchedModels(fetched)
            val count = form.modelOptions.size
            when {
                fetched.none { it.isNotBlank() } -> getString(R.string.model_fetch_empty)
                unselected != null -> getQuantityString(R.plurals.model_fetched_unselected, count, count, unselected)
                else -> getQuantityString(R.plurals.model_fetched, count, count)
            }
        },
        onFailure = { failed -> (failed as? JsonRpcException)?.finding()?.let { this.words(it) } ?: getString(R.string.model_fetch_failed) },
    )

/**
 * One model: its name and image input, then its context and output limits: on its line on a wide pane, side by side
 * under it on a narrower one, one under the other on a phone.
 */
@Composable
private fun ProviderModelRow(form: ProviderFormState, model: String) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxWidth
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
                Text(model, modifier = Modifier.weight(1f), style = ConsoleType.body, color = ConsoleColors.text)
                if (width >= MODEL_ROW_ONE_LINE) ModelLimitFields(form, model, fill = false)
                ModelImageInputSwitch(form, model)
            }
            when {
                width >= MODEL_ROW_ONE_LINE -> Unit
                width >= MODEL_LIMITS_SIDE_BY_SIDE -> ModelLimitFields(form, model, fill = true)
                else -> Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
                    ContextLimitField(form, model, Modifier.fillMaxWidth())
                    OutputLimitField(form, model, Modifier.fillMaxWidth())
                }
            }
            HorizontalDivider(color = ConsoleColors.borderSubtle)
        }
    }
}

/** The two limits in a row of their own, tops aligned whatever their notes' lengths; [fill] shares the row's width. */
@Composable
private fun ModelLimitFields(form: ProviderFormState, model: String, fill: Boolean) {
    Row(if (fill) Modifier.fillMaxWidth() else Modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        val each = if (fill) Modifier.weight(1f) else Modifier.width(LIMIT_FIELD_WIDTH)
        ContextLimitField(form, model, each)
        OutputLimitField(form, model, each)
    }
}

@Composable
private fun ContextLimitField(form: ProviderFormState, model: String, modifier: Modifier) {
    val text = form.windowInputs[model].orEmpty()
    val valid = typedTokens(text)
    val defaultWindow = form.defaultWindows[model]?.let(::formatTokens).orEmpty()
    TokenField(
        text, { form.windowInputs[model] = it }, stringResource(R.string.model_context_limit),
        placeholder = stringResource(R.string.model_context_default, defaultWindow),
        supporting = if (valid) stringResource(R.string.model_context_default_compacts, defaultWindow) else stringResource(R.string.model_context_invalid),
        valid = valid, modifier = modifier,
    )
}

/**
 * 输出上限: the output tokens this model's requests ask for ([cc.noharry.agent.appserver.protocol.ModelSettingsView.outputOverride]);
 * blank asks for what the service's data says, shown as the placeholder, and its documented ceiling when known.
 */
@Composable
private fun OutputLimitField(form: ProviderFormState, model: String, modifier: Modifier) {
    val text = form.outputInputs[model].orEmpty()
    val valid = typedTokens(text)
    val default = form.defaultOutputs[model]?.let(::formatTokens)
    val max = form.maxOutputs[model]?.let(::formatTokens)
    val placeholder = default?.let { stringResource(R.string.model_output_default, it) } ?: stringResource(R.string.model_output_service_default)
    TokenField(
        text, { form.outputInputs[model] = it }, stringResource(R.string.model_output_limit),
        placeholder = placeholder,
        supporting = when {
            !valid -> stringResource(R.string.model_context_invalid)
            max != null -> stringResource(R.string.model_output_up_to, placeholder, max)
            else -> placeholder
        },
        valid = valid, modifier = modifier,
    )
}

@Composable
private fun TokenField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    supporting: String,
    valid: Boolean,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        supportingText = { Text(supporting) },
        modifier = modifier, singleLine = true, isError = !valid,
        colors = outlinedTextFieldColors(),
    )
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
internal fun ConfigField(
    label: String,
    value: String,
    secret: Boolean = false,
    placeholder: String? = null,
    supporting: (@Composable () -> Unit)? = null,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supporting,
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
    /** The model service it is on ([ProviderFormState.service]): null for a new profile until its provider is chosen. */
    val service: String? = null,
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
                service = editor.service,
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

internal fun ProviderDraft.toEditor(): ProviderEditState = ProviderEditState(
    model = model.trim(), variant = "", baseUrl = baseUrl.trim(), profileId = id, provider = provider, endpoint = endpoint,
)

/** A token count as the usage page writes it: 128,000. */
internal fun formatTokens(tokens: Long): String = NumberFormat.getIntegerInstance(Locale.US).format(tokens)

/** Wide enough for a model's name, both limits and its image switch on one line. */
private val MODEL_ROW_ONE_LINE = 760.dp

/** Wide enough for the two limits side by side under the model's name. */
private val MODEL_LIMITS_SIDE_BY_SIDE = 480.dp
private val LIMIT_FIELD_WIDTH = 240.dp
