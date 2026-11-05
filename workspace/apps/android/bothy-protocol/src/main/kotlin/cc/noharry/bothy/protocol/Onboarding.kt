package cc.noharry.bothy.protocol

import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.SaveResult
import cc.noharry.agent.appserver.protocol.TextParams
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.serialization.Serializable

/**
 * The first launch's setup: whether it shows (no model key yet, and not finished), and what it reads back each time the
 * person returns from a settings screen: the 豆包语音 key, notification access, the accessibility service.
 */
@Serializable
data class OnboardingView(
    val needed: Boolean = false,
    val doubaoKeySaved: Boolean = false,
    val notificationAccess: Boolean = false,
    val accessibilityEnabled: Boolean = false,
)

/** The first launch's setup. Keys go in only. */
object OnboardingRequests {
    val READ = RequestMethod("onboarding/read", Empty.serializer(), OnboardingView.serializer())

    /** Saves the key to the selected model service and asks it for its models: ready, or why not, in words. */
    val SAVE_MODEL_KEY = RequestMethod("onboarding/saveModelKey", TextParams.serializer(), CheckLine.serializer())
    val SAVE_DOUBAO_KEY = RequestMethod("onboarding/saveDoubaoKey", TextParams.serializer(), SaveResult.serializer())

    /** 开始使用: the setup never shows again, and the assistant comes up. */
    val FINISH = RequestMethod("onboarding/finish", Empty.serializer(), Empty.serializer())
}
