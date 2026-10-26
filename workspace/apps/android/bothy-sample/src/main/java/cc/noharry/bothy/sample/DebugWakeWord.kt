package cc.noharry.bothy.sample

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import cc.noharry.agent.voice.LeasedAudioSource
import cc.noharry.agent.voice.NearSpeechGate
import cc.noharry.agent.voice.VoiceAudioSource
import cc.noharry.agent.voice.WakeSensitivity
import cc.noharry.agent.voice.WakeWordListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex

/** What woke the overlay; [ADB] is the debug broadcast's `wake`. */
/** What woke 小博; [ENGLISH_WORD] is OK Bothy, answered in English. */
internal enum class DebugWakeSource { WORD, ENGLISH_WORD, LONG_PRESS, TAP, ADB, SURFACE }

/** The wake word's state for Settings → 语音: whether it is listening, else why not. */
internal data class DebugWakeStatus(val listening: Boolean = false, val message: String = "已关闭")

/**
 * The wake word 你好，小博 (Settings → 语音 → 语音唤醒, off by default). While the switch is on the on-device keyword
 * spotter listens, whether or not the overlay runs (owner 2026-10-14: 「悬浮窗唤醒这个限制关了吧，那个唤醒开关改成是否能
 * 语音唤醒」; one device's overlay had closed with the console, so nothing listened). A hit wakes the overlay; without one it
 * wakes in the console when that is in front, else brings 小博 up and hands it the wake. Without a voice session it records
 * the microphone itself, which passes between it and the session through [micLease], so the two never record at once
 * and neither blocks the main thread waiting for the other. During a session it hears the session's recording
 * ([DebugVoiceController.wakeAudio]), so the wake word said again interrupts whatever 小博 is doing. Main thread.
 */
internal object DebugWakeWord {
    /** One microphone user at a time: this listener or the voice session. */
    val micLease = Mutex()
    private val main = Handler(Looper.getMainLooper())
    private val mutableStatus = MutableStateFlow(DebugWakeStatus())
    val status: StateFlow<DebugWakeStatus> = mutableStatus
    private val mutableEnabled = MutableStateFlow(false)
    /** The switch, once [attach] or [setEnabled] has read it. */
    val enabled: StateFlow<Boolean> = mutableEnabled

    /**
     * How loud the speaker said the latest wake phrase (dBFS): the voice session's near-speech gate keeps quieter talk
     * from the recognizer. Saved, so a tap, a report's follow-up, or a session after the app restarted uses it too (a
     * reinstall once left a follow-up session without a gate and a video stopped the report).
     */
    @Volatile
    private var lastLevelDb: Double? = null

    /** 现场调校: the wake phrase's level as measured, so the near gate matches the applied gain before the next wake. */
    fun setCallerLevelDb(context: Context, levelDb: Double) {
        lastLevelDb = levelDb
        context.getSharedPreferences(WAKE_PREFS, Context.MODE_PRIVATE).edit().putFloat(CALLER_LEVEL_KEY, levelDb.toFloat()).apply()
    }

    fun callerLevelDb(context: Context): Double? = lastLevelDb ?: context.getSharedPreferences(WAKE_PREFS, Context.MODE_PRIVATE)
        .getFloat(CALLER_LEVEL_KEY, Float.NaN).toDouble().takeIf { it.isFinite() }?.also { lastLevelDb = it }

    /** New voice settings were saved: the listener reopens the microphone with them. */
    fun reload() {
        main.post {
            stopListener()
            refresh()
        }
    }

    /** Why the wake word stands aside for now (something else is recording), or null. Main thread. */
    private var pausedFor: String? = null

    /** Lets go of the microphone until [resume], for something else that records. Main thread. */
    fun pause(reason: String) {
        pausedFor = reason
        refresh()
    }

    fun resume() {
        if (pausedFor == null) return
        pausedFor = null
        refresh()
    }

