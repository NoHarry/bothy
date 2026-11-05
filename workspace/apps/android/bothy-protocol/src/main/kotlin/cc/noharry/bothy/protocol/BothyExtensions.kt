package cc.noharry.bothy.protocol

import cc.noharry.agent.appserver.protocol.SaveResult
import cc.noharry.agent.appserver.protocol.SpeechSection
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.client.extension
import cc.noharry.agent.appserver.protocol.client.withExtension
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/** The sections Bothy's server adds beyond the protocol's own ([ExtensionSection]), by name. */
object BothyExtensions {
    const val VOICE = "voice"
    const val SPEECH = "speech"
    const val ON_DEVICE = "onDevice"
    const val CHECKS = "checks"
    const val TOOL_SEARCH = "toolSearch"

    /** What each section's `data` holds, by its name: the published schema types them. */
    val sections: Map<String, KSerializer<*>> = mapOf(
        VOICE to VoiceSection.serializer(),
        SPEECH to SpeechSection.serializer(),
        ON_DEVICE to OnDeviceSection.serializer(),
        CHECKS to ChecksSection.serializer(),
        TOOL_SEARCH to ToolSearchSection.serializer(),
    )
}

/** Jev's tool search ([JevSettings]) as Bothy's own section. */
@Serializable
data class ToolSearchSection(val settings: JevSettings = JevSettings())

/** Requests Bothy's server answers beside the protocol's own. */
object ToolSearchRequests {
    /** Settings → 工具与技能 → 工具检索: Jev's tool search, saved or refused. */
    val SET = RequestMethod("jev/setToolSearch", JevSettings.serializer(), SaveResult.serializer())
}

/** Jev picking the relevant tools before the first model call. */
val ServerState.toolSearch: JevSettings get() = extension(BothyExtensions.TOOL_SEARCH, ToolSearchSection.serializer())?.settings ?: JevSettings()

fun ServerState.withToolSearch(settings: JevSettings): ServerState =
    withExtension(BothyExtensions.TOOL_SEARCH, ToolSearchSection.serializer(), ToolSearchSection(settings))

/** The voice session and the wake word. */
val ServerState.voice: VoiceView get() = extension(BothyExtensions.VOICE, VoiceSection.serializer())?.voice ?: VoiceView()

/** The reply's loudness at the speaker while it streams; null when nothing plays. */
val ServerState.speechLevel: Float? get() = extension(BothyExtensions.SPEECH, SpeechSection.serializer())?.level

/** The models the device can run and its inference server. */
val ServerState.onDevice: OnDeviceView get() = extension(BothyExtensions.ON_DEVICE, OnDeviceSection.serializer())?.onDevice ?: OnDeviceView()

/** Settings → 检查: the checks and their latest results. */
val ServerState.checks: ChecksView get() = extension(BothyExtensions.CHECKS, ChecksSection.serializer())?.checks ?: ChecksView()

fun ServerState.withVoice(voice: VoiceView): ServerState = withExtension(BothyExtensions.VOICE, VoiceSection.serializer(), VoiceSection(voice))

fun ServerState.withSpeechLevel(level: Float?): ServerState = withExtension(BothyExtensions.SPEECH, SpeechSection.serializer(), SpeechSection(level))

fun ServerState.withOnDevice(onDevice: OnDeviceView): ServerState =
    withExtension(BothyExtensions.ON_DEVICE, OnDeviceSection.serializer(), OnDeviceSection(onDevice))

fun ServerState.withChecks(checks: ChecksView): ServerState = withExtension(BothyExtensions.CHECKS, ChecksSection.serializer(), ChecksSection(checks))
