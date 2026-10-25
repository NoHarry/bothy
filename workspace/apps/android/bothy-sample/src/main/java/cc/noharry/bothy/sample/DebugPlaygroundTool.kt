package cc.noharry.bothy.sample

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolCallTrace
import cc.noharry.agent.kernel.api.ToolCatalogCacheIdentity
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolPrefetchProvider
import cc.noharry.agent.kernel.api.ToolPrefetchRequest
import cc.noharry.agent.kernel.api.ToolPrefetchTrace
import cc.noharry.agent.androiduse.AndroidUseAccessibility
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `playground_open`: brings Bothy Playground's head unit, or its home, to the front (owner 2026-10-25: 「要加一个技能方便
 * 别人回到playground这个界面」; the skill is `skills/playground`). Bothy starts it itself, which its overlay permission
 * allows from the background, so it needs no screen control. Mounted only while the Playground is installed.
 */
@OptIn(ExperimentalAgentRuntimeApi::class)
internal class DebugPlaygroundToolProvider(
    private val open: (scene: String) -> Boolean,
    /** Whether one of these packages came to the front after [open]; null where it cannot be seen (screen control off). */
    private val cameToFront: suspend (packages: Set<String>) -> Boolean? = { null },
) : McpClientProvider, ToolCatalogCacheIdentity, ToolPrefetchProvider {
    constructor(context: Context) : this(
        open = { scene -> openPlayground(context.applicationContext, scene) },
        cameToFront = { packages -> waitForFront(context.applicationContext, packages) },
    )

    override val serverId: String = PLAYGROUND_TOOL_SERVER_ID
    override val displayName: String = "Bothy Playground"
    override val catalogCacheIdentity: String = "bothy-sample:$PLAYGROUND_TOOL_SERVER_ID:v1"
    override val providerConcurrency: ProviderConcurrencyDeclaration = ProviderConcurrencyDeclaration.threadSafe()

    override suspend fun listTools(): List<ToolDescriptor> = listOf(
        ToolDescriptor(
            name = PLAYGROUND_OPEN_TOOL,
            title = "回到车机",
            description = PLAYGROUND_OPEN_DESCRIPTION,
            inputSchemaJson = PLAYGROUND_OPEN_SCHEMA,
        ),
    )

    /**
     * Loaded at the start of every run (owner 2026-10-25: 「回到车机这个工具一直加载吧」): 「回到车机」 is called at once
     * instead of after a search_tools round (≈15 s on the tablet, with a look at the screen as well).
     */
    override fun localToolNames(request: ToolPrefetchRequest): List<String> = listOf(PLAYGROUND_OPEN_TOOL)

    override val remotePrefetchEnabled: Boolean get() = false

    override suspend fun prefetchTools(request: ToolPrefetchRequest, trace: ToolPrefetchTrace): List<String> = emptyList()

    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
        if (toolName != PLAYGROUND_OPEN_TOOL) return failure("UNKNOWN_TOOL", "Unknown playground tool: $toolName")
        val scene = runCatching { Json.parseToJsonElement(argumentsJson).jsonObject["scene"]?.jsonPrimitive?.contentOrNull }
            .getOrNull()?.takeIf { it in PLAYGROUND_SCENES } ?: "cabin"
        if (!open(scene)) return failure("PLAYGROUND_NOT_INSTALLED", "这台设备没装 Bothy Playground，告诉用户装上它才能回到车机。")
        // Opened through the interface; only if something else is in front then (owner 2026-10-25: a system dialog
        // asking whether to allow the launch, 「接口开可能会遇到弹窗，这时候才走android use」) does the screen come in.
        val expected = if (scene == "nav") setOf(PLAYGROUND_PACKAGE, AMAP_PACKAGE) else setOf(PLAYGROUND_PACKAGE)
        val inFront = cameToFront(expected)
        return ToolCallResult(
            success = true,
            resultJson = buildJsonObject {
                put("ok", true)
                put("scene", scene)
                if (inFront != null) put("inFront", inFront)
                put("message", if (inFront == false) NOT_IN_FRONT else PLAYGROUND_OPENED.getValue(scene))
            }.toString(),
            trace = ToolCallTrace(validationOk = true, validationCode = "OK"),
        )
    }

    private fun failure(code: String, message: String): ToolCallResult = ToolCallResult(
        success = false,
        resultJson = buildJsonObject {
            put("ok", false)
            put("code", code)
            put("message", message)
        }.toString(),
        trace = ToolCallTrace(validationOk = true, validationCode = code),
    )
}