    private var appContext: Context? = null
    private var onWake: ((DebugWakeSource) -> Unit)? = null
    private var listener: WakeWordListener? = null
    /** The session recording [listener] hears, or null when it records the microphone itself. */
    private var listenerWithin: VoiceAudioSource? = null

    /** A wake heard while no overlay ran: 小博 is being brought up to take it ([attach]). */
    private var pendingWake: DebugWakeSource? = null

    /** The overlay is up: wakes go to [wake], on the main thread; one heard while it started is handed over now. */
    fun attach(context: Context, wake: (DebugWakeSource) -> Unit) {
        onWake = wake
        val pending = pendingWake
        pendingWake = null
        load(context)
        pending?.let { source -> main.post { onWake?.invoke(source) } }
    }

    /** Reads the saved switch, for Settings before the overlay has started. */
    fun load(context: Context) {
        appContext = context.applicationContext
        mutableEnabled.value = loadDebugVoiceConfig(context).overlayWake
        refresh()
    }

    fun detach() {
        onWake = null
        refresh()
    }

    /** 唤醒阈值: saved, then the spotter starts again with its threshold and phrases. */
    fun setSensitivity(context: Context, sensitivity: WakeSensitivity): Boolean {
        if (!saveDebugWakeSensitivity(context, sensitivity)) return false
        DebugVoiceLog.i(TAG, "wake sensitivity ${sensitivity.wireName}: ${sensitivity.paths} paths, threshold ${sensitivity.threshold}, ${XIAOBO_WAKE_PROFILE.phrasesFor(sensitivity).map { it.phrase }.distinct().joinToString("/")}")
        reload()
        return true
    }

