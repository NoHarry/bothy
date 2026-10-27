package cc.noharry.bothy.app.overlay
import android.content.Context
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.R
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What 小博 is doing. 小博 keeps one colour and one body whatever it does; each act is a different way of moving
 * plus, for some, a prop beside it (a magnifier, a pencil, a gear…), the way the Grok Bot persona shows its state
 * through motion rather than colour. The body never spins or swings around a point.
 */
internal enum class MascotAction {
    /** Nothing under way: still between little fidgets (a blink, a glance, a jiggle, a hum, a yawn, a hop). */
    IDLE,

    /** No tool yet: eyes narrowed and wandering up, a thought bubble that now and then turns into a light bulb. */
    THINKING,

    /** A search or lookup is running: eyes and a magnifier sweep side to side, a quick bob. */
    SEARCHING,

    /** Another tool is running: focused lids, eyes on the work, a gear turning beside it. */
    WORKING,

    /** The image tool is drawing: eyes on the picture below, a pencil scribbling. */
    DRAWING,

    /** Waiting for the user's answer: eyes wide and on them, a question mark wobbling. */
    ASKING,

    /** Picks are offered: eyes go down between the tiles and look up for the choice. */
    PICKING,

    /** Finished: smiling eyes, two hops and a burst of sparkles, then quiet fidgets. */
    DONE,

    /** Failed: worried eyes looking down, a shake, a sweat drop, then slow sighs. */
    FAILED,

    /** Stopped: eyes nearly shut, settled low, a few Z's floating up now and then. */
    STOPPED,

    /** Held by the user's finger (the bubble only): stretched, eyes wide, swaying. */
    HELD,

    /** Travelling between the bubble's spot and the card: leaning and looking where it goes. */
    FLYING,

    /** Signing off before the overlay closes: eyes shut, Z's, and it settles down. */
    SLEEPING,

    /** Operating the screen (Android Use): a pointing hand up-left, eyes on the fingertip, a light bob. */
    POINTING,
}

/** One-off moves layered over the running act on their own channels, so they never interrupt it. */
internal enum class MascotReaction {
    /** A tap on 小博: squinting eyes, a bounce and a heart. */
    GIGGLE,

    /** Something arrived: eyes pop, a jump and an exclamation mark. */
    NOTICE,

    /** Put down after a drag or a flight: a squash that springs back. */
    LAND,

    /** Turns all the way round once: the face slides off one side and comes back from the other. */
    TURN,

    /** A double nod. */
    NOD,
}

/**
 * The props, cut from one generated sticker sheet (flat vector style on a chroma key; see the overlay trace).
 * [anchorX]/[anchorY] place a held prop's centre against the body, in body radii; [size] is in body radii too.
 */
internal enum class MascotProp(@DrawableRes val res: Int, val anchorX: Float, val anchorY: Float, val size: Float) {
    MAGNIFIER(R.drawable.mascot_prop_magnifier, 0.95f, 0.35f, 0.85f),
    PENCIL(R.drawable.mascot_prop_pencil, 0.95f, 0.5f, 0.85f),
    GEAR(R.drawable.mascot_prop_gear, 0.9f, -0.85f, 0.62f),
    THOUGHT(R.drawable.mascot_prop_thought, 1f, -0.95f, 0.85f),
    BULB(R.drawable.mascot_prop_bulb, 0.95f, -0.95f, 0.72f),
    QUESTION(R.drawable.mascot_prop_question, 0.95f, -0.95f, 0.62f),
    SPARKLE(R.drawable.mascot_prop_sparkle, 0f, 0f, 0.55f),
    EXCLAIM(R.drawable.mascot_prop_exclaim, 0.9f, -1f, 0.6f),
    DROP(R.drawable.mascot_prop_drop, -0.78f, -0.5f, 0.42f),
    Z(R.drawable.mascot_prop_z, 0.75f, -0.75f, 0.45f),
    NOTE(R.drawable.mascot_prop_note, 0.85f, -0.4f, 0.5f),
    HEART(R.drawable.mascot_prop_heart, 0.8f, -0.8f, 0.55f),

    /** Generated with the openai-image-api skill in the sheet's style; its fingertip is the sprite's top-left. */
    // Larger than the other props: on the 2560px car screen it must read as a pointer at a glance.
    POINTER(R.drawable.mascot_prop_pointer, -1.2f, -1.15f, 1.5f),
}

