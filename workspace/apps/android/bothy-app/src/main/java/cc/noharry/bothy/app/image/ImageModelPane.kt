package cc.noharry.bothy.app.image
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.SettingsNote
import cc.noharry.bothy.app.console.SettingsSaveBar
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.outlinedTextFieldColors
import cc.noharry.bothy.app.console.singleLine
import cc.noharry.bothy.app.console.switchColors
import cc.noharry.bothy.app.console.ui.SelectableOptionRow
import cc.noharry.bothy.app.voice.tts.validationError
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings → 生图模型: the image model is its own entry, independent of the chat providers, so a device
 * can chat through one service and draw through another. The switch and 删除 API key apply when tapped; the typed
 * fields wait for 保存. "测试生成" runs one generation with the saved settings and previews the result.
 */
@Composable
internal fun ImageModelPane() {
    val context = LocalContext.current
    val resources = LocalResources.current
    var saved by remember { mutableStateOf(loadImageModelConfig(context)) }
    var draft by remember { mutableStateOf(ImageModelDraft.of(saved)) }
    // Credentials are never saveable UI state or echoed from storage.
    var key by remember { mutableStateOf("") }
    var note by remember { mutableStateOf(SettingsNote()) }
    val savedNote = stringResource(R.string.image_saved)
    val keyDeleted = stringResource(R.string.image_key_deleted)
    val discarded = stringResource(R.string.image_discarded)
    val apply = { candidate: ImageModelConfig, newKey: String, clearKey: Boolean, done: String ->
        val error = saveImageModelConfig(context, candidate, newKey, clearKey)
        note = SettingsNote(error?.let { resources.getString(it.shown) } ?: done, error = error != null)
        if (error == null) saved = loadImageModelConfig(context)
        error == null
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).widthIn(max = 880.dp).verticalScroll(rememberScrollState()).padding(Dimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
        ) {
            Text(stringResource(R.string.image_intro), color = ConsoleColors.muted)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.image_allow), Modifier.weight(1f), color = ConsoleColors.text)
                Switch(saved.enabled, { apply(saved.copy(enabled = it), "", false, savedNote) }, colors = switchColors())
            }
            ImageModelField(stringResource(R.string.image_base_url), draft.baseUrl, { draft = draft.copy(baseUrl = it) })
            val keyLabel = if (saved.hasApiKey) R.string.image_api_key_saved else R.string.image_api_key
            ImageModelField(stringResource(keyLabel), key, { key = it }, secret = true)
            ImageModelPicker(saved) { model -> apply(saved.copy(model = model), "", false, savedNote) }
            ImageModelField(stringResource(R.string.image_size), draft.size, { draft = draft.copy(size = it) })
            ImageModelField(stringResource(R.string.image_timeout), draft.timeoutSeconds, { draft = draft.copy(timeoutSeconds = it) })
            TextButton(onClick = {
                if (apply(saved.copy(enabled = false), "", true, keyDeleted)) key = ""
            }, enabled = saved.hasApiKey) { Text(stringResource(R.string.image_delete_key)) }
            ImageModelTestButton(saved)
        }
        SettingsSaveBar(
            unsaved = draft != ImageModelDraft.of(saved) || key.isNotBlank(),
            note = note,
            onSave = {
                if (apply(draft.applyTo(saved), key, false, savedNote)) { key = ""; draft = ImageModelDraft.of(saved) }
            },
            onDiscard = { draft = ImageModelDraft.of(saved); key = ""; note = SettingsNote(discarded) },
        )
    }
}

/** The typed fields; numbers stay text until saved, where validation says what is wrong. The model is picked. */
private data class ImageModelDraft(val baseUrl: String, val size: String, val timeoutSeconds: String) {
    fun applyTo(saved: ImageModelConfig): ImageModelConfig =
        saved.copy(baseUrl = baseUrl, size = size, timeoutMillis = (timeoutSeconds.toLongOrNull() ?: 0L) * 1000)

    companion object {
        fun of(config: ImageModelConfig) = ImageModelDraft(config.baseUrl, config.size, (config.timeoutMillis / 1000).toString())
    }
}

/**
 * 模型: picked from the provider's own list (`GET {baseUrl}/models` with the saved key), never typed; picking one saves
 * it at once. The list follows the saved Base URL and key, so it is fetched again after they are saved.
 */
