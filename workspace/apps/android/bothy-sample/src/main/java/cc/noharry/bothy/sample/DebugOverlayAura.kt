package cc.noharry.bothy.sample

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.nio.Buffer
import java.nio.IntBuffer
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * An aurora along the bottom of the card while voice is on or 小博 speaks (see drawAura). It bounces with
 * the loudness of whoever speaks and carries it across as crests: the user's mic flowing right, the reply at the
 * speaker's playback head flowing left, each as fast as that voice speaks (owner 2026-10-12). It all but disappears in
 * silence. Only the draw phase runs per frame.
 */
@Composable
internal fun VoiceAura(voiceOn: Boolean, modifier: Modifier) {
    val speaking by DebugTtsActivity.active.collectAsState()
    val active = voiceOn || speaking
    val presence by animateFloatAsState(if (active) 1f else 0f, tween(if (active) AURA_IN_MS else AURA_OUT_MS), label = "aura")
    if (presence == 0f) return
    val trace = remember { AuraTrace() }
    val mic = remember { MicLevelPlayhead() }
    val soft = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) null else AuraSoftLayer() }
    var seconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        var last = start
        while (true) {
            withFrameNanos { now ->
                trace.advance(auraHeardLevel(mic, now), auraSpokenLevel(), voiceAuraThinking(), (now - last) / 1e9f)
                seconds = (now - start) / 1e9f
                last = now
            }
        }
    }
    // Soft as light: blurred by the platform on Android 12+, else drawn small, blurred and stretched ([AuraSoftLayer]);
    // offscreen so the fade can mask the band.
    val layer = Modifier.graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
        if (soft == null) renderEffect = BlurEffect(AURA_BLUR_DP.dp.toPx(), AURA_BLUR_DP.dp.toPx(), TileMode.Decal)
    }
    Canvas(modifier.then(layer)) {
        // Read so each frame redraws; the trace itself is plain state advanced above.
        seconds.let { if (soft == null) drawAura(trace, presence) else soft.draw(this) { drawAura(trace, presence) } }
    }
}

/**
 * The blur where the platform has none (Android 11 and older: there the band turned almost a flat colour and
 * lost its haze). Only the card's bottom [AURA_SOFT_REACH_DP] holds the band, so only
 * that strip is drawn: small, a pixel [AURA_SOFT_TEXEL_DP] across, blurred as the platform would ([blurPremultiplied]),
 * then stretched over the strip with bilinear filtering. Stretching coarse pixels without the blur showed their grid,
 * flickering as the band moved (owner 2026-10-12: 「卡片下面光效像马赛克一样」). The bitmap is kept and redrawn in place
 * each frame; it is a few tens of thousands of pixels.
 */
internal class AuraSoftLayer {
    private var bitmap: ImageBitmap? = null
    private var canvas: androidx.compose.ui.graphics.Canvas? = null
    private var pixels = IntArray(0)
    private var scratch = IntArray(0)
    // Typed as Buffer so rewind() binds to Buffer.rewind(), which every Android version has.
    private var buffer: Buffer = IntBuffer.wrap(pixels)
    private val small = CanvasDrawScope()