/** Short bursts of props that play once and leave: drawn from a 0…1 clock, no state per particle. */
internal enum class MascotEffect(val millis: Int) {
    SPARKLES(950),
    ZZZ(2_600),
    NOTES(1_200),
    SWEAT(1_400),
    HEART(950),
    EXCLAIM(800),
}

internal fun OverlayHud.mascotAction(ui: OverlayUi): MascotAction = when {
    phase == OverlayPhase.LIVE && headline.startsWith("等待回答") -> MascotAction.ASKING
    phase == OverlayPhase.LIVE && picture != null && picture.reference == null -> MascotAction.DRAWING
    phase == OverlayPhase.LIVE -> toolLines.lastOrNull { it.running }?.name?.let(::toolAct) ?: MascotAction.THINKING
    (choices.isNotEmpty() && !ui.choicesAnswered) || (controls.isNotEmpty() && !ui.panelAnswered) -> MascotAction.PICKING
    phase == OverlayPhase.DONE && headline == "等待你的回复" -> MascotAction.ASKING
    phase == OverlayPhase.DONE -> MascotAction.DONE
    phase == OverlayPhase.FAIL -> MascotAction.FAILED
    phase == OverlayPhase.STOP -> MascotAction.STOPPED
    else -> MascotAction.IDLE
}

private fun toolAct(tool: String): MascotAction {
    val name = tool.lowercase()
    return if (SEARCH_TOOL_HINTS.any { it in name }) MascotAction.SEARCHING else MascotAction.WORKING
}

/** The prop an act holds for as long as it runs; bursts come and go on their own. */
private val MascotAction.prop: MascotProp?
    get() = when (this) {
        MascotAction.THINKING -> MascotProp.THOUGHT
        MascotAction.SEARCHING -> MascotProp.MAGNIFIER
        MascotAction.WORKING -> MascotProp.GEAR
        MascotAction.DRAWING -> MascotProp.PENCIL
        MascotAction.ASKING -> MascotProp.QUESTION
        MascotAction.POINTING -> MascotProp.POINTER
        else -> null
    }

/**
 * Everything 小博 can move, shared by every view that draws it: the bubble, the flight and the card's header draw
 * the same pose, so 小博 passes between them without a jump. Exactly one view drives it at a time (see [Mascot]).
 * Eye and prop values are fractions of the body's radius; [lift], [sway] and [jump] are dp; [tilt] is degrees.
 */
internal class MascotPose {
    /** Gaze: -1 far left … 1 far right, -1 up … 1 down; 0, 0 looks straight at the person. */
    val lookX = Animatable(0f)
    val lookY = Animatable(0f)

    /** Eyelids for blinks: 1 open, 0 shut. */
    val open = Animatable(1f)

    /** How tall the eyes stand: under 1 narrowed (thinking, sad, drowsy), over 1 wide. */
    val tall = Animatable(1f)
    val eyeScale = Animatable(1f)

    /** How far lids hang over the eyes, 0 … 1, and their tilt: positive worried, negative focused. */
    val lid = Animatable(0f)
    val slant = Animatable(0f)

    /** Eyes squinting into smiling arcs, 0 … 1. The face has no mouth. */
    val smile = Animatable(0f)
    val lift = Animatable(0f)
    val sway = Animatable(0f)

    /** Positive flattens the body onto its base (landing), negative stretches it (mid-jump, held). */
    val squash = Animatable(0f)

    /** A small resting lean of the head, never animated as a swing. */
    val tilt = Animatable(0f)

    /**
     * The face turning on the sphere, degrees (owner 2026-10-12: 「眼睛动作更多，眼睛更大，还会转体」): [spin] is yaw
     * added to the gaze (past 90 the face goes round the back), [nod] is pitch.
     */
    val spin = Animatable(0f)
    val nod = Animatable(0f)

    // Reaction channels, added on top of the act's values.
    val jump = Animatable(0f)
    val bounce = Animatable(0f)
    val joy = Animatable(0f)
    val pop = Animatable(0f)

    /** The act's held prop, how far it has popped in, where it is nudged to, and its own turn (a gear turning). */
    var prop by mutableStateOf<MascotProp?>(null)
    val propShow = Animatable(0f)
    val propX = Animatable(0f)
    val propY = Animatable(0f)
    val propTurn = Animatable(0f)

