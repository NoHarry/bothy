package cc.noharry.bothy.protocol

import cc.noharry.agent.appserver.protocol.AppServerMethods
import cc.noharry.agent.appserver.protocol.ProtocolMethod
import cc.noharry.agent.appserver.protocol.clientNotification
import cc.noharry.agent.appserver.protocol.request
import cc.noharry.agent.appserver.protocol.serverNotification
import cc.noharry.agent.appserver.protocol.serverRequest

/**
 * Every method Bothy's server speaks: the app server's ([AppServerMethods]) and Bothy's own, one line each. The schema
 * files (`schema/`, `./gradlew :bothy-protocol:generateSchema`) are written from this list, and tests check it against
 * the methods defined and the requests the server answers, so a new method needs its line too.
 */
object ProtocolMethods {
    val all: List<ProtocolMethod> = AppServerMethods.all + listOf(
        request(ToolSearchRequests.SET),
        request(CheckRequests.RUN),
        request(CompanionRequests.READ),
        request(CompanionRequests.SAVE),
        request(CompanionRequests.TRY),
        request(CompanionRequests.DECISIONS),
        request(ConnectorRequests.LIST),
        request(ConnectorRequests.REFRESH_ALL),
        request(ConnectorRequests.REFRESH),
        request(ConnectorRequests.PAGE),
        request(ConnectorRequests.CONNECT),
        request(ConnectorRequests.NEW_CODE),
        request(ConnectorRequests.SIGN_IN),
        request(ConnectorRequests.SIGN_OUT),
        request(ConnectorRequests.TEST),
        request(ConnectorRequests.DISCONNECT),
        request(ConnectorRequests.CONNECT_APP),
        request(ConnectorRequests.CHECK_APP),
        request(ConnectorRequests.RESUME_APP),
        request(OnDeviceRequests.DOWNLOAD),
        request(OnDeviceRequests.CANCEL),
        request(OnDeviceRequests.DELETE),
        request(OnDeviceRequests.START),
        request(OnDeviceRequests.STOP),
        request(OnboardingRequests.READ),
        request(OnboardingRequests.SAVE_MODEL_KEY),
        request(OnboardingRequests.SAVE_DOUBAO_KEY),
        request(OnboardingRequests.FINISH),
        request(RealtimeRequests.READ),
        request(RealtimeRequests.SAVE),
        request(RealtimeRequests.TEST),
        request(PlaygroundRequests.SET_REAL),
        request(VoiceRequests.WAKE),
        request(VoiceRequests.CLOSED_BY_HAND),
        request(VoiceRequests.END_WAKE_SESSION),
        request(VoiceRequests.CLEAR_NOTICE),
        request(VoiceRequests.SET_WAKE_WORD),
        request(VoiceRequests.CLEAR_WAKE_MIGRATION),
        request(VoiceRequests.INTERRUPT_SPEECH),
        request(VoiceRequests.TOGGLE),
        request(VoiceRequests.STOP),
        request(VoiceRequests.BEGIN_PRESS),
        request(VoiceRequests.RELEASE_PRESS),
        request(VoiceRequests.CANCEL_PRESS),
        request(VoiceRequests.NOTICE),
        request(VoiceSettingsRequests.READ),
        request(VoiceSettingsRequests.SAVE_VOICE),
        request(VoiceSettingsRequests.CHOOSE_TTS),
        request(VoiceSettingsRequests.SAVE),
        request(VoiceSettingsRequests.DELETE_KEY),
        request(VoiceSettingsRequests.SET_WAKE_LEVEL),
        request(VoiceSettingsRequests.PREVIEW),
        serverNotification(VoiceNotifications.DISMISSED),
        serverRequest(BothyPresentationRequests.PREVIEW_MASCOT),
    )
}