    fun draw(scope: DrawScope, content: DrawScope.() -> Unit) {
        val texel = with(scope) { AURA_SOFT_TEXEL_DP.dp.toPx() }
        val strip = minOf(scope.size.height, with(scope) { AURA_SOFT_REACH_DP.dp.toPx() })
        val width = (scope.size.width / texel).roundToInt().coerceAtLeast(2)
        val height = (strip / texel).roundToInt().coerceAtLeast(2)
        val image = bitmap?.takeIf { it.width == width && it.height == height }
            ?: ImageBitmap(width, height).also { created ->
                bitmap = created
                canvas = androidx.compose.ui.graphics.Canvas(created)
                pixels = IntArray(width * height)
                scratch = IntArray(width * height)
                buffer = IntBuffer.wrap(pixels)
            }
        val frame = image.asAndroidBitmap()
        frame.eraseColor(android.graphics.Color.TRANSPARENT)
        val target = canvas ?: return
        // The band sits on the bottom edge, so drawn into the strip alone it lands where it would on the whole card.
        small.draw(Density(scope.density / texel, scope.fontScale), scope.layoutDirection, target, Size(width.toFloat(), height.toFloat()), content)
        // Raw pixels are premultiplied, which a blur needs: transparent texels then add no colour at the band's edge.
        frame.copyPixelsToBuffer(buffer.rewind())
        blurPremultiplied(pixels, scratch, width, height, AURA_SOFT_BLUR_TEXELS)
        frame.copyPixelsFromBuffer(buffer.rewind())
        val stripHeight = strip.roundToInt()
        scope.drawImage(
            image,
            dstOffset = IntOffset(0, scope.size.height.roundToInt() - stripHeight),
            dstSize = IntSize(scope.size.width.roundToInt(), stripHeight),
            filterQuality = FilterQuality.Low,
        )
    }
}

/**
 * Three box blurs each way over premultiplied pixels: close to a Gaussian of σ = √(r(r+1)) pixels for [radius] r.
 * Beyond the edges is transparent, like the platform blur's decal edge. Each byte is blurred on its own, so the
 * channel order does not matter, and rounding the same way for every channel keeps each colour within its alpha.
 */
internal fun blurPremultiplied(pixels: IntArray, scratch: IntArray, width: Int, height: Int, radius: Int) {
    repeat(BOX_BLUR_PASSES) {
        boxBlurLines(pixels, scratch, lines = height, length = width, step = 1, lineStep = width, radius = radius)
        boxBlurLines(scratch, pixels, lines = width, length = height, step = width, lineStep = 1, radius = radius)
    }
}

/** One box blur along each line of [from] into [to], with a running sum per channel. */
private fun boxBlurLines(from: IntArray, to: IntArray, lines: Int, length: Int, step: Int, lineStep: Int, radius: Int) {
    val window = 2 * radius + 1
    val half = window / 2
    for (line in 0 until lines) {
        val start = line * lineStep
        var c0 = 0
        var c1 = 0
        var c2 = 0
        var c3 = 0
        for (i in 0..minOf(radius, length - 1)) {
            val pixel = from[start + i * step]
            c0 += pixel and 0xFF
            c1 += (pixel ushr 8) and 0xFF
            c2 += (pixel ushr 16) and 0xFF
            c3 += pixel ushr 24
        }
        for (i in 0 until length) {
            to[start + i * step] = ((c3 + half) / window shl 24) or ((c2 + half) / window shl 16) or
                ((c1 + half) / window shl 8) or ((c0 + half) / window)
            val entering = i + radius + 1
            if (entering < length) {
                val pixel = from[start + entering * step]
                c0 += pixel and 0xFF
                c1 += (pixel ushr 8) and 0xFF
                c2 += (pixel ushr 16) and 0xFF
                c3 += pixel ushr 24
            }
            val leaving = i - radius
            if (leaving >= 0) {
                val pixel = from[start + leaving * step]
                c0 -= pixel and 0xFF
                c1 -= (pixel ushr 8) and 0xFF
                c2 -= (pixel ushr 16) and 0xFF
                c3 -= pixel ushr 24
            }
        }
    }
}

/**
 * How fast a voice speaks, from its loudness alone: each syllable is a swell of the level, so a rise of
 * [AURA_ONSET_RISE] above the last trough counts one, and the next counts only once the level has fallen as much
 * below its peak. The rate is the count over the last [AURA_RATE_WINDOW_SECONDS].
 */
internal class AuraSyllables {
    private val onsets = ArrayDeque<Float>()
    private var trough = 0f
    private var peak = 0f
    private var waitingForRise = true
    var perSecond = 0f
        private set