    /** An act's burst and a reaction's burst, each with its own 0…1 clock. */
    var effect by mutableStateOf<MascotEffect?>(null)
    val effectTime = Animatable(0f)
    var flair by mutableStateOf<MascotEffect?>(null)
    val flairTime = Animatable(0f)

    /** The last act begun, so an act resumed by another view (or after a flight) skips its entrance. */
    var lastAct: MascotAction? = null

    private val reactions = MutableSharedFlow<MascotReaction>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Plays [reaction] on whichever view drives the pose; dropped when none does. Call on the main thread. */
    fun react(reaction: MascotReaction) {
        reactions.tryEmit(reaction)
    }

    /** A new reaction replaces one still playing, starting from where it was. */
    suspend fun playReactions(scope: CoroutineScope) {
        var current: Job? = null
        reactions.collect { reaction ->
            current?.cancel()
            current = scope.launch { perform(reaction) }
        }
    }
}

/**
 * 小博: a flat body with two white pill eyes, at rest centred a little above the middle so it looks out at the
 * person in front of the screen, and the act's prop beside it. With [driving], this view plays [action] (and
 * reactions) on the shared [pose]; otherwise it only draws. Every pose value is read in layer and draw blocks, so
 * motion re-records two small layers and never recomposes. Acts hold still between moves and produce no frames
 * then; working acts move continuously, while the card's edge comet runs anyway. [aim] is the direction a
 * [MascotAction.FLYING] 小博 is heading.
 */
@Composable
internal fun Mascot(pose: MascotPose, action: MascotAction, driving: Boolean, modifier: Modifier = Modifier, aim: Offset = Offset.Zero) {
    if (driving) {
        LaunchedEffect(pose, action, aim) {
            // A move from the view handing over can still interrupt this one's for a frame; the act starts again.
            while (true) {
                try {
                    pose.perform(action, aim)
                    return@LaunchedEffect
                } catch (interrupted: CancellationException) {
                    if (!isActive) throw interrupted
                }
            }
        }
        LaunchedEffect(pose) { coroutineScope { pose.playReactions(this) } }
    }
    val context = LocalContext.current
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(MASCOT_BOX)) {
        // A soft shadow on the ground under the body: smaller and fainter the higher it hops, the surest cue of depth.
        Spacer(Modifier.matchParentSize().groundShadow(pose))
        Spacer(
            Modifier
                .size(MASCOT_BODY)
                .graphicsLayer {
                    translationX = pose.sway.value.dp.toPx()
                    translationY = (pose.lift.value + pose.jump.value).dp.toPx()
                    rotationZ = pose.tilt.value + pose.lookX.value * GAZE_TILT
                    val squash = pose.squash.value + pose.bounce.value
                    scaleX = 1f + squash * 0.6f
                    scaleY = 1f - squash
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .drawWithCache {
                    val face = MascotFace()
                    val skin = MascotSkin(size, MascotProps.body(context))
                    onDrawBehind { drawMascot(pose, skin, face) }
                },
        )
        // Props ride along with the body's travel but not its squash, and may reach past the box.
        Spacer(Modifier.matchParentSize().props(pose, context))
    }
}

/** The ground shadow follows the body sideways but stays on the ground. */
private fun Modifier.groundShadow(pose: MascotPose): Modifier = graphicsLayer { translationX = pose.sway.value.dp.toPx() }
    .drawBehind { drawGroundShadow(pose, MASCOT_BODY.toPx() / 2f) }

private fun Modifier.props(pose: MascotPose, context: Context): Modifier = graphicsLayer {
    translationX = pose.sway.value.dp.toPx()
    translationY = (pose.lift.value + pose.jump.value).dp.toPx()
}.drawWithCache {
    val radius = MASCOT_BODY.toPx() / 2f
    onDrawBehind { drawProps(context, pose, radius) }
}

/** Where an act's face and body come to rest; anything not named goes back to neutral. */
private data class Rest(
    val lookX: Float = 0f,
    val lookY: Float = 0f,
    val tall: Float = 1f,
    val eyeScale: Float = 1f,
    val lid: Float = 0f,
    val slant: Float = 0f,
    val tilt: Float = 0f,
    val lift: Float = 0f,
    val squash: Float = 0f,
)

