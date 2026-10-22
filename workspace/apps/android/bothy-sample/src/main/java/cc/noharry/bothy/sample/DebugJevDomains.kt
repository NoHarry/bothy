@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolPrefetchRequest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class DebugJevDomain(val id: String, val description: String, val tools: List<ToolDescriptor>)

/** Namespace membership is derived only from this request's live provider catalog. */
internal fun jevDomains(tools: List<ToolDescriptor>, secondLevel: Boolean = false): List<DebugJevDomain> =
    tools.groupBy { jevDomainId(it.name, secondLevel) }.toSortedMap().map { (id, members) ->
        val description = if (secondLevel) members.map { it.description.substringBefore("使用说明").trim() }
            .distinct().joinToString("\n") else JEV_DOMAIN_SCOPES[id]
            ?: members.joinToString("\n") { "${it.name}: ${it.description}" }
        DebugJevDomain(id, description, members)
    }

private fun jevDomainId(name: String, secondLevel: Boolean): String {
    val parts = name.split('_', limit = 3)
    if (parts.size < 2) return "general"
    return if (secondLevel) parts.take(2).joinToString("_") else parts.first()
}

internal fun jevDomainQuestionBody(
    request: ToolPrefetchRequest,
    domains: List<DebugJevDomain>,
    model: String,
    sessionContext: String,
): String = buildJsonObject {
    put("model", model)
    put("state", buildJsonObject {
        put("latest_user_request", request.input)
        put("recent_context", sessionContext)
    })
    put("questions", buildJsonObject {
        domains.forEachIndexed { index, domain ->
            put("domain_$index", buildJsonObject {
                put("type", "noul")
                put("instructions", buildJsonObject {
                    put("question", JEV_DOMAIN_QUESTION)
                    put("domain_id", domain.id)
                    put("domain_scope", domain.description)
                })
            })
        }
    })
}.toString()

internal fun selectJevDomains(response: String, domains: List<DebugJevDomain>, threshold: Double): List<DebugJevDomain> {
    val scores = jevScores(response, "domain", domains.size)
    return domains.filterIndexed { index, _ -> scores[index] >= threshold }
}

private const val JEV_DOMAIN_QUESTION =
    "Does fulfilling the latest request require capabilities in this domain, including prerequisites? " +
        "Judge every domain independently; multiple domains can be relevant."

// Broad catalog namespaces, not a test-specific tool allowlist or execution sequence.
private val JEV_DOMAIN_SCOPES = mapOf(
    "device" to "外部设备、智能设备互联与控制。",
    "mall" to "商城商品和购物服务。",
    "media" to "音乐、广播、视频等内容的搜索、列表、音源与播放控制。音量通道查询和调节属于system域。",
    "navigation" to "地点搜索、当前位置、城市、地图、收藏、路线与途经点、导航引导和导航设置。",
    "system" to "应用、系统音量与音频通道、屏幕、网络与蓝牙、电话、日程、账户、时间及其他系统功能。",
    "device" to "设备与智能家居的状态查询，以及温度、灯光、窗帘、电器等控制。",
    "weather" to "城市天气查询。",
)