    /** The settings switch: saved, then applied at once. */
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        appContext = context.applicationContext
        if (!saveDebugOverlayWake(context, enabled)) return false
        mutableEnabled.value = enabled
        // Switched in Settings, so the app is in front and the microphone service may start (or stops).
        DebugVoiceForegroundService.sync(context)
        // The wake word is heard through the overlay; switching it on brings the overlay up.
        if (enabled) DebugOverlayControl.ensureRunning(context)
        refresh()
        return true
    }

    /** Listens exactly when the switch, the overlay, the permission, the model and the voice session allow. */
    fun refresh() {
        val context = appContext ?: return
        val blocked = blocker(context)
        if (blocked != null) {
            stopListener()
            mutableStatus.value = DebugWakeStatus(false, blocked)
            return
        }
        val within = DebugVoiceController.wakeAudio
        if (listener != null && listenerWithin !== within) stopListener()
        if (listener == null) {
            lateinit var created: WakeWordListener
            val config = loadDebugVoiceConfig(context)
            created = WakeWordListener(
                assets = context.assets,
                // The spotter needs the echo canceller as much as the session does: 小博's own replies must not wake it.
                audio = within ?: LeasedAudioSource(micLease, debugMicrophone(config)),
                profile = XIAOBO_WAKE_PROFILE,
                sensitivity = config.wakeSensitivity,
                onWake = { phrase ->
                    val level = created.lastHitLevelDb
                    main.post { heard(phrase, level, within) }
                },
                onError = { error -> main.post { failed(error) } },
            )
            val started = runCatching { created.start() }
            started.exceptionOrNull()?.let { error ->
                mutableStatus.value = DebugWakeStatus(false, error.message ?: "唤醒词无法启动")
                return
            }
            listener = created
            listenerWithin = within
        }
        mutableStatus.value = DebugWakeStatus(true, if (within != null) "对话中也在听「你好，小博」，再叫一次就打断重听" else "正在听「你好，小博」和「OK Bothy」")
    }

    private fun blocker(context: Context): String? = when {
        !mutableEnabled.value -> "已关闭"
        pausedFor != null -> "暂停：$pausedFor"
        pendingWake != null -> "正在唤醒小博"
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> "需要麦克风权限"
        else -> WakeWordListener.availabilityError(context.assets)
    }

    /** A hit from [within] (the session recording it heard, or null for the microphone itself). */
    private fun heard(phrase: String, levelDb: Double?, within: VoiceAudioSource?) {
        val context = appContext ?: return
        val inSession = within != null
        // Heard in a session that has since ended or restarted (the hit was posted just before): already handled.
        if (inSession && within !== DebugVoiceController.wakeAudio) return
        // Said again during a session it stops 小博's work, so it must be said as near as the last wake was.
        val callerDb = appContext?.let(::callerLevelDb)
        if (inSession && isFarWake(levelDb, callerDb)) {
            DebugVoiceLog.i(TAG, "wake word heard in the session but far: level=${levelDb?.let { "%.1f".format(it) }} caller=${callerDb?.let { "%.1f".format(it) }}")
            return
        }
        // Over music the phrase measures the music too: the near gate set from it shut out the speaker (2026-10-22, a
        // wake at -20.8 dBFS over 网易云, then nothing heard), so the level is learnt only with nothing else playing.
        val overAudio = runCatching { context.getSystemService(android.media.AudioManager::class.java).isMusicActive }.getOrDefault(false)
        levelDb?.takeUnless { overAudio }?.let { level ->
            lastLevelDb = level
            appContext?.getSharedPreferences(WAKE_PREFS, Context.MODE_PRIVATE)?.edit()?.putFloat(CALLER_LEVEL_KEY, level.toFloat())?.apply()
        }
        if (overAudio && levelDb != null) DebugVoiceLog.i(TAG, "wake level not learnt: other audio is playing")
        DebugVoiceLog.i(TAG, "wake word heard: $phrase level=${levelDb?.let { "%.1f dBFS".format(it) } ?: "-"}")
        // Lets go of the microphone; the voice session takes it as soon as the recording is closed.
        stopListener()
        val source = if (phrase == BOTHY_WAKE_PHRASE) DebugWakeSource.ENGLISH_WORD else DebugWakeSource.WORD
        onWake?.invoke(source) ?: wakeWithoutOverlay(context, source)
        // Listening resumes at once when the wake did not start a session.
        refresh()
    }

    /**
     * No overlay is up. With the console in front the session runs there, as its microphone button's would; otherwise
     * 小博 is started and takes the wake as it attaches, or, if it does not come up within [PENDING_WAKE_MS], the
     * session runs without it (the answer and the replies are still heard).
     */
    private fun wakeWithoutOverlay(context: Context, source: DebugWakeSource) {
        if (DebugConsoleVisibility.inFront) {
            startWokenVoice(context, source)
            return
        }
        pendingWake = source
        val refused = DebugOverlayControl.start(context, open = false)
        if (refused != null) DebugVoiceLog.w(TAG, "wake without the overlay: $refused")
        main.postDelayed({
            if (pendingWake == source) {
                pendingWake = null
                startWokenVoice(context, source)
                refresh()
            }
        }, if (refused != null) 0L else PENDING_WAKE_MS)
    }

    private fun failed(error: Throwable) {
        DebugVoiceLog.w(TAG, "wake word stopped: ${error.javaClass.simpleName} ${error.message}")
        listener = null
        listenerWithin = null
        mutableStatus.value = DebugWakeStatus(false, "麦克风或模型出错：${error.message}")
    }

    private fun stopListener() {
        listener?.stop()
        listener = null
        listenerWithin = null
    }

    private const val TAG = "DebugVoice"
    private const val WAKE_PREFS = "debug_wake"
    private const val CALLER_LEVEL_KEY = "caller_level_db"
    private const val PENDING_WAKE_MS = 5_000L
}

/** A wake phrase quieter than the near-speech gate lets through, measured against the last wake's level; unknown levels pass. */
internal fun isFarWake(levelDb: Double?, callerDb: Double?): Boolean =
    levelDb != null && callerDb != null && levelDb < callerDb - NearSpeechGate.DEFAULT_BELOW_REFERENCE_DB
