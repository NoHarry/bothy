package cc.noharry.bothy.sample

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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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

// How 小博 is drawn: the felt ball under its tiled roof (a generated sprite), the eyes projected on the ball, its ground
// shadow, and the props and bursts beside it.
// Moved out of DebugOverlayMascot.kt (which keeps the acts and their timing) when 小博 became a sphere, owner 2026-10-12.

/**
 * Where 小博's sprite lands, worked out once per size: its felt ball fills this box and the roof reaches above it.
 * Lids are the sprite drawn again under a clip, so they take the felt under them.
 */
internal class MascotSkin(size: Size, val image: ImageBitmap) {
    private val side = (size.minDimension / 2f / SPRITE_BALL_RADIUS).roundToInt()
    val topLeft = IntOffset(((size.width - side) / 2f).roundToInt(), ((size.height - side) / 2f).roundToInt())
    val drawn = IntSize(side, side)
}

private fun DrawScope.drawSkin(skin: MascotSkin) =
    drawImage(skin.image, dstOffset = skin.topLeft, dstSize = skin.drawn, filterQuality = FilterQuality.High)

/**
 * The body and the eyes painted on its ball. The gaze ([MascotPose.lookX], [MascotPose.lookY]) plus
 * [MascotPose.spin] and [MascotPose.nod] turn the face on the ball: each eye is placed by projecting its point on
 * the sphere, narrows toward the edge, and is hidden round the back.
 */
internal fun DrawScope.drawMascot(pose: MascotPose, skin: MascotSkin, face: MascotFace) {
    val radius = size.minDimension / 2f
    drawSkin(skin)
    val lookY = pose.lookY.value
    val yaw = pose.lookX.value * LOOK_YAW + pose.spin.value
    val pitch = pose.nod.value + EYES_REST_PITCH + lookY * if (lookY > 0f) LOOK_DOWN else LOOK_UP
    val scale = pose.eyeScale.value * (1f + pose.pop.value)
    face.smile = maxOf(pose.smile.value, pose.joy.value)
    for (side in EYE_SIDES) {
        val eyeYaw = Math.toRadians((yaw + side * EYE_SPREAD).toDouble())
        val eyePitch = Math.toRadians(pitch.toDouble())
        val depth = (cos(eyeYaw) * cos(eyePitch)).toFloat()
        if (depth <= 0f) continue
        val at = Offset(
            center.x + radius * EYE_DEPTH * (sin(eyeYaw) * cos(eyePitch)).toFloat(),
            center.y + radius * EYE_DEPTH * sin(eyePitch).toFloat(),
        )
        face.width = radius * EYE_WIDTH * scale * cos(eyeYaw).toFloat()
        face.height = radius * EYE_HEIGHT * scale * pose.tall.value * cos(eyePitch).toFloat()
        face.fade = (depth / LIMB_FADE).coerceAtMost(1f)
        drawEye(at, innerOnRight = side < 0f, face, pose, skin)
    }
}

/** The shadow on the ground under a body of [radius]: it shrinks and fades as 小博 rises. */
internal fun DrawScope.drawGroundShadow(pose: MascotPose, radius: Float) {
    val rise = (-(pose.lift.value + pose.jump.value) / SHADOW_FADE_DP).coerceIn(0f, 0.7f)
    val width = radius * 1.4f * (1f - rise * 0.5f)
    val height = SHADOW_HEIGHT_DP.dp.toPx() * (1f - rise * 0.4f)
    val y = center.y + radius + 1.dp.toPx()
    drawOval(
        SHADOW_COLOR.copy(alpha = SHADOW_ALPHA * (1f - rise)),
        Offset(center.x - width / 2f, y - height / 2f),
        Size(width, height),
    )
}

/** One frame's eye geometry and reusable paths, kept with the draw cache so drawing a frame allocates nothing. */
internal class MascotFace {
    val arc = Path()
    val lidShape = Path()
    var width = 0f
    var height = 0f
    var smile = 0f
    /** Toward the ball's edge an eye fades out instead of ending in a sliver. */
    var fade = 1f
}

