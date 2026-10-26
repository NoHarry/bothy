package cc.noharry.bothy.sample

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The lines 小博 answers a wake with. [file] names the clip under assets/voice-acks/<voice>/, which
 * tools/voice/make_wake_acks.py makes from the same list; [afterInterrupt] answers a wake that stopped what 小博 was doing,
 * and the [english] ones answer OK Bothy.
 */
internal enum class DebugWakeAckLine(val file: String, val text: String, val afterInterrupt: Boolean = false, val english: Boolean = false) {
    HERE("here", "我在"),
    YES("yes", "在呢"),
    MM("mm", "嗯，我在"),
    GO_ON("go-on", "好，你说", afterInterrupt = true),
    HERE_EN("here-en", "I'm here", english = true),
    YES_EN("yes-en", "Yes?", english = true),
    GO_ON_EN("go-on-en", "Go ahead", afterInterrupt = true, english = true),
}

/** One line decoded: 16-bit mono samples at [sampleRate]. */
internal class DebugWakeClip(val line: DebugWakeAckLine, val samples: ShortArray, val sampleRate: Int) {
    val millis: Long get() = samples.size * 1000L / sampleRate
}

/** The chosen voice's lines, decoded; none when 播报回复 is off or none could be had. [key] is the voice and model. */
internal data class DebugWakeAckSet(val key: String, val clips: List<DebugWakeClip>, val complete: Boolean)

/**
 * 小博 answers a wake out loud at once (owner 2026-10-12: 「唤醒后要立即回应一下…提前用那几个音色都生成一个固定音频」). Short
 * lines in every MiMo preset voice ship in the APK and the chosen voice's are kept decoded in memory, so a wake plays
 * one with no network round trip. Another voice's lines are synthesized once in the background and kept under
 * no_backup; until then, and with 播报回复 off, the wake chime plays instead. The line counts as 小博 speaking
 * ([DebugTtsActivity]): full duplex drops its echo and a barge-in stops it. Main thread, but for the loading.
 */
internal object DebugWakeAck {
    private val main = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var ready: DebugWakeAckSet? = null
    private var preparing: Job? = null
    private var last: DebugWakeAckLine? = null
    private var playing: AudioTrack? = null

    /** Whether a wake line is playing now (a wake during it is not interrupting 小博's work). */
    val speaking: Boolean get() = playing != null

    /** Reads the 播报 settings and decodes the chosen voice's lines in the background; again after each wake. */
    fun prepare(context: Context) {
        if (preparing?.isActive == true) return
        val app = context.applicationContext
        preparing = io.launch {
            runCatching { ready = load(app, ready) }.onFailure { Log.w(TAG, "wake lines not loaded: ${it.message}") }
        }
    }

    /**
     * Answers a wake ([interrupted]: it stopped what 小博 was doing), in English to OK Bothy ([english]); false when no
     * line could play, the chime alone answering.
     */
    fun play(context: Context, interrupted: Boolean, english: Boolean = false): Boolean {
        val clip = ready?.let { pickWakeAck(it.clips, interrupted, last, english) }
        val played = clip != null && start(clip)
        if (played) last = clip?.line
        Log.i(TAG, "wake answered ${if (played) "with 「${clip?.line?.text}」 (${ready?.key})" else "with the chime"}")
        prepare(context)
        return played
    }

