package cc.noharry.bothy.app.overlay

import android.content.Context
import android.view.View
import cc.noharry.bothy.app.console.FrontEnd
import cc.noharry.bothy.app.voice.VoiceController

/**
 * A phone has no resting 小博 over other apps (owner 2026-11-03: 「点击弹悬浮窗这种应该是平板上的交互」): the bubble stays
 * hidden, the card comes when 小博 is woken or a task or a plan arrives, and once it has folded with nothing left
 * under way the overlay goes. [folded] is null while the card is up, else whether anything is still under way that
 * keeps it (a task on the capsule, Android Use); a voice session keeps it too. Checked again until it can go.
 */
internal class OverlayPhoneRest private constructor(
    private val view: View,
    private val folded: () -> Boolean?,
    private val leave: () -> Unit,
) {
    private val check = Runnable {
        when (folded()) {
            null -> Unit
            true -> arm()
            false -> if (VoiceController.listening) arm() else leave()
        }
    }

    /** Looks again in a while; called when the card has folded, and at the start. */
    fun arm() {
        view.removeCallbacks(check)
        view.postDelayed(check, REST_CHECK_MS)
    }

    fun stop() = view.removeCallbacks(check)

    companion object {
        /** The rest on a phone, its bubble hidden ([bubble]); null on a tablet or a head unit, where 小博 rests. */
        fun on(context: Context, view: View, bubble: OverlayBubble, leave: () -> Unit, folded: () -> Boolean?): OverlayPhoneRest? {
            if (!FrontEnd.phone(context)) return null
            bubble.suppressed = true
            return OverlayPhoneRest(view, folded, leave).also { it.arm() }
        }

        private const val REST_CHECK_MS = 4_000L
    }
}