private fun MascotAction.rest(aim: Offset): Rest = when (this) {
    MascotAction.IDLE, MascotAction.DONE -> Rest()
    MascotAction.THINKING -> Rest(lookY = -0.5f, tall = 0.75f, tilt = 3f)
    MascotAction.SEARCHING -> Rest(tall = 0.9f)
    // Focus is calm: level lids a little lowered, barely tilted, so it never reads as a frown.
    MascotAction.WORKING -> Rest(lookY = 0.55f, lid = 0.18f, slant = -0.15f)
    MascotAction.DRAWING -> Rest(lookX = 0.3f, lookY = 0.85f, lid = 0.2f, slant = -0.2f)
    MascotAction.ASKING -> Rest(eyeScale = 1.12f, tilt = 4f)
    MascotAction.PICKING -> Rest(lookY = 0.8f, lid = 0.12f)
    MascotAction.FAILED -> Rest(lookY = 0.55f, tall = 0.6f, lid = 0.3f, slant = 1f, tilt = -4f, lift = 1.5f)
    MascotAction.STOPPED -> Rest(lookY = 0.3f, tall = 0.25f, lift = 1.5f)
    MascotAction.HELD -> Rest(eyeScale = 1.15f, tall = 1.1f, squash = -0.08f)
    MascotAction.FLYING -> Rest(lookX = aim.x.coerceIn(-0.8f, 0.8f), lookY = aim.y.coerceIn(-0.8f, 0.8f), tilt = aim.x * 8f, squash = -0.06f)
    MascotAction.SLEEPING -> Rest(lookY = 0.4f, tall = 0.12f, lift = 2f, squash = 0.06f)
    MascotAction.POINTING -> Rest(lookX = -0.7f, lookY = -0.6f, tall = 1.05f, tilt = -4f)
}

/** Plays [action] until the next one cancels it; the next act starts from wherever this one was interrupted. */
private suspend fun MascotPose.perform(action: MascotAction, aim: Offset): Unit = coroutineScope {
    val rest = action.rest(aim)
    // Being held or flying passes; the act around it resumes without replaying its entrance.
    val transient = action == MascotAction.HELD || action == MascotAction.FLYING
    val fresh = transient || action != lastAct
    if (!transient) lastAct = action
    launch { holdProp(if (transient) prop else action.prop) }
    settle(rest)
    when (action) {
        MascotAction.IDLE -> fidget(rest)
        MascotAction.THINKING -> {
            launch { bob(rest, amplitude = 1f, period = 2_000) }
            launch { ideas() }
            ponder()
        }
        MascotAction.SEARCHING -> {
            launch { bob(rest, amplitude = 2f, period = 1_000) }
            sweep()
        }
        MascotAction.WORKING -> {
            launch { bob(rest, amplitude = 2f, period = 1_800) }
            launch { turnGear() }
            focus(rest)
        }
        MascotAction.DRAWING -> {
            launch { bob(rest, amplitude = 1.5f, period = 3_000) }
            sketch()
        }
        MascotAction.ASKING -> {
            if (fresh) hop(3f)
            wonder()
        }
        MascotAction.PICKING -> scan(rest)
        MascotAction.DONE -> {
            if (fresh) cheer()
            fidget(rest)
        }
        MascotAction.FAILED -> {
            if (fresh) shake()
            sigh(rest)
        }
        MascotAction.STOPPED -> doze()
        MascotAction.HELD -> wobbleWhileHeld()
        MascotAction.SLEEPING -> burst(MascotEffect.ZZZ)
        MascotAction.FLYING -> Unit
        MascotAction.POINTING -> bob(rest, amplitude = 1f, period = 1_600)
    }
}

private suspend fun MascotPose.settle(rest: Rest): Unit = coroutineScope {
    launch { lookX.animateTo(rest.lookX, SETTLE) }
    launch { lookY.animateTo(rest.lookY, SETTLE) }
    launch { tall.animateTo(rest.tall, SETTLE) }
    launch { eyeScale.animateTo(rest.eyeScale, SETTLE) }
    launch { lid.animateTo(rest.lid, SETTLE) }
    launch { slant.animateTo(rest.slant, SETTLE) }
    launch { tilt.animateTo(rest.tilt, SETTLE) }
    launch { lift.animateTo(rest.lift, SETTLE) }
    launch { squash.animateTo(rest.squash, SETTLE) }
    launch { open.animateTo(1f, SETTLE) }
    launch { smile.animateTo(0f, SETTLE) }
    launch { nod.animateTo(0f, SETTLE) }
    launch {
        // A turn cut short comes back the short way round.
        spin.snapTo((spin.value % 360f + 540f) % 360f - 180f)
        spin.animateTo(0f, SETTLE)
    }
    sway.animateTo(0f, SETTLE)
}

