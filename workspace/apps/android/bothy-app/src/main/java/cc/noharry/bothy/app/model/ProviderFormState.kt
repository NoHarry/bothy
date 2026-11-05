package cc.noharry.bothy.app.model
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ModelSettingsView
import cc.noharry.agent.appserver.protocol.ProviderConnection
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.protocol.normalizeProviderModels
import cc.noharry.agent.appserver.protocol.parseContextWindow

/**
 * A provider profile as typed. Each model's context window and image input come from the server ([applySettings]):
 * until then the window is blank (its default) and images are on.
 */
internal class ProviderFormState(
    initial: ProviderDraft,
) {
    val id = initial.id
    /** The profile has a key saved on the server; a blank [apiKey] keeps it. */
    val hasSavedKey = initial.hasSavedKey
    var label by mutableStateOf(initial.label)
    var provider by mutableStateOf(initial.provider)
    var endpoint by mutableStateOf(initial.endpoint)
    var apiKey by mutableStateOf(initial.apiKey)
    var model by mutableStateOf(initial.model)
        private set
    var pendingModel by mutableStateOf("")
    var modelOptions by mutableStateOf(normalizeProviderModels(initial.modelOptions + listOf(initial.model)))
        private set
    var baseUrl by mutableStateOf(initial.baseUrl)
    val windowInputs = mutableStateMapOf<String, String>().apply {
        modelOptions.forEach { put(it, "") }
    }
    /** 图片输入 per model: true = 文本+图片 (default), false = 仅文本. */
    val imageInputs = mutableStateMapOf<String, Boolean>().apply {
        modelOptions.forEach { put(it, true) }
    }
    /** Each model's own context window, for the field's placeholder. */
    val defaultWindows = mutableStateMapOf<String, Long>()
    private val loaded = mutableSetOf<String>()

    /** The listed models whose settings the server has not given yet. */
    fun modelsToLoad(): List<String> = modelOptions.filter { it !in loaded }

    /** The server's settings for [models], each taken once. */
    fun applySettings(models: List<ModelSettingsView>) {
        models.filter { loaded.add(it.model) }.forEach { settings ->
            windowInputs[settings.model] = settings.windowOverride?.toString().orEmpty()
            imageInputs[settings.model] = settings.imageInput
            defaultWindows[settings.model] = settings.defaultWindow
        }
    }

    /** Each listed model's settings as typed, to save. */
    fun modelSettings(): List<ModelSettingsView> = modelOptions.map { model ->
        ModelSettingsView(model, parseContextWindow(windowInputs[model].orEmpty()), imageInput = imageInputs[model] ?: true)
    }

    fun selectProvider(selected: ModelProvider) {
        if (selected == provider) return
        provider = selected
        endpoint = ProviderEndpoint.defaultFor(selected)
    }

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

    private fun showModels(models: List<String>) {
        modelOptions = models
        models.forEach { if (it !in windowInputs) windowInputs[it] = "" }
        models.forEach { if (it !in imageInputs) imageInputs[it] = true }
    }

    fun syncModelOptions(initial: ProviderDraft) {
        if (provider == initial.provider && apiKey == initial.apiKey && baseUrl == initial.baseUrl) addModels(initial.modelOptions)
    }

    fun canSave(isNew: Boolean): Boolean = baseUrl.isNotBlank() && (!isNew || label.isNotBlank()) &&
        modelOptions.all { windowInputs[it].isNullOrBlank() || parseContextWindow(windowInputs[it].orEmpty()) != null }

    fun toDraft(initial: ProviderDraft): ProviderDraft =
        ProviderDraft(initial.id, label, provider, endpoint, apiKey, model, baseUrl, initial.canDelete,
            normalizeProviderModels(modelOptions + listOf(pendingModel)), hasSavedKey)

    fun connectionIdentity(): List<Any> = listOf(provider, endpoint, apiKey, baseUrl)

    /** The profile as filled in, for the DeepSeek check; its passthrough check needs a model, the selected one or the first. */
    fun probeConnection(id: String): ProviderConnection =
        ProviderConnection(id, label, provider, endpoint, apiKey, model.ifBlank { modelOptions.firstOrNull().orEmpty() }, baseUrl.trim())
}
