@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.tools
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolPrefetchProvider
import cc.noharry.agent.kernel.api.ToolPrefetchRequest
import cc.noharry.agent.kernel.api.ToolPrefetchTrace
import cc.noharry.agent.kernel.api.ToolProvider

/**
 * The device media tools on hand when the words are about playback (2026-10-26: with a B站 video in front, 「暂停一下」
 * went to Android Use, whose tools are loaded on every turn, and five taps missed the pause button; the video's media
 * session pauses it in one call). Catalogue hints only; the delegate runs everything.
 */
internal class MediaToolHints(private val delegate: ToolProvider) : ToolProvider by delegate, ToolPrefetchProvider {
    override val providerConcurrency: ProviderConcurrencyDeclaration = delegate.providerConcurrency
    override val remotePrefetchEnabled: Boolean get() = false

    override fun localToolNames(request: ToolPrefetchRequest): List<String> =
        if (!aboutPlayback(request.input)) emptyList()
        else request.tools.map { it.name }.filter { name -> MEDIA_HINT_TOOLS.any(name::endsWith) }.take(request.maxTools)

    override suspend fun prefetchTools(request: ToolPrefetchRequest, trace: ToolPrefetchTrace): List<String> = emptyList()
}

/** Whether [text] asks to play, pause, skip or name what plays (music, a video, a podcast). */
internal fun aboutPlayback(text: String): Boolean = PLAYBACK_WORDS.any { it in text }

private val MEDIA_HINT_TOOLS = listOf("media.control", "media.status")
private val PLAYBACK_WORDS = listOf(
    "暂停", "播放", "继续放", "接着放", "放首", "放点", "放歌", "快进", "快退", "倒回",
    "下一首", "上一首", "下一集", "上一集", "切歌", "换一首", "这首歌", "什么歌",
)