/** Swaps the held prop: the old one shrinks away, the new one pops in. */
private suspend fun MascotPose.holdProp(wanted: MascotProp?) {
    if (prop == wanted) {
        propShow.animateTo(if (wanted == null) 0f else 1f, SETTLE)
        return
    }
    if (prop != null) propShow.animateTo(0f, tween(120))
    propX.snapTo(0f)
    propY.snapTo(0f)
    propTurn.snapTo(0f)
    prop = wanted
    if (wanted != null) propShow.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 500f))
}

/** Plays a burst of props once, on the act's channel. */
private suspend fun MascotPose.burst(kind: MascotEffect) {
    effect = kind
    try {
        effectTime.snapTo(0f)
        effectTime.animateTo(1f, tween(kind.millis, easing = LinearEasing))
    } finally {
        // Cut short (the act changed or another view took over): never leave a burst frozen on screen.
        if (effect == kind) effect = null
    }
}

private suspend fun MascotPose.look(x: Float, y: Float, millis: Int): Unit = coroutineScope {
    launch { lookX.animateTo(x, tween(millis, easing = FastOutSlowInEasing)) }
    lookY.animateTo(y, tween(millis, easing = FastOutSlowInEasing))
}

private suspend fun MascotPose.blink(slow: Boolean = false) {
    open.animateTo(0f, tween(if (slow) 140 else 70))
    open.animateTo(1f, tween(if (slow) 220 else 110))
}

/** A bob about the rest height, eased like a sine: the working acts' heartbeat. */
private suspend fun MascotPose.bob(rest: Rest, amplitude: Float, period: Int) {
    while (true) {
        lift.animateTo(rest.lift - amplitude, tween(period / 2, easing = EaseInOutSine))
        lift.animateTo(rest.lift + amplitude, tween(period / 2, easing = EaseInOutSine))
    }
}

/** A crouch, a jump with the body stretched, and a squashed landing that springs back. */
private suspend fun MascotPose.hop(height: Float) {
    squash.animateTo(0.1f, tween(80))
    coroutineScope {
        launch { squash.animateTo(-0.08f, tween(140)) }
        lift.animateTo(-height, tween(200, easing = LinearOutSlowInEasing))
    }
    coroutineScope {
        launch { squash.animateTo(0f, tween(120)) }
        lift.animateTo(0f, tween(180, easing = FastOutLinearInEasing))
    }
    squash.animateTo(0.12f, tween(70))
    squash.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 900f))
}

/** A jelly wobble: the body squashes and stretches a few times, getting smaller, in place. */
private suspend fun MascotPose.jiggle() {
    for (amount in JIGGLE_STEPS) squash.animateTo(amount, tween(90, easing = FastOutSlowInEasing))
}

/** Turns all the way round: the face slides off one side and comes back from the other (「还会转体」). */
private suspend fun MascotPose.turnAround() {
    val side = if (Random.nextBoolean()) 1f else -1f
    spin.animateTo(side * 360f, tween(TURN_MS, easing = FastOutSlowInEasing))
    spin.snapTo(0f)
}

/** Turns to look off to one side, the eyes sliding toward the edge of the sphere, a blink, and back. */
private suspend fun MascotPose.peek() {
    val side = if (Random.nextBoolean()) 1f else -1f
    spin.animateTo(side * PEEK_TURN, tween(320, easing = FastOutSlowInEasing))
    delay(Random.nextLong(500, 900))
    blink()
    spin.animateTo(0f, tween(360, easing = FastOutSlowInEasing))
}

private suspend fun MascotPose.nodYes() {
    repeat(2) {
        nod.animateTo(NOD_DOWN, tween(140, easing = FastOutSlowInEasing))
        nod.animateTo(-NOD_DOWN / 3f, tween(140, easing = FastOutSlowInEasing))
    }
    nod.animateTo(0f, tween(140))
}

