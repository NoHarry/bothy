package cc.noharry.bothy.protocol

import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.TextParams
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.serialization.Serializable

/**
 * 陪聊 (owner 2026-10-13: 「我准备接入一个陪聊的api,也就是用户闲聊的时候走那边」; 「元宝/混元、豆包、qwen3.8-omni-flash
 * 都可以适配下，设置里面可以配置」): small talk in a voice session goes to a companion chat service over the
 * OpenAI-compatible `/chat/completions`, everything else to the agent. Each service keeps its own address, model,
 * key and extra request fields, so switching between them keeps what was filled in.
 */
@Serializable
enum class CompanionService(
    val defaultBaseUrl: String,
    val defaultModel: String,
    /** Merged into each request: Qwen's omni model thinks by default, far too slow for a chat line. */
    val defaultExtraBody: String,
) {
    QWEN(
        "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "qwen3.8-omni-flash",
        """{"enable_thinking": false}""",
    ),
    DOUBAO("https://ark.cn-beijing.volces.com/api/v3", "", ""),
    HUNYUAN(
        "https://api.hunyuan.cloud.tencent.com/v1",
        "hunyuan-turbos-latest",
        "",
    ),
    CUSTOM("", "", ""),
    ;

    val wireName: String get() = name.lowercase()

    companion object {
        fun from(value: String?): CompanionService = entries.firstOrNull { it.wireName == value?.trim()?.lowercase() } ?: QWEN
    }
}

/** Observe first (owner's rollout): score and log where each turn would go, while everything still reaches the agent. */
@Serializable
enum class CompanionMode(val wireName: String) {
    OBSERVE("observe"),
    ROUTE("route"),
    ;

    companion object {
        fun from(value: String?): CompanionMode = entries.firstOrNull { it.wireName == value } ?: OBSERVE
    }
}

/** One service's settings; its key is stored apart, on the server, and never sent back. */
@Serializable
data class CompanionEndpoint(
    val baseUrl: String,
    val model: String,
    val extraBody: String,
)

@Serializable
data class CompanionConfig(
    val enabled: Boolean = false,
    val mode: CompanionMode = CompanionMode.OBSERVE,
    /** Jev's `small_talk` score from which a turn is small talk. High: a request misread as chat goes undone. */
    val threshold: Double = DEFAULT_COMPANION_THRESHOLD,
    val service: CompanionService = CompanionService.QWEN,
    val persona: String = DEFAULT_COMPANION_PERSONA,
    val endpoints: Map<CompanionService, CompanionEndpoint> = emptyMap(),
) {
    fun endpoint(service: CompanionService = this.service): CompanionEndpoint =
        endpoints[service] ?: CompanionEndpoint(service.defaultBaseUrl, service.defaultModel, service.defaultExtraBody)

    /** Whether Jev should score small talk at all: on, in either mode. */
    val scoring: Boolean get() = enabled
}

const val DEFAULT_COMPANION_THRESHOLD = 0.75

/** 小博 chatting: short, spoken, warm; a request to do something is handed back to the agent with 【交回】. */
const val DEFAULT_COMPANION_PERSONA =
    "你是小博，一个语音助手，现在在陪用户闲聊。回答像朋友说话一样口语化、温暖，一般一两句，不超过 60 个字；不用列表、" +
        "标题、表情符号和括号说明，因为会被读出来。不知道的事就直说，不编造。"

/**
 * Settings → 陪聊 as stored: the settings, the services with a key of their own ([ownKeys]) and those that can be asked
 * at all ([usableKeys]: Qwen also takes speech recognition's 百炼 key), and why the chosen one cannot be asked yet.
 */
@Serializable
data class CompanionView(
    val config: CompanionConfig = CompanionConfig(),
    val ownKeys: Set<CompanionService> = emptySet(),
    val usableKeys: Set<CompanionService> = emptySet(),
    val notReady: Finding? = null,
)

/** A blank [key] keeps the stored one. */
@Serializable
data class CompanionSaveParams(val config: CompanionConfig, val key: String = "")

@Serializable
data class CompanionSaveResult(val view: CompanionView, val error: Finding? = null)

/** One routing decision, newest first: when (epoch milliseconds), the start of the words, where they went and why. */
@Serializable
data class CompanionDecisionView(val atEpochMillis: Long, val words: String, val toCompanion: Boolean, val reason: Finding)

@Serializable
data class CompanionDecisionsResult(val decisions: List<CompanionDecisionView>)

/** 试一试: the reply and how long it took, or why there is none. */
@Serializable
data class CompanionTryResult(val reply: String? = null, val elapsedMillis: Long = 0, val failure: Finding? = null)

/** Settings → 陪聊. */
object CompanionRequests {
    val READ = RequestMethod("companion/read", Empty.serializer(), CompanionView.serializer())
    val SAVE = RequestMethod("companion/save", CompanionSaveParams.serializer(), CompanionSaveResult.serializer())
    val TRY = RequestMethod("companion/try", TextParams.serializer(), CompanionTryResult.serializer())
    val DECISIONS = RequestMethod("companion/decisions", Empty.serializer(), CompanionDecisionsResult.serializer())
}
