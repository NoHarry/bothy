package cc.noharry.bothy.app.image
import android.content.Context
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolCallTrace
import cc.noharry.agent.kernel.api.ToolCatalogCacheIdentity
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.bothy.app.overlay.OverlayPicture
import cc.noharry.bothy.app.tools.SchemaValidation
import cc.noharry.bothy.app.tools.validateInputSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `image_generate`: draws with the 生图模型 from Settings and saves the picture in app-private storage.
 * The result carries only a reference to the file, never image bytes; the overlay shows the picture from
 * the reference. Mounted only while the image model is enabled and has a key.
 */
internal class ImageToolProvider(
    private val generate: suspend (prompt: String, sessionId: String) -> ImageResult,
) : McpClientProvider, ToolCatalogCacheIdentity {
    constructor(context: Context) : this({ prompt, sessionId ->
        val app = context.applicationContext
        ImageGenerator(app).generate(loadImageModelConfig(app), prompt, sessionId)
    })

    override val serverId: String = IMAGE_TOOL_SERVER_ID
    override val displayName: String = "生图模型"
    override val catalogCacheIdentity: String = "bothy-app:$IMAGE_TOOL_SERVER_ID:v1"
    @OptIn(ExperimentalAgentRuntimeApi::class)
    override val providerConcurrency: ProviderConcurrencyDeclaration = ProviderConcurrencyDeclaration.threadSafe()

    override suspend fun listTools(): List<ToolDescriptor> = listOf(
        ToolDescriptor(
            name = IMAGE_GENERATE_TOOL,
            title = "生成图片",
            description = IMAGE_GENERATE_DESCRIPTION,
            inputSchemaJson = IMAGE_GENERATE_SCHEMA,
        ),
    )

    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
        if (toolName != IMAGE_GENERATE_TOOL) return failure("UNKNOWN_TOOL", "Unknown image tool: $toolName", validation = false)
        val invalid = validateInputSchema(IMAGE_GENERATE_SCHEMA, argumentsJson) as? SchemaValidation.Invalid
        if (invalid != null) return failure(invalid.code, invalid.message, validation = false)
        val prompt = Json.parseToJsonElement(argumentsJson).jsonObject["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        PictureBoard.drawing(context.requestId, prompt)
        // Saved under the run's session, so deleting that session deletes the picture too.
        val result = try {
            generate(prompt, context.sessionId).also { outcome ->
                if (outcome is ImageResult.Success) PictureBoard.drawn(context.requestId, outcome.image.reference, prompt)
            }
        } finally {
            PictureBoard.settle(context.requestId)
        }
        return when (result) {
            is ImageResult.Success -> ToolCallResult(
                success = true,
                resultJson = buildJsonObject {
                    put("ok", true)
                    putJsonObject("image") {
                        put("reference", result.image.reference)
                        put("width", result.image.width)
                        put("height", result.image.height)
                    }
                    put("elapsedMs", result.image.elapsedMillis)
                    put("message", "图片已生成，屏幕上正在显示。用一句话告诉用户画好了，不要描述文件路径。")
                }.toString(),
                trace = ToolCallTrace(validationOk = true, validationCode = "OK"),
            )
            is ImageResult.Failure -> failure("IMAGE_GENERATION_FAILED", result.message, validation = true)
        }
    }

    private fun failure(code: String, message: String, validation: Boolean): ToolCallResult = ToolCallResult(
        success = false,
        resultJson = buildJsonObject {
            put("ok", false)
            put("code", code)
            put("message", message)
        }.toString(),
        trace = ToolCallTrace(validationOk = validation, validationCode = code, validationMessage = message.takeIf { !validation }),
    )
}

/**
 * Pictures the image tool is drawing or drew, by the run's request ID, for views that see only the A2A wire (text
 * only): an A2A task's run uses the task ID as its request ID, so client mode talking to this car's own server, and
 * its mirrors, find the picture of the task on screen. In-process only; bounded to the latest few.
 */
internal object PictureBoard {
    private val pictures = object : LinkedHashMap<String, OverlayPicture>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OverlayPicture>?): Boolean =
            size > MAX_BOARD_PICTURES
    }

    @Synchronized fun drawing(requestId: String, prompt: String) {
        pictures[requestId] = OverlayPicture(null, prompt)
    }

    @Synchronized fun drawn(requestId: String, reference: String, prompt: String) {
        pictures[requestId] = OverlayPicture(reference, prompt)
    }

    /** A call that ended without a picture (failed or cancelled) leaves no frame behind; an earlier picture stays. */
    @Synchronized fun settle(requestId: String) {
        if (pictures[requestId]?.reference == null) pictures.remove(requestId)
    }

    @Synchronized fun forTask(taskId: String): OverlayPicture? = pictures[taskId]

    private const val MAX_BOARD_PICTURES = 16
}

internal const val IMAGE_GENERATE_TOOL = "image_generate"
private const val IMAGE_TOOL_SERVER_ID = "image_gen"
private val IMAGE_GENERATE_DESCRIPTION = """
    生成图片 / 画图 / 生图 / 画一张 / 插画 / 壁纸 / 海报 / 头像（image generation, draw a picture）：
    按文字描述生成一张图片，并直接显示在屏幕的悬浮窗上。prompt 写清主体、场景、风格、光线和构图；
    用户用中文描述时直接用中文。生成需要 15–30 秒，每个请求只调用一次；完成后用一句话告诉用户画好了。
""".trimIndent().replace("\n", "")
private const val IMAGE_GENERATE_SCHEMA =
    """{"type":"object","properties":{"prompt":{"type":"string","minLength":1,"maxLength":1000,""" +
        """"description":"画面描述：主体、场景、风格、光线、构图"}},"required":["prompt"],"additionalProperties":false}"""
