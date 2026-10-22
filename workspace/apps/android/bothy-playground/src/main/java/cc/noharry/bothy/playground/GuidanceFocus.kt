package cc.noharry.bothy.playground

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.util.Log
import com.amap.api.navi.TTSPlayListener

/**
 * Audio focus for each guidance prompt (owner 2026-10-22: 「声音混在一起了，要分别申请下焦点」, a prompt over the assistant's
 * reply): transient focus that may duck while AMap speaks, so music and the assistant dip under it. Nothing pauses
 * (owner: 「不要暂停…走混音，压低其他的」): when the assistant speaks over a prompt with focus of its own, the platform
 * turns the prompt down (AMap plays it as media of unknown content, which the platform ducks) and it goes on.
 */
internal class GuidanceFocus(context: Context, private val main: Handler) : TTSPlayListener {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val listener = AudioManager.OnAudioFocusChangeListener { change -> Log.i(TAG, "focus change $change") }
    private var held = false

    private val request: AudioFocusRequest? =
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

    override fun onPlayStart(text: String?) {
        main.post {
            if (held) return@post
            held = true
            val result = request?.let(audio::requestAudioFocus)
                ?: @Suppress("DEPRECATION") audio.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            Log.i(TAG, "prompt 「$text」: focus ${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "taken" else "not granted ($result)"}")
        }
    }

    override fun onPlayEnd(text: String?) {
        main.post {
            if (!held) return@post
            held = false
            request?.let(audio::abandonAudioFocusRequest) ?: @Suppress("DEPRECATION") audio.abandonAudioFocus(listener)
        }
    }

    private companion object {
        const val TAG = "PlaygroundGuidance"
    }
}
