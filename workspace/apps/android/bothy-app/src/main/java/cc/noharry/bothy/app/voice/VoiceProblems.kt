package cc.noharry.bothy.app.voice
import android.content.Context
import androidx.annotation.StringRes
import cc.noharry.bothy.app.R
import cc.noharry.bothy.voice.DashScopeAsrModel
import cc.noharry.bothy.voice.HotwordProblem
import cc.noharry.bothy.voice.ModelProblem
import cc.noharry.bothy.voice.SherpaOnnxAsrEngine
import cc.noharry.bothy.voice.ThresholdProblem
import cc.noharry.bothy.voice.VoiceProblem
import cc.noharry.bothy.voice.WakeWordListener

/*
 * What bothy-voice reports as data, worded for the screen in the app's language: the library carries no screen text.
 */

/** Why on-device recognition cannot run on this device, or null when it can (Settings → 语音, 检查). */
internal fun localAsrUnavailable(context: Context): String? = SherpaOnnxAsrEngine.availabilityProblem(context.assets)
    ?.let { describe(context, it, R.string.voice_local_model_missing) }

/** Why the wake word cannot be listened for on this device, or null when it can. */
internal fun wakeModelUnavailable(context: Context): String? = WakeWordListener.availabilityProblem(context.assets)
    ?.let { describe(context, it, R.string.voice_wake_model_missing) }

private fun describe(context: Context, problem: ModelProblem, @StringRes missing: Int): String = when (problem) {
    is ModelProblem.Missing -> context.getString(missing, problem.modelDir, problem.files.joinToString())
    is ModelProblem.NativeLibrary -> context.getString(R.string.voice_native_library_failed, problem.detail)
}

/** A session's [problem] as the voice strip and the overlay show it. */
internal fun describeVoiceProblem(context: Context, problem: VoiceProblem): String = when (problem) {
    VoiceProblem.MicrophoneStopped -> context.getString(R.string.voice_problem_mic_stopped)
    is VoiceProblem.JudgeFailed -> context.getString(R.string.voice_error_jev, problem.detail)
    is VoiceProblem.RecognitionFailed ->
        context.getString(R.string.voice_problem_recognition_failed, problem.code, problem.detail)
    is VoiceProblem.ConnectionFailed -> context.getString(
        R.string.voice_problem_connection_failed, problem.status?.toString().orEmpty(), problem.detail,
    ).trim()
    is VoiceProblem.Other -> problem.detail
}

internal fun describeHotwordProblem(context: Context, problem: HotwordProblem): String = when (problem) {
    HotwordProblem.Blank -> context.getString(R.string.voice_hotword_blank)
    is HotwordProblem.TooManyChars -> context.resources
        .getQuantityString(R.plurals.voice_hotword_too_many_chars, problem.max, problem.word, problem.max)
    is HotwordProblem.TooManyWords -> context.resources
        .getQuantityString(R.plurals.voice_hotword_too_many_words, problem.max, problem.word, problem.max)
}

@StringRes
internal fun ThresholdProblem.message(): Int = when (this) {
    ThresholdProblem.SCORE_RANGE -> R.string.voice_threshold_score_range
    ThresholdProblem.EOS_FLOOR_ABOVE_EOS -> R.string.voice_threshold_eos_floor
    ThresholdProblem.SIDE_ABOVE_AGENT -> R.string.voice_threshold_side_above_agent
    ThresholdProblem.DURATIONS -> R.string.voice_threshold_durations
    ThresholdProblem.SILENCE_CAPS -> R.string.voice_threshold_silence_caps
    ThresholdProblem.LONGEST_TURN -> R.string.voice_threshold_longest_turn
    ThresholdProblem.AGENT_TURN -> R.string.voice_threshold_agent_turn
    ThresholdProblem.UNSETTLED_WAIT -> R.string.voice_threshold_unsettled_wait
}

/** The recognition model's name on the settings page. */
@StringRes
internal fun DashScopeAsrModel.displayLabel(): Int = when (this) {
    DashScopeAsrModel.PARAFORMER_REALTIME_V2 -> R.string.voice_asr_model_paraformer_v2
    DashScopeAsrModel.QWEN_AUDIO_3_0_FLASH -> R.string.voice_asr_model_qwen_audio_flash
}

/** The recognizer [config] picks, as the voice strip names it. */
internal fun engineLabel(context: Context, config: VoiceConfig): String = when (config.engine) {
    VoiceEngine.LOCAL -> context.getString(R.string.voice_engine_label_local)
    VoiceEngine.DASHSCOPE ->
        context.getString(R.string.voice_engine_label_dashscope, DashScopeAsrModel.of(config.dashscopeModel).id)
}
