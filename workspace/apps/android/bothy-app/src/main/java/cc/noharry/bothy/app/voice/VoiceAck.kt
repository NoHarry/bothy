package cc.noharry.bothy.app.voice
import android.content.Context
import android.util.Log
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.model.ModelVariantRegistry
import cc.noharry.bothy.app.model.ProviderConfig
import cc.noharry.bothy.app.model.isOnDevice
import cc.noharry.bothy.app.runtime.append
import cc.noharry.bothy.app.usage.ModelUsage
import cc.noharry.bothy.app.usage.UsageStore
import cc.noharry.bothy.app.usage.toModelUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The spoken acknowledgement of a voice append: one short sentence from the chat model in use, asked on its own
 * so it does not wait for the working agent's next round. Null when the provider is not ready, the call fails or
 * takes longer than [ACK_TIMEOUT_MILLIS]; the working agent is then asked to acknowledge instead.
 */
internal suspend fun voiceAck(context: Context, task: String, words: String): String? = withContext(Dispatchers.IO) {
    val started = System.nanoTime()
    fun elapsed() = (System.nanoTime() - started) / 1_000_000
    try {
        val config = ProviderConfig.load(context)
        val profile = config.activeProfile
        if (!config.isReady(profile)) return@withContext null.also { Log.w(TAG, "ack skipped: provider not ready") }
        // The on-device server has one slot: an ack would wait behind the agent's round or evict its cached prompt.
        if (profile.isOnDevice()) return@withContext null.also { Log.i(TAG, "ack skipped: on-device model") }
        val reply = StringBuilder()
        var firstThinking = -1L
        var firstText = -1L
        // One sentence needs no reasoning: thinking off wherever the model offers it (DeepSeek's `none`).
        val ref = config.modelRef(profile).let { ref ->
            val canSkipThinking = ModelVariantRegistry.variants(ref.providerId, ref.modelId).any { it.id == NO_THINKING }
            if (canSkipThinking) ref.copy(variant = NO_THINKING) else ref
        }
        val finished = withTimeoutOrNull(ACK_TIMEOUT_MILLIS) {
            config.client(profile, stream = true).complete(
                ModelRequest(messages = listOf(ModelMessage(role = "user", content = voiceAckPrompt(task, words))), modelRef = ref),
            ).collect { event ->
                when (event) {
                    is ModelEvent.ThinkingDelta -> if (firstThinking < 0) firstThinking = elapsed()
                    is ModelEvent.TextDelta -> {
                        if (firstText < 0) firstText = elapsed()
                        reply.append(event.text)
                    }
                    is ModelEvent.UsageDelta -> UsageStore.record(ref.modelId, event.toModelUsage())
                    else -> Unit
                }
            }
        }
        val line = voiceAckLine(reply.toString())
        Log.i(
            TAG,
            "ack ${if (finished == null) "timed out" else "done"} in ${elapsed()} ms model=${ref.modelId} variant=${ref.variant} " +
                "firstThinking=$firstThinking firstText=$firstText line=${line ?: "-"}",
        )
        // A sentence that arrived before the deadline is still usable.
        line
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(TAG, "ack failed in ${elapsed()} ms: ${error.javaClass.simpleName} ${error.message}")
        null
    }
}

internal fun voiceAckPrompt(task: String, words: String): String =
    "你是语音助手小博，正在执行用户的任务：「${task.ifBlank { "（当前任务）" }}」。用户在你执行时追加说：「$words」。" +
        "用一句不超过15个字的口语回应，让用户知道你收到了并会一起处理，例如「好，空调也一起打开」。" +
        "不要说已经完成，不要提问，只输出这一句话。"

/** The first line of the reply, without quotes, cut to a short spoken sentence; null when nothing usable is left. */
internal fun voiceAckLine(reply: String): String? =
    reply.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty)
        ?.trim('"', '“', '”', '「', '」', '\'')
        ?.take(MAX_ACK_CHARS)
        ?.takeIf(String::isNotBlank)

private const val ACK_TIMEOUT_MILLIS = 6_000L
private const val NO_THINKING = "none"
private const val MAX_ACK_CHARS = 30
private const val TAG = "DebugVoice"

private fun ModelEvent.UsageDelta.toModelUsage() = ModelUsage(
    requests = 1,
    inputTokens = inputTokens.toLong(),
    cacheReadTokens = cacheReadTokens.toLong(),
    cacheWriteTokens = cacheWriteTokens.toLong(),
    outputTokens = outputTokens.toLong(),
)
