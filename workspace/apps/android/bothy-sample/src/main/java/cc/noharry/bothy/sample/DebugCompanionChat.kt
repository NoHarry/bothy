package cc.noharry.bothy.sample

import cc.noharry.bothy.transport.HttpEvent
import cc.noharry.bothy.transport.HttpRequest
import cc.noharry.bothy.transport.HttpTransport
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * One reply from a companion service over the OpenAI-compatible `/chat/completions`, streamed. The stream is cut into
 * lines on bytes, so a character split between two chunks is decoded whole. A service that ignores `stream` and
 * answers with one JSON body is read too.
 */
internal class DebugCompanionChat(private val http: HttpTransport) {
    /** Throws IOException when the service fails or is silent for [COMPANION_TIMEOUT_MILLIS], so the agent takes the turn. */
    suspend fun reply(endpoint: DebugCompanionEndpoint, key: String, messages: List<Pair<String, String>>): String =
        withTimeoutOrNull(COMPANION_TIMEOUT_MILLIS) {
            var status = 0
            val lines = SseLines()
            val text = StringBuilder()
            http.stream(request(endpoint, key, messages)).collect { event ->
                when (event) {
                    is HttpEvent.Response -> status = event.statusCode
                    is HttpEvent.Data -> lines.feed(event.bytes).forEach { line -> if (status in 200..299) streamedText(line)?.let(text::append) }
                    HttpEvent.Done -> Unit
                }
            }
            if (status !in 200..299) throw IOException(companionHttpProblem(status, lines.raw()))
            text.toString().ifEmpty { wholeReply(lines.raw()) }.trim()
        } ?: throw IOException("陪聊服务 ${COMPANION_TIMEOUT_MILLIS / 1000} 秒没有回应")

    private fun request(endpoint: DebugCompanionEndpoint, key: String, messages: List<Pair<String, String>>): HttpRequest {
        val extra = endpoint.extraBody.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it) as? JsonObject }.orEmpty()
        val body = buildJsonObject {
            extra.forEach { (name, value) -> put(name, value) }
            put("model", endpoint.model)
            put("stream", true)
            put("max_tokens", MAX_REPLY_TOKENS)
            put("messages", buildJsonArray {
                messages.forEach { (role, content) -> add(buildJsonObject { put("role", role); put("content", content) }) }
            })
        }
        return HttpRequest(
            method = "POST",
            url = endpoint.baseUrl.trim().trimEnd('/') + "/chat/completions",
            headers = mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json", "Accept" to "text/event-stream"),
            body = body.toString().toByteArray(Charsets.UTF_8),
        )
    }
}

/** Splits a byte stream into UTF-8 lines; keeps everything for an error body or a non-streamed reply. */
private class SseLines {
    private val pending = ByteArrayOutputStream()
    private val all = ByteArrayOutputStream()

    fun feed(bytes: ByteArray): List<String> {
        all.write(bytes)
        val lines = mutableListOf<String>()
        bytes.forEach { byte ->
            if (byte == '\n'.code.toByte()) {
                lines += pending.toString(Charsets.UTF_8.name()).trimEnd('\r')
                pending.reset()
            } else {
                pending.write(byte.toInt())
            }
        }
        return lines
    }

    fun raw(): String = all.toString(Charsets.UTF_8.name())
}

