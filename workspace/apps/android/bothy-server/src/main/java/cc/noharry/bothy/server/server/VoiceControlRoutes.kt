package cc.noharry.bothy.server.server

import android.content.Context
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.SaveResult
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.protocol.text
import cc.noharry.agent.appserver.server.RouteModule
import cc.noharry.agent.appserver.server.RouteTable
import cc.noharry.bothy.protocol.BothyFindingCode
import cc.noharry.bothy.protocol.VoiceRequests
import cc.noharry.bothy.protocol.VoiceWakeResult
import cc.noharry.bothy.server.voice.VoiceController
import cc.noharry.bothy.server.voice.WakeWord
import cc.noharry.bothy.server.voice.clearWakeMigrationNotice
import cc.noharry.bothy.server.voice.tts.TtsActivity

/** The mic beside Send: tap on and off, or hold to talk; and the strip's own notices. */
internal class VoiceControlRoutes(private val app: Context) : RouteModule {
    override fun RouteTable.install() {
        on(VoiceRequests.TOGGLE) { done { VoiceController.toggle(app) } }
        on(VoiceRequests.STOP) { done { VoiceController.stop() } }
        on(VoiceRequests.BEGIN_PRESS) {
            val failure = VoiceController.beginPress(app)
            val started = VoiceController.listening
            if (!started) failure?.let(VoiceController::notice)
            VoiceWakeResult(started = started, failure = failure)
        }
        // Only a session the press opened: the press may have failed to start, or another session took over since.
        on(VoiceRequests.RELEASE_PRESS) { done { if (VoiceController.holding) VoiceController.releasePress() } }
        on(VoiceRequests.CANCEL_PRESS) { done { if (VoiceController.holding) VoiceController.cancelPress() } }
        on(VoiceRequests.NOTICE) { done { VoiceController.notice(text(it.text)) } }
        on(VoiceRequests.WAKE) { wakeFor(app, it) }
        on(VoiceRequests.CLOSED_BY_HAND) { done { VoiceController.closedByHand() } }
        on(VoiceRequests.END_WAKE_SESSION) { done { if (VoiceController.wakeSession) VoiceController.stop() } }
        on(VoiceRequests.CLEAR_NOTICE) { done { if (VoiceController.notice.value == it) VoiceController.notice(null) } }
        on(VoiceRequests.SET_WAKE_WORD) {
            clearWakeMigration()
            SaveResult(finding(BothyFindingCode.VOICE_SWITCH_SAVE_FAILED).takeUnless { _ -> WakeWord.setEnabled(app, it.on) })
        }
        on(VoiceRequests.CLEAR_WAKE_MIGRATION) { done { clearWakeMigration() } }
        on(VoiceRequests.INTERRUPT_SPEECH) { done { TtsActivity.interrupt() } }
    }

    private fun clearWakeMigration() {
        clearWakeMigrationNotice(app)
        wakeNoticeShown.value += 1
    }

    private inline fun done(block: () -> Unit): Empty {
        block()
        return Empty
    }
}