    fun feed(level: Float, time: Float) {
        if (waitingForRise) {
            trough = minOf(trough, level)
            if (level - trough >= AURA_ONSET_RISE) {
                onsets.addLast(time)
                waitingForRise = false
                peak = level
            }
        } else {
            peak = maxOf(peak, level)
            if (peak - level >= AURA_ONSET_RISE) {
                waitingForRise = true
                trough = level
            }
        }
        while (onsets.isNotEmpty() && time - onsets.first() > AURA_RATE_WINDOW_SECONDS) onsets.removeFirst()
        perSecond = onsets.size / AURA_RATE_WINDOW_SECONDS
    }
}

/**
 * One voice's loudness for the aura, like a level meter's trace: eased toward each new reading and sampled as the
 * lane moves, newest first. It moves [speed] samples a second: a slow drift in silence, quicker the faster the voice
 * speaks (owner 2026-10-12: 「移动速度和语速关联」). [fraction] is how far it has moved since the newest sample.
 */
internal class AuraLane(val size: Int) {
    private val values = FloatArray(size)
    private var newest = 0
    private var time = 0f
    val syllables = AuraSyllables()
    /** The eased level now, where the lane enters the card. */
    var level = 0f
        private set
    var fraction = 0f
        private set
    var speed = AURA_IDLE_SPEED
        private set

    fun advance(target: Float, dt: Float) {
        val step = dt.coerceIn(0f, 0.25f)
        time += step
        level = auraFollow(level, target, dt)
        syllables.feed(target, time)
        val wanted = AURA_IDLE_SPEED + syllables.perSecond * AURA_SAMPLES_PER_SYLLABLE
        speed += (wanted - speed) * (1f - exp(-step / AURA_SPEED_SECONDS))
        fraction += step * speed
        while (fraction >= 1f) {
            fraction -= 1f
            newest = (newest + 1) % size
            values[newest] = level
        }
    }

    /** The sample [age] steps back; 0 is the newest. */
    operator fun get(age: Int): Float = values[(newest - age).mod(size)]

    /** The trace [position] samples in from where the lane enters, between samples; the level now at 0. */
    fun at(position: Float): Float {
        if (position <= fraction) return if (fraction <= 0f) level else level + (this[0] - level) * (position.coerceAtLeast(0f) / fraction)
        val back = position - fraction
        val older = back.toInt()
        if (older >= size - 1) return this[size - 1]
        return this[older] + (this[older + 1] - this[older]) * (back - older)
    }
}

/**
 * The aura's two lanes (owner 2026-10-12: 「人说话的…移动方向是向右，但是 tts 播报的光效要向左」): the user's voice
 * enters at the left edge and flows right, 小博's reply enters at the right and flows left, so after a change of
 * speaker the last words still finish their way across. The swell waves and colours follow whoever spoke last,
 * turning round over [AURA_TURN_SECONDS], at the pace of the faster lane.
 */
internal class AuraTrace(val size: Int = AURA_SAMPLES) {
    val heard = AuraLane(size)
    val spoken = AuraLane(size)
    /** 0..1, eased: how far the band has gone into its thinking look (lower, swelling faster, a light sweeping). */
    var thinking = 0f
        private set
    /** +1 flowing right (the user), -1 flowing left (小博), eased between. */
    var direction = -1f
        private set
    private var heading = -1f
    /** How far the swell and colours have travelled (signed by direction), and the thinking sweep's phase. */
    var flowPhase = 0f
        private set
    var sweepPhase = 0f
        private set

    /** The louder voice now, eased: the band lifts with it, so it bounces with each syllable. */
    val loudness: Float get() = maxOf(heard.level, spoken.level)

    fun advance(heardNow: Float, spokenNow: Float, thinkingNow: Boolean, dt: Float) {
        val step = dt.coerceIn(0f, 0.25f)
        heard.advance(heardNow, dt)
        spoken.advance(spokenNow, dt)
        if (heardNow > spokenNow + AURA_LEAD) heading = 1f else if (spokenNow > heardNow + AURA_LEAD) heading = -1f
        direction += (heading - direction) * (1f - exp(-step / AURA_TURN_SECONDS))
        thinking += ((if (thinkingNow) 1f else 0f) - thinking) * (1f - exp(-step / AURA_MODE_SECONDS))
        val voicePace = AURA_PACE_FLOOR + (1f - AURA_PACE_FLOOR) * maxOf(heard.speed, spoken.speed) / AURA_SPEAKING_SPEED
        flowPhase += step * direction * (voicePace + (AURA_THINK_SWELL_PACE - voicePace) * thinking)
        sweepPhase += step * AURA_SWEEP_SPEED
    }
}

