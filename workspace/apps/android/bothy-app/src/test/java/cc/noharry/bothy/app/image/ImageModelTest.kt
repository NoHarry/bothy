package cc.noharry.bothy.app.image
import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.bothy.app.console.ConversationRenderItem
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.console.toRenderItems
import cc.noharry.bothy.app.overlay.OverlayPicture
import cc.noharry.bothy.app.plans.matches
import cc.noharry.bothy.app.session.ConversationItem
import cc.noharry.bothy.app.session.ToolCallStatus
import cc.noharry.bothy.app.session.TranscriptRole
import cc.noharry.bothy.app.voice.tts.validationError
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ImageModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun baseUrlMustBeHttpsOrLoopbackHttp() {
        val base = ImageModelConfig(baseUrl = "https://api.example.com/v1", model = "img-1")
        assertNull(base.validationError())
        assertNull(base.copy(baseUrl = "http://127.0.0.1:18080/v1").validationError())
        assertTrue(base.copy(baseUrl = "http://api.example.com/v1").validationError().orEmpty().contains("https"))
        assertTrue(base.copy(baseUrl = "https://api.example.com/v1?key=x").validationError().orEmpty().contains("query"))
        assertTrue(base.copy(size = "big").validationError().orEmpty().contains("Size"))
        assertTrue(base.copy(enabled = true).validationError().orEmpty().contains("API key"))
        // An untouched, disabled entry is valid so the page can be saved empty.
        assertNull(ImageModelConfig().validationError())
    }

    @Test
    fun keyIsStoredApartFromSettingsAndNeverEchoed() {
        val config = ImageModelConfig(enabled = true, baseUrl = "https://api.example.com/v1/", model = "img-1")
        assertNull(saveImageModelConfig(context, config, replacementKey = "sk-test-key"))
        val loaded = loadImageModelConfig(context)
        assertTrue(loaded.ready)
        assertEquals("https://api.example.com/v1", loaded.baseUrl)
        val prefs = context.getSharedPreferences("debug_image_model", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(prefs.contains("sk-test-key"))
        assertNull(saveImageModelConfig(context, loaded.copy(enabled = false), clearKey = true))
        assertFalse(loadImageModelConfig(context).hasApiKey)
    }

    @Test
    fun anApkCarryingTheImageModelSeedsSettingsOnceAndLaterEditsWin() {
        val embedded = mapOf(
            "IMAGE_MODEL_BASE_URL" to "https://img.example.com/v1",
            "IMAGE_MODEL_NAME" to "img-2",
            "IMAGE_MODEL_API_KEY" to "embedded-key",
        )
        seedEmbeddedImageModel(context) { embedded }
        val seeded = loadImageModelConfig(context)
        assertTrue(seeded.ready)
        assertEquals("img-2", seeded.model)
        assertEquals("embedded-key", loadImageModelKey(context))

        // The user removes the key: the embedded one must not come back on the next load.
        assertNull(saveImageModelConfig(context, seeded.copy(enabled = false), clearKey = true))
        seedEmbeddedImageModel(context) { embedded }
        assertEquals("", loadImageModelKey(context))
    }

    @Test
    fun anEmbeddedImageModelNeverOverridesSavedSettings() {
        val mine = ImageModelConfig(enabled = true, baseUrl = "https://mine.example.com/v1", model = "mine")
        assertNull(saveImageModelConfig(context, mine, replacementKey = "my-key"))
        seedEmbeddedImageModel(context) {
            mapOf("IMAGE_MODEL_BASE_URL" to "https://img.example.com/v1", "IMAGE_MODEL_NAME" to "img-2", "IMAGE_MODEL_API_KEY" to "embedded-key")
        }
        assertEquals("my-key", loadImageModelKey(context))
        assertEquals("mine", loadImageModelConfig(context).model)
    }

    @Test
    fun anApkWithoutTheValuesSeedsNothingAndLeavesNoMark() {
        seedEmbeddedImageModel(context) { mapOf("IMAGE_MODEL_NAME" to "img-2") }
        assertEquals("", loadImageModelKey(context))
        val prefs = context.getSharedPreferences("debug_image_model", Context.MODE_PRIVATE)
        assertFalse(prefs.contains("embedded_seeded"))
    }

    @Test
    fun theModelIsPickedAfterBaseUrlAndKeyAreSaved() {
        val unpicked = ImageModelConfig(baseUrl = "https://api.example.com/v1")
        assertNull(unpicked.validationError())
        assertTrue(unpicked.copy(enabled = true).validationError().orEmpty().contains("Pick a model"))
        assertTrue(unpicked.copy(model = "img 1").validationError().orEmpty().contains("whitespace"))
    }

    @Test
    fun imageModelsAreRecognisedAmongAGatewaysList() {
        listOf("gpt-image-1", "dall-e-3", "flux-1-schnell", "doubao-seedream-4-0-250828", "qwen-image", "gemini-2.5-flash-image-preview", "sd3-medium", "cogview-4")
            .forEach { assertTrue(it, looksLikeImageModel(it)) }
        listOf("deepseek-chat", "gpt-4o", "claude-sonnet-4", "qwen3-max", "mimo-v2.5-tts")
            .forEach { assertFalse(it, looksLikeImageModel(it)) }
    }

    @Test
    fun requestSendsSizeOnlyWhenSet() {
        val config = ImageModelConfig(baseUrl = "https://api.example.com/v1", model = "img-1")
        assertFalse(imageRequestBody(config, "猫").containsKey("size"))
        assertEquals("\"1536x1024\"", imageRequestBody(config.copy(size = "1536x1024"), "猫")["size"].toString())
    }

    @Test
    fun payloadPrefersInlineImageThenUrl() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val inline = parseImagePayload(json("""{"data":[{"b64_json":"$encoded","url":"https://x/y.png"}]}"""))
        assertArrayEquals(bytes, (inline as ImagePayload.Inline).bytes)
        val prefixed = parseImagePayload(json("""{"data":[{"b64_json":"data:image/png;base64,$encoded"}]}"""))
        assertArrayEquals(bytes, (prefixed as ImagePayload.Inline).bytes)
        assertEquals(ImagePayload.Remote("https://x/y.png"), parseImagePayload(json("""{"data":[{"url":"https://x/y.png"}]}""")))
        assertNull(parseImagePayload(json("""{"data":[]}""")))
        assertNull(parseImagePayload(json("""{"error":{"message":"quota"}}""")))
    }

    @Test
    fun imageNamesSurviveTracePhoneRedaction() {
        // Mirrors TraceSecurity's phone pattern: a dated name was rewritten to [REDACTED_PHONE] on the bench.
        val phone = Regex("""(?<!\d)(?:\+?\d[\d -]{8,}\d)(?!\d)""")
        val random = Random(7)
        listOf(1_791_538_029_106L, 1_800_000_000_000L, 9_999_999_999_999L).forEach { now ->
            val name = generatedImageName(now, random)
            assertFalse(name, phone.containsMatchIn(name))
            assertTrue(name, Regex("""img-[a-z0-9]+-[a-z]{4}""").matches(name))
        }
    }

    @Test
    fun picturesLiveInTheirSessionFolderAndGoWithIt() {
        val kept = File(sessionImagesDir(context, "keep-me"), "img-a-bcde.jpg").also { it.parentFile?.mkdirs(); it.writeText("x") }
        val dropped = File(sessionImagesDir(context, "drop-me"), "img-b-cdef.jpg").also { it.parentFile?.mkdirs(); it.writeText("x") }
        val reference = "$GENERATED_IMAGES_DIR/${dropped.parentFile!!.name}/${dropped.name}"
        assertEquals(dropped.canonicalFile, generatedImageFile(context, reference))

        assertTrue(deleteSessionGeneratedImages(context, "drop-me"))
        assertFalse(dropped.exists())
        assertTrue(kept.exists())
        assertNull(generatedImageFile(context, reference))
        assertTrue(deleteSessionGeneratedImages(context, "never-drew"))
    }

    @Test
    fun referencesOutsideASessionFolderAreRejected() {
        File(context.filesDir, "secret.txt").writeText("x")
        File(context.filesDir, "$GENERATED_IMAGES_DIR/loose.jpg").also { it.parentFile?.mkdirs(); it.writeText("x") }
        assertNull(generatedImageFile(context, "$GENERATED_IMAGES_DIR/abc/../../secret.txt"))
        assertNull(generatedImageFile(context, "$GENERATED_IMAGES_DIR/loose.jpg"))
    }

    @Test
    fun toolReturnsOnlyAReferenceAndDrawsInTheRunsSession() = runBlocking {
        var seenSession = ""
        val provider = ImageToolProvider { _, session ->
            seenSession = session
            ImageResult.Success(GeneratedImageResult("$GENERATED_IMAGES_DIR/c2Vzcw/img-a-bcde.jpg", 1024, 1024, 300_000, 16_000, null))
        }
        val result = provider.callTool(IMAGE_GENERATE_TOOL, """{"prompt":"雪山湖泊"}""", ToolCallContext("sess", "run", "req"))
        assertTrue(result.success)
        assertEquals("sess", seenSession)
        val body = Json.parseToJsonElement(result.resultJson).jsonObject
        assertEquals("\"$GENERATED_IMAGES_DIR/c2Vzcw/img-a-bcde.jpg\"", body["image"]!!.jsonObject["reference"].toString())
        assertFalse(result.resultJson.contains("b64"))
    }

    @Test
    fun toolReportsFailuresAndRejectsBadArguments() = runBlocking {
        val failing = ImageToolProvider { _, _ -> ImageResult.Failure("HTTP 401: bad key", 120) }
        val failed = failing.callTool(IMAGE_GENERATE_TOOL, """{"prompt":"猫"}""", ToolCallContext("s", "r", "q"))
        assertFalse(failed.success)
        assertTrue(failed.resultJson.contains("IMAGE_GENERATION_FAILED"))
        val invalid = failing.callTool(IMAGE_GENERATE_TOOL, """{"size":"big"}""", ToolCallContext("s", "r", "q"))
        assertFalse(invalid.success)
        assertFalse(invalid.trace!!.validationOk)
    }

    @Test
    fun stoppingARunCancelsAnImageRequestAtOnce() = runBlocking {
        MockWebServer().use { server ->
            // A provider that never answers would keep the run waiting until its two-minute timeout.
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val config = ImageModelConfig(enabled = true, baseUrl = "http://127.0.0.1:${server.port}/v1", model = "m")
            assertNull(saveImageModelConfig(context, config, replacementKey = "test-key"))
            val generation = launch(Dispatchers.IO) {
                ImageGenerator(context).generate(loadImageModelConfig(context), "猫", "s")
            }
            server.takeRequest(5, TimeUnit.SECONDS)
            val stopped = System.nanoTime()
            generation.cancelAndJoin()
            assertTrue(generation.isCancelled)
            assertTrue((System.nanoTime() - stopped) / 1_000_000 < 2_000)
        }
    }

    @Test
    fun consoleShowsThePictureRightAfterItsCollapsedToolGroup() {
        val reference = "$GENERATED_IMAGES_DIR/c2Vzcw/img-mv0rmhd9-jtne.jpg"
        val items = listOf(
            ConversationItem.TurnHeader(id = 1, label = "You", preview = "画一张图", fullText = "画一张图"),
            tool(2, "mcp__image_gen__image_generate", """{"ok":true,"image":{"reference":"$reference"}}"""),
            tool(3, "finish", """{"response":"画好了"}"""),
            ConversationItem.MessageItem(id = 4, role = TranscriptRole.ASSISTANT, content = "画好了"),
        )
        val rendered = items.toRenderItems()
        assertTrue(rendered[1] is ConversationRenderItem.BusinessToolGroup)
        assertEquals(ConversationRenderItem.GeneratedImage(2, OverlayPicture(reference, "雪山湖泊")), rendered[2])
        assertEquals(1, rendered.count { it is ConversationRenderItem.GeneratedImage })
    }

    private fun tool(id: Long, name: String, result: String) = ConversationItem.ToolCallItem(
        id = id,
        toolName = name,
        argumentsJson = """{"prompt":"雪山湖泊"}""",
        resultJson = result,
        status = ToolCallStatus.DONE,
        startedAt = 0L,
    )

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
}
