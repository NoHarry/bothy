package cc.noharry.bothy.voice

/** Recognizer output; called from the engine's own thread. */
interface AsrListener {
    /** The best guess for the open segment so far; replaces the previous partial. */
    fun onPartial(text: String)

    /** The segment closed with this text; the next partial starts a new segment. */
    fun onFinal(text: String)

    /** The engine stopped working; the session ends. */
    fun onError(problem: VoiceProblem)
}

/**
 * What went wrong in a voice session, for the host to word in its own language ([VoiceSessionState.problem]). Each
 * [detail] is the service's or the system's own message, shown as it came.
 */
sealed interface VoiceProblem {
    /** The microphone's recording ended. */
    data object MicrophoneStopped : VoiceProblem

    /** Jev could not judge the latest words. */
    data class JudgeFailed(val detail: String) : VoiceProblem

    /** The cloud recognizer failed the task with [code]. */
    data class RecognitionFailed(val code: String, val detail: String) : VoiceProblem

    /** The cloud recognizer's connection failed, with the HTTP [status] when there was a response. */
    data class ConnectionFailed(val status: Int?, val detail: String) : VoiceProblem

    /** Anything else, in the words of whatever failed. */
    data class Other(val detail: String) : VoiceProblem
}

/**
 * A streaming recognizer fed 16 kHz mono PCM16. [start] and [stop] bracket one listening session; [accept] is called
 * from a single thread, in order, with consecutive frames.
 */
interface StreamingAsrEngine {
    /** Shown in the UI, e.g. "本地 · sherpa-onnx". */
    val label: String

    /** Whether the transcript has punctuation, which changes what Jev is told about it. */
    val punctuates: Boolean

    fun start(listener: AsrListener)

    fun accept(samples: ShortArray)

    fun stop()
}

const val VOICE_SAMPLE_RATE = 16_000
/** 100 ms per frame. */
const val VOICE_FRAME_SAMPLES = 1_600