@Composable
private fun ImageModelPicker(saved: ImageModelConfig, onPick: (String) -> Unit) {
    val context = LocalContext.current
    var models by remember { mutableStateOf<Result<List<String>>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val canFetch = saved.hasApiKey && saved.baseUrl.isNotBlank()
    LaunchedEffect(saved.baseUrl, saved.hasApiKey, refresh) {
        models = null
        if (!canFetch) return@LaunchedEffect
        loading = true
        models = fetchImageModels(context, saved.baseUrl)
        loading = false
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val current = if (saved.model.isBlank()) stringResource(R.string.image_model_none)
        else stringResource(R.string.image_model_current, saved.model)
        val currentColor = if (saved.model.isBlank()) ConsoleColors.warning else ConsoleColors.muted
        Text(current, Modifier.weight(1f), style = ConsoleType.label, color = currentColor)
        TextButton(enabled = canFetch && !loading, onClick = { refresh += 1 }) {
            Text(stringResource(R.string.image_refresh_list))
        }
    }
    val result = models
    when {
        !canFetch -> Text(stringResource(R.string.image_fetch_first), style = ConsoleType.bodySmall, color = ConsoleColors.muted)
        loading -> Text(stringResource(R.string.image_fetching), style = ConsoleType.bodySmall, color = ConsoleColors.muted)
        result == null -> Unit
        result.isFailure -> Text(
            stringResource(
                R.string.image_fetch_failed,
                result.exceptionOrNull()?.message ?: stringResource(R.string.image_unknown_error),
            ),
            style = ConsoleType.bodySmall,
            color = ConsoleColors.danger,
        )
        else -> ImageModelList(result.getOrThrow(), saved.model, onPick)
    }
}

/** Image models first; the rest fold under 其他模型, opened when the saved model is among them. */
@Composable
private fun ImageModelList(ids: List<String>, selected: String, onPick: (String) -> Unit) {
    val (images, others) = ids.partition(::looksLikeImageModel)
    var showOthers by remember(selected) { mutableStateOf(selected in others) }
    if (ids.isEmpty()) {
        Text(stringResource(R.string.image_no_models), style = ConsoleType.bodySmall, color = ConsoleColors.warning)
    }
    if (selected.isNotBlank() && selected !in ids) {
        SelectableOptionRow(selected, true, {}, subtitle = stringResource(R.string.image_model_not_listed))
    }
    if (images.isEmpty() && others.isNotEmpty()) {
        Text(stringResource(R.string.image_no_image_models), style = ConsoleType.bodySmall, color = ConsoleColors.muted)
    }
    images.forEach { id -> SelectableOptionRow(id, id == selected, { onPick(id) }) }
    if (others.isNotEmpty()) {
        TextButton(onClick = { showOthers = !showOthers }) {
            Text(
                if (showOthers) {
                    stringResource(R.string.image_hide_others)
                } else {
                    stringResource(R.string.image_other_models, others.size)
                },
            )
        }
        if (showOthers) others.forEach { id -> SelectableOptionRow(id, id == selected, { onPick(id) }) }
    }
}

/** One generation with the saved settings; shows elapsed time while waiting and the image when done. */
@Composable
private fun ImageModelTestButton(config: ImageModelConfig) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf(DEFAULT_TEST_PROMPT) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var result by remember { mutableStateOf<ImageResult?>(null) }
    val running = startedAt > 0L
    LaunchedEffect(running) {
        while (running) {
            elapsed = System.currentTimeMillis() - startedAt
            delay(TICK_MS)
        }
    }
    Text(
        stringResource(R.string.image_test),
        style = ConsoleType.body,
        color = ConsoleColors.text,
        modifier = Modifier.padding(top = Dimens.spaceMd),
    )
    ImageModelField(stringResource(R.string.image_prompt), prompt, { prompt = it })
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = {
                startedAt = System.currentTimeMillis()
                result = null
                scope.launch {
                    result = ImageGenerator(context).generate(config, prompt, IMAGE_TEST_SESSION, keep = TEST_IMAGES_KEPT)
                    startedAt = 0L
                }
            },
            enabled = !running && config.hasApiKey && config.validationError() == null,
        ) { Text(stringResource(if (running) R.string.image_generating else R.string.image_test)) }
        if (running) Text("${elapsed / 1000}s", color = ConsoleColors.muted)
        if (!config.hasApiKey) Text(stringResource(R.string.image_save_key_first), color = ConsoleColors.muted)
    }
    when (val outcome = result) {
        is ImageResult.Failure -> Text(
            stringResource(R.string.image_test_failed, outcome.elapsedMillis / 1000, outcome.message),
            color = ConsoleColors.danger,
        )
        is ImageResult.Success -> GeneratedPreview(outcome.image)
        null -> Unit
    }
}

@Composable
private fun GeneratedPreview(image: GeneratedImageResult) {
    val bitmap by rememberPicture(image.reference, PREVIEW_PX)
    var viewing by remember(image.reference) { mutableStateOf(false) }
    if (viewing) GeneratedImageViewer(image.reference, image.revisedPrompt.orEmpty()) { viewing = false }
    Text(
        "${image.width}×${image.height} · ${image.bytes / 1024} KB · ${"%.1f".format(image.elapsedMillis / 1000.0)}s · ${image.reference}",
        color = ConsoleColors.muted,
    )
    image.revisedPrompt?.let { Text(stringResource(R.string.image_revised_prompt, it), color = ConsoleColors.muted) }
    val viewLarger = stringResource(R.string.image_view_larger)
    bitmap?.let {
        Image(
            it,
            contentDescription = stringResource(R.string.image_generated),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(PREVIEW_SIZE)
                .clip(RoundedCornerShape(Dimens.radiusMd))
                .clickable(onClickLabel = viewLarger) { viewing = true },
        )
    }
}

@Composable
private fun ImageModelField(label: String, value: String, change: (String) -> Unit, secret: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = change, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        singleLine = true, colors = outlinedTextFieldColors(),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

private const val DEFAULT_TEST_PROMPT = "雪山脚下的湖泊，清晨薄雾，电影感，广角"
/** Test pictures only need to outlive their preview. */
internal const val TEST_IMAGES_KEPT = 3
private const val TICK_MS = 250L
private const val PREVIEW_PX = 768
private val PREVIEW_SIZE = 360.dp
