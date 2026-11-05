package cc.noharry.bothy.app.phoneassist

import android.content.Context
import android.content.Intent

/**
 * The phone's assistant over other apps (owner 2026-11-05: 手机版和车机版完全按两套来做，放在一个 APK 里): a service of
 * its own ([PhoneAssistantService]) with a capsule at the top while 小博 works and a sheet from the bottom to listen,
 * follow a task, read the answer or approve a step. Tablets and head units keep the overlay
 * ([cc.noharry.bothy.app.overlay.OverlayService]). [cc.noharry.bothy.app.overlay.OverlayControl] starts one or the
 * other, and both answer through its hooks, so the server and Android Use never know which one is up.
 */
internal object PhoneAssistant {
    /** The start of the phone's assistant: its sheet up when [open], else folded (only the capsule shows, if anything). */
    fun intent(context: Context, open: Boolean): Intent =
        Intent(context, PhoneAssistantService::class.java).apply { if (open) action = ACTION_OPEN_SHEET }

    /** The start that wakes 小博 at once, its sheet listening (a long press of the power key, [cc.noharry.bothy.app.assist]). */
    fun wakeIntent(context: Context): Intent = Intent(context, PhoneAssistantService::class.java).setAction(ACTION_WAKE)
}

/** Starts the phone's assistant listening. */
internal const val ACTION_WAKE = "cc.noharry.bothy.WAKE_PHONE_ASSISTANT"

/** Starts the phone's assistant with its sheet up. */
internal const val ACTION_OPEN_SHEET = "cc.noharry.bothy.OPEN_PHONE_SHEET"
