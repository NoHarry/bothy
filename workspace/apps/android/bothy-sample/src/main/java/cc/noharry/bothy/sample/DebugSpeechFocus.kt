package cc.noharry.bothy.sample

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Audio focus while 小博 speaks (owner 2026-10-22: 「声音混在一起了，要分别申请下焦点」, the head unit's guidance talking
 * over a reply). Transient focus from 小博's first sound to its last ([DebugTtsActivity]): media pauses, and a guidance
 * prompt in progress gives way. A prompt that starts meanwhile takes focus that may duck; the platform does not duck
 * speech, so 小博's players are turned down here until it is over.
 */
internal object DebugSpeechFocus {
    private var audio: AudioManager? = null
    private val players: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
    private var holding = false
    private var ducked = false

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        // Any loss while 小博 holds focus is something else to hear over it: a guidance prompt, a call, another app.
        val duck = change < 0
        DebugTtsLog.info("audio focus ${focusName(change)}${if (duck) ": speech turned down" else ""}")
        setDucked(duck)
    }

    private val request: AudioFocusRequest? by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@lazy null
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(DebugSpeechAudio.attributes())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
            .build()
    }

    fun attach(context: Context) {
        audio = context.applicationContext.getSystemService(AudioManager::class.java)
    }

    /** [holder] began speaking ([DebugTtsActivity.begin]); the first one takes focus. */
    @Synchronized
    fun began(holder: Any) {
        if (holder is AudioTrack || holder is MediaPlayer) {
            players += holder
            volume(holder, if (ducked) DUCKED_VOLUME else 1f)
        }
        if (holding) return
        val manager = audio ?: return
        holding = true
        val result = request?.let(manager::requestAudioFocus)
            ?: @Suppress("DEPRECATION") manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) DebugTtsLog.warn("audio focus not granted ($result): speaking anyway")
    }

    /** [holder] is done; with [last] (no speech left) focus goes back. */
    @Synchronized
    fun ended(holder: Any, last: Boolean) {
        players -= holder
        if (!last || !holding) return
        holding = false
        ducked = false
        val manager = audio ?: return
        request?.let(manager::abandonAudioFocusRequest) ?: @Suppress("DEPRECATION") manager.abandonAudioFocus(listener)
    }

    @Synchronized
    private fun setDucked(duck: Boolean) {
        if (!holding || duck == ducked) return
        ducked = duck
        players.forEach { volume(it, if (duck) DUCKED_VOLUME else 1f) }
    }

    private fun volume(player: Any, level: Float) {
        runCatching {
            when (player) {
                is AudioTrack -> player.setVolume(level)
                is MediaPlayer -> player.setVolume(level, level)
            }
        }
    }

    private fun focusName(change: Int): String = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> "back"
        AudioManager.AUDIOFOCUS_LOSS -> "lost"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "lost for a while"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "lost to a prompt"
        else -> change.toString()
    }

    /** About -10 dB: a prompt over 小博 stays clear, and 小博 can still be followed under it. */
    private const val DUCKED_VOLUME = 0.3f
}