/** Quick darts left and right, as if checking something. */
private suspend fun MascotPose.dart(rest: Rest) {
    look(-0.75f, rest.lookY, 90)
    delay(260)
    look(0.75f, rest.lookY, 110)
    delay(260)
    look(rest.lookX, rest.lookY, 160)
}

/** The eyes roll round once. */
private suspend fun MascotPose.rollEyes(rest: Rest) {
    for (step in 0..ROLL_STEPS) {
        val angle = step * 2 * PI / ROLL_STEPS - PI / 2
        look(cos(angle).toFloat() * ROLL_REACH, sin(angle).toFloat() * ROLL_REACH, 80)
    }
    look(rest.lookX, rest.lookY, 160)
}

/** Eyes go wide for a moment, as if surprised. */
private suspend fun MascotPose.widen() {
    eyeScale.animateTo(1.22f, tween(110))
    delay(420)
    eyeScale.animateTo(1f, tween(260))
}

/**
 * Quiet life: now and then one small thing, and nothing at all in between. Each move redraws an always-on-top window
 * and with it the whole screen's composition, so idle moves stay rare (measured on an Android 11 test device: one every 2.5–6 s
 * kept the bubble at 9–14 frames a second while nothing happened).
 */
private suspend fun MascotPose.fidget(rest: Rest) {
    while (true) {
        delay(Random.nextLong(IDLE_FIDGET_MIN_MS, IDLE_FIDGET_MAX_MS))
        when (Random.nextInt(24)) {
            in 0..4 -> blink()
            5, 6 -> {
                blink()
                delay(120)
                blink()
            }
            in 7..9 -> {
                look(Random.nextFloat() * 1.6f - 0.8f, Random.nextFloat() - 0.6f, 260)
                delay(Random.nextLong(500, 1_100))
                look(rest.lookX, rest.lookY, 300)
            }
            10 -> turnAround()
            11, 12 -> peek()
            13 -> nodYes()
            14 -> dart(rest)
            15 -> rollEyes(rest)
            16 -> widen()
            17 -> hop(4f)
            18 -> jiggle()
            19 -> hum()
            20 -> yawn(rest)
            21 -> {
                hop(3f)
                hop(2f)
            }
            else -> stretch()
        }
    }
}

/** Sways side to side as if humming along, with notes floating up. */
private suspend fun MascotPose.hum(): Unit = coroutineScope {
    launch { burst(MascotEffect.NOTES) }
    repeat(2) {
        sway.animateTo(-2f, tween(260, easing = EaseInOutSine))
        sway.animateTo(2f, tween(260, easing = EaseInOutSine))
    }
    sway.animateTo(0f, tween(200))
}

/** Eyes droop shut as the body stretches tall, then it blinks awake. */
private suspend fun MascotPose.yawn(rest: Rest) {
    coroutineScope {
        launch { squash.animateTo(-0.08f, tween(500)) }
        tall.animateTo(0.2f, tween(500))
    }
    delay(500)
    coroutineScope {
        launch { squash.animateTo(rest.squash, tween(300)) }
        tall.animateTo(rest.tall, tween(300))
    }
    blink()
}

private suspend fun MascotPose.stretch() {
    squash.animateTo(-0.14f, tween(260, easing = FastOutSlowInEasing))
    delay(200)
    squash.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 500f))
}

private suspend fun MascotPose.ponder() {
    var spot = 0
    while (true) {
        val (x, y) = PONDER_SPOTS[spot++ % PONDER_SPOTS.size]
        look(x, y, 320)
        delay(Random.nextLong(450, 900))
        if (Random.nextInt(3) == 0) blink()
    }
}

/** While thinking, the thought bubble floats gently and now and then becomes a light bulb for a moment. */
private suspend fun MascotPose.ideas() {
    while (true) {
        repeat(3) {
            propY.animateTo(-0.06f, tween(600, easing = EaseInOutSine))
            propY.animateTo(0.04f, tween(600, easing = EaseInOutSine))
        }
        holdProp(MascotProp.BULB)
        delay(900)
        holdProp(MascotProp.THOUGHT)
    }
}