private fun DrawScope.drawEye(at: Offset, innerOnRight: Boolean, face: MascotFace, pose: MascotPose, skin: MascotSkin) {
    val width = face.width
    if (face.smile < 1f) {
        // A blink closes to a short dash rather than vanishing; a smile squeezes the pill toward its arc.
        val shown = maxOf(face.height * pose.open.value * (1f - face.smile * 0.6f), width * 0.5f)
        val top = at.y - shown / 2f
        val alpha = (1f - face.smile) * face.fade
        drawRoundRect(EYE_COLOR.copy(alpha = alpha), Offset(at.x - width / 2f, top), Size(width, shown), CornerRadius(width / 2f))
        // A glint high on the eye keeps it alive; it goes once a blink has the eye nearly shut.
        if (shown > width * GLINT_OPEN) {
            drawCircle(Color.White.copy(alpha = alpha * GLINT_ALPHA), width * GLINT_SIZE, Offset(at.x - width * 0.12f, top + width * 0.42f))
        }
        val lid = pose.lid.value
        val slant = pose.slant.value
        if (lid > 0f || slant != 0f) {
            // Lids are the body drawn again over the eye's top; a tilted edge reads as worried or focused.
            val outer = (lid + slant * LID_TILT).coerceIn(0f, 1f) * shown
            val inner = (lid - slant * LID_TILT).coerceIn(0f, 1f) * shown
            val left = at.x - width / 2f - 1f
            val right = at.x + width / 2f + 1f
            face.lidShape.rewind()
            face.lidShape.moveTo(left, top - 1f)
            face.lidShape.lineTo(right, top - 1f)
            face.lidShape.lineTo(right, top + if (innerOnRight) inner else outer)
            face.lidShape.lineTo(left, top + if (innerOnRight) outer else inner)
            face.lidShape.close()
            clipPath(face.lidShape) { drawSkin(skin) }
        }
    }
    if (face.smile > 0f) {
        // Smiling is squinting: narrow "^" arcs, set apart so the two eyes never run into one line.
        face.arc.rewind()
        face.arc.moveTo(at.x - width * SMILE_SPAN, at.y + face.height * 0.12f)
        face.arc.quadraticTo(at.x, at.y - face.height * 0.5f, at.x + width * SMILE_SPAN, at.y + face.height * 0.12f)
        drawPath(face.arc, EYE_COLOR.copy(alpha = face.smile * face.fade), style = Stroke(width * 0.55f, cap = StrokeCap.Round))
    }
}

/** The held prop, then the act's burst and the reaction's burst, around a body of [radius] at this box's centre. */
internal fun DrawScope.drawProps(context: Context, pose: MascotPose, radius: Float) {
    val held = pose.prop
    val shown = pose.propShow.value
    if (held != null && shown > 0f) {
        val at = center + Offset(held.anchorX + pose.propX.value, held.anchorY + pose.propY.value) * radius
        drawProp(context, held, at, held.size * radius * shown, alpha = shown.coerceIn(0f, 1f), turn = pose.propTurn.value)
    }
    pose.effect?.let { drawEffect(context, it, pose.effectTime.value, radius) }
    pose.flair?.let { drawEffect(context, it, pose.flairTime.value, radius) }
}

/** The bursts as fixed choreographies over a 0…1 clock: each particle has its own start, path, growth and fade. */
private fun DrawScope.drawEffect(context: Context, kind: MascotEffect, time: Float, radius: Float) {
    when (kind) {
        MascotEffect.SPARKLES -> SPARKLE_SPOTS.forEachIndexed { i, (x, y) ->
            val t = ((time - i * 0.15f) / 0.6f).coerceIn(0f, 1f)
            if (t <= 0f) return@forEachIndexed
            val grow = if (t < 0.4f) t / 0.4f else 1f
            val fade = if (t > 0.6f) 1f - (t - 0.6f) / 0.4f else 1f
            drawProp(context, MascotProp.SPARKLE, center + Offset(x, y) * radius, MascotProp.SPARKLE.size * radius * grow, fade)
        }
        MascotEffect.ZZZ -> repeat(3) { i ->
            val t = ((time - i * 0.22f) / 0.56f).coerceIn(0f, 1f)
            if (t <= 0f) return@repeat
            val at = center + Offset(MascotProp.Z.anchorX + 0.55f * t, MascotProp.Z.anchorY - 0.9f * t) * radius
            val fade = if (t < 0.2f) t / 0.2f else 1f - (t - 0.2f) / 0.8f
            drawProp(context, MascotProp.Z, at, MascotProp.Z.size * radius * (0.6f + 0.6f * t), fade)
        }
        MascotEffect.NOTES -> repeat(2) { i ->
            val t = ((time - i * 0.35f) / 0.65f).coerceIn(0f, 1f)
            if (t <= 0f) return@repeat
            val swing = if (i == 0) 0.15f else -0.12f
            val at = center + Offset(MascotProp.NOTE.anchorX + swing * kotlin.math.sin(t * 6f), MascotProp.NOTE.anchorY - 0.9f * t) * radius
            drawProp(context, MascotProp.NOTE, at, MascotProp.NOTE.size * radius, 1f - t * t)
        }
        MascotEffect.SWEAT -> {
            val at = center + Offset(MascotProp.DROP.anchorX, MascotProp.DROP.anchorY + 0.3f * time) * radius
            val fade = if (time < 0.15f) time / 0.15f else 1f - ((time - 0.15f) / 0.85f)
            drawProp(context, MascotProp.DROP, at, MascotProp.DROP.size * radius, fade)
        }
        MascotEffect.HEART -> {
            val at = center + Offset(MascotProp.HEART.anchorX, MascotProp.HEART.anchorY - 0.6f * time) * radius
            val grow = if (time < 0.25f) 0.6f + 1.6f * time else 1f
            drawProp(context, MascotProp.HEART, at, MascotProp.HEART.size * radius * grow, 1f - time * time)
        }
        MascotEffect.EXCLAIM -> {
            val at = center + Offset(MascotProp.EXCLAIM.anchorX, MascotProp.EXCLAIM.anchorY) * radius
            val grow = when {
                time < 0.2f -> time / 0.2f * 1.2f
                time < 0.3f -> 1.2f - (time - 0.2f) * 2f
                else -> 1f
            }
            val fade = if (time > 0.75f) 1f - (time - 0.75f) / 0.25f else 1f
            drawProp(context, MascotProp.EXCLAIM, at, MascotProp.EXCLAIM.size * radius * grow, fade)
        }
    }
}

