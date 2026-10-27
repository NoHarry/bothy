package cc.noharry.bothy.app.voice
import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import cc.noharry.bothy.app.voice.tts.TtsLog
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Audio focus while 小博 speaks or listens (owner 2026-10-22: 「声音混在一起了，要分别申请下焦点」, the head unit's guidance
 * talking over a reply; then 「唤醒和识别都不行了，刚才播放了网易云音乐」, music at full volume over the speaker). Transient focus
 * that may duck, from the first holder to the last: 小博's sounds ([TtsActivity]) and the voice session while it is
 * open ([LISTENING], [VoiceController]). Nothing pauses
 * (owner: 「不要暂停…走混音，压低其他的」); the platform turns music and a guidance prompt down under 小博. AMap asks for
 * the same kind of focus at each prompt; 小博 takes it straight back (owner: 「语音说话应该压低导航的」), so the prompt is
 * turned down under 小博 rather than 小博 under the prompt. Only a call or an app taking focus outright turns 小博 down;
 * the platform does not duck speech, so 小博's players are turned down here.
 */
internal object SpeechFocus {
    private var audio: AudioManager? = null
    private val players: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
    private val holders: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
    private var holding = false

    /** The voice session's hold: open, 小博 hears the speaker over music turned down. */
    val LISTENING = Any()
    private var ducked = false
    /** Times focus was taken back from prompts while this speech lasts: a source that grabs it back each time gives up first. */
    private var reclaims = 0
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val reclaim = Runnable { takeBack() }

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when {
            // A prompt, asking for focus that may duck: taken back once its request has settled (AMap asks twice).
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                main.removeCallbacks(reclaim)
                main.postDelayed(reclaim, RECLAIM_DELAY_MILLIS)
            }
            change == AudioManager.AUDIOFOCUS_GAIN -> setDucked(false)
            change < 0 -> {
                TtsLog.info("audio focus ${focusName(change)}: speech turned down")
                setDucked(true)
            }
        }
    }

    private val request: AudioFocusRequest? by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@lazy null
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(SpeechAudio.attributes())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
            .build()
    }

    fun attach(context: Context) {
        audio = context.applicationContext.getSystemService(AudioManager::class.java)
    }

    /** [holder] began speaking ([TtsActivity.begin]) or listening ([LISTENING]); the first one takes focus. */
    @Synchronized
    fun began(holder: Any) {
        if (holder is AudioTrack || holder is MediaPlayer) {
            players += holder
            volume(holder, if (ducked) DUCKED_VOLUME else 1f)
        }
        holders += holder
        if (holding) return
        val manager = audio ?: return
        holding = true
        reclaims = 0
        val result = requestFocus(manager)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) TtsLog.warn("audio focus not granted ($result): speaking anyway")
    }

    @Synchronized
    private fun takeBack() {
        val manager = audio ?: return
        if (!holding || reclaims >= MAX_RECLAIMS) return
        reclaims += 1
        val result = requestFocus(manager)
        TtsLog.info("audio focus taken back from a prompt${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "" else " (not granted: $result)"}")
    }

    private fun requestFocus(manager: AudioManager): Int = request?.let(manager::requestAudioFocus)
        ?: @Suppress("DEPRECATION") manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)

    /** [holder] is done; once none is left, focus goes back. */
    @Synchronized
    fun ended(holder: Any) {
        players -= holder
        holders -= holder
        if (holders.isNotEmpty() || !holding) return
        holding = false
        ducked = false
        main.removeCallbacks(reclaim)
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

    /** About -10 dB: a call or an app over 小博 stays clear, and 小博 can still be followed under it. */
    private const val DUCKED_VOLUME = 0.3f
    private const val RECLAIM_DELAY_MILLIS = 150L
    private const val MAX_RECLAIMS = 30
}
