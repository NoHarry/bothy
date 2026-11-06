package cc.noharry.bothy.server.evidence

import cc.noharry.agent.appserver.runtime.AnswerProblems

/** Whether [request] asks the car or the music to do something (navigate, park, play, cool down): a run must act. */
internal fun requiresCarAction(request: String): Boolean {
    val normalized = request.lowercase()
    return ACTION_KEYWORDS.any { keyword -> normalized.contains(keyword) }
}

/**
 * The bench's name-and-route request (introduce yourself, then a route past 广州塔 with parking, a toilet and 周杰伦):
 * an answer that passed is still a failure when it is missing or only narrates finishing, and it should explain the name
 * and give the route's key facts.
 */
internal fun nameRouteProblems(request: String, answer: String): AnswerProblems {
    if (!requiresComplexNameRouteAnswer(request)) return AnswerProblems.NONE
    if (answer.isBlank()) return AnswerProblems(failures = listOf("final response is missing for complex name-route request"))
    if (answer.isPureNarrationFinalResponse()) {
        return AnswerProblems(failures = listOf("final response is process narration, not a direct user-visible answer"))
    }
    val warnings = listOfNotNull(
        "final response is missing name explanation".takeIf { !answer.hasNameExplanation() },
        "final response is missing route key info".takeIf { !answer.hasRouteKeyInfo() },
    )
    return AnswerProblems(warnings = warnings)
}

private fun requiresComplexNameRouteAnswer(input: String): Boolean {
    val asksName = input.containsAny("介绍下你自己", "介绍一下你自己", "为什么叫这个名字", "名字")
    val asksRoute = input.contains("广州塔") &&
        input.containsAny("深圳北站", "肯德基") &&
        input.containsAny("停车", "厕所", "洗手间", "周杰伦", "不走高速")
    return asksName && asksRoute
}

private fun String.hasProspectiveFinishNarration(): Boolean =
    PROSPECTIVE_FINISH_NARRATION.any { pattern -> pattern.containsMatchIn(this) }

private fun String.isPureNarrationFinalResponse(): Boolean {
    val normalized = trim().replace(Regex("""\s+"""), " ")
    if (normalized.isBlank()) return false
    if (normalized.hasConcreteFinalResultContent()) return false
    return normalized.hasProspectiveFinishNarration() ||
        (normalized.length < SHORT_NARRATION_RESPONSE_LIMIT && normalized.hasGenericNonAnswerNarration())
}

private fun String.hasConcreteFinalResultContent(): Boolean =
    hasNameExplanation() ||
        hasRouteKeyInfo() ||
        containsAny(
            "广州塔",
            "深圳北站",
            "肯德基",
            "停车",
            "厕所",
            "洗手间",
            "卫生间",
            "导航",
            "路线",
            "周杰伦",
            "KFC",
            "parking",
            "route",
            "navigation",
        )

private fun String.hasGenericNonAnswerNarration(): Boolean =
    GENERIC_NON_ANSWER_NARRATION.any { pattern -> pattern.containsMatchIn(this) }

private fun String.hasNameExplanation(): Boolean =
    containsAny("我叫", "我是", "名字", "叫这个名字", "叫做", "称为") &&
        containsAny("因为", "代表", "寓意", "含义", "意思", "来源", "取自")

private fun String.hasRouteKeyInfo(): Boolean {
    val signals = listOf(
        contains("广州塔"),
        contains("停车"),
        containsAny("深圳北站", "肯德基"),
        containsAny("不走高速", "避开高速", "高速"),
        containsAny("厕所", "洗手间", "卫生间"),
        containsAny("周杰伦", "音乐", "歌"),
        containsAny("导航", "路线"),
    )
    return signals.count { it } >= 4
}

private fun String.containsAny(vararg values: String): Boolean =
    values.any { contains(it, ignoreCase = true) }

private val PROSPECTIVE_FINISH_NARRATION = listOf(
    Regex("""\blet me (?:now )?(?:update|finish|call|provide)\b""", RegexOption.IGNORE_CASE),
    Regex("""\b(?:call|use)\s+finish\b""", RegexOption.IGNORE_CASE),
    Regex("""\b(?:provide|write|compose) (?:the )?final response\b""", RegexOption.IGNORE_CASE),
    Regex("""\bupdate (?:the )?todo\b""", RegexOption.IGNORE_CASE),
    Regex("""\bfinish with (?:a |the )?.*response\b""", RegexOption.IGNORE_CASE),
    Regex("""(我来|我会|我将|现在|准备).{0,8}(更新|调用).{0,8}(todo|finish|最终答复|最终回答)"""),
)

private const val SHORT_NARRATION_RESPONSE_LIMIT = 120
private val GENERIC_NON_ANSWER_NARRATION = listOf(
    Regex("""\ball (?:tasks|actions|steps|operations) (?:are )?(?:complete|completed|done)\b""", RegexOption.IGNORE_CASE),
    Regex("""\bcompleted successfully\b""", RegexOption.IGNORE_CASE),
    Regex("""\boperations are complete\b""", RegexOption.IGNORE_CASE),
    Regex("""^\(?same reasoning as previous tool call\)?$""", RegexOption.IGNORE_CASE),
)

private val ACTION_KEYWORDS = listOf(
    "导航",
    "去",
    "停车",
    "厕所",
    "洗手间",
    "沿途",
    "高速",
    "路线",
    "回家",
    "音乐",
    "歌曲",
    "歌",
    "播放",
    "听",
    "主题曲",
    "电视剧",
    "周杰伦",
    "王菲",
    "空调",
    "温度",
    "热",
    "冷",
    "音量",
    "navigate",
    "parking",
    "toilet",
    "restroom",
    "route",
    "music",
    "song",
    "play",
    "hot",
    "cold",
    "warmer",
    "cooler",
)