/**
 * The user's loudness at 50 a second: the session publishes the mic's levels five at a time, every 100 ms, so
 * the newest five are played back one per 20 ms, a batch late, the way the reply's levels follow its playback head.
 */
internal class MicLevelPlayhead {
    private var seen: List<Float>? = null
    private var batch: List<Float> = emptyList()
    private var arrivedAt = 0L

    fun level(levels: List<Float>, nowNanos: Long): Float {
        if (levels !== seen) {
            seen = levels
            batch = levels.takeLast(MIC_LEVELS_PER_FRAME)
            arrivedAt = nowNanos
        }
        if (batch.isEmpty()) return 0f
        return batch[((nowNanos - arrivedAt) / MIC_LEVEL_NANOS).toInt().coerceIn(0, batch.size - 1)]
    }
}

/** 0..1: the reply's loudness at the speaker while it streams. */
internal fun auraSpokenLevel(): Float = DebugTtsLevels.current()?.let(::auraCurve) ?: 0f

/** 0..1: the user's loudness while a session listens and 小博 is quiet. */
internal fun auraHeardLevel(mic: MicLevelPlayhead, nowNanos: Long): Float {
    if (DebugTtsActivity.active.value || !DebugVoiceController.listening) return 0f
    return auraCurve(mic.level(DebugVoiceController.state.value.micLevels, nowNanos))
}

/**
 * One frame of the level easing toward [target] over [dt] seconds, by time rather than by frame so it looks the same
 * at any refresh rate: up within a fraction of a syllable and down within one, so the band rises and dips with each
 * syllable (owner 2026-10-12: 「随着响度变化上下动的动态感要有」).
 */
internal fun auraFollow(level: Float, target: Float, dt: Float): Float {
    val tau = if (target > level) AURA_RISE_SECONDS else AURA_FALL_SECONDS
    return level + (target - level) * (1f - exp(-dt.coerceIn(0f, 0.25f) / tau))
}

/**
 * Thinking: the agent works (or a reply is being synthesized) and nobody is heard, neither 小博 nor the user;
 * the band then lowers and its light moves side to side, quicker.
 */
internal fun voiceAuraThinking(): Boolean {
    if (DebugTtsLevels.current() != null) return false
    val state = DebugVoiceController.state.value
    return (state.agentWorking || DebugTtsActivity.active.value) && state.liveText.isBlank()
}

/** Room noise and breath sit below the floor; speech fills the rest. */
internal fun auraCurve(level: Float): Float = ((level - AURA_FLOOR) / (AURA_CEILING - AURA_FLOOR)).coerceIn(0f, 1f)

/**
 * The band: a glow of its own height along the bottom that lifts with the loudness now, most where the voice
 * enters, with the loudness lanes riding on it as crests that travel across (the user's to the right, 小博's to
 * the left; where both are, the higher shows). Filled under a smooth curve in the flowing colours, bright at the
 * bottom and fading upward.
 */