    private fun start(clip: DebugWakeClip): Boolean {
        val track = DebugSpeechAudio.open { attributes ->
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(clip.sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes((clip.sampleRate * CHIME_LEAD_MILLIS.toInt() / 1000 + clip.samples.size) * 2)
                .build()
        } ?: return false
        var finished = false
        val finish = {
            if (!finished) {
                finished = true
                if (playing === track) playing = null
                DebugTtsActivity.end(track)
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        }
        // Silence first, so the line starts as the wake chime ([DebugVoiceFeedback.woke]) fades rather than over it.
        val samples = ShortArray(clip.sampleRate * CHIME_LEAD_MILLIS.toInt() / 1000 + clip.samples.size)
        clip.samples.copyInto(samples, samples.size - clip.samples.size)
        if (runCatching { track.write(samples, 0, samples.size) }.getOrDefault(-1) < 0) return false.also { finish() }
        playing = track
        DebugTtsActivity.begin(track, finish)
        DebugTtsActivity.speaking(clip.line.text)
        if (runCatching { track.play() }.isFailure) return false.also { finish() }
        main.postDelayed(finish, CHIME_LEAD_MILLIS + clip.millis + RELEASE_GRACE_MILLIS)
        return true
    }

    @Volatile private var http: BenchHttpTransport? = null

    private fun transport(app: Context): BenchHttpTransport = http ?: BenchHttpTransport(app).also { http = it }

    /** The lines for the 播报 settings now; [before] is kept when it is for the same voice and had them all. */
    private suspend fun load(app: Context, before: DebugWakeAckSet?): DebugWakeAckSet {
        val config = DebugProviderConfig.load(app).ttsConfig()
        val voice = config.voice.trim()
        val key = "${config.enabled}|${config.voiceIdentity}"
        if (before?.key == key && before.complete) return before
        if (!config.enabled) return DebugWakeAckSet(key, emptyList(), complete = true)
        val preset = MimoTtsVoice.entries.firstOrNull { it.id == voice }?.takeIf { config.provider == DebugTtsProvider.MIMO }
        if (preset != null) {
            val clips = DebugWakeAckLine.entries.mapNotNull { line -> packaged(app, preset, line) }
            return DebugWakeAckSet(key, clips, complete = true)
        }
        val clips = DebugWakeAckLine.entries.mapNotNull { line -> synthesized(app, config, line) }
        return DebugWakeAckSet(key, clips, complete = clips.size == DebugWakeAckLine.entries.size)
    }

    private fun packaged(app: Context, voice: MimoTtsVoice, line: DebugWakeAckLine): DebugWakeClip? {
        val bytes = try {
            app.assets.open("$WAKE_ACK_ASSETS/${voice.name.lowercase()}/${line.file}.wav").use { it.readBytes() }
        } catch (_: IOException) {
            return null
        }
        return parseWav(bytes)?.let { (samples, rate) -> DebugWakeClip(line, samples, rate) }
    }

    /** A voice without packaged lines: synthesized once, trimmed, and kept under no_backup for the next wakes. */
    private suspend fun synthesized(app: Context, config: DebugTtsConfig, line: DebugWakeAckLine): DebugWakeClip? {
        val file = File(app.noBackupFilesDir, "$WAKE_ACK_ASSETS/${cacheName(config)}/${line.file}.wav")
        if (!file.isFile && config.ready) {
            val audio = runCatching {
                val style = if (line.english) WAKE_ACK_STYLE_EN else WAKE_ACK_STYLE
                debugTtsClient(transport(app)).synthesize(config.copy(format = WAV, style = style), line.text)
            }.getOrNull()
            val (samples, rate) = audio?.bytes?.let(::parseWav) ?: return null
            file.parentFile?.mkdirs()
            // Written aside and renamed, so a crash mid-write never leaves a cut-off line that is kept for good.
            val partial = File(file.parentFile, "${file.name}.partial")
            partial.writeBytes(wavBytes(trimSilence(samples, rate), rate))
            if (!partial.renameTo(file)) partial.delete()
        }
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return parseWav(bytes)?.let { (samples, rate) -> DebugWakeClip(line, samples, rate) }
    }

    private fun cacheName(config: DebugTtsConfig): String =
        MessageDigest.getInstance("SHA-256").digest(config.voiceIdentity.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(CACHE_NAME_CHARS)

    private const val TAG = "DebugVoice"
    private const val WAV = "wav"
    private const val RELEASE_GRACE_MILLIS = 150L
    /** How long the wake chime sounds before the spoken answer comes in. */
    private const val CHIME_LEAD_MILLIS = 320L
    private const val CACHE_NAME_CHARS = 16
}

/**
 * Which line answers this wake, in its language ([english]): after an interrupt 「好，你说」, else another than the one
 * played last. None in that language, none plays: a Chinese 「我在」 would answer OK Bothy in the wrong language.
 */
internal fun pickWakeAck(
    clips: List<DebugWakeClip>,
    interrupted: Boolean,
    last: DebugWakeAckLine?,
    english: Boolean = false,
    random: Random = Random,
): DebugWakeClip? {
    val spoken = clips.filter { it.line.english == english }
    if (interrupted) spoken.firstOrNull { it.line.afterInterrupt }?.let { return it }
    val answers = spoken.filterNot { it.line.afterInterrupt }.ifEmpty { spoken }
    return answers.filter { it.line != last }.ifEmpty { answers }.randomOrNull(random)
}

/** The samples and sample rate of a 16-bit mono PCM RIFF/WAVE file, or null for anything else. */
internal fun parseWav(bytes: ByteArray): Pair<ShortArray, Int>? {
    if (bytes.size < WAV_HEADER_BYTES || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var rate = 0
    var offset = 12
    while (offset + 8 <= bytes.size) {
        val id = String(bytes, offset, 4, Charsets.US_ASCII)
        val body = offset + 8
        // A streamed WAV may leave its sizes unset; the data then runs to the end.
        val size = buffer.getInt(offset + 4).takeIf { it >= 0 && body + it <= bytes.size } ?: (bytes.size - body)
        when (id) {
            "fmt " -> {
                val pcmMono16 = buffer.getShort(body).toInt() == 1 && buffer.getShort(body + 2).toInt() == 1 && buffer.getShort(body + 14).toInt() == 16
                if (!pcmMono16) return null
                rate = buffer.getInt(body + 4)
            }
            "data" -> return if (rate > 0) ShortArray(size / 2) { buffer.getShort(body + 2 * it) } to rate else null
        }
        offset = body + size + (size and 1)
    }
    return null
}

/** [samples] as a 16-bit mono PCM WAV file. */
internal fun wavBytes(samples: ShortArray, rate: Int): ByteArray {
    val data = samples.size * 2
    val buffer = ByteBuffer.allocate(WAV_HEADER_BYTES + data).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(WAV_HEADER_BYTES - 8 + data).put("WAVE".toByteArray(Charsets.US_ASCII))
    buffer.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
    buffer.put("data".toByteArray(Charsets.US_ASCII)).putInt(data)
    samples.forEach { buffer.putShort(it) }
    return buffer.array()
}

/** [samples] without the silence around the speech (a little kept), faded in and out, as the packaged lines are. */
internal fun trimSilence(samples: ShortArray, rate: Int): ShortArray {
    val window = (rate * TRIM_WINDOW_MS / 1000).coerceAtLeast(1)
    fun loud(start: Int): Boolean {
        val end = minOf(samples.size, start + window)
        val energy = (start until end).sumOf { samples[it].toDouble() * samples[it] } / (end - start)
        return sqrt(energy) > TRIM_FLOOR
    }
    val voiced = (samples.indices step window).filter(::loud)
    if (voiced.isEmpty()) return samples
    val pad = rate * TRIM_PAD_MS / 1000
    val clip = samples.copyOfRange(maxOf(0, voiced.first() - pad), minOf(samples.size, voiced.last() + window + pad))
    val fade = minOf(clip.size / 2, rate * TRIM_FADE_MS / 1000)
    for (i in 0 until fade) {
        clip[i] = (clip[i] * i / fade).toShort()
        clip[clip.size - 1 - i] = (clip[clip.size - 1 - i] * i / fade).toShort()
    }
    return clip
}

/** Under assets/ in the APK, and under no_backup for synthesized voices. */
internal const val WAKE_ACK_ASSETS = "voice-acks"
/** How the lines are read, the English ones in English; tools/voice/make_wake_acks.py uses the same. */
private const val WAKE_ACK_STYLE =
    "被叫到名字时随口答应一声：自然、轻快、亲切，短促干脆，不拖音，不要播音腔或客服腔，像反应很快的 AI 伙伴在近距离聊天。不要模仿任何特定真人声音。"
private const val WAKE_ACK_STYLE_EN =
    "Answer casually when called by name: natural, light and friendly, short and crisp, no drawl, no announcer " +
        "or call-centre voice, like a quick AI companion chatting close by. Do not imitate any real person's voice."
private const val WAV_HEADER_BYTES = 44
private const val TRIM_WINDOW_MS = 10
private const val TRIM_PAD_MS = 40
private const val TRIM_FADE_MS = 12
/** -45 dBFS. */
private const val TRIM_FLOOR = 184.0
