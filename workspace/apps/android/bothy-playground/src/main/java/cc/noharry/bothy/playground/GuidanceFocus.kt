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
 * reply). A prompt takes transient focus that may duck, so music and the assistant dip under it. When the assistant
 * starts speaking over a prompt (transient focus of its own), the prompt stops and guidance stays [silence]d until the
 * assistant gives focus back, then [resume]s; prompts due meanwhile are skipped, as AMap does when muted.
 */
internal class GuidanceFocus(
    context: Context,
    private val main: Handler,
    private val silence: () -> Unit,
    private val resume: () -> Unit,
) : TTSPlayListener {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private var held = false
    private var yielded = false

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when {
            // The assistant (or a call) speaks now: this prompt and the ones after wait for it.
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && !yielded -> {
                Log.i(TAG, "focus taken: guidance quiet until it comes back")
                yielded = true
                silence()
            }
            change == AudioManager.AUDIOFOCUS_GAIN && yielded -> {
                Log.i(TAG, "focus back: guidance resumes")
                yielded = false
                resume()
                abandon()
            }
            // Taken for good (music started): guidance goes on, under it, from the next prompt.
            change == AudioManager.AUDIOFOCUS_LOSS -> {
                if (yielded) resume()
                yielded = false
                held = false
            }
        }
    }

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
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) Log.w(TAG, "focus not granted ($result) for 「$text」")
        }
    }

    // A prompt cut short by the assistant keeps its place in line, so focus comes back here when the assistant is done.
    override fun onPlayEnd(text: String?) {
        main.post { if (!yielded) abandon() }
    }

    private fun abandon() {
        if (!held) return
        held = false
        request?.let(audio::abandonAudioFocusRequest) ?: @Suppress("DEPRECATION") audio.abandonAudioFocus(listener)
    }

    private companion object {
        const val TAG = "PlaygroundGuidance"
    }
}
