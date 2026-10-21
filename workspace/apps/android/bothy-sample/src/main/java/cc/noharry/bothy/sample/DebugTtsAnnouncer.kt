@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import cc.noharry.bothy.kernel.api.AgentEvent
import cc.noharry.bothy.kernel.api.reliability.RuntimeCompletionSummary
import cc.noharry.bothy.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.bothy.kernel.api.reliability.RuntimeRunStatus
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** The announcer that speaks through the configured provider (MiMo or 豆包) on this device's player; [onStatus] hears what goes wrong. */
internal fun deviceTtsAnnouncer(context: Context, scope: CoroutineScope, onStatus: (String) -> Unit): DebugTtsAnnouncer {
    val appContext = context.applicationContext
    return DebugTtsAnnouncer(scope, debugTtsClient(BenchHttpTransport(appContext)), AndroidDebugTtsPlayer(appContext), onStatus = onStatus)
}

internal class DebugTtsAnnouncer(
    private val scope: CoroutineScope,
    private val client: DebugTtsClient? = null,
    private val player: DebugTtsPlayer? = null,
    private val textOutput: ((text: String, final: Boolean) -> Unit)? = null,
    private val onStatus: (String) -> Unit = {},
) {
    init { require(textOutput != null || (client != null && player != null)) }
    private val queueLock = Any()
    private val pendingAnnouncements = ArrayDeque<QueuedTtsAnnouncement>()
    @Volatile private var activeJob: Job? = null

    /** Whether something is being said or waits its turn. */
    val busy: Boolean get() = activeJob?.isActive == true
    private var progressRunId: String? = null
    private val progressBuffer = StringBuilder()
    private val announcedProgress = linkedSetOf<String>()
    private val startedProgressTexts = mutableListOf<String>()
    private val heldProgress = mutableListOf<String>()
    private var lastQuestion: Pair<String, String>? = null
    /** The line being synthesized or played, so a voice append's acknowledgement can cut a progress line short. */
    @Volatile private var currentLine: Job? = null
    @Volatile private var currentKind: TtsAnnouncementKind? = null
    private val announcedQuestionIds = linkedSetOf<String>()

    fun observe(
        runScope: DebugRuntimeRunScope,
        event: AgentEvent,
        config: DebugTtsConfig,
        ownsUi: (DebugRuntimeRunScope) -> Boolean,
    ) {
        if ((textOutput == null && !config.ready) || !ownsUi(runScope)) return
        if (event is AgentEvent.UserInteraction) {
            val question = event.interaction
            if (question.kind == "QUESTION" && question.state == "PENDING" && announcedQuestionIds.add(question.questionId)) {
                enqueueAnnouncement(config, question.text, clearPending = false, kind = TtsAnnouncementKind.PROGRESS, runId = event.runId)
            }
            return
        }
        event.followUpQuestion()?.let { (runId, question) ->
            announceQuestion(runId, question, config)
            return
        }
        when (event) {
            is AgentEvent.Started -> if (lastQuestion?.first != event.runId) { lastQuestion = null; announcedQuestionIds.clear() }
            is AgentEvent.TextDelta -> observeProgress(event, config)
            is AgentEvent.NativeToolCallStarted ->
                if (event.runId == progressRunId && !closesReply(event.toolName)) releaseHeldProgress(config)
            // Hosted web search runs on the provider and emits no NativeToolCallStarted.
            is AgentEvent.SearchActivity -> if (event.runId == progressRunId) releaseHeldProgress(config)
            is AgentEvent.NetworkToolActivity -> if (event.runId == progressRunId) releaseHeldProgress(config)
            is AgentEvent.ResultAvailable -> {
                val runId = event.result.runId
                val successful = RuntimeCompletionSummary.firstPartyPass(event.result.status, event.result.completion)
                val canAsk = event.result.status in setOf(RuntimeRunStatus.COMPLETED, RuntimeRunStatus.BLOCKED) &&
                    event.result.completion.outcome in setOf(RuntimeCompletionOutcome.PARTIAL, RuntimeCompletionOutcome.BLOCKED)
                // A spoken request is answered out loud whatever the outcome; silence would read as not heard.
                val spoken = DebugSpokenRuns.contains(runId)
                if (successful || canAsk || spoken) {
                    val finalText = when {
                        spoken -> spokenRunFinal(runId, event.result.response)
                        successful -> finalTextForCompletedResult(runId, event.result.response)
                        else -> event.result.response.toFinalTtsSourceOrNull()?.let { it.partialReplyTts(it.spokenReply(MAX_FINAL_TTS_TEXT_CHARS)) }
                    }
                    finalText?.let {
                        enqueueAnnouncement(
                            config = config,
                            text = it,
                            clearPending = true,
                            kind = TtsAnnouncementKind.FINAL,
                            runId = runId,
                        )
                    }
                }
                // Drop mid-progress for this run after final; keep isolation if another run is active.
                dropQueuedForRun(runId, keepFinal = true)
                if (progressRunId == null || progressRunId == runId) {
                    resetProgress()
                }
            }
            else -> Unit
        }
    }

    private fun announceQuestion(runId: String, question: String, config: DebugTtsConfig) {
        val text = question.toFinalTtsSourceOrNull()?.spokenQuestion() ?: return
        val identity = runId to text
        if (lastQuestion == identity) return
        lastQuestion = identity
        val alreadySpoken = synchronized(queueLock) {
            progressRunId == runId && startedProgressTexts.any { it.normalizedProgressKey() == text.normalizedProgressKey() }
        }
        if (!alreadySpoken) enqueueAnnouncement(config, text, clearPending = true, runId = runId)
        dropQueuedForRun(runId, keepFinal = true)
        resetProgress()
    }

    fun announce(config: DebugTtsConfig, text: String) {
        enqueueAnnouncement(config, text, clearPending = true)
    }

    /**
     * Speaks [text] next, ahead of anything queued (a voice append's acknowledgement). A progress line already
     * playing gives way and is dropped; a final answer or question is let finish first.
     */
    fun announceFirst(config: DebugTtsConfig, text: String) {
        val speakable = text.replace(Regex("\\s+"), " ").trim().take(MAX_TTS_TEXT_CHARS)
        if (speakable.isBlank() || client == null || player == null || !config.ready) return
        synchronized(queueLock) { pendingAnnouncements.addFirst(QueuedTtsAnnouncement(config, speakable, TtsAnnouncementKind.ACK, null)) }
        if (currentKind == TtsAnnouncementKind.PROGRESS) {
            currentLine?.cancel()
            player.stop()
        }
        startWorkerIfNeeded()
    }

    /**
     * Plays A2A `channel=speech` text as the server selected it: no sentence re-selection. Progress queues
     * behind earlier lines of the same task; final drops progress that has not started, as the final is
     * complete on its own.
     */
    fun speakWireSpeech(config: DebugTtsConfig, taskId: String, text: String, final: Boolean) {
        val speakable = text.replace(Regex("\\s+"), " ").trim().take(MAX_TTS_TEXT_CHARS)
        if (speakable.isBlank() || client == null || player == null || !config.ready) return
        synchronized(queueLock) {
            pendingAnnouncements.removeAll { it.kind == TtsAnnouncementKind.PROGRESS && (final || it.runId != taskId) }
            pendingAnnouncements += QueuedTtsAnnouncement(
                config, speakable, if (final) TtsAnnouncementKind.FINAL else TtsAnnouncementKind.PROGRESS, taskId,
            )
        }
        startWorkerIfNeeded()
    }

    private fun enqueueAnnouncement(
        config: DebugTtsConfig,
        text: String,
        clearPending: Boolean,
        kind: TtsAnnouncementKind = TtsAnnouncementKind.FINAL,
        runId: String? = null,
    ) {
        val speakable = text.withSpokenRecommendationNotes().replace(Regex("\\s+"), " ").trim().take(MAX_TTS_TEXT_CHARS)
        if (speakable.isBlank()) return
        textOutput?.let { output ->
            if (kind == TtsAnnouncementKind.PROGRESS) {
                synchronized(queueLock) { startedProgressTexts += speakable }
            }
            output(speakable, kind == TtsAnnouncementKind.FINAL)
            return
        }
        if (!config.ready) return
        synchronized(queueLock) {
            if (clearPending) {
                // Clear other runs' leftovers; never let previous-run progress ride into a new final. An
                // acknowledgement still waiting was promised to the speaker just now: it stays, ahead of the final.
                pendingAnnouncements.removeAll { it.kind != TtsAnnouncementKind.ACK }
            } else if (runId != null) {
                pendingAnnouncements.removeAll { it.runId != null && it.runId != runId && it.kind == TtsAnnouncementKind.PROGRESS }
            }
            pendingAnnouncements += QueuedTtsAnnouncement(config, speakable, kind, runId)
        }
        startWorkerIfNeeded()
    }

    fun stop() {
        announcedQuestionIds.clear()
        lastQuestion = null
        activeJob?.cancel()
        activeJob = null
        synchronized(queueLock) { pendingAnnouncements.clear() }
        resetProgress()
        player?.stop()
    }

    private fun observeProgress(event: AgentEvent.TextDelta, config: DebugTtsConfig) {
        if (progressRunId != event.runId) {
            // New run: drop queued progress from any previous run so mid-TTS cannot leak across turns.
            dropQueuedProgressExcept(event.runId)
            resetProgress()
            progressRunId = event.runId
        }
        progressBuffer.append(event.text)
        heldProgress += drainProgressText()
    }

    /**
     * A progress line is spoken once its round goes on to real work. When the round instead closes the
     * reply (finish/ask), the line is dropped: the final answer or question says it, not twice.
     */
    private fun releaseHeldProgress(config: DebugTtsConfig) {
        val lines = heldProgress.toList()
        heldProgress.clear()
        lines.forEach { announceProgress(config, it) }
    }

    private fun announceProgress(config: DebugTtsConfig, text: String) {
        val normalized = text.normalizedProgressKey()
        if (announcedProgress.size >= MAX_PROGRESS_ANNOUNCEMENTS_PER_RUN) return
        // A reworded repeat ("我先看下当前用的音源…") adds nothing for the listener.
        if (announcedProgress.any { it.bigramSimilarity(normalized) >= NEAR_DUPLICATE_PROGRESS_SIMILARITY }) return
        announcedProgress += normalized
        enqueueAnnouncement(
            config = config,
            text = text.take(MAX_PROGRESS_TTS_TEXT_CHARS),
            clearPending = false,
            kind = TtsAnnouncementKind.PROGRESS,
            runId = progressRunId,
        )
    }

    private fun drainProgressText(): List<String> {
        val output = mutableListOf<String>()
        while (true) {
            val bufferText = progressBuffer.toString()
            val marker = TTS_PROGRESS_MARKER.find(bufferText)
            if (marker == null) {
                trimProgressBuffer()
                return output
            }
            val markerStart = marker.range.first
            if (markerStart > 0) {
                progressBuffer.delete(0, markerStart)
            }
            val normalized = progressBuffer.toString()
            val contentStart = marker.value.length
            val contentEnd = normalized.progressMessageEnd(contentStart)
            if (contentEnd == null) {
                trimProgressBuffer()
                return output
            }
            normalized.substring(contentStart, contentEnd)
                .toProgressTtsTextOrNull()
                ?.let(output::add)
            progressBuffer.delete(0, normalized.consumeProgressEnd(contentEnd))
        }
    }

    private fun trimProgressBuffer() {
        if (progressBuffer.length <= MAX_PROGRESS_BUFFER_CHARS) return
        progressBuffer.delete(0, progressBuffer.length - MAX_PROGRESS_BUFFER_CHARS)
    }

    private fun resetProgress() {
        progressRunId = null
        progressBuffer.clear()
        heldProgress.clear()
        announcedProgress.clear()
        synchronized(queueLock) { startedProgressTexts.clear() }
    }

    private fun dropQueuedProgressExcept(runId: String) {
        synchronized(queueLock) {
            pendingAnnouncements.removeAll {
                it.kind == TtsAnnouncementKind.PROGRESS && it.runId != null && it.runId != runId
            }
        }
    }

    private fun dropQueuedForRun(runId: String, keepFinal: Boolean) {
        synchronized(queueLock) {
            pendingAnnouncements.removeAll {
                it.runId == runId && (!keepFinal || it.kind == TtsAnnouncementKind.PROGRESS)
            }
        }
    }

    /**
     * A spoken request's answer: all of the reply's prose, in whole sentences up to [MAX_SPOKEN_RUN_CHARS], since
     * what was added by voice is often answered further down (lists stay on screen). When progress was already
     * spoken, the usual short close instead, so nothing is said twice.
     */
    private fun spokenRunFinal(runId: String, response: String): String? {
        val progressSpoken = synchronized(queueLock) { progressRunId == runId && startedProgressTexts.isNotEmpty() }
        if (progressSpoken) return response.toFinalTtsAfterProgress()
        val source = response.toFinalTtsSourceOrNull() ?: return null
        val prose = source.lineSequence().filterNot { TTS_LIST_ITEM_PREFIX.containsMatchIn(it) }.joinToString("\n").trim()
        return (prose.leadingSentences(MAX_SPOKEN_RUN_CHARS) ?: prose.firstSpeakableSentence(MAX_SPOKEN_RUN_CHARS))
            ?.withFollowUpQuestions(source)
    }

    private fun finalTextForCompletedResult(runId: String, response: String): String? {
        val progressTexts = synchronized(queueLock) {
            if (progressRunId == runId) startedProgressTexts.toList() else emptyList()
        }
        return if (progressTexts.isNotEmpty()) {
            response.toFinalTtsAfterProgress()
        } else {
            response.toStandaloneFinalTtsTextOrNull()
        }
    }

    private fun startWorkerIfNeeded() {
        if (activeJob?.isActive == true) return
        // Released on completion, which also runs for a job cancelled before it started.
        val worker = Any()
        DebugTtsActivity.begin(worker, ::stop)
        activeJob = scope.launch {
            while (true) {
                val next = synchronized(queueLock) { pendingAnnouncements.removeFirstOrNull() } ?: break
                if (isStaleProgress(next)) continue
                recordStartedProgress(next)
                currentKind = next.kind
                // Its own job, so announceFirst can cancel this line and the queue still goes on.
                coroutineScope { currentLine = launch { synthesizeAndPlay(next.config, next.text) } }
                currentLine = null
                currentKind = null
            }
            activeJob = null
        }.also { job -> job.invokeOnCompletion { DebugTtsActivity.end(worker) } }
    }

    private fun isStaleProgress(next: QueuedTtsAnnouncement): Boolean {
        if (next.kind != TtsAnnouncementKind.PROGRESS) return false
        val active = progressRunId
        return next.runId != null && active != null && next.runId != active
    }

    private fun recordStartedProgress(next: QueuedTtsAnnouncement) {
        if (next.kind != TtsAnnouncementKind.PROGRESS) return
        synchronized(queueLock) {
            if (next.runId == progressRunId) startedProgressTexts += next.text
        }
    }

    private suspend fun synthesizeAndPlay(config: DebugTtsConfig, text: String) {
        val client = requireNotNull(client)
        val player = requireNotNull(player)
        DebugTtsActivity.speaking(text)
        try {
            if (config.stream) {
                onStatus("TTS streaming")
                player.playStream(client.stream(config, text))
            } else {
                onStatus("TTS synthesizing")
                val audio = client.synthesize(config, text)
                onStatus("TTS playing")
                player.play(audio)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onStatus("TTS failed: ${error.message.orEmpty().ifBlank { "unknown error" }}")
        }
    }
}

private data class QueuedTtsAnnouncement(
    val config: DebugTtsConfig,
    val text: String,
    val kind: TtsAnnouncementKind,
    val runId: String?,
)

private enum class TtsAnnouncementKind {
    FINAL,
    PROGRESS,

    /** A voice append's acknowledgement: spoken next, kept when a final clears the queue. */
    ACK,
}

internal interface DebugTtsPlayer {
    suspend fun play(audio: DebugTtsAudio)

    suspend fun playStream(chunks: Flow<DebugTtsAudioChunk>)

    fun stop()
}

internal class AndroidDebugTtsPlayer(
    context: Context,
) : DebugTtsPlayer {
    private val appContext = context.applicationContext
    private var player: MediaPlayer? = null
    private var audioTrack: AudioTrack? = null
    private var currentFile: File? = null

    override suspend fun play(audio: DebugTtsAudio) {
        if (audio.format == PCM16_FORMAT) {
            playStream(flowOf(DebugTtsAudioChunk(audio.bytes, audio.format)))
            return
        }
        require(audio.format == WAV_FORMAT) { "Unsupported TTS audio format: ${audio.format}" }
        DebugTtsLog.info("player preparing bytes=${audio.bytes.size} format=${audio.format}")
        val file = withContext(Dispatchers.IO) { writeAudioFile(audio.bytes) }
        withContext(Dispatchers.Main.immediate) { startPlayer(file) }
    }

    override suspend fun playStream(chunks: Flow<DebugTtsAudioChunk>) {
        var track: AudioTrack? = null
        var chunkCount = 0
        var byteCount = 0
        try {
            chunks.collect { chunk ->
                require(chunk.format == PCM16_FORMAT) { "Unsupported TTS stream format: ${chunk.format}" }
                if (chunk.bytes.isEmpty()) return@collect
                val activeTrack = track ?: withContext(Dispatchers.Main.immediate) {
                    startPcm16Track()
                }.also { track = it }
                chunkCount += 1
                byteCount += chunk.bytes.size
                if (chunkCount == 1) {
                    DebugTtsLog.info("stream player first chunk bytes=${chunk.bytes.size}")
                }
                writePcm16(activeTrack, chunk.bytes)
            }
            track?.let { activeTrack -> waitForPcm16Drain(activeTrack, byteCount) }
            DebugTtsLog.info("stream player completed chunks=$chunkCount bytes=$byteCount")
        } finally {
            track?.let { activeTrack ->
                withContext(Dispatchers.Main.immediate) { releaseTrack(activeTrack) }
            }
        }
    }

    override fun stop() {
        val active = player
        val track = audioTrack
        val file = currentFile
        player = null
        audioTrack = null
        currentFile = null
        active?.let(DebugTtsActivity::end)
        track?.let(DebugTtsLevels::stop)
        track?.let(DebugTtsActivity::end)
        runCatching { active?.stop() }
        runCatching { active?.release() }
        runCatching { track?.stop() }
        runCatching { track?.release() }
        file?.delete()
    }

    private fun writeAudioFile(bytes: ByteArray): File =
        File.createTempFile(TTS_FILE_PREFIX, TTS_FILE_SUFFIX, appContext.cacheDir)
            .also { it.writeBytes(bytes) }

    private fun startPlayer(file: File) {
        stop()
        val next = MediaPlayer()
        next.setAudioAttributes(DebugSpeechAudio.attributes())
        next.setDataSource(file.absolutePath)
        next.setOnCompletionListener {
            DebugTtsLog.info("player completed fileBytes=${file.length()}")
            releasePlayer(it, file)
        }
        next.setOnErrorListener { mediaPlayer, what, extra ->
            DebugTtsLog.warn("player error what=$what extra=$extra fileBytes=${file.length()}")
            releasePlayer(mediaPlayer, file)
            true
        }
        next.prepare()
        player = next
        currentFile = file
        DebugTtsActivity.begin(next)
        next.start()
        DebugTtsLog.info("player started fileBytes=${file.length()}")
    }

    private fun startPcm16Track(): AudioTrack {
        stop()
        val minBuffer = AudioTrack.getMinBufferSize(
            PCM16_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // The speech channel (DebugSpeechAudio).
        val track = DebugSpeechAudio.open { attributes ->
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(PCM16_SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(max(minBuffer, PCM16_MIN_BUFFER_BYTES))
                .build()
        } ?: error("No speech player could be opened")
        audioTrack = track
        DebugTtsActivity.begin(track)
        DebugTtsLevels.start(track, PCM16_SAMPLE_RATE_HZ)
        track.play()
        DebugTtsLog.info("stream player started sampleRate=$PCM16_SAMPLE_RATE_HZ")
        return track
    }

    private suspend fun writePcm16(track: AudioTrack, bytes: ByteArray) {
        DebugTtsLevels.append(track, bytes)
        var offset = 0
        while (offset < bytes.size) {
            val written = withContext(Dispatchers.IO) {
                track.write(bytes, offset, bytes.size - offset)
            }
            when {
                written > 0 -> offset += written
                written == 0 -> yield()
                else -> error("AudioTrack write failed: $written")
            }
        }
    }

    private suspend fun waitForPcm16Drain(track: AudioTrack, byteCount: Int) {
        val targetFrames = byteCount / PCM16_BYTES_PER_FRAME
        if (targetFrames <= 0) return
        val deadline = System.currentTimeMillis() + pcm16DrainTimeoutMs(targetFrames)
        while (
            track.playState == AudioTrack.PLAYSTATE_PLAYING &&
            track.playbackHeadPosition < targetFrames &&
            System.currentTimeMillis() < deadline
        ) {
            delay(PCM16_DRAIN_POLL_MS)
        }
        DebugTtsLog.info(
            "stream player drained targetFrames=$targetFrames playbackHead=${track.playbackHeadPosition}",
        )
    }

    private fun pcm16DrainTimeoutMs(targetFrames: Int): Long {
        val audioDurationMs = targetFrames * MILLIS_PER_SECOND / PCM16_SAMPLE_RATE_HZ
        return max(audioDurationMs + PCM16_DRAIN_GRACE_MS, PCM16_DRAIN_MIN_TIMEOUT_MS)
    }

    private fun releasePlayer(mediaPlayer: MediaPlayer, file: File) {
        if (player === mediaPlayer) player = null
        if (currentFile == file) currentFile = null
        DebugTtsActivity.end(mediaPlayer)
        runCatching { mediaPlayer.release() }
        file.delete()
    }

    private fun releaseTrack(track: AudioTrack) {
        if (audioTrack === track) audioTrack = null
        DebugTtsLevels.stop(track)
        DebugTtsActivity.end(track)
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    companion object {
        private const val PCM16_FORMAT = "pcm16"
        private const val PCM16_SAMPLE_RATE_HZ = 24_000
        private const val PCM16_BYTES_PER_FRAME = 2
        private const val PCM16_MIN_BUFFER_BYTES = 9_600
        private const val PCM16_DRAIN_POLL_MS = 20L
        private const val PCM16_DRAIN_GRACE_MS = 500L
        private const val PCM16_DRAIN_MIN_TIMEOUT_MS = 1_000L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val WAV_FORMAT = "wav"
        private const val TTS_FILE_PREFIX = "debug-tts-"
        private const val TTS_FILE_SUFFIX = ".wav"
    }
}

/**
 * The progress lines in this text as they are spoken: from each `TTS:` marker, anywhere in a line, to the line end, the
 * first sentence end or the next marker; an unfinished last one runs to the end. The card shows only these when
 * 显示思考过程 is off.
 */
internal fun String.spokenProgress(): List<String> = buildList {
    var start = 0
    while (true) {
        val marker = TTS_PROGRESS_MARKER.find(this@spokenProgress, start) ?: break
        val contentStart = marker.range.last + 1
        val end = progressMessageEnd(contentStart) ?: length
        substring(contentStart, end).trim().takeIf(String::isNotEmpty)?.let(::add)
        start = maxOf(end, contentStart)
    }
}

private fun String.progressMessageEnd(start: Int): Int? {
    val lineEnd = indexOfAny(charArrayOf('\n', '\r'), startIndex = start).takeIf { it >= 0 }
    val sentenceEnd = indexOfFirstAfter(start) { it in PROGRESS_SENTENCE_ENDINGS }
        ?.plus(1)
    // Next TTS: marker ends the previous progress line even without punctuation.
    val nextMarker = TTS_PROGRESS_MARKER.find(this, startIndex = start)?.range?.first
        ?.takeIf { it > start }
    return listOfNotNull(lineEnd, sentenceEnd, nextMarker).minOrNull()
}

private fun String.consumeProgressEnd(end: Int): Int {
    var index = end
    while (index < length && (this[index] == '\n' || this[index] == '\r')) index += 1
    return index
}

private fun String.indexOfFirstAfter(start: Int, predicate: (Char) -> Boolean): Int? {
    var index = start
    while (index < length) {
        if (predicate(this[index])) return index
        index += 1
    }
    return null
}

private fun String.toProgressTtsTextOrNull(): String? {
    val normalized = withoutCodeParentheticals().trim().replace(Regex("\\s+"), " ")
    if (normalized.length < MIN_PROGRESS_TTS_TEXT_CHARS || normalized.length > MAX_PROGRESS_TTS_TEXT_CHARS) {
        return null
    }
    if (normalized.normalizedProgressSpeech() in BLOCKED_GENERIC_PROGRESS_TEXT) return null
    if (normalized.startsWith("{") || normalized.startsWith("[") || normalized.contains("```")) return null
    val lower = normalized.lowercase()
    if (BLOCKED_PROGRESS_TOKENS.any { it in lower }) return null
    return normalized
}

/**
 * After mid-progress was already spoken for this run, keep the completion cue short and generic.
 * Content comes from the model finish text; host does not rebuild scene scripts (places/artists).
 */
private fun String.toFinalTtsAfterProgress(): String? {
    val source = toFinalTtsSourceOrNull() ?: return "好了，都处理好了。"
    val first = source.spokenReply(MAX_AFTER_PROGRESS_FINAL_TTS_TEXT_CHARS)
    return first?.withFollowUpQuestions(source) ?: "好了，都处理好了。"
}

private fun String.toStandaloneFinalTtsTextOrNull(): String? {
    val normalized = toFinalTtsSourceOrNull() ?: return null
    return normalized.spokenReply(MAX_FINAL_TTS_TEXT_CHARS)?.withFollowUpQuestions(normalized)
        ?: normalized.take(MAX_FINAL_TTS_TEXT_CHARS).trimEnd().let {
            if (it.endsWith('。') || it.endsWith('！') || it.endsWith('？')) it else "$it。"
        }
}

/** The on-screen reply: marked progress lines were already spoken and are not part of it. */
internal fun String.displayReplyText(): String {
    val kept = lines().filterNot { it.isTtsMarkedLine() }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    return kept.ifBlank { lines().joinToString("\n") { it.replace(TTS_LINE_PREFIX, "") }.trim() }
}

/**
 * Speak-then-show: the prose before the first list is what the assistant says (and the screen captions);
 * the list and what follows are for the screen. A colon intro falls back to the first sentence; a lead too
 * long to listen to keeps the whole sentences that fit.
 */
private fun String.spokenReply(maxChars: Int): String? {
    // Prose before the first list line is spoken, even when the model splits it into paragraphs (an
    // introduction, then the results); the list and anything after it stay on screen.
    val lead = lineSequence()
        .takeWhile { !TTS_LIST_ITEM_PREFIX.containsMatchIn(it) }
        .joinToString("\n").trim()
    if (lead.isEmpty() || lead.last() == '：' || lead.last() == ':') return firstSpeakableSentence(maxChars)
    if (lead.length <= MAX_SPOKEN_LEAD_CHARS) return lead
    // A long multi-intent lead keeps the whole sentences that fit, not just its first one.
    return lead.leadingSentences(MAX_SPOKEN_LEAD_CHARS) ?: firstSpeakableSentence(maxChars)
}

private fun String.leadingSentences(maxChars: Int): String? {
    var end = 0
    while (end < length) {
        val next = substring(end).firstSentenceEnd()?.let { end + it + 1 } ?: break
        if (next > maxChars) break
        end = next
    }
    return substring(0, end).trim().takeIf { it.isNotEmpty() }
}

private fun String.isTtsMarkedLine(): Boolean = TTS_LINE_PREFIX.containsMatchIn(this)

private fun String.toFinalTtsSourceOrNull(): String? {
    val joined = lines()
        .filterNot { it.isTtsMarkedLine() }
        .joinToString("\n") { it.replace(MARKDOWN_HEADING_PREFIX, "") }
    if (joined.contains("```")) return null
    // Emphasis markers are display formatting; a synthesizer would read them out. Line and paragraph
    // breaks stay as boundaries (lead paragraph, follow-up questions); speech flattens them on enqueue.
    val normalized = joined.replace(MARKDOWN_EMPHASIS, "").withoutCodeParentheticals().replace(INLINE_ENUMERATION, "")
        .lines().joinToString("\n") { it.replace(Regex("\\s+"), " ").trim() }
        .replace(Regex("\n{3,}"), "\n\n").trim()
    if (normalized.isBlank() || normalized.startsWith("{") || normalized.startsWith("[")) {
        return null
    }
    return normalized
}

/**
 * Dates as they are said (owner 2026-10-19: 10/20、10/24 是日期就按日期播报): 「2026/10/24」「2026-10-24」 become
 * 「2026年10月24日」, and 「10/24」 becomes 「10月24日」 when it can only be a date (a day past 12, a month from 10, or a
 * weekday or 号 after it), so a fraction such as 「3/4」 stays. A temperature (「30/22°」), a run of numbers
 * (「80/180/280」), a rating (「4.7/5」) or a time is left alone. The screen keeps what the model wrote.
 */
internal fun String.spokenDates(): String {
    val full = FULL_DATE.replace(this) { m ->
        "${m.groupValues[1]}年${m.groupValues[2].toInt()}月${m.groupValues[3].toInt()}日"
    }
    return MONTH_DAY.replace(full) { m ->
        val month = m.groupValues[1].toInt()
        val day = m.groupValues[2].toInt()
        val followedByDay = DATE_FOLLOWER.containsMatchIn(full.substring(m.range.last + 1).take(DATE_FOLLOWER_LOOKAHEAD))
        // 「评分10/10」「进度11/12」 are a score and a count, whatever the numbers.
        val counted = COUNT_BEFORE.containsMatchIn(full.substring(0, m.range.first).takeLast(DATE_FOLLOWER_LOOKAHEAD))
        if (!counted && (day > MONTHS || month >= 10 || followedByDay)) "${month}月${day}日" else m.value
    }
}

/** Parenthesized result codes are display detail, e.g. "（resultCode=10000）" or "（need_login，20003）". */
private fun String.withoutCodeParentheticals(): String =
    PARENTHETICAL.replace(this) { if (SPOKEN_CODE_TOKEN.containsMatchIn(it.value)) "" else it.value }

/** Prefer the first complete Chinese/English sentence; otherwise a truncated clause. */
private fun String.firstSpeakableSentence(maxChars: Int): String? {
    val sentenceEnd = firstSentenceEnd()?.plus(1)
    return when {
        sentenceEnd != null && sentenceEnd <= maxChars -> substring(0, sentenceEnd)
        length <= maxChars -> this
        else -> take(maxChars).trimEnd() + "。"
    }
}

/**
 * First sentence-ending punctuation. A Chinese ending always ends a sentence. An ASCII `.`, `!` or `?`
 * ends one only before whitespace or the end of text, and a `.` right after a bare number is a list
 * marker ("1. ") or a decimal ("12.5"), not the end of a sentence.
 */
private fun String.firstSentenceEnd(): Int? {
    for (index in indices) {
        val char = this[index]
        // An options list ("1. 东广场停车场（推荐）") ends the spoken intro unless its first item is a sentence.
        if (char == '\n' && index > 0 && startsOptionList(index + 1)) return index - 1
        if (char in CJK_SENTENCE_ENDINGS) return index
        if (char !in FINAL_SENTENCE_ENDINGS) continue
        val next = getOrNull(index + 1)
        if (next != null && !next.isWhitespace()) continue
        if (char == '.' && precededByBareNumber(index)) continue
        return index
    }
    return null
}

private fun String.startsOptionList(lineStart: Int): Boolean {
    val line = substring(lineStart).substringBefore('\n')
    val marker = TTS_LIST_ITEM_PREFIX.find(line) ?: return false
    return line.substring(marker.range.last + 1).firstSentenceEnd() == null
}

private fun String.precededByBareNumber(dot: Int): Boolean {
    var start = dot
    while (start > 0 && this[start - 1].isDigit()) start--
    return start < dot && (start == 0 || !this[start - 1].isLetterOrDigit())
}

private fun String.normalizedProgressKey(): String =
    trim().replace(Regex("\\s+"), " ").lowercase()

/** Jaccard similarity of character bigrams, ignoring punctuation and spaces. */
private fun String.bigramSimilarity(other: String): Double {
    fun String.bigrams() = filter { it.isLetterOrDigit() }.windowed(2).toSet()
    val left = bigrams()
    val right = other.bigrams()
    if (left.isEmpty() || right.isEmpty()) return if (this == other) 1.0 else 0.0
    return (left intersect right).size.toDouble() / (left union right).size
}

private fun String.normalizedProgressSpeech(): String =
    trim()
        .trim('。', '！', '？', '.', '!', '?', '，', ',')
        .replace(Regex("\\s+"), "")

private val TTS_PROGRESS_MARKER = Regex("""(?i)(?<![A-Za-z0-9_])TTS:""")
private val PROGRESS_SENTENCE_ENDINGS = setOf('。', '！', '？', '.', '!', '?')
private val FINAL_SENTENCE_ENDINGS = setOf('。', '！', '？', '.', '!', '?')
private val CJK_SENTENCE_ENDINGS = setOf('。', '！', '？')
private val MARKDOWN_EMPHASIS = Regex("\\*\\*|__|`")
private val MARKDOWN_HEADING_PREFIX = Regex("^\\s*#{1,6}\\s+")
private val TTS_LINE_PREFIX = Regex("""^\s*TTS:\s*""", RegexOption.IGNORE_CASE)
// "请选一个：1. 东广场 2. 西广场。" Options listed inline stay on screen; speech keeps "请选一个。".
private val INLINE_ENUMERATION = Regex("""[：:][ \t]*(?:1[.、]|[（(]1[）)])[^。！？\n]*?(?:2[.、]|[（(]2[）)])[^。！？\n]*""")
private val PARENTHETICAL = Regex("""[（(][^（）()]*[）)]""")
private val FULL_DATE = Regex("""(?<![\d/.:-])(20\d{2})[/-](1[0-2]|0?[1-9])[/-](3[01]|[12]\d|0?[1-9])(?![\d/.:])""")
private val MONTH_DAY = Regex("""(?<![\d/.:])(1[0-2]|0?[1-9])/(3[01]|[12]\d|0?[1-9])(?![\d/.:]|\s*(?:[°℃%]|度|分|元|折|星))""")
private val COUNT_BEFORE = Regex("""(?:评分|得分|分数|打分|比分|进度|完成|第)\s*$""")
private val DATE_FOLLOWER = Regex("""^\s*[（(]?\s*(?:周|星期|礼拜|号)""")
private const val DATE_FOLLOWER_LOOKAHEAD = 4
private const val MONTHS = 12
// key=value, snake_case, dotted package names, or a bare 4+ digit number with no unit after it.
private val SPOKEN_CODE_TOKEN = Regex(
    """[A-Za-z]\w*\s*=|[A-Za-z0-9]+_\w+|[a-z]+\.[a-z]+\.[a-z]|(?<![\d.])\d{4,}(?![\d.])(?!\s*[一-鿿A-Za-z%])""",
)
private val BLOCKED_GENERIC_PROGRESS_TEXT = setOf(
    "好的",
    "收到",
    "明白",
    "明白了",
    "我来处理",
    "我先处理",
    "准备出发",
)
private val BLOCKED_PROGRESS_TOKENS = listOf(
    "read_file",
    "mcp__",
    "command_",
    "schema",
    "json",
    "api key",
    "secret",
    "structuredcontent",
)
private const val MAX_TTS_TEXT_CHARS = 600
private const val MAX_FINAL_TTS_TEXT_CHARS = 180
/** A voice request's spoken answer: about 45 s of speech. */
private const val MAX_SPOKEN_RUN_CHARS = 220
private const val MAX_SPOKEN_LEAD_CHARS = 140
private const val MAX_AFTER_PROGRESS_FINAL_TTS_TEXT_CHARS = 120
private const val MIN_PROGRESS_TTS_TEXT_CHARS = 6
private const val MAX_PROGRESS_TTS_TEXT_CHARS = 80
private const val MAX_PROGRESS_BUFFER_CHARS = 512
private const val MAX_PROGRESS_ANNOUNCEMENTS_PER_RUN = 4
/** Tools that end the reply (or only update the plan); progress written alongside them is not spoken. */
private val REPLY_CLOSING_TOOLS = setOf("finish", "ask", "update_todo")

/**
 * Handing a task to someone's own agent answers within a second and the reply says it was handed over, so a line
 * like "我这就转给…" written with the call would be said twice (and before the hand-over is known to have worked).
 * The same for subagents: "已经派出三路…" with spawn_agent, then the reply saying so again (owner 2026-10-17: 太重复).
 * wait_agent is real work: a line held from spawn_agent is said when the agent goes on to wait, as the "先回我一声"
 * before the results.
 */
internal fun closesReply(toolName: String): Boolean =
    toolName in REPLY_CLOSING_TOOLS || toolName in SUBAGENT_TOOLS || toolName.substringAfterLast("__") == DELEGATION_TOOL_NAME

private const val DELEGATION_TOOL_NAME = "a2a_message_send"
private val SUBAGENT_TOOLS = setOf("spawn_agent", "cancel_agent")
private const val NEAR_DUPLICATE_PROGRESS_SIMILARITY = 0.6