private fun DrawScope.drawAura(trace: AuraTrace, presence: Float) {
    // Thinking lowers the band; a broad light then sweeps side to side across it.
    val idle = AURA_IDLE_DP.dp.toPx() * (1f - (1f - AURA_THINK_HEIGHT) * trace.thinking)
    val rise = AURA_RISE_DP.dp.toPx()
    val lift = AURA_LIFT_DP.dp.toPx() * trace.loudness
    val spacing = size.width / (trace.size - 2)
    val sweepAt = 0.5f + AURA_SWEEP_REACH * sin(trace.sweepPhase)
    // The top edge swells and settles on two waves travelling the way the voice flows, so the band moves even in silence.
    fun top(x: Float, value: Float): Float {
        val u = x / size.width
        val phase = trace.flowPhase
        val swell = 0.6f * sin(u * AURA_SWELL_WAVES_A - phase * AURA_SWELL_SPEED_A) + 0.4f * sin(u * AURA_SWELL_WAVES_B - phase * AURA_SWELL_SPEED_B)
        val sweep = trace.thinking * AURA_SWEEP_LIFT * exp(-((u - sweepAt) / AURA_SWEEP_WIDTH).let { it * it })
        // Full lift where the voice enters, half at the far side.
        val lean = 0.75f - 0.25f * trace.direction * (2f * u - 1f)
        return size.height - idle * (1f + AURA_SWELL * swell + sweep) - rise * value - lift * lean
    }
    val points = List(AURA_POINTS + 1) { i ->
        val x = size.width * i / AURA_POINTS
        val value = maxOf(trace.heard.at(x / spacing), trace.spoken.at((size.width - x) / spacing))
        Offset(x, top(x, value))
    }
    val band = Path().apply {
        moveTo(0f, size.height)
        lineTo(points[0].x, points[0].y)
        // Through the midpoints, with each point as the control point: a smooth crest, no corners.
        for (i in 1 until points.size) {
            val mid = (points[i - 1] + points[i]) / 2f
            quadraticTo(points[i - 1].x, points[i - 1].y, mid.x, mid.y)
        }
        lineTo(points.last().x, points.last().y)
        lineTo(points.last().x, size.height)
        close()
    }
    // Rising hue moves the colours left; they drift the way the voice flows.
    val hue = -trace.flowPhase * AURA_FLOW_PER_SECOND
    drawPath(band, Brush.horizontalGradient(List(AURA_HUE_STOPS) { auraColor(hue + it * AURA_SPAN / (AURA_HUE_STOPS - 1)) }), alpha = presence * AURA_BAND_ALPHA)
    // Fades upward: full at the bottom edge, about a third at the loudest crest.
    drawRect(
        Brush.verticalGradient(
            listOf(Color.Black.copy(alpha = AURA_TOP_ALPHA), Color.Black),
            startY = size.height - AURA_IDLE_DP.dp.toPx() * (1f + AURA_SWELL) - rise - AURA_LIFT_DP.dp.toPx(),
            endY = size.height,
        ),
        blendMode = BlendMode.DstIn,
    )
}

/** The palette at [position], cycling: each whole number is one round of sage, glow, ember and mist. */
internal fun auraColor(position: Float): Color {
    val cycle = position - kotlin.math.floor(position)
    val scaled = cycle * AURA_PALETTE.size
    val from = scaled.toInt() % AURA_PALETTE.size
    return lerp(AURA_PALETTE[from], AURA_PALETTE[(from + 1) % AURA_PALETTE.size], scaled - scaled.toInt())
}

/** Bothy's dusk colours (the felt, the hut window, the fire, the mist), in the order the band flows through them. */
private val AURA_PALETTE = listOf(Color(0xFF8CC7A5), Color(0xFFF5C26B), Color(0xFFF2925A), Color(0xFF7FB3C9))
/** Samples across the card. */
private const val AURA_SAMPLES = 38
/**
 * How fast a lane moves, in samples a second: [AURA_IDLE_SPEED] in silence (about 12 s across), plus this much for
 * each syllable a second; ordinary speech, about 4 a second, crosses in about 4 s ([AURA_SPEAKING_SPEED]).
 */
