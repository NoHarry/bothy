package cc.noharry.bothy.server.voice
import android.content.Context
import androidx.annotation.StringRes
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.FindingKind
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.protocol.text
import cc.noharry.bothy.protocol.BothyFindingCode
import cc.noharry.bothy.protocol.TtsProvider
import cc.noharry.bothy.server.R
import cc.noharry.bothy.server.voice.tts.TtsConfig
import cc.noharry.bothy.server.voice.tts.labelFinding
import cc.noharry.bothy.voice.DashScopeAsrModel
import cc.noharry.bothy.voice.HotwordProblem
import cc.noharry.bothy.voice.ModelProblem
import cc.noharry.bothy.voice.SherpaOnnxAsrEngine
import cc.noharry.bothy.voice.ThresholdProblem
import cc.noharry.bothy.voice.VoiceProblem
import cc.noharry.bothy.voice.WakeWordListener

/*
 * What bothy-voice reports as data, as findings for the screens to word: neither the library nor the server carries
 * screen text for it.
 */

/** Why on-device recognition cannot run on this device, or null when it can (Settings → 语音, 检查). */
internal fun localAsrUnavailable(context: Context): Finding? = SherpaOnnxAsrEngine.availabilityProblem(context.assets)
    ?.let { modelProblem(it, BothyFindingCode.VOICE_LOCAL_MODEL_MISSING) }

/** Why the wake word cannot be listened for on this device, or null when it can. */
internal fun wakeModelUnavailable(context: Context): Finding? = WakeWordListener.availabilityProblem(context.assets)
    ?.let { modelProblem(it, BothyFindingCode.VOICE_WAKE_MODEL_MISSING) }

private fun modelProblem(problem: ModelProblem, missing: FindingKind): Finding = when (problem) {
    is ModelProblem.Missing -> finding(missing, problem.modelDir, problem.files.joinToString())
    is ModelProblem.NativeLibrary -> finding(BothyFindingCode.VOICE_NATIVE_LIBRARY_FAILED, problem.detail)
}

/** A session's [problem] as the voice strip and the overlay show it. */
internal fun voiceProblemFinding(problem: VoiceProblem): Finding = when (problem) {
    VoiceProblem.MicrophoneStopped -> finding(BothyFindingCode.VOICE_PROBLEM_MIC_STOPPED)
    is VoiceProblem.JudgeFailed -> finding(BothyFindingCode.VOICE_ERROR_JEV, problem.detail)
    is VoiceProblem.RecognitionFailed -> finding(BothyFindingCode.VOICE_PROBLEM_RECOGNITION_FAILED, problem.code, problem.detail)
    is VoiceProblem.ConnectionFailed ->
        finding(BothyFindingCode.VOICE_PROBLEM_CONNECTION_FAILED, problem.status?.toString().orEmpty(), problem.detail)
    is VoiceProblem.Other -> text(problem.detail)
}

internal fun hotwordProblemFinding(problem: HotwordProblem): Finding = when (problem) {
    HotwordProblem.Blank -> finding(BothyFindingCode.VOICE_HOTWORD_BLANK)
    is HotwordProblem.TooManyChars ->
        Finding(BothyFindingCode.VOICE_HOTWORD_TOO_MANY_CHARS, listOf(problem.word, problem.max.toString()), count = problem.max)
    is HotwordProblem.TooManyWords ->
        Finding(BothyFindingCode.VOICE_HOTWORD_TOO_MANY_WORDS, listOf(problem.word, problem.max.toString()), count = problem.max)
}

internal val ThresholdProblem.code: FindingKind
    get() = when (this) {
        ThresholdProblem.SCORE_RANGE -> BothyFindingCode.VOICE_THRESHOLD_SCORE_RANGE
        ThresholdProblem.EOS_FLOOR_ABOVE_EOS -> BothyFindingCode.VOICE_THRESHOLD_EOS_FLOOR
        ThresholdProblem.SIDE_ABOVE_AGENT -> BothyFindingCode.VOICE_THRESHOLD_SIDE_ABOVE_AGENT
        ThresholdProblem.DURATIONS -> BothyFindingCode.VOICE_THRESHOLD_DURATIONS
        ThresholdProblem.SILENCE_CAPS -> BothyFindingCode.VOICE_THRESHOLD_SILENCE_CAPS
        ThresholdProblem.LONGEST_TURN -> BothyFindingCode.VOICE_THRESHOLD_LONGEST_TURN
        ThresholdProblem.AGENT_TURN -> BothyFindingCode.VOICE_THRESHOLD_AGENT_TURN
        ThresholdProblem.UNSETTLED_WAIT -> BothyFindingCode.VOICE_THRESHOLD_UNSETTLED_WAIT
    }

/** The recognition model's name on the settings page. */
@StringRes
internal fun DashScopeAsrModel.displayLabel(): Int = when (this) {
    DashScopeAsrModel.PARAFORMER_REALTIME_V2 -> R.string.voice_asr_model_paraformer_v2
    DashScopeAsrModel.QWEN_AUDIO_3_0_FLASH -> R.string.voice_asr_model_qwen_audio_flash
}

/** Settings → 语音 → 播报's line: ready with what speaks, or what is missing. */
internal fun ttsStatus(context: Context, config: TtsConfig): Finding = when {
    config.ready -> Finding(BothyFindingCode.VOICE_READY_DETAIL, more = listOf(config.labelFinding(context)))
    config.provider == TtsProvider.DOUBAO && config.doubao.key.isBlank() -> finding(BothyFindingCode.VOICE_DOUBAO_KEY_MISSING_STATUS)
    config.provider == TtsProvider.MIMO && config.apiKey.isBlank() -> finding(BothyFindingCode.VOICE_MIMO_KEY_MISSING_STATUS)
    !config.enabled -> finding(BothyFindingCode.VOICE_OFF)
    else -> finding(BothyFindingCode.VOICE_CONFIG_INCOMPLETE)
}
