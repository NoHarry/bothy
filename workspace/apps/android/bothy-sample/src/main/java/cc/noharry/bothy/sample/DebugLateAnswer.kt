@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import cc.noharry.agent.kernel.api.PostUserInputResult

/** A late reply must carry the question, since the new run's model request starts from this text. */
internal fun lateAnswerText(question: DebugUserQuestion, text: String): String =
    "关于你之前的问题「${question.title}」，我的回答是：$text"

/** Where a question reply goes after the exact-run post: consumed by that run, a new turn, an error, or nothing to show. */
internal sealed interface DebugAnswerRoute {
    data object Delivered : DebugAnswerRoute
    data object Late : DebugAnswerRoute
    data object Silent : DebugAnswerRoute
    data class Failed(val reason: String) : DebugAnswerRoute
}

/**
 * Only the selected, idle session may turn a reply to an ended run into a new turn; a busy selected session reports
 * instead, and a run the UI no longer owns stays silent because its errors belong to another view.
 */
internal fun debugAnswerRoute(result: PostUserInputResult, selected: Boolean, running: Boolean, ownsRun: Boolean): DebugAnswerRoute =
    when (result) {
        PostUserInputResult.Accepted, PostUserInputResult.Duplicate -> DebugAnswerRoute.Delivered
        PostUserInputResult.RunNotActive, PostUserInputResult.NotFound -> when {
            selected && !running -> DebugAnswerRoute.Late
            selected -> DebugAnswerRoute.Failed("任务已结束且当前会话正在执行其他任务")
            else -> DebugAnswerRoute.Silent
        }
        is PostUserInputResult.Rejected -> if (ownsRun) DebugAnswerRoute.Failed(result.code) else DebugAnswerRoute.Silent
    }