private const val AURA_IDLE_SPEED = 3f
private const val AURA_SAMPLES_PER_SYLLABLE = 1.6f
private const val AURA_SPEAKING_SPEED = AURA_IDLE_SPEED + 4 * AURA_SAMPLES_PER_SYLLABLE
/** How quickly a lane's speed follows the speech rate, in seconds. */
private const val AURA_SPEED_SECONDS = 0.4f
/** A syllable: a swell of this much on the aura's 0..1 scale (about 4.5 dB); the rate counts the last 1.5 s. */
private const val AURA_ONSET_RISE = 0.15f
private const val AURA_RATE_WINDOW_SECONDS = 1.5f
/** The swell and colours' pace in silence, as a share of their pace in ordinary speech. */
private const val AURA_PACE_FLOOR = 0.5f
/** How much of a colour round the width spans, and how fast the colours flow (a round about every 12 s). */
private const val AURA_SPAN = 0.35f
private const val AURA_FLOW_PER_SECOND = 0.085f
private const val AURA_HUE_STOPS = 5
/** Points across the width the band's edge is drawn through: twice the samples, so a passing crest keeps its height. */
private const val AURA_POINTS = AURA_SAMPLES * 2
/**
 * The band's own height, there even in silence; how much higher a loud stretch's crest flows; and how far the whole
 * band lifts with the loudness now (owner 2026-10-12: 振幅大点, 随着响度变化上下动).
 */
private const val AURA_IDLE_DP = 30
private const val AURA_RISE_DP = 32
private const val AURA_LIFT_DP = 16
/** The silent swell: a share of the band's height, on two waves across the width (radians) drifting at these speeds. */
private const val AURA_SWELL = 0.5f
private const val AURA_SWELL_WAVES_A = 7.5f
private const val AURA_SWELL_SPEED_A = 0.9f
private const val AURA_SWELL_WAVES_B = 13f
private const val AURA_SWELL_SPEED_B = 0.6f
/**
 * Thinking: the band at this share of its height, its swell this many times quicker, a light sweeping side to side
 * (owner 2026-10-12: 思考中执行中的光效要再低再快点).
 */
private const val AURA_THINK_HEIGHT = 0.35f
private const val AURA_THINK_SWELL_PACE = 4.5f
private const val AURA_SWEEP_SPEED = 5.5f
private const val AURA_SWEEP_REACH = 0.42f
private const val AURA_SWEEP_WIDTH = 0.16f
private const val AURA_SWEEP_LIFT = 0.9f
/** How long the band takes to settle into or out of its thinking look, and to turn round for the other speaker. */
private const val AURA_MODE_SECONDS = 0.5f
private const val AURA_TURN_SECONDS = 0.6f
/** How much louder one voice must be than the other to set the flow its way. */
private const val AURA_LEAD = 0.05f
private const val AURA_BAND_ALPHA = 0.85f
private const val AURA_TOP_ALPHA = 0.35f
private const val AURA_BLUR_DP = 10
/**
 * The soft layer's pixel, and its blur radius in those pixels: σ = √(3·4) ≈ 3.5 pixels, 7 dp, near the platform's
 * σ for [AURA_BLUR_DP] (0.577 × 10 + 0.5 ≈ 6.3 dp). Stretched only 2×, the blurred pixels leave no grid.
 */
private const val AURA_SOFT_TEXEL_DP = 2
private const val AURA_SOFT_BLUR_TEXELS = 3
private const val BOX_BLUR_PASSES = 3
/**
 * The band's greatest height (its swell, the thinking sweep, a loud crest and the full lift, an upper bound), and the
 * strip the soft layer draws: that and room for the blur above it.
 */
private const val AURA_REACH_DP = AURA_IDLE_DP * (1f + AURA_SWELL + AURA_SWEEP_LIFT) + AURA_RISE_DP + AURA_LIFT_DP
private const val AURA_SOFT_REACH_DP = AURA_REACH_DP + 3 * AURA_BLUR_DP
private const val AURA_FLOOR = 0.25f
private const val AURA_CEILING = 0.75f
/** Time constants of [auraFollow], in seconds. */
private const val AURA_RISE_SECONDS = 0.06f
private const val AURA_FALL_SECONDS = 0.18f
private const val AURA_IN_MS = 260
private const val AURA_OUT_MS = 700
/** The session's levels: five per 100 ms frame, 20 ms each. */
private const val MIC_LEVELS_PER_FRAME = 5
private const val MIC_LEVEL_NANOS = 20_000_000L