/** Eyes and the magnifier sweep from side to side together, pausing at the ends. */
private suspend fun MascotPose.sweep() {
    while (true) {
        for (side in SWEEP_SIDES) {
            coroutineScope {
                launch { propX.animateTo(side * 0.22f, tween(380, easing = FastOutSlowInEasing)) }
                launch { spin.animateTo(side * SEARCH_TURN, tween(380, easing = FastOutSlowInEasing)) }
                look(side, -0.1f, 380)
            }
            delay(140)
        }
        if (Random.nextInt(4) == 0) blink()
    }
}

/** The gear beside 小博 turns while a tool runs; the gear turns, 小博 does not. */
private suspend fun MascotPose.turnGear() {
    while (true) {
        propTurn.animateTo(propTurn.value + 120f, tween(900, easing = FastOutSlowInEasing))
        delay(200)
    }
}

/** Eyes on the work, darting a little, with a quick nod of the eyes every so often. */
private suspend fun MascotPose.focus(rest: Rest) {
    while (true) {
        look(rest.lookX + Random.nextFloat() * 0.5f - 0.25f, rest.lookY, 200)
        delay(Random.nextLong(300, 700))
        if (Random.nextInt(3) == 0) {
            coroutineScope {
                launch { lift.animateTo(rest.lift + 1.2f, tween(120)) }
                lookY.animateTo(rest.lookY + 0.25f, tween(120))
            }
            coroutineScope {
                launch { lift.animateTo(rest.lift, tween(160)) }
                lookY.animateTo(rest.lookY, tween(160))
            }
        }
    }
}

/** Eyes follow the pencil as it scribbles on the picture below, in short strokes. */
private suspend fun MascotPose.sketch() {
    while (true) {
        val x = Random.nextFloat() * 0.3f - 0.15f
        val y = Random.nextFloat() * 0.2f - 0.1f
        coroutineScope {
            launch { propX.animateTo(x, tween(180, easing = FastOutSlowInEasing)) }
            launch { propY.animateTo(y, tween(180, easing = FastOutSlowInEasing)) }
            look(0.3f + x * 2f, 0.85f + y, 180)
        }
        delay(Random.nextLong(80, 260))
        if (Random.nextInt(10) == 0) blink()
    }
}

/** Waiting for the answer: the question mark wobbles and 小博 blinks, eyes on the user. */
private suspend fun MascotPose.wonder() {
    while (true) {
        repeat(2) {
            propTurn.animateTo(-12f, tween(220, easing = EaseInOutSine))
            propTurn.animateTo(12f, tween(220, easing = EaseInOutSine))
        }
        propTurn.animateTo(0f, tween(160))
        delay(Random.nextLong(1_800, 3_500))
        blink()
    }
}

/** Moves between the tiles below a couple of times, then looks up at the user as if to say "your pick". */
private suspend fun MascotPose.scan(rest: Rest) {
    while (true) {
        repeat(2) {
            look(-0.55f, rest.lookY, 420)
            delay(900)
            look(0.45f, rest.lookY, 420)
            delay(900)
        }
        look(0f, -0.1f, 360)
        blink()
        delay(1_200)
    }
}

private suspend fun MascotPose.cheer() {
    coroutineScope {
        launch { smile.animateTo(1f, tween(160)) }
        launch { burst(MascotEffect.SPARKLES) }
        launch { turnAround() }
        hop(7f)
        hop(3.5f)
    }
    delay(1_400)
    smile.animateTo(0f, tween(240))
}

private suspend fun MascotPose.shake(): Unit = coroutineScope {
    launch { burst(MascotEffect.SWEAT) }
    for (x in SHAKE_STEPS) sway.animateTo(x, tween(55))
}

/** Slumped after a failure: a slow blink now and then, or a sigh that sinks and lifts the body with a sweat drop. */
private suspend fun MascotPose.sigh(rest: Rest) {
    while (true) {
        delay(Random.nextLong(3_000, 5_500))
        if (Random.nextBoolean()) {
            blink(slow = true)
        } else {
            coroutineScope {
                launch { burst(MascotEffect.SWEAT) }
                launch { squash.animateTo(0.06f, tween(600, easing = EaseInOutSine)) }
                lift.animateTo(rest.lift + 1.5f, tween(600, easing = EaseInOutSine))
            }
            coroutineScope {
                launch { squash.animateTo(rest.squash, tween(700, easing = EaseInOutSine)) }
                lift.animateTo(rest.lift, tween(700, easing = EaseInOutSine))
            }
        }
    }
}

