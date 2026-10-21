package cc.noharry.bothy.sample

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh

/**
 * One soft note: [frequency] Hz from [start] s, dying away by [decayPerSecond]; it rises into tune from
 * [frequency]×[from], or from where its [DebugSoftTimbre] says when null.
 */
internal data class DebugSoftNote(
    val frequency: Double,
    val start: Double,
    val decayPerSecond: Double,
    val from: Double? = null,
)

/**
 * How the notes sound: how much of the note itself ([body]) and of the [octave], [twelfth] and [fifteenth] (2×, 3×
 * and 4× the note) over it. The device's voice speaker hardly plays below about 1 kHz: a low note there is heard through
 * its harmonics (the ear fills in the missing fundamental), and pushing the note itself only distorts. A second sine
 * [detune] times the note beside it (1 for none; 1.004 drifts about 1.3 Hz against an E4, which can sound like a
 * warble), where the notes glide [from] (1 for none), the rounded [attackSeconds], how much of the small [room], a
 * tanh [drive] (0 for none; it made the chime sound distorted in the device), and a low-pass at [lowpassHz] of [poles]
 * one-pole stages (two at about 1 kHz give the muffled 「闷」 sound).
 */
internal data class DebugSoftTimbre(
    val body: Double = 1.0,
    val octave: Double = 0.2,
    val twelfth: Double = 0.0,
    val fifteenth: Double = 0.0,
    val detune: Double = 1.0,
    val from: Double = 1.0,
    val attackSeconds: Double = 0.012,
    val room: Double = 0.2,
    val drive: Double = 0.0,
    val lowpassHz: Double = 3_000.0,
    val poles: Int = 1,
)

/**
 * A soft electronic chime for the plans (owner 2026-10-15: 「提示音…更像特斯拉那种点」, then 「太响亮了，声音听着太尖了，
 * 科技感未来感没有」, 「声音太小了听不见」, and of the four tried 「Lower and warmer 这种音色有点像，但是还是觉得声音好
 * 小」, 「这个声音听着有点失真」, 「都太亮了，我希望有点闷的那种音」, 「我希望整体声音再大一点」): notes as [timbre] says, a
 * small dark room behind them if it says so, a low-pass. The peak stays at [peak] of full scale.
 */
internal fun softChimeSamples(
    notes: List<DebugSoftNote>,
    timbre: DebugSoftTimbre = PLAN_CHIME_TIMBRE,
    sampleRate: Int = CHIME_SAMPLE_RATE,
    peak: Double = PLAN_CHIME_PEAK,
): ShortArray {
    val dryEnd = notes.maxOf { it.start + SOFT_SILENT_LOG / it.decayPerSecond }
    val dry = DoubleArray(((dryEnd + SOFT_ROOM_TAIL_SECONDS) * sampleRate).toInt())
    notes.forEach { addSoftNote(dry, it, timbre, sampleRate) }
    val room = if (timbre.room > 0) roomOf(dry, sampleRate) else DoubleArray(dry.size)
    val smoothing = 1 - exp(-2 * PI * timbre.lowpassHz / sampleRate)
    val fade = (SOFT_END_FADE_SECONDS * sampleRate).toInt()
    val low = DoubleArray(timbre.poles)
    val mixed = DoubleArray(dry.size) { i ->
        var x = dry[i] + timbre.room * room[i]
        for (pole in low.indices) {
            low[pole] += smoothing * (x - low[pole])
            x = low[pole]
        }
        x * min(1.0, (dry.size - i).toDouble() / fade)
    }
    val loudest = mixed.maxOf { abs(it) }.coerceAtLeast(1e-9)
    val shape: (Double) -> Double = if (timbre.drive > 0) { x -> tanh(timbre.drive * x) / tanh(timbre.drive) } else { x -> x }
    return ShortArray(mixed.size) { (shape(mixed[it] / loudest) * peak * Short.MAX_VALUE).toInt().toShort() }
}

private fun addSoftNote(out: DoubleArray, note: DebugSoftNote, timbre: DebugSoftTimbre, sampleRate: Int) {
    val first = (note.start * sampleRate).toInt()
    val length = SOFT_SILENT_LOG / note.decayPerSecond
    val from = note.from ?: timbre.from
    val weight = (timbre.body + timbre.octave + timbre.twelfth + timbre.fifteenth).coerceAtLeast(1e-9)
    var phase = 0.0
    var beside = 0.0
    for (i in 0 until minOf((length * sampleRate).toInt(), out.size - first)) {
        val t = i.toDouble() / sampleRate
        val frequency = note.frequency * (1 - (1 - from) * exp(-t / SOFT_GLIDE_SECONDS))
        phase += 2 * PI * frequency / sampleRate
        beside += 2 * PI * frequency * timbre.detune / sampleRate
        val attack = if (t < timbre.attackSeconds) (1 - cos(PI * t / timbre.attackSeconds)) / 2 else 1.0
        val envelope = attack * exp(-t * note.decayPerSecond) * min(1.0, (length - t) / SOFT_NOTE_TAIL_SECONDS)
        val tone = timbre.body * (sin(phase) + sin(beside)) / 2 + timbre.octave * sin(2 * phase) +
            timbre.twelfth * sin(3 * phase) + timbre.fifteenth * sin(4 * phase)
        out[first + i] += tone * envelope / weight
    }
}