private fun DrawScope.drawProp(context: Context, prop: MascotProp, at: Offset, side: Float, alpha: Float, turn: Float = 0f) {
    if (side <= 0.5f || alpha <= 0f) return
    val image = MascotProps.image(context, prop)
    val half = side / 2f
    val draw = {
        drawImage(
            image,
            dstOffset = IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt()),
            dstSize = IntSize(side.roundToInt(), side.roundToInt()),
            alpha = alpha.coerceIn(0f, 1f),
        )
    }
    if (turn == 0f) draw() else rotate(turn, at) { draw() }
}

/** 小博's sprite and prop stickers, decoded once per process and shared by every window that draws 小博. */
internal object MascotProps {
    private val images = mutableMapOf<Int, ImageBitmap>()

    fun body(context: Context): ImageBitmap = decoded(context, R.drawable.mascot_body)

    fun image(context: Context, prop: MascotProp): ImageBitmap = decoded(context, prop.res)

    private fun decoded(context: Context, @DrawableRes res: Int): ImageBitmap =
        images.getOrPut(res) { BitmapFactory.decodeResource(context.resources, res).asImageBitmap() }
}

/** The ball's radius in the sprite, as a fraction of its side (measured on the cut-out mascot_body.png). */
private const val SPRITE_BALL_RADIUS = 0.3676f
private val EYE_COLOR = Color(0xFF262B27)
private const val GLINT_SIZE = 0.2f
private const val GLINT_ALPHA = 0.92f
/** The glint shows while the eye stands at least this many widths tall. */
private const val GLINT_OPEN = 0.9f
private val SHADOW_COLOR = Color(0xFF03060C)
/** Bigger than the flat 0.2 × 0.46 (owner 2026-10-12: 「眼睛更大」). */
private const val EYE_WIDTH = 0.26f
private const val EYE_HEIGHT = 0.56f
/** Each eye's yaw off the face's centre, degrees; the rest pitch, degrees (negative is up). */
private const val EYE_SPREAD = 18f
private const val EYES_REST_PITCH = -6f
/** Eyes sit a little inside the silhouette, as if painted on a slightly larger ball. */
private const val EYE_DEPTH = 0.94f
/** How far the gaze turns the face: yaw at lookX ±1, pitch at lookY -1 and 1, degrees. */
private const val LOOK_YAW = 42f
private const val LOOK_UP = 22f
private const val LOOK_DOWN = 26f
/** Below this depth (cos of the angle off the viewer) an eye fades toward the ball's edge. */
private const val LIMB_FADE = 0.35f
private val EYE_SIDES = floatArrayOf(-1f, 1f)
private const val SHADOW_FADE_DP = 12f
private const val SHADOW_HEIGHT_DP = 4f
private const val SHADOW_ALPHA = 0.35f
private const val LID_TILT = 0.3f
private const val SMILE_SPAN = 0.62f
private val SPARKLE_SPOTS = listOf(0.95f to -0.85f, -0.95f to -0.6f, 1.05f to 0.3f)