/** Eyes nearly shut, with a few Z's floating up every so often. */
private suspend fun MascotPose.doze() {
    while (true) {
        burst(MascotEffect.ZZZ)
        delay(Random.nextLong(5_000, 8_000))
    }
}

/** Hanging from the finger: sways a little from side to side. */
private suspend fun MascotPose.wobbleWhileHeld() {
    while (true) {
        sway.animateTo(-1.5f, tween(420, easing = EaseInOutSine))
        sway.animateTo(1.5f, tween(420, easing = EaseInOutSine))
    }
}

private suspend fun MascotPose.flash(kind: MascotEffect) {
    flair = kind
    try {
        flairTime.snapTo(0f)
        flairTime.animateTo(1f, tween(kind.millis, easing = LinearEasing))
    } finally {
        if (flair == kind) flair = null
    }
}

private suspend fun MascotPose.perform(reaction: MascotReaction): Unit = coroutineScope {
    when (reaction) {
        MascotReaction.GIGGLE -> {
            launch { flash(MascotEffect.HEART) }
            launch {
                joy.animateTo(1f, tween(120))
                delay(650)
                joy.animateTo(0f, tween(220))
            }
            jump.animateTo(-5f, tween(150, easing = LinearOutSlowInEasing))
            jump.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 700f))
        }
        MascotReaction.NOTICE -> {
            launch { flash(MascotEffect.EXCLAIM) }
            launch {
                pop.animateTo(0.2f, tween(120))
                delay(380)
                pop.animateTo(0f, tween(200))
            }
            jump.animateTo(-6f, tween(160, easing = LinearOutSlowInEasing))
            jump.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 600f))
        }
        MascotReaction.TURN -> turnAround()
        MascotReaction.NOD -> nodYes()
        MascotReaction.LAND -> {
            bounce.animateTo(0.16f, tween(70, easing = LinearEasing))
            bounce.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 600f))
        }
    }
}

/** 小博's felt green; the glass around the card takes a faint tint of it. */
internal val MASCOT_COLOR = Color(0xFF8DB596)

/** The space 小博 takes in the card's header and in the bubble: room for hops around a 38dp ball (its roof reaches higher). */
internal val MASCOT_BOX = 54.dp
private val MASCOT_BODY = 38.dp

/**
 * Where the pointing hand's fingertip sits against 小博's centre, for [unitPx] pixels per dp. Android Use places
 * 小博 so this lands on the tap point, keeping the target itself in view.
 */
internal fun pointerFingertip(unitPx: Float): Offset {
    val radius = MASCOT_BODY.value * unitPx / 2f
    val prop = MascotProp.POINTER
    return Offset(prop.anchorX + (POINTER_TIP_X - 0.5f) * prop.size, prop.anchorY + (POINTER_TIP_Y - 0.5f) * prop.size) * radius
}

/** The fingertip in the pointer sprite, as fractions of its side (measured on the 192px felt sticker). */
private const val POINTER_TIP_X = 0.08f
private const val POINTER_TIP_Y = 0.05f
private const val GAZE_TILT = 2f
private const val TURN_MS = 900
private const val PEEK_TURN = 58f
private const val SEARCH_TURN = 28f
private const val NOD_DOWN = 14f
private const val ROLL_STEPS = 8
private const val ROLL_REACH = 0.6f
private val SETTLE: AnimationSpec<Float> = tween(320, easing = FastOutSlowInEasing)
private val PONDER_SPOTS = listOf(0.5f to -0.75f, -0.7f to -0.6f, 0.2f to -0.2f, -0.3f to -0.85f, 0.7f to 0.1f)
private val SWEEP_SIDES = listOf(-0.8f, 0.7f)
private val SHAKE_STEPS = listOf(-3f, 3f, -2.4f, 2.4f, -1.2f, 0f)
private val JIGGLE_STEPS = listOf(0.1f, -0.08f, 0.06f, -0.04f, 0.02f, 0f)
private val SEARCH_TOOL_HINTS = listOf("search", "poi", "keyword", "query", "find", "lookup", "fetch", "browse", "web")

private const val IDLE_FIDGET_MIN_MS = 10_000L
private const val IDLE_FIDGET_MAX_MS = 20_000L
