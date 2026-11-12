package cc.noharry.bothy.app.model
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ModelSettingsView
import cc.noharry.agent.appserver.protocol.ProviderConnection
import cc.noharry.agent.appserver.protocol.ProviderCreateParams
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.protocol.ServiceView
import cc.noharry.agent.appserver.protocol.normalizeProviderModels
import cc.noharry.agent.appserver.protocol.parseContextWindow

/**
 * A provider profile as typed. Each model's context window, image input and output limit come from the server
 * ([applySettings]): until then the window and the limit are blank (their defaults) and images are on. A new profile
 * starts with choosing its provider ([chooseService], [chooseCustom]); a service fills the rest in, all of it editable.
 */
internal class ProviderFormState(
    initial: ProviderDraft,
) {
    val id = initial.id
    /** The profile has a key saved on the server; a blank [apiKey] keeps it. */
    val hasSavedKey = initial.hasSavedKey
    var label by mutableStateOf(initial.label)
    var provider by mutableStateOf(initial.provider)
        private set
    var endpoint by mutableStateOf(initial.endpoint)
        private set
    var apiKey by mutableStateOf(initial.apiKey)
    var model by mutableStateOf(initial.model)
        private set
    var pendingModel by mutableStateOf("")
    var modelOptions by mutableStateOf(normalizeProviderModels(initial.modelOptions + listOf(initial.model)))
        private set
    var baseUrl by mutableStateOf(initial.baseUrl)

    /**
     * The model service the profile is on ([ServiceView.id]): for a new one the provider chosen (null until then, blank
     * for Custom); for a saved one the service the server found for its address (blank: none it knows).
     */
    var service by mutableStateOf(initial.service)
        private set

    /** The region of the service's endpoint in use ([cc.noharry.agent.appserver.protocol.ServiceEndpointView.region]). */
    var region by mutableStateOf("")
        private set
    private var chosen: ServiceView? = null

    /**
     * The name the last choice filled in, at first the profile's own (the first launch's setup starts from the selected
     * profile): the next choice replaces it, unless it was typed over.
     */
    private var filledLabel = initial.label
    val windowInputs = mutableStateMapOf<String, String>().apply {
        modelOptions.forEach { put(it, "") }
    }
    /** 图片输入 per model: true = 文本+图片 (default), false = 仅文本. */
    val imageInputs = mutableStateMapOf<String, Boolean>().apply {
        modelOptions.forEach { put(it, true) }
    }
    /** Each model's own context window, for the field's placeholder. */
    val defaultWindows = mutableStateMapOf<String, Long>()

    /** The output limit typed per model ([ModelSettingsView.outputOverride]); blank asks for the default. */
    val outputInputs = mutableStateMapOf<String, String>()

    /** What a request asks for when no limit is set ([cc.noharry.agent.appserver.protocol.ModelFacts.requestOutputTokens]). */
    val defaultOutputs = mutableStateMapOf<String, Long>()

    /** The documented ceiling of one answer ([cc.noharry.agent.appserver.protocol.ModelFacts.maxOutputTokens]). */
    val maxOutputs = mutableStateMapOf<String, Long>()
    private val loaded = mutableSetOf<String>()

    /** The listed models whose settings the server has not given yet. */
    fun modelsToLoad(): List<String> = modelOptions.filter { it !in loaded }

    /** The server's settings for [models], each taken once. */
    fun applySettings(models: List<ModelSettingsView>) {
        models.filter { loaded.add(it.model) }.forEach { settings ->
            windowInputs[settings.model] = settings.windowOverride?.toString().orEmpty()
            imageInputs[settings.model] = settings.imageInput
            defaultWindows[settings.model] = settings.defaultWindow
            outputInputs[settings.model] = settings.outputOverride?.toString().orEmpty()
            settings.facts.requestOutputTokens?.let { defaultOutputs[settings.model] = it } ?: defaultOutputs.remove(settings.model)
            settings.facts.maxOutputTokens?.let { maxOutputs[settings.model] = it } ?: maxOutputs.remove(settings.model)
        }
    }

    /** Each listed model's settings as typed, to save. */
    fun modelSettings(): List<ModelSettingsView> = modelOptions.map { model ->
        ModelSettingsView(
            model,
            parseContextWindow(windowInputs[model].orEmpty()),
            imageInput = imageInputs[model] ?: true,
            outputOverride = parseContextWindow(outputInputs[model].orEmpty()),
        )
    }

    /**
     * [view] as the provider of a new profile, at its endpoint in [region] (its first region unless told): its name
     * (unless one was typed), endpoint, address, default model and model list fill the form in.
     */
    fun chooseService(view: ServiceView, region: String = view.regions().firstOrNull().orEmpty()) {
        val at = view.endpointIn(region) ?: return
        service = view.id
        chosen = view
        if (label.isBlank() || label == filledLabel) label = view.label
        filledLabel = view.label
        useEndpoint(at.endpoint, at.baseUrl, at.region)
        model = view.defaultModel
        loaded.clear()
        showModels(normalizeProviderModels(view.models + view.defaultModel), replace = true)
    }

    /** The chosen service's endpoint in [region], of the endpoint kind in use where it has one there. */
    fun chooseRegion(region: String) {
        val at = chosen?.endpointIn(region, endpoint) ?: return
        useEndpoint(at.endpoint, at.baseUrl, at.region)
    }

    /** Custom: the form as typed, emptied of what a service filled in. */
    fun chooseCustom() {
        service = ""
        chosen = null
        if (label == filledLabel) label = ""
        filledLabel = ""
        useEndpoint(ProviderEndpoint.defaultFor(ModelProvider.OPENAI), "", "")
        model = ""
        showModels(emptyList(), replace = true)
    }

    /** [view] as the service a saved profile is on, as the server found it: its region, and endpoints to follow; nothing filled in. */
    fun knownAs(view: ServiceView?) {
        chosen = view
        region = view?.let { it.regionAt(baseUrl) ?: it.regions().firstOrNull() }.orEmpty()
    }

    fun selectProvider(selected: ModelProvider) {
        if (selected == provider) return
        provider = selected
        val listed = chosen?.endpoints?.firstOrNull { it.region == region && it.endpoint.provider == selected }?.endpoint
        selectEndpoint(listed ?: ProviderEndpoint.defaultFor(selected))
    }

    /** The endpoint kind; on a service's address not typed over, the address follows to the service's endpoint of that kind. */
    fun selectEndpoint(kind: ProviderEndpoint) {
        endpoint = kind
        val view = chosen?.takeIf { it.lists(baseUrl) } ?: return
        view.endpoints.firstOrNull { it.region == region && it.endpoint == kind }?.let { baseUrl = it.baseUrl }
    }

    private fun useEndpoint(kind: ProviderEndpoint, address: String, at: String) {
        provider = kind.provider
        endpoint = kind
        baseUrl = address
        region = at
    }

    /** The model, typed where the form has no model list (the first launch's setup); it joins the list when saved. */
    fun typeModel(value: String) { model = value }

    fun updateApiKey(value: String) { apiKey = value }
    fun updateBaseUrl(value: String) { baseUrl = value }

    fun addPendingModel() {
        addModels(listOf(pendingModel))
        pendingModel = ""
    }

    fun addModels(models: List<String>) {
        showModels(normalizeProviderModels(modelOptions + models))
    }

    /**
     * 从上游拉取模型 replaces the list with what the service lists now (owner 2026-10-19: 新模型没有拉下来覆盖旧的列表; it
     * used to be merged in, so a retired model stayed). A selected model the service no longer lists is unselected, so
     * the composer asks again instead of keeping it; that model is returned. An empty answer keeps the list.
     */
    fun useFetchedModels(fetched: List<String>): String? {
        val models = normalizeProviderModels(fetched)
        if (models.isEmpty()) return null
        showModels(models)
        return model.takeIf { it.isNotBlank() && it !in models }?.also { model = "" }
    }

    /** [models] as the list; [replace] also drops what was typed for models no longer on it. */
    private fun showModels(models: List<String>, replace: Boolean = false) {
        modelOptions = models
        if (replace) listOf(windowInputs, outputInputs).forEach { inputs -> inputs.keys.filter { it !in models }.forEach { inputs.remove(it) } }
        models.forEach { if (it !in windowInputs) windowInputs[it] = "" }
        models.forEach { if (it !in imageInputs) imageInputs[it] = true }
    }

    fun syncModelOptions(initial: ProviderDraft) {
        if (provider == initial.provider && apiKey == initial.apiKey && baseUrl == initial.baseUrl) addModels(initial.modelOptions)
    }

    fun canSave(isNew: Boolean): Boolean = baseUrl.isNotBlank() && (!isNew || label.isNotBlank()) &&
        modelOptions.all { typedTokens(windowInputs[it]) && typedTokens(outputInputs[it]) }

    fun toDraft(initial: ProviderDraft): ProviderDraft =
        ProviderDraft(initial.id, label, provider, endpoint, apiKey, model, baseUrl, initial.canDelete,
            normalizeProviderModels(modelOptions + listOf(pendingModel)), hasSavedKey, service)

    /** The new profile as filled in, to create: with the chosen service's default model selected, none for Custom. */
    fun createParams(): ProviderCreateParams = ProviderCreateParams(label.trim(), provider, endpoint, apiKey.trim(), model.trim(), baseUrl.trim())

    fun connectionIdentity(): List<Any> = listOf(provider, endpoint, apiKey, baseUrl)

    /** The profile as filled in, for a service's checks; its passthrough check needs a model, the selected one or the first. */
    fun probeConnection(id: String): ProviderConnection =
        ProviderConnection(id, label, provider, endpoint, apiKey, model.ifBlank { modelOptions.firstOrNull().orEmpty() }, baseUrl.trim())
}

/** A token count field: blank (the default), or a positive whole number, with K or M. */
internal fun typedTokens(text: String?): Boolean = text.isNullOrBlank() || parseContextWindow(text) != null