/** The text a `data:` line adds: `choices[0].delta.content`; null for anything else (`[DONE]`, reasoning, usage). */
internal fun streamedText(line: String): String? {
    if (!line.startsWith("data:")) return null
    val payload = line.removePrefix("data:").trim()
    if (payload.isEmpty() || payload == "[DONE]") return null
    val choice = (jsonObjectOrNull(payload)?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
    return ((choice["delta"] as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull
}

/** `choices[0].message.content` of a body that came back whole. */
private fun wholeReply(body: String): String {
    val choice = (jsonObjectOrNull(body)?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject
    return ((choice?.get("message") as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty()
}

internal fun companionHttpProblem(status: Int, body: String): String {
    val message = ((jsonObjectOrNull(body)?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
    val hint = when (status) {
        401, 403 -> "，检查 API key（千问的 sk-ws- 工作空间 key 要用工作空间地址）"
        404 -> "，检查地址和模型名"
        else -> ""
    }
    return "HTTP $status${message?.let { "：${it.take(MAX_ERROR_CHARS)}" } ?: ""}$hint"
}

private fun jsonObjectOrNull(text: String): JsonObject? =
    try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * The messages for one chat: the hand-back rule, then the persona, the latest exchanges of this session (the agent's
 * too, so 「刚才那家店怎么样」 has its context), then the words with a reminder of the rule. With the rule only after the
 * persona, a flight booking got 「这个我可办不了」; with history and no reminder, 「给老婆发个消息」 was answered too. The
 * reminder rides on the user message, since some services take a system message only first. [profile] is what 小博
 * remembers about the user ([memoryProfileText]), so small talk knows who it talks to.
 */
internal fun companionMessages(persona: String, recent: List<Pair<String, String>>, words: String, profile: String = ""): List<Pair<String, String>> =
    listOf("system" to listOf(HANDOFF_RULE, persona, profile).filter(String::isNotBlank).joinToString("\n\n")) +
        recent.takeLast(MAX_HISTORY_EXCHANGES).flatMap { (user, assistant) -> listOf("user" to user, "assistant" to assistant) } +
        ("user" to "$words\n$HANDOFF_REMINDER")

/** Where a voice turn goes, and why: logged for every turn while the companion is on. */
internal data class CompanionDecision(val toCompanion: Boolean, val reason: String)

/**
 * Small talk goes to the companion only when Jev scored it so, nothing in the words asks for something the agent does,
 * and the agent did not just ask a question (an answer is for it). Observe mode decides the same but sends nothing.
 */
internal fun decideCompanionRoute(
    config: DebugCompanionConfig,
    words: String,
    smallTalk: Double?,
    lastAssistant: String?,
    lastWasCompanion: Boolean,
): CompanionDecision {
    val taskWord = COMPANION_TASK_WORDS.firstOrNull { it in words }
    val answering = !lastWasCompanion && lastAssistant?.trimEnd()?.let { it.endsWith('？') || it.endsWith('?') } == true
    return when {
        !config.enabled -> CompanionDecision(false, "off")
        smallTalk == null -> CompanionDecision(false, "not scored")
        smallTalk < config.threshold -> CompanionDecision(false, "small_talk %.2f < %.2f".format(smallTalk, config.threshold))
        taskWord != null -> CompanionDecision(false, "task word 「$taskWord」")
        answering -> CompanionDecision(false, "answers the agent's question")
        config.mode == DebugCompanionMode.OBSERVE -> CompanionDecision(false, "observe: would chat (%.2f)".format(smallTalk))
        else -> CompanionDecision(true, "small_talk %.2f".format(smallTalk))
    }
}

/** Words that name something the agent does: they keep a turn with it however Jev scored the small talk. */
internal val COMPANION_TASK_WORDS = listOf(
    "导航", "路线", "打开", "关闭", "关掉", "开一下", "空调", "温度", "窗户", "窗帘", "灯", "加热", "通风", "闹钟",
    "音量", "大声", "小声", "播放", "放一首", "放首", "放点", "听歌", "音乐", "电台", "视频", "暂停", "下一首", "上一首",
    "打电话", "打给", "发消息", "短信", "提醒", "闹钟", "日程", "设置", "查一下", "查查", "帮我查", "搜一下", "搜索",
    "天气", "几点", "几号", "附近", "地图", "加油站", "充电桩", "停车场", "拍照", "截图", "屏幕", "返回", "退出",
    "订票", "机票", "火车票", "高铁票", "订酒店", "订餐", "外卖", "打车", "叫车",
    // 记忆 is 小博's (user_memory): 「记住我家在…」 told to 陪聊 would be answered and forgotten.
    "记住", "记一下", "记下来", "忘掉", "别记", "叫我",
    // 计划 is 小博's too (plan_manage).
    "定时", "计划",
)

private const val HANDOFF_RULE =
    "先判断用户这句话：如果是在请你做事或查信息——导航、控车、放音乐、打电话、发消息、订票订餐、设提醒、查天气、查资料、" +
        "让你记住或忘掉什么、操作手机或屏幕等，不管你能不能做到——只输出「$COMPANION_HANDOFF」这四个字，不要输出任何别的内容。" +
        "只有纯闲聊（聊天、倾诉、开玩笑、讲笑话、讲故事、问你的看法）才正常回答。"
private const val HANDOFF_REMINDER =
    "（看用户最新这句话：要你做事或查信息，比如订票、发消息、设闹钟、导航、放歌，只输出「$COMPANION_HANDOFF」；" +
        "说心情、累了、聊天、开玩笑、问你看法，就正常陪他聊。）"

private const val COMPANION_TIMEOUT_MILLIS = 20_000L
private const val MAX_REPLY_TOKENS = 300
private const val MAX_HISTORY_EXCHANGES = 4
private const val MAX_ERROR_CHARS = 160
