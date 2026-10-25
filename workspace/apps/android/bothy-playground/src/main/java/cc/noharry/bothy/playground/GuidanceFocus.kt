package cc.noharry.bothy.playground

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.amap.api.navi.TTSPlayListener

/**
 * Lets the assistant's voice turn guidance down (owner 2026-10-22: 「语音说话应该压低导航的，但是导航现在没变低」). AMap
 * asks for focus that may duck at each prompt but says it will duck itself (it pauses-on-duck, then plays on at full
 * volume), so the platform leaves it alone. This asks for the same focus just after AMap, without that promise, and
 * keeps it while the prompt plays: when the assistant then takes focus, the platform ducks this app's players, AMap's
 * among them. Nothing pauses (owner: 「不要暂停…走混音，压低其他的」).
 */
internal class GuidanceFocus(context: Context, private val main: Handler) : TTSPlayListener {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private var held = false
    private var requestedAt = 0L
    private var retried = false

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        // AMap's own request landing after this one puts this one under it, out of the platform's reach: once more.
        if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK && held && !retried &&
            SystemClock.elapsedRealtime() - requestedAt < LATE_AMAP_MILLIS
        ) {
            retried = true
            request()
        }
    }

    private val focus: AudioFocusRequest? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) null
        else AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(listener, main)
            .build()

    private val take = Runnable {
        if (held) return@Runnable
        held = true
        retried = false
        request()
    }

    // After AMap's own request, which follows this callback by some 10 ms.
    override fun onPlayStart(text: String?) {
        main.removeCallbacks(take)
        main.postDelayed(take, AFTER_AMAP_MILLIS)
    }

    override fun onPlayEnd(text: String?) {
        // A prompt over before its focus was taken: taken late, it would never be given back and would hold music down.
        main.removeCallbacks(take)
        main.post {
            if (!held) return@post
            held = false
            focus?.let(audio::abandonAudioFocusRequest) ?: @Suppress("DEPRECATION") audio.abandonAudioFocus(listener)
        }
    }

    private fun request() {
        requestedAt = SystemClock.elapsedRealtime()
        val result = focus?.let(audio::requestAudioFocus)
            ?: @Suppress("DEPRECATION") audio.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) Log.w(TAG, "guidance focus not granted ($result)")
    }

    private companion object {
        const val TAG = "PlaygroundGuidance"
        const val AFTER_AMAP_MILLIS = 120L
        const val LATE_AMAP_MILLIS = 400L
    }
}
