package cc.noharry.bothy.app
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import cc.noharry.agent.appserver.protocol.ProviderCreateParams
import cc.noharry.agent.appserver.protocol.ProviderEditState
import cc.noharry.agent.appserver.protocol.ProviderModelsParams
import cc.noharry.agent.appserver.protocol.ServiceView
import cc.noharry.agent.appserver.protocol.SettingsRequests
import cc.noharry.agent.appserver.protocol.TextParams
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import cc.noharry.agent.appserver.protocol.normalizeProviderModels
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.app.frontend.ServerAccess
import cc.noharry.bothy.app.model.CUSTOM_SERVICE
import cc.noharry.bothy.app.model.ConfigField
import cc.noharry.bothy.app.model.KeyHint
import cc.noharry.bothy.app.model.ProtocolFields
import cc.noharry.bothy.app.model.ProviderDraft
import cc.noharry.bothy.app.model.ProviderFormState
import cc.noharry.bothy.app.model.ServicePicker
import cc.noharry.bothy.app.model.keyHintText
import cc.noharry.bothy.app.model.named
import cc.noharry.bothy.app.model.rememberServices
import cc.noharry.bothy.protocol.CheckLine
import cc.noharry.bothy.protocol.OnboardingRequests

/**
 * 1. A model key: the provider, as in 设置 → 模型 → 添加服务 (the services the server knows, or Custom), set to the selected
 * profile's own until another is chosen; then the key, checked against the service's model list. Left as it is, the key
 * goes to the selected profile (Bothy's DeepSeek preset on a fresh install); another provider gets a profile of its own,
 * selected, with the key.
 */
@Composable
internal fun ModelKeyStep() {
    val server = LocalServer.current
    val resources = LocalResources.current
    val services = rememberServices()
    val active = server.state.configEditor
    val form = remember(active.profileId, services) { onboardingForm(active, services.orEmpty()) }
    var key by OnboardingDraft.modelKey
    var status by remember { mutableStateOf<Pair<String, Color>?>(null) }
    val good = ConsoleColors.success
    val bad = ConsoleColors.danger
    val checking = stringResource(R.string.onboarding_checking) to ConsoleColors.muted
    val chosen = services.orEmpty().named(form.service)
    OnboardingStep(stringResource(R.string.onboarding_step_model_key), done = status?.second == good) {
        Text(stringResource(R.string.onboarding_model_key_note), style = ConsoleType.bodySmall, color = ConsoleColors.muted)
        // Until the services are known the form would read as Custom; it waits for them.
        if (services != null) {
            if (services.isNotEmpty()) ServicePicker(services, form)
            OnboardingConnectionFields(form, chosen)
        }
        KeyField(chosen?.let { stringResource(R.string.onboarding_service_key, it.label) } ?: "API key", key) { key = it }
        chosen?.let { resources.keyHintText(it, form.region) }?.let { KeyHint(it) }
        Button(enabled = key.isNotBlank() && form.readyToSave(), onClick = {
            status = checking
            server.saveOnboardingKey(form.onboardingSave(active), key, refused = { status = it.message.orEmpty() to bad }) { checked ->
                status = resources.words(checked.finding) to if (checked.failed) bad else good
            }
        }) { Text(stringResource(R.string.onboarding_save_and_check)) }
        status?.let { (text, color) -> Text(text, style = ConsoleType.bodySmall, color = color) }
    }
}

/** Custom's connection as typed; a service fills it in, and asks only for a model when it names none of its own. */
@Composable
private fun OnboardingConnectionFields(form: ProviderFormState, chosen: ServiceView?) {
    val modelField = @Composable { ConfigField(stringResource(R.string.onboarding_model), form.model, onValueChange = form::typeModel) }
    when {
        form.service == CUSTOM_SERVICE -> Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
            ConfigField(stringResource(R.string.model_service_name), form.label) { form.label = it }
            ProtocolFields(form)
            ConfigField("Base URL", form.baseUrl, onValueChange = form::updateBaseUrl)
            modelField()
        }
        chosen != null && chosen.defaultModel.isBlank() -> modelField()
    }
}

/**
 * The setup's form: the selected profile as it is, on the service the server found for it, else as Custom (also when
 * the server knows no service).
 */
internal fun onboardingForm(active: ProviderEditState, services: List<ServiceView>): ProviderFormState {
    val on = services.named(active.service)
    return ProviderFormState(ProviderDraft.from(active).copy(service = on?.id ?: CUSTOM_SERVICE)).apply { knownAs(on) }
}

/** Whether there is enough to save a key to: an address, a name and a model. */
internal fun ProviderFormState.readyToSave(): Boolean = baseUrl.isNotBlank() && label.isNotBlank() && model.isNotBlank()

/** Where 保存并检查 puts the key. */
internal sealed interface OnboardingSave {
    /** Into the selected profile, left as it was. */
    data object Selected : OnboardingSave

    /** Into a new profile for the provider chosen ([params] without the key), which creating it selects, with [models]. */
    data class Create(val params: ProviderCreateParams, val models: List<String>) : OnboardingSave
}

/** The selected profile when its connection was left as it is ([active]); else a new profile as filled in. */
internal fun ProviderFormState.onboardingSave(active: ProviderEditState): OnboardingSave {
    val unchanged = provider == active.provider && endpoint == active.endpoint &&
        baseUrl.trim() == active.baseUrl.trim() && model.trim() == active.model.trim()
    if (unchanged) return OnboardingSave.Selected
    val params = createParams().copy(apiKey = "")
    return OnboardingSave.Create(params, normalizeProviderModels(modelOptions + params.model))
}

/**
 * Saves [key] where [save] says, then checks the selected profile with it (`onboarding/saveModelKey`, which saves the key
 * to the selected profile and asks its service for its models): ready, or why not.
 */
internal fun ServerAccess.saveOnboardingKey(save: OnboardingSave, key: String, refused: (JsonRpcException) -> Unit, checked: (CheckLine) -> Unit) {
    val check = { send(OnboardingRequests.SAVE_MODEL_KEY, TextParams(key), refused, checked) }
    when (save) {
        OnboardingSave.Selected -> check()
        is OnboardingSave.Create -> send(SettingsRequests.PROVIDER_CREATE, save.params.copy(apiKey = key.trim()), refused) { created ->
            val profile = with(save.params) {
                ProviderEditState(model = model, baseUrl = baseUrl, profileId = created.profileId, provider = provider, endpoint = endpoint)
            }
            send(SettingsRequests.PROVIDER_SAVE_MODELS, ProviderModelsParams(profile, save.models), refused) { check() }
        }
    }
}