/** A small room: four damped combs side by side, then two all-passes to smear them (Schroeder). */
private fun roomOf(dry: DoubleArray, sampleRate: Int): DoubleArray {
    val wet = DoubleArray(dry.size)
    SOFT_COMB_SECONDS.forEach { seconds ->
        val line = DoubleArray((seconds * sampleRate).toInt())
        var index = 0
        var damped = 0.0
        dry.forEachIndexed { i, sample ->
            val delayed = line[index]
            damped = delayed * (1 - SOFT_DAMPING) + damped * SOFT_DAMPING
            line[index] = sample + damped * SOFT_FEEDBACK
            index = (index + 1) % line.size
            wet[i] += delayed / SOFT_COMB_SECONDS.size
        }
    }
    SOFT_ALLPASS_SECONDS.forEach { seconds -> allpass(wet, (seconds * sampleRate).toInt()) }
    return wet
}

private fun allpass(signal: DoubleArray, delay: Int) {
    val line = DoubleArray(delay)
    var index = 0
    signal.indices.forEach { i ->
        val delayed = line[index]
        val input = signal[i]
        line[index] = input + delayed * SOFT_ALLPASS_GAIN
        signal[i] = delayed - input * SOFT_ALLPASS_GAIN
        index = (index + 1) % delay
    }
}

/**
 * Notes as adb writes them for the plan chime's try-out ([DebugPlanNoticeReceiver]): 「329.63,0,8;493.88,0.13,5」,
 * each frequency Hz, start s, decay per s and optionally where it glides from (0.944 is a semitone below).
 */
internal fun softNotesOf(text: String): List<DebugSoftNote> {
    val notes = text.split(';').map(String::trim).filter(String::isNotEmpty).map { note ->
        val parts = note.split(',').map { it.trim().toDouble() }
        require(parts.size in 3..4) { "each note is frequency,start,decay[,from]" }
        DebugSoftNote(parts[0], parts[1], parts[2], parts.getOrNull(3)).also {
            require(it.frequency in 80.0..4_000.0 && it.start in 0.0..2.0 && it.decayPerSecond in 1.0..60.0) { "out of range: $note" }
            require(it.from == null || it.from in 0.5..1.5) { "out of range: $note" }
        }
    }
    require(notes.size in 1..MAX_TRIED_NOTES) { "1 to $MAX_TRIED_NOTES notes" }
    return notes
}

/**
 * A timbre as adb writes it: 「body=0.5,octave=1,twelfth=0.7,fifteenth=0.4,detune=1,from=1,attack=0.008,room=0,drive=0,
 * lowpass=1800,poles=2」; what is not named stays as in [base].
 */
internal fun softTimbreOf(text: String, base: DebugSoftTimbre = PLAN_CHIME_TIMBRE): DebugSoftTimbre =
    text.split(',').map(String::trim).filter(String::isNotEmpty).fold(base) { timbre, pair ->
        val (key, raw) = pair.split('=', limit = 2).map(String::trim).also { require(it.size == 2) { "write name=value: $pair" } }
        val value = raw.toDouble()
        when (key) {
            "body" -> timbre.copy(body = value.inRange(0.0..1.0, key))
            "octave" -> timbre.copy(octave = value.inRange(0.0..1.0, key))
            "fifteenth" -> timbre.copy(fifteenth = value.inRange(0.0..1.0, key))
            "twelfth" -> timbre.copy(twelfth = value.inRange(0.0..1.0, key))
            "detune" -> timbre.copy(detune = value.inRange(0.98..1.02, key))
            "from" -> timbre.copy(from = value.inRange(0.5..1.5, key))
            "attack" -> timbre.copy(attackSeconds = value.inRange(0.001..0.1, key))
            "room" -> timbre.copy(room = value.inRange(0.0..1.0, key))
            "drive" -> timbre.copy(drive = value.inRange(0.0..4.0, key))
            "lowpass" -> timbre.copy(lowpassHz = value.inRange(200.0..8_000.0, key))
            "poles" -> timbre.copy(poles = value.inRange(1.0..4.0, key).toInt())
            else -> throw IllegalArgumentException("unknown timbre setting $key")
        }
    }

private fun Double.inRange(range: ClosedFloatingPointRange<Double>, name: String): Double =
    also { require(it in range) { "$name out of range" } }

/**
 * G5 then C6, a fourth up, quick, the second ringing longer: the 「嘟-叮」 of a device turning a driving aid on, picked in
 * the device from five tries (owner 2026-10-16: 「5这个吧」).
 */
internal val PLAN_CHIME_NOTES = listOf(DebugSoftNote(783.99, 0.0, 14.0), DebugSoftNote(1_046.5, 0.09, 7.0))
/** Pure sines, a 12 ms rounded attack, no room, and two low-pass stages at 1.5 kHz: muffled, not bright. */
internal val PLAN_CHIME_TIMBRE = DebugSoftTimbre(octave = 0.0, room = 0.0, lowpassHz = 1_500.0, poles = 2)
/**
 * About 2.4 dB over the wake chime as the device's voice speaker plays them (A-weighted, through a 900 Hz second-order
 * high-pass, loudest 300 ms), below the 0.6 to 0.9 that distorted there.
 */
internal const val PLAN_CHIME_PEAK = 0.51

/** A note is cut where it has fallen 50 dB: ln 316. */
private const val SOFT_SILENT_LOG = 5.756
private const val SOFT_NOTE_TAIL_SECONDS = 0.03
private const val SOFT_GLIDE_SECONDS = 0.03
private val SOFT_COMB_SECONDS = listOf(0.0297, 0.0371, 0.0411, 0.0437)
private val SOFT_ALLPASS_SECONDS = listOf(0.005, 0.0017)
private const val SOFT_FEEDBACK = 0.72
private const val SOFT_DAMPING = 0.4
private const val SOFT_ALLPASS_GAIN = 0.7
private const val SOFT_ROOM_TAIL_SECONDS = 0.45
private const val SOFT_END_FADE_SECONDS = 0.08
private const val MAX_TRIED_NOTES = 6
