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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
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
    var saved by remember { mutableStateOf(loadImageModelConfig(context)) }
    var draft by remember { mutableStateOf(ImageModelDraft.of(saved)) }
    // Credentials are never saveable UI state or echoed from storage.
    var key by remember { mutableStateOf("") }
    var note by remember { mutableStateOf(SettingsNote()) }
    val apply = { candidate: ImageModelConfig, newKey: String, clearKey: Boolean, done: String ->
        val error = saveImageModelConfig(context, candidate, newKey, clearKey)
        note = SettingsNote(error ?: done, error = error != null)
        if (error == null) saved = loadImageModelConfig(context)
        error == null
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).widthIn(max = 880.dp).verticalScroll(rememberScrollState()).padding(Dimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
        ) {
            Text(
                "OpenAI 兼容的 images/generations 接口，与对话模型分开配置。开启后助手可以调用 image_generate 生成图片，并显示在悬浮窗上。",
                color = ConsoleColors.muted,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("允许助手生成图片", Modifier.weight(1f), color = ConsoleColors.text)
                Switch(saved.enabled, { apply(saved.copy(enabled = it), "", false, IMAGE_SAVED) }, colors = switchColors())
            }
            ImageModelField("Base URL（例如 https://api.example.com/v1）", draft.baseUrl, { draft = draft.copy(baseUrl = it) })
            ImageModelField(if (saved.hasApiKey) "API key · 已保存（留空保持不变）" else "API key", key, { key = it }, secret = true)
            ImageModelPicker(saved) { model -> apply(saved.copy(model = model), "", false, IMAGE_SAVED) }
            ImageModelField("Size（可选，如 1024x1024；留空用服务默认）", draft.size, { draft = draft.copy(size = it) })
            ImageModelField("超时（秒，10–300）", draft.timeoutSeconds, { draft = draft.copy(timeoutSeconds = it) })
            TextButton(onClick = {
                if (apply(saved.copy(enabled = false), "", true, "已删除 API key · 生图已关闭")) key = ""
            }, enabled = saved.hasApiKey) { Text("删除 API key") }
            ImageModelTestButton(saved)
        }
        SettingsSaveBar(
            unsaved = draft != ImageModelDraft.of(saved) || key.isNotBlank(),
            note = note,
            onSave = { if (apply(draft.applyTo(saved), key, false, IMAGE_SAVED)) { key = ""; draft = ImageModelDraft.of(saved) } },
            onDiscard = { draft = ImageModelDraft.of(saved); key = ""; note = SettingsNote("已放弃修改") },
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
        Text(
            if (saved.model.isBlank()) "模型 · 未选" else "模型 · ${saved.model}",
            Modifier.weight(1f),
            style = ConsoleType.label,
            color = if (saved.model.isBlank()) ConsoleColors.warning else ConsoleColors.muted,
        )
        TextButton(enabled = canFetch && !loading, onClick = { refresh += 1 }) { Text("刷新列表") }
    }
    val result = models
    when {
        !canFetch -> Text("先填 Base URL 和 API key 并保存，再从服务拉取模型列表。", style = ConsoleType.bodySmall, color = ConsoleColors.muted)
        loading -> Text("正在拉取模型列表…", style = ConsoleType.bodySmall, color = ConsoleColors.muted)
        result == null -> Unit
        result.isFailure -> Text(
            "拉取失败：${result.exceptionOrNull()?.message ?: "未知错误"}",
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
    if (ids.isEmpty()) Text("服务没有列出任何模型。", style = ConsoleType.bodySmall, color = ConsoleColors.warning)
    if (selected.isNotBlank() && selected !in ids) {
        SelectableOptionRow(selected, true, {}, subtitle = "当前在用，服务的列表里没有")
    }
    if (images.isEmpty() && others.isNotEmpty()) {
        Text("没认出生图模型，到其他模型里找。", style = ConsoleType.bodySmall, color = ConsoleColors.muted)
    }
    images.forEach { id -> SelectableOptionRow(id, id == selected, { onPick(id) }) }
    if (others.isNotEmpty()) {
        TextButton(onClick = { showOthers = !showOthers }) { Text(if (showOthers) "收起其他模型" else "其他模型（${others.size}）") }
        if (showOthers) others.forEach { id -> SelectableOptionRow(id, id == selected, { onPick(id) }) }
    }
}

private const val IMAGE_SAVED = "已保存 · 下一次请求生效"

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
    Text("测试生成", style = ConsoleType.body, color = ConsoleColors.text, modifier = Modifier.padding(top = Dimens.spaceMd))
    ImageModelField("描述", prompt, { prompt = it })
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
        ) { Text(if (running) "生成中…" else "测试生成") }
        if (running) Text("${elapsed / 1000}s", color = ConsoleColors.muted)
        if (!config.hasApiKey) Text("先保存 API key", color = ConsoleColors.muted)
    }
    when (val outcome = result) {
        is ImageResult.Failure -> Text("失败（${outcome.elapsedMillis / 1000}s）：${outcome.message}", color = ConsoleColors.danger)
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
    image.revisedPrompt?.let { Text("改写后的描述：$it", color = ConsoleColors.muted) }
    bitmap?.let {
        Image(
            it,
            contentDescription = "生成的图片",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(PREVIEW_SIZE)
                .clip(RoundedCornerShape(Dimens.radiusMd))
                .clickable(onClickLabel = "放大查看") { viewing = true },
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
