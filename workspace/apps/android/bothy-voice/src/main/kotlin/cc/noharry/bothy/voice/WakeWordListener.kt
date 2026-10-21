package cc.noharry.bothy.voice

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Listens for a wake phrase on the device with sherpa-onnx keyword spotting: the 3.3M-parameter Zipformer trained on
 * WenetSpeech, INT8 encoder and joiner, one CPU thread. It is light enough to stay on while the full recognizer is
 * off. The phrase is given as pinyin tokens ([wakeKeyword]), so homophones match alike. Audio stays in memory.
 * [onWake] runs on a background thread with the phrase heard; [onError] when the mic or model fails, which ends it.
 * Before [onWake], [lastHitLevelDb] is set to how loud the phrase was said ([wakeLevelDb]), the voice session's
 * [NearSpeechGate] reference.
 */
class WakeWordListener(
    private val assets: AssetManager,
    private val audio: VoiceAudioSource,
    sensitivity: WakeSensitivity = WakeSensitivity.STANDARD,
    private val keywords: String = sensitivity.keywords(),
    private val modelDir: String = WAKE_MODEL_ASSET_DIR,
    private val threshold: Float = sensitivity.threshold,
    private val boost: Float = sensitivity.boost,
    private val paths: Int = sensitivity.paths,
    private val onWake: (String) -> Unit,
    private val onError: (Throwable) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    val listening: Boolean get() = job?.isActive == true

    /** The speaker's level at the latest hit in dBFS; null before one, or when too little was voiced to tell. */
    @Volatile
    var lastHitLevelDb: Double? = null
        private set

    fun start() {
        if (listening) return
        availabilityError(assets, modelDir)?.let { throw IllegalStateException(it) }
        job = scope.launch { spot() }
    }

    /** Stops listening; the microphone is released shortly after, on the listening thread. */
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun spot() {
        val spotter = KeywordSpotter(assets, spotterConfig(modelDir, threshold, boost, paths))
        val stream = spotter.createStream(keywords)
        val recentDb = ArrayDeque<Double>()
        try {
            audio.frames().collect { frame ->
                recentDb.addLast(NearSpeechGate.frameDb(frame))
                while (recentDb.size > WAKE_LEVEL_WINDOW_FRAMES) recentDb.removeFirst()
                stream.acceptWaveform(FloatArray(frame.size) { frame[it] / PCM_SCALE }, VOICE_SAMPLE_RATE)
                while (spotter.isReady(stream)) {
                    spotter.decode(stream)
                    val heard = spotter.getResult(stream).keyword
                    // The stream must be reset right after a hit, or the same phrase fires again.
                    if (heard.isNotEmpty()) {
                        spotter.reset(stream)
                        // A near miss that outscored the phrase ([DECOY_WAKE_PHRASES]): heard, and let go.
                        if (heard.startsWith(DECOY_MARK)) continue
                        lastHitLevelDb = wakeLevelDb(recentDb.toList())
                        onWake(heard)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onError(error)
        } finally {
            stream.release()
            spotter.release()
        }
    }

    companion object {
        /** Null when the native library and every model file are present, else why wake-word listening cannot run. */
        fun availabilityError(assets: AssetManager, modelDir: String = WAKE_MODEL_ASSET_DIR): String? {
            val files = runCatching { assets.list(modelDir)?.toSet().orEmpty() }.getOrDefault(emptySet())
            val missing = WAKE_MODEL_FILES.filterNot(files::contains)
            if (missing.isNotEmpty()) return "唤醒词模型未安装（缺少 ${missing.joinToString()}），运行 tools/voice/fetch_voice_assets.py 后重新打包"
            return SherpaOnnxAsrEngine.nativeLibraryError
        }
    }
}

/**
 * One microphone user at a time: the wrapped [source] opens only once the previous holder of [lease] has closed its
 * recording, and holds it until its own flow ends. The wake-word listener and the voice session hand the mic over
 * this way without either blocking the main thread.
 */
class LeasedAudioSource(private val lease: Mutex, private val source: VoiceAudioSource) : VoiceAudioSource {
    override fun frames(): Flow<ShortArray> = flow { lease.withLock { emitAll(source.frames()) } }
}

/**
 * Lets a second listener hear what one recording hears, without opening the microphone again: [frames] records from
 * [source] as usual and offers a copy of each frame to [copies], dropping the oldest when that listener lags. The
 * wake-word spotter listens to a voice session this way, so saying the wake phrase again can interrupt it. Without
 * [copySource] the recording hands over other frames itself through [offer]: a session that hears only the caller's
 * seat gives the spotter every seat ([ArrayMicrophoneAudioSource]'s `onEveryone`).
 */
class VoiceAudioTee(
    private val source: VoiceAudioSource,
    buffered: Int = TEE_BUFFERED_FRAMES,
    private val copySource: Boolean = true,
) : VoiceAudioSource {
    private val shared = MutableSharedFlow<ShortArray>(extraBufferCapacity = buffered, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override fun frames(): Flow<ShortArray> = if (copySource) source.frames().onEach(::offer) else source.frames()

    /** Hands [copies] a copy of [frame]. */
    fun offer(frame: ShortArray) {
        shared.tryEmit(frame.copyOf())
    }

    /** The frames [frames] records from when this is collected; it never completes, so its collector is cancelled. */
    val copies: VoiceAudioSource = VoiceAudioSource { shared }
}

/** Two seconds of 100 ms frames. */
private const val TEE_BUFFERED_FRAMES = 20

/** A keyword line for the spotter: space-separated pinyin tokens, then `@` and the phrase reported on a hit. */
fun wakeKeyword(tokens: String, phrase: String): String = "${tokens.trim().split(Regex("\\s+")).joinToString(" ")} @$phrase"

/** One phrase the spotter listens for: its pinyin [tokens] and the [phrase] a hit reports. */
data class WakePhrase(val tokens: String, val phrase: String)

/**
 * How readily the spotter wakes (Settings → 语音 → 唤醒阈值): how many paths its search keeps ([paths]) and the phrases
 * it listens for, 嗨/嘿小博 (「hi 小博」) besides 你好小博 on every level. [LOW] also takes 博 heard as 波. Every level
 * also listens for [DECOY_WAKE_PHRASES], which never wake.
 *
 * More paths keep the phrase in the search, at some cost: each spotter used about 11% more time at 12 paths than at 4.
 * The decoys keep a wider search from waking on near misses. Re-measure on recordings of the phrase before tuning.
 */
enum class WakeSensitivity(
    val wireName: String,
    val threshold: Float,
    val boost: Float,
    val paths: Int,
    val phrases: List<WakePhrase>,
) {
    HIGH("high", DEFAULT_WAKE_THRESHOLD, DEFAULT_WAKE_BOOST, 6, CORE_WAKE_PHRASES),
    STANDARD("standard", DEFAULT_WAKE_THRESHOLD, DEFAULT_WAKE_BOOST, 12, CORE_WAKE_PHRASES),
    LOW("low", DEFAULT_WAKE_THRESHOLD, DEFAULT_WAKE_BOOST, 12, CORE_WAKE_PHRASES + BO_LEVEL_WAKE_PHRASE),
    ;

    /** The spotter's keywords: one line per phrase, the decoys after them, `/` between them. */
    fun keywords(): String = (phrases + DECOY_WAKE_PHRASES).joinToString("/") { wakeKeyword(it.tokens, it.phrase) }

    companion object {
        fun of(wireName: String?): WakeSensitivity? = entries.firstOrNull { it.wireName == wireName }
    }
}

internal fun spotterConfig(
    modelDir: String,
    threshold: Float,
    boost: Float = DEFAULT_WAKE_BOOST,
    paths: Int = DEFAULT_WAKE_PATHS,
) = KeywordSpotterConfig(
    featConfig = FeatureConfig(sampleRate = VOICE_SAMPLE_RATE, featureDim = 80),
    modelConfig = OnlineModelConfig(
        transducer = OnlineTransducerModelConfig(
            encoder = "$modelDir/$WAKE_ENCODER",
            decoder = "$modelDir/$WAKE_DECODER",
            joiner = "$modelDir/$WAKE_JOINER",
        ),
        tokens = "$modelDir/$WAKE_TOKENS",
        numThreads = 1,
        modelType = "zipformer2",
    ),
    keywordsFile = "$modelDir/$WAKE_KEYWORDS_FILE",
    keywordsScore = boost,
    keywordsThreshold = threshold,
    maxActivePaths = paths,
)

/** 你好，小博: 博 is bó, so 小伯 and 小泊 wake it too. */
const val DEFAULT_WAKE_PHRASE = "你好小博"
const val DEFAULT_WAKE_TOKENS = "n ǐ h ǎo x iǎo b ó"
/** Every level's phrases: 你好小博, and 「hi 小博」 as 嗨 in either tone or 嘿 (hey). */
private val CORE_WAKE_PHRASES = listOf(
    WakePhrase(DEFAULT_WAKE_TOKENS, DEFAULT_WAKE_PHRASE),
    WakePhrase("h āi x iǎo b ó", "嗨小博"),
    WakePhrase("h ài x iǎo b ó", "嗨小博"),
    WakePhrase("h ēi x iǎo b ó", "嘿小博"),
)
/** [WakeSensitivity.LOW]'s extra: 博 heard as 波. */
private val BO_LEVEL_WAKE_PHRASE = WakePhrase("n ǐ h ǎo x iǎo b ō", "你好小波")

/**
 * Near misses the spotter listens for so that they, not the phrase, win on their own words: a hit on one ([DECOY_MARK])
 * never wakes.
 */
val DECOY_WAKE_PHRASES = listOf(
    WakePhrase("n ǐ h ǎo x iǎo w áng", "${DECOY_MARK}你好小王"),
    WakePhrase("n ǐ h ǎo x iǎo g ē", "${DECOY_MARK}你好小哥"),
    WakePhrase("n ǐ h ǎo x iǎo m ěi", "${DECOY_MARK}你好小美"),
    WakePhrase("n ǐ h ǎo x iǎo p éng", "${DECOY_MARK}你好小朋"),
    WakePhrase("n ǐ h ǎo x iǎo b ǎo", "${DECOY_MARK}你好小宝"),
    WakePhrase("n ǐ h ǎo x iǎo j iě", "${DECOY_MARK}你好小姐"),
    WakePhrase("n ǐ h ǎo x iǎo q ì", "${DECOY_MARK}你好小气"),
    WakePhrase("n ǐ h ǎo x iǎo q ū", "${DECOY_MARK}你好小区"),
)

/** Starts a decoy's phrase. */
const val DECOY_MARK = "~"
/**
 * A wake phrase as a recognizer writes it inside a turn: punctuation may split it ("你好，小博", "Hi，小伯") and 博 comes
 * back as any of its homophones. 宝 is left out: "你好小宝" greets a person.
 */
val DEFAULT_WAKE_PHRASE_HEARD = Regex("(?:你好|[嗨嘿]|hi|hey)[，,、。.！!？?\\s]*[小晓][博伯泊勃铂]", RegexOption.IGNORE_CASE)
/** sherpa-onnx's default; higher wakes less often by mistake and misses more. */
const val DEFAULT_WAKE_THRESHOLD = 0.25f
/** The Kotlin binding's keywords score, which the spotter always ran with. */
const val DEFAULT_WAKE_BOOST = 1.5f
/** The paths the spotter's search keeps, as [WakeSensitivity.STANDARD] (sherpa-onnx's default is 4). */
const val DEFAULT_WAKE_PATHS = 12
const val WAKE_MODEL_NAME = "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile"
const val WAKE_MODEL_ASSET_DIR = "voice-models/$WAKE_MODEL_NAME"
private const val WAKE_ENCODER = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
private const val WAKE_DECODER = "decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
private const val WAKE_JOINER = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
private const val WAKE_TOKENS = "tokens.txt"
/** Written by the fetch script with the default phrase; each stream is created with [WakeWordListener]'s own. */
private const val WAKE_KEYWORDS_FILE = "keywords.txt"
private val WAKE_MODEL_FILES = listOf(WAKE_ENCODER, WAKE_DECODER, WAKE_JOINER, WAKE_TOKENS, WAKE_KEYWORDS_FILE)
private const val PCM_SCALE = 32_768f
