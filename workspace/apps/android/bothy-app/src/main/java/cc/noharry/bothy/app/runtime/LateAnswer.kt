@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.runtime
import cc.noharry.agent.kernel.api.PostUserInputResult
import cc.noharry.bothy.app.a2ui.UserQuestion

/** A late reply must carry the question, since the new run's model request starts from this text. */
internal fun lateAnswerText(question: UserQuestion, text: String): String =
    "关于你之前的问题「${question.title}」，我的回答是：$text"

/** Where a question reply goes after the exact-run post: consumed by that run, a new turn, an error, or nothing to show. */
internal sealed interface AnswerRoute {
    data object Delivered : AnswerRoute
    data object Late : AnswerRoute
    data object Silent : AnswerRoute
    data class Failed(val reason: String) : AnswerRoute
}

/**
 * Only the selected, idle session may turn a reply to an ended run into a new turn; a busy selected session reports
 * instead, and a run the UI no longer owns stays silent because its errors belong to another view.
 */
internal fun answerRoute(result: PostUserInputResult, selected: Boolean, running: Boolean, ownsRun: Boolean): AnswerRoute =
    when (result) {
        PostUserInputResult.Accepted, PostUserInputResult.Duplicate -> AnswerRoute.Delivered
        PostUserInputResult.RunNotActive, PostUserInputResult.NotFound -> when {
            selected && !running -> AnswerRoute.Late
            selected -> AnswerRoute.Failed("任务已结束且当前会话正在执行其他任务")
            else -> AnswerRoute.Silent
        }
        is PostUserInputResult.Rejected -> if (ownsRun) AnswerRoute.Failed(result.code) else AnswerRoute.Silent
    }