/** Waits up to [FRONT_WAIT_MS] for one of [packages] to be the app in front; null without screen control to see it. */
private suspend fun waitForFront(context: Context, packages: Set<String>): Boolean? {
    if (!AndroidUseAccessibility.isConnected()) return null
    repeat((FRONT_WAIT_MS / FRONT_CHECK_MS).toInt()) {
        if (AndroidUseAccessibility.foregroundApp(context)?.packageName in packages) return true
        delay(FRONT_CHECK_MS)
    }
    return false
}

internal fun playgroundInstalled(context: Context): Boolean =
    context.packageManager.getLaunchIntentForPackage(PLAYGROUND_PACKAGE) != null

/** The Playground's own activity with the scene it reads ([PLAYGROUND_EXTRA_SCENE]); false when it is not installed. */
private fun openPlayground(context: Context, scene: String): Boolean = try {
    context.startActivity(
        Intent().setClassName(PLAYGROUND_PACKAGE, "$PLAYGROUND_PACKAGE.PlaygroundActivity")
            .putExtra(PLAYGROUND_EXTRA_SCENE, scene)
            // The head unit already up takes the scene in onNewIntent instead of a second copy opening over it.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )
    true
} catch (_: ActivityNotFoundException) {
    false
}

internal const val PLAYGROUND_PACKAGE = "cc.noharry.bothy.playground"
private const val AMAP_PACKAGE = "com.autonavi.minimap"
private const val FRONT_WAIT_MS = 2_500L
private const val FRONT_CHECK_MS = 250L
private const val NOT_IN_FRONT = "已经发出打开请求，但它没有到前台，多半是系统弹了确认框（是否允许打开）挡住了。现在用 android_observe " +
    "看屏幕：是打开 Bothy Playground（或高德地图）的确认，就点「允许」或「打开」，确认它到了前台再 finish；是别的就如实告诉用户。"
internal const val PLAYGROUND_OPEN_TOOL = "playground_open"
/** The Playground reads it as the scene to show: `cabin` (the head unit), `home`, or `nav` (where the user drives). */
private const val PLAYGROUND_EXTRA_SCENE = "scene"
private const val PLAYGROUND_TOOL_SERVER_ID = "playground_screen"
private val PLAYGROUND_OPENED = mapOf(
    "cabin" to "车机已在前台。",
    "home" to "虚拟小家已在前台（真实导航模式下没有小家，会停在车机）。",
    "nav" to "已回到导航：用户在车机设置里选了「导航交给高德地图 App」的，打开的是高德地图，否则是车机自己的地图。",
)
private val PLAYGROUND_SCENES = PLAYGROUND_OPENED.keys
private val PLAYGROUND_OPEN_DESCRIPTION = """
    回到车机 / 打开车机 / 回到 Playground / 打开虚拟座舱 / 去虚拟小家 / 回到导航界面（open the head unit）：把 Bothy Playground
    的车机界面切到屏幕前台；scene=home 打开虚拟小家；scene=nav 回到导航，按用户在车机里的选择打开高德地图 App 或车机自己的地图。
    直接调用，不用先看屏幕或找图标。
""".trimIndent().replace("\n", "")
private const val PLAYGROUND_OPEN_SCHEMA =
    """{"type":"object","properties":{"scene":{"type":"string","enum":["cabin","home","nav"],""" +
        """"description":"cabin 车机（默认），home 虚拟小家，nav 回到导航界面"}},"additionalProperties":false}"""
