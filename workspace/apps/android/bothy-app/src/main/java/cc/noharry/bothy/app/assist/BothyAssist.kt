package cc.noharry.bothy.app.assist

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import cc.noharry.bothy.app.console.FrontEnd
import cc.noharry.bothy.app.overlay.OverlayControl
import cc.noharry.bothy.app.phoneassist.PhoneAssistant
import cc.noharry.bothy.protocol.WakeOrigin

/**
 * Bothy as the system's digital assistant (owner 2026-11-05, after Eta: 按键唤醒说话): chosen in the system settings
 * (默认应用 → 数字助理), a long press of the power key, or the assistant gesture, wakes 小博 to listen. The system shows
 * an assistant through a session ([BothyAssistSession]); Bothy draws its own sheet instead and lets the session go.
 */
class BothyAssistService : VoiceInteractionService()

/** Opens a session for each summons. */
class BothyAssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = BothyAssistSession(this)
}

/** A summons: 小博 wakes in Bothy's own sheet or card, and the session, which shows nothing, ends at once. */
internal class BothyAssistSession(context: Context) : VoiceInteractionSession(context) {
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        summon(context)
        hide()
    }
}

/**
 * Wakes 小博 for a long press: on a phone its assistant starts listening ([PhoneAssistant]); elsewhere the overlay that
 * runs is woken, or started with its card open.
 */
internal fun summon(context: Context) {
    val app = context.applicationContext
    val refused = when {
        FrontEnd.phone(app) -> OverlayControl.launch(app, PhoneAssistant.wakeIntent(app))
        OverlayControl.wake(WakeOrigin.LONG_PRESS) -> null
        else -> OverlayControl.start(app, open = true)
    }
    if (refused != null) Log.w(TAG, "assistant summons refused: $refused")
}

/**
 * The speech recognizer an assistant must name. Bothy listens through its own voice, not through this: other apps that
 * ask the system's recognizer while Bothy is the assistant are told it is busy.
 */
class BothyRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}

private const val TAG = "BothyAssist"

/**
 * Whether Bothy can be the system's digital assistant here: from Android 12 (`R.bool.assist_enabled`), where choosing
 * it leaves the system's speech recognizer as it was.
 */
internal val assistOffered: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Whether Bothy is the system's digital assistant now (a long press of the power key wakes 小博). */
internal fun isAssistant(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
    } else {
        val chosen = Settings.Secure.getString(context.contentResolver, "assistant")?.let(ComponentName::unflattenFromString)
        chosen?.packageName == context.packageName
    }

/** The system's default-apps page, where the person picks the digital assistant (no app may pick itself). */
internal fun openAssistantSettings(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
