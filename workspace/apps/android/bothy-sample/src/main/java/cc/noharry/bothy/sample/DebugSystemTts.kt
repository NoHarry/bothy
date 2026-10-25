package cc.noharry.bothy.sample

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The device's own speech synthesis ([DebugTtsProvider.SYSTEM]), for replies when no voice service has a key: out of
 * the box 小博 speaks in the device's voice (owner 2026-10-25: a model key alone should be enough to try it). Each line
 * is written to a WAV file and played like the services' audio, so focus, interruption and the speaking state hold.
 */
internal object DebugSystemTts : DebugTtsClient {
    private var app: Context? = null
    private val starting = Mutex()
    @Volatile private var engine: TextToSpeech? = null
    private val pending = ConcurrentHashMap<String, CancellableContinuation<Unit>>()

    fun attach(context: Context) {
        app = context.applicationContext
    }

    override suspend fun synthesize(config: DebugTtsConfig, text: String): DebugTtsAudio {
        val context = app ?: throw IOException("系统语音还没准备好")
        val tts = engine(context) ?: throw IOException(NO_ENGINE)
        val file = File(File(context.cacheDir, CACHE_DIR).apply { mkdirs() }, "${UUID.randomUUID()}.wav")
        try {
            val id = file.nameWithoutExtension
            suspendCancellableCoroutine { continuation ->
                pending[id] = continuation
                continuation.invokeOnCancellation { pending.remove(id) }
                if (tts.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) {
                    pending.remove(id)?.resumeWithException(IOException("系统语音合成失败"))
                }
            }
            return DebugTtsAudio(file.readBytes(), WAV_FORMAT)
        } finally {
            file.delete()
        }
    }

    /** Never asked for: the device's voice is not streamed ([DebugTtsConfig.stream] is off with it). */
    override fun stream(config: DebugTtsConfig, text: String): Flow<DebugTtsAudioChunk> = flow {
        throw IOException("系统语音不支持流式播放")
    }

    /** Android's page for the speech engine and its language, where a Chinese voice is installed or picked. */
    fun settingsIntent(): Intent = Intent(TTS_SETTINGS_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The engine, started once (on the main thread, where it reports back), Chinese if it has it; null when there is none. */
    private suspend fun engine(context: Context): TextToSpeech? = engine ?: starting.withLock {
        engine ?: withContext(Dispatchers.Main) { start(context) }?.also { engine = it }
    }

    private suspend fun start(context: Context): TextToSpeech? {
        var created: TextToSpeech? = null
        val status = suspendCancellableCoroutine { continuation ->
            created = TextToSpeech(context) { status -> if (continuation.isActive) continuation.resume(status) }
            // Cancelled while the engine starts: nobody will keep it, so it goes.
            continuation.invokeOnCancellation { created?.shutdown() }
        }
        val tts = created ?: return null
        if (status != TextToSpeech.SUCCESS) {
            tts.shutdown()
            DebugTtsLog.warn("system tts: no engine ($status)")
            return null
        }
        val language = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
        if (language < TextToSpeech.LANG_AVAILABLE) DebugTtsLog.warn("system tts: no Chinese voice ($language), the default speaks")
        tts.setOnUtteranceProgressListener(Listener)
        DebugTtsLog.info("system tts ready: ${tts.defaultEngine}")
        return tts
    }

    private object Listener : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            utteranceId?.let { pending.remove(it) }?.resume(Unit)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            utteranceId?.let { pending.remove(it) }?.resumeWithException(IOException("系统语音合成出错"))
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            utteranceId?.let { pending.remove(it) }?.resumeWithException(IOException("系统语音合成出错（$errorCode）"))
        }
    }

    private const val WAV_FORMAT = "wav"
    private const val CACHE_DIR = "system-tts"
    private const val TTS_SETTINGS_ACTION = "com.android.settings.TTS_SETTINGS"
    private const val NO_ENGINE = "这台设备没有可用的系统语音：到系统设置的「文字转语音」装一个中文语音，或者在「密钥」页填豆包语音 key"
}
