package cc.noharry.bothy.sample

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Window-side state the stage cannot derive from the HUD. */
internal data class DebugOverlayUi(
    /** Uniform zoom for large car displays, where 1dp is one pixel and the stage would read too small. */
    val scale: Float = 1f,
    /** Room above the device's dock widgets, which draw over overlays; the body shrinks and scrolls to fit it. */
    val maxHeight: Dp = Dp.Unspecified,
    /** The card width class chosen for this content; WIDE lays four or more picks out in two columns. */
    val size: DebugOverlaySize = DebugOverlaySize.REGULAR,
    /** That class's width in the stage's dp, already capped to the screen. */
    val cardWidth: Dp = DebugOverlaySize.REGULAR.landscapeWidthDp.dp,
    val clientMode: Boolean = false,
    val landscape: Boolean = true,
    val showInput: Boolean = false,
    val showDetails: Boolean = false,
    val choicesAnswered: Boolean = false,
    /** The tapped pick, confirmed on the card until its follow-up run ends. */
    val chosen: DebugDisplayChoice? = null,
    /** The generated picture is shown large; a tap on it toggles this. */
    val pictureExpanded: Boolean = false,
    val stopVisible: Boolean = false,
    val pendingQuestion: Boolean = false,
    val expandedToolIds: Set<Long> = emptySet(),
    /** Unfolded from 小博's spot; false folds the card back into it (the overlay opening from or closing to 小博). */
    val cardShown: Boolean = true,
    /** Whether the header's 小博 plays the acts; while another window shows 小博, that one does, on the same pose. */
    val mascotDriving: Boolean = true,
    /**
     * False while 小博 flies to the still-folded card: the card is laid out (its size and 小博's seat are known)
     * but neither drawn nor animated, so its frames never compete with the flight's.
     */
    val cardDrawn: Boolean = true,
    /** A debug preview's act, shown instead of the state's own for a few seconds. */
    val mascotPreview: MascotAction? = null,
    /** The reply's slider was confirmed; it waits for the follow-up instead of offering itself again. */
    val panelAnswered: Boolean = false,
    /** A voice session listens (or a wake could not start one): the request line shows what is being said. */
    val voiceShown: Boolean = false,
    /** Why the last wake could not listen, shown on the request line. */
    val voiceFailure: String? = null,
    /** The card opened fresh to listen; this labels the last result behind its 上次 chip. */
    val lastResult: String? = null,
    /** A result a new request replaced before it was read, behind its 上一个结果 chip. */
    val previousResult: String? = null,
    /** That replaced result is on the card now (its chip reads 回到当前). */
    val showingPrevious: Boolean = false,
    /** Delegated tasks under way, and settled ones waiting to be spoken, for the tray. */
    val delegations: List<DebugDelegationView> = emptyList(),
    /** When this state was made, for the tray's elapsed times. */
    val nowMillis: Long = 0L,
)

internal class DebugOverlayActions(
    val onDrag: (Float, Float) -> Unit,
    val onClose: () -> Unit,
    val onPage: () -> Unit,
    val onStop: () -> Unit,
    val onToggleInput: () -> Unit,
    val onToggleDetails: () -> Unit,
    val onToggleTool: (Long) -> Unit,
    val onSubmit: (String) -> Unit,
    val onPick: (DebugDisplayChoice) -> Unit,
    /** A confirmed slider value, as the reply the user would say ("设为30%"). */
    val onPanel: (String) -> Unit = {},
    /** The card's size for its new content, reported before it animates there; the window grows to fit at once. */
    val onCardTarget: (IntSize) -> Unit,
    /** The card's size once its animation ends; the window shrinks to it so empty glass never blocks the map. */
    val onCardSettled: (IntSize) -> Unit,
    val onTogglePicture: () -> Unit = {},
    /** Where the header's 小博 sits: its centre in window pixels. */
    val onMascotPlaced: (Offset) -> Unit = {},
    /** The card finished unfolding (true) or folding away (false). */
    val onRevealSettled: (Boolean) -> Unit = {},
    /** The 上次 chip: show the last result after all. */
    val onLastResult: () -> Unit = {},
    /** The 上一个结果 / 回到当前 chip. */
    val onPreviousResult: () -> Unit = {},
    /** A tray line's 查看: speak the delegation report that waits for the user to finish reading. */
    val onReleaseReport: () -> Unit = {},
)

/**
 * The assistant as a glass card over the map: 小博 acting out its state and one line of it, what was asked, what the
 * assistant said in large type, and its picks as tiles. Everything technical (mode, timing, plan, tools) waits
 * behind the info button. The device reads mostly by ear, so the screen carries what is easier seen than heard.
 */
@Composable
internal fun DebugOverlayStage(hud: DebugOverlayHud, ui: DebugOverlayUi, actions: DebugOverlayActions, mascot: MascotPose) {
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density * ui.scale, base.fontScale)) {
        MaterialTheme(colorScheme = OverlayColorScheme) { GlassCard(hud, ui, actions, mascot) }
    }
}

/**
 * Size changes animate inside the window, never as window relayouts: the content is laid out at its final size
 * (and reports it, so the window can grow once), while the glass around it springs from the old size to the new
 * one and clips. Laying out at the final width means text never re-wraps frame by frame.
 */
@Composable
private fun GlassCard(hud: DebugOverlayHud, ui: DebugOverlayUi, actions: DebugOverlayActions, mascot: MascotPose) {
    val mood = hud.mood(ui)
    Box(
        Modifier
            // Measure past the window's current bounds; the window catches up from the reported target. Hung from the
            // top, like the window itself, so a window resize never moves the card: it only grows and shrinks below.
            .wrapContentSize(Alignment.TopCenter, unbounded = true)
            .drawWithContent { if (ui.cardDrawn) drawContent() }
            .padding(STAGE_MARGIN)
            .reveal(ui.cardShown, actions.onRevealSettled)
            .clip(CardShape)
            .background(OverlayPalette.glass)
            // A faint aura from 小博's corner tints the glass with the assistant's state.
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(MASCOT_COLOR.copy(alpha = 0.20f), Color.Transparent),
                        center = Offset(size.width * 0.08f, 0f),
                        radius = size.width * 0.7f,
                    ),
                )
            }
            .border(1.dp, OverlayPalette.edge, CardShape)
            .animateContentSize(CardMotion) { _, settled -> actions.onCardSettled(settled) },
    ) {
        // Behind the content and inside the glass: the aura rises from the bottom while voice is on or 小博 speaks.
        if (ui.cardDrawn) VoiceAura(ui.voiceShown, Modifier.matchParentSize())
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                // An enlarged picture is all the card shows, so the glass wraps it instead of its width class.
                .requiredWidth(if (ui.pictureExpanded) ui.expandedPictureSide() + CARD_PADDING_HORIZONTAL * 2 else ui.cardWidth)
                .heightIn(max = ui.maxHeight)
                .onSizeChanged(actions.onCardTarget)
                // Its own layer: while the glass springs to a new size, text and tiles are not re-recorded per frame.
                .graphicsLayer()
                .padding(horizontal = CARD_PADDING_HORIZONTAL, vertical = CARD_PADDING_VERTICAL),
        ) {
            Header(hud, ui, mood, actions, mascot)
            // While voice is on, the request line shows what is being said; the card keeps its own look.
            if (ui.voiceShown) OverlayVoiceAsked(hud.requestPreview, ui.voiceFailure)
            else hud.requestPreview?.takeIf { it.isNotBlank() }?.let { Asked(it) }
            Body(hud, ui, actions, Modifier.weight(1f, fill = false))
            CardFooter(hud, ui, actions)
        }
    }
}

/** Under the body: the way back to results, subagents and delegations under way, then the details and input drawers. */
@Composable
private fun CardFooter(hud: DebugOverlayHud, ui: DebugOverlayUi, actions: DebugOverlayActions) {
    OverlayResultChips(ui, actions)
    if (!ui.clientMode) OverlaySubagentLanes(turnDone = hud.phase != DebugOverlayPhase.LIVE)
    OverlayDelegationTray(ui.delegations, ui.nowMillis, actions.onReleaseReport)
    // Fades only: a height that grows frame by frame would make the card chase it; the card springs instead.
    AnimatedVisibility(visible = ui.showDetails, enter = fadeIn(), exit = fadeOut()) { Details(hud, ui, actions) }
    AnimatedVisibility(visible = ui.showInput, enter = fadeIn(), exit = fadeOut()) { InputBar(actions.onSubmit) }
}

/**
 * The card unfolds out of 小博 when the overlay opens and folds back into it when it closes: it grows from 小博's
 * centre, so 小博 (drawn on top by the bubble meanwhile) stays put while the glass opens around it.
 */
@Composable
private fun Modifier.reveal(shown: Boolean, onSettled: (Boolean) -> Unit): Modifier {
    val unfold by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = if (shown) spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow) else tween(240, easing = FastOutSlowInEasing),
        label = "reveal",
        finishedListener = { onSettled(it == 1f) },
    )
    return graphicsLayer {
        val grown = FOLDED_SCALE + (1f - FOLDED_SCALE) * unfold
        alpha = (unfold * 1.8f).coerceIn(0f, 1f)
        scaleX = grown
        scaleY = grown
        transformOrigin = TransformOrigin(
            (CARD_PADDING_HORIZONTAL + MASCOT_BOX / 2).toPx() / size.width,
            (CARD_PADDING_VERTICAL + MASCOT_BOX / 2).toPx() / size.height,
        )
    }
}

@Composable
private fun Header(hud: DebugOverlayHud, ui: DebugOverlayUi, mood: Mood, actions: DebugOverlayActions, mascot: MascotPose) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    actions.onDrag(drag.x, drag.y)
                }
            },
    ) {
        // 小博's seat stays laid out (and reported) even while 小博 itself is drawn by another window.
        Box(
            Modifier
                .size(MASCOT_BOX)
                .onGloballyPositioned { placed ->
                    actions.onMascotPlaced(placed.localToWindow(Offset(placed.size.width / 2f, placed.size.height / 2f)))
                }
                // A tap, not a press: a drag that starts on 小博 still moves the card.
                .pointerInput(mascot) { detectTapGestures { mascot.react(MascotReaction.GIGGLE) } },
        ) {
            if (ui.cardDrawn) Mascot(mascot, mood.action, driving = ui.mascotDriving)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("小博", color = OverlayPalette.faint, fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.sp)
            // State changes roll in from below rather than snapping.
            AnimatedContent(
                targetState = hud.statusLine(ui),
                transitionSpec = {
                    (fadeIn(tween(240)) + slideInVertically(tween(240)) { it / 2 }) togetherWith
                        (fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 2 })
                },
                label = "status",
            ) { status ->
                Text(status, color = OverlayPalette.text, fontSize = 19.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.weight(1f))
        if (ui.stopVisible) StopButton(actions.onStop)
        GlassIcon(Icons.Filled.Edit, "输入", tint = if (ui.pendingQuestion || ui.showInput) OverlayPalette.glow else OverlayPalette.secondary, onClick = actions.onToggleInput)
        GlassIcon(Icons.Filled.Info, "详情", tint = if (ui.showDetails) OverlayPalette.glow else OverlayPalette.secondary, onClick = actions.onToggleDetails)
        // A portrait card has no room for five: 打开页面 waits until the run stops.
        if (!ui.stopVisible || ui.cardWidth >= ALL_HEADER_ICONS_WIDTH) GlassIcon(Icons.AutoMirrored.Filled.ExitToApp, "打开页面", onClick = actions.onPage)
        GlassIcon(Icons.Filled.Close, "收起", onClick = actions.onClose)
    }
}

@Composable
private fun Asked(request: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "“$request”",
            color = OverlayPalette.secondary,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Body(hud: DebugOverlayHud, ui: DebugOverlayUi, actions: DebugOverlayActions, modifier: Modifier) {
    // The card grows to its content and widens instead of scrolling; only content taller than the room above the
    // dock still scrolls, as a last resort.
    val live = hud.phase == DebugOverlayPhase.LIVE
    val picking = hud.choices.isNotEmpty() && !ui.choicesAnswered
    // A pending panel, like picks, comes before the summary: its shared confirm must not drop below the dock.
    val widgets = picking || (hud.controls.isNotEmpty() && !ui.panelAnswered)
    val step = if (live) hud.currentStep?.stepPhrase() else null
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
    ) {
        val picture = hud.picture
        if (picture != null) {
            Picture(picture, hud.resultText, if (ui.pictureExpanded) ui.expandedPictureSide() else PICTURE_SIZE, actions.onTogglePicture)
        } else if (hud.resultText.isNotBlank()) {
            // With a card the reply was spoken and the card holds the detail (owner 2026-10-17: the full reply left the
            // card too little room): a caption of a few lines, the rest a tap away.
            val captioned = widgets || hud.cards.isNotEmpty()
            var opened by remember(hud.cards) { mutableStateOf(false) }
            // One call site for the hero across phases, so streamed text keeps revealing where it left off.
            Hero(
                // Three capped lines would spend one on a paragraph gap and show a lone "…".
                if (captioned && !opened) hud.resultText.replace(PARAGRAPH_GAP, "\n") else hud.resultText,
                OverlayPalette.text,
                // While picking or sliding, the controls matter most and the summary was already spoken.
                maxLines = when { widgets -> 3; captioned && !opened -> CARD_CAPTION_LINES; else -> Int.MAX_VALUE },
                compact = captioned,
                modifier = if (captioned && !widgets) Modifier.clickable { opened = !opened } else Modifier,
            )
            step?.let { Shimmer(it, 15.sp) }
        } else if (live) {
            // The header already says it is working; the fallback only fills a body that would otherwise be empty.
            (step ?: "正在处理".takeIf { ui.chosen == null })?.let { Shimmer(it, 22.sp) }
        }
        Cards(hud.cards)
        hud.controls.takeIf { it.isNotEmpty() && !ui.panelAnswered }?.let { DebugOverlayA2uiPanel(it, actions.onPanel) }
        val chosen = ui.chosen
        when {
            picking -> Picks(hud.choices, hud.choiceIcons, ui.size == DebugOverlaySize.WIDE, actions.onPick)
            // The other tiles leave and the glass springs shorter around the tapped one, which stays while the
            // assistant acts on it, so the user sees their choice being followed.
            chosen != null && (ui.choicesAnswered || live) -> Chosen(chosen)
        }
    }
}

/** The reply's cards, two to a row when they fit side by side ([overlayCardRows]); a lone last one keeps its column. */
@Composable
private fun Cards(cards: List<DebugCard>) {
    val rows = cards.overlayCardRows()
    val paired = rows.any { it.size > 1 }
    rows.forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            row.forEach { DebugOverlayA2uiCard(it, Modifier.weight(1f)) }
            if (paired) repeat(CARDS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/**
 * A generated picture leads the card: the frame on the left, the assistant's words (or what was asked for) on the
 * right. While drawing, light drifts across the empty frame; the finished picture develops into it.
 */
@Composable
private fun Picture(picture: DebugOverlayPicture, caption: String, side: Dp, onToggle: () -> Unit) {
    val expanded = side != PICTURE_SIZE
    // One Row in both states keeps the frame at the same call site, so its zoom remembers the previous side.
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        PictureFrame(picture, side, onToggle.takeIf { picture.reference != null })
        if (!expanded) PictureWords(picture, caption, Modifier.weight(1f))
    }
}

/**
 * The frame lays out at its new side at once (one window resize), then zooms visually from the old side,
 * anchored at its top-left corner where both sizes start.
 */
@Composable
private fun PictureFrame(picture: DebugOverlayPicture, side: Dp, onToggle: (() -> Unit)?) {
    val last = remember { SideMemo(side) }
    val zoom = remember(side) { Animatable(last.side / side) }
    SideEffect { last.side = side }
    LaunchedEffect(side) { zoom.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) }
    Box(
        Modifier
            .size(side)
            .graphicsLayer {
                scaleX = zoom.value
                scaleY = zoom.value
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .clip(PictureShape)
            .drawBehind { drawRect(PICTURE_BASE) }
            .border(1.dp, OverlayPalette.hairline, PictureShape)
            .then(if (onToggle != null) Modifier.clickable(onClickLabel = "放大或收起图片", onClick = onToggle) else Modifier),
    ) {
        val reference = picture.reference
        if (reference == null) DrawingLight(Modifier.matchParentSize()) else Developing(reference)
    }
}

private class SideMemo(var side: Dp)

/** The largest square that fits the card's width and the room above the dock, never smaller than the normal frame. */
private fun DebugOverlayUi.expandedPictureSide(): Dp {
    val byWidth = cardWidth - CARD_PADDING_HORIZONTAL * 2
    val byHeight = if (maxHeight == Dp.Unspecified) byWidth else maxHeight - EXPANDED_PICTURE_CHROME
    return minOf(byWidth, byHeight).coerceAtLeast(PICTURE_SIZE)
}

/** Beside the frame: "正在作画" while drawing, then the assistant's words, or the prompt when there are none. */
@Composable
private fun PictureWords(picture: DebugOverlayPicture, caption: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (picture.reference == null) {
            Shimmer("正在作画", 22.sp)
            // The prompt arrives with the raw tool transcript; until then the wait is described instead.
            if (picture.prompt.isBlank()) Text("通常需要 15–30 秒", color = OverlayPalette.faint, fontSize = 15.sp)
        }
        if (picture.reference != null && caption.isNotBlank()) {
            Hero(caption, OverlayPalette.text, maxLines = 6, compact = true)
        } else if (picture.prompt.isNotBlank()) {
            Text("“${picture.prompt}”", color = OverlayPalette.secondary, fontSize = 16.sp, lineHeight = 24.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
        }
        if (picture.reference != null) Text("点图片放大", color = OverlayPalette.faint, fontSize = 13.sp)
    }
}

/**
 * A slanted band of light crossing the empty frame; read in the draw phase of its own layer only. Positions are
 * measured along the band's own direction, and each sweep starts and ends with the band wholly outside the frame,
 * so the loop restarts unseen. (Measured horizontally, a slanted band still lit a corner at both ends and popped.)
 */
@Composable
private fun DrawingLight(modifier: Modifier) {
    val sweep by rememberInfiniteTransition(label = "drawing").animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(DRAWING_SWEEP_MS, easing = LinearEasing)),
        label = "sweep",
    )
    Canvas(modifier.graphicsLayer()) {
        // The frame spans 0..far along the unit direction (SWEEP_X, SWEEP_Y).
        val far = size.width * SWEEP_X + size.height * SWEEP_Y
        val band = far * SWEEP_BAND_SHARE
        val centre = -band + sweep * (far + 2 * band)
        val from = centre - band / 2
        val to = centre + band / 2
        drawRect(
            Brush.linearGradient(
                listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0f)),
                start = Offset(SWEEP_X * from, SWEEP_Y * from),
                end = Offset(SWEEP_X * to, SWEEP_Y * to),
            ),
        )
    }
}

/** The picture fades in from a slight zoom once decoded, like a print developing. */
@Composable
private fun Developing(reference: String) {
    val bitmap by rememberPicture(reference, PICTURE_PX)
    val develop = remember(reference) { Animatable(0f) }
    LaunchedEffect(bitmap) { if (bitmap != null) develop.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    bitmap?.let { image ->
        Image(
            image,
            contentDescription = "生成的图片",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                alpha = develop.value
                val zoom = 1.08f - 0.08f * develop.value
                scaleX = zoom
                scaleY = zoom
            },
        )
    }
}

/**
 * What the assistant said, revealed with a soft leading edge instead of landing as a block. The text is laid out
 * at full length from the start, so the card's size never moves while it reveals; text that only grew (streamed,
 * or re-flowed for picking) continues from where the edge was rather than starting over.
 */
@Composable
private fun Hero(text: String, color: Color, maxLines: Int = Int.MAX_VALUE, compact: Boolean = false, modifier: Modifier = Modifier) {
    val reveal = remember { Animatable(0f) }
    var revealing by remember { mutableStateOf("") }
    LaunchedEffect(text) {
        if (!text.continues(revealing)) reveal.snapTo(0f)
        revealing = text
        val target = text.length + REVEAL_EDGE_CHARS
        if (reveal.value >= target) {
            reveal.snapTo(target)
        } else {
            val ms = ((target - reveal.value) * REVEAL_MS_PER_CHAR).toInt().coerceIn(REVEAL_MIN_MS, REVEAL_MAX_MS)
            reveal.animateTo(target, tween(ms, easing = LinearEasing))
        }
    }
    // Half-character steps are motion enough and bound recompositions to twice the text's length.
    val frontier by remember { derivedStateOf { (reveal.value * 2).toInt() / 2f } }
    val shown = remember(text, frontier, color) { revealed(text, frontier, color) }
    Text(
        shown,
        fontSize = if (compact) 19.sp else 21.sp,
        lineHeight = if (compact) 29.sp else 33.sp,
        fontWeight = FontWeight.Normal,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Characters before the frontier are solid, a few fade in at its edge, the rest wait transparent. */
private fun revealed(text: String, frontier: Float, color: Color): AnnotatedString = buildAnnotatedString {
    val solid = (frontier - REVEAL_EDGE_CHARS).toInt().coerceIn(0, text.length)
    val hidden = frontier.toInt().coerceIn(solid, text.length)
    withStyle(SpanStyle(color = color)) { append(text, 0, solid) }
    for (index in solid until hidden) {
        val alpha = ((frontier - index) / REVEAL_EDGE_CHARS).coerceIn(0f, 1f)
        withStyle(SpanStyle(color = color.copy(alpha = color.alpha * alpha))) { append(text[index]) }
    }
    withStyle(SpanStyle(color = Color.Transparent)) { append(text, hidden, text.length) }
}

/** Whether this is [previous] with more added, ignoring whitespace that was only re-flowed. */
private fun String.continues(previous: String): Boolean =
    filterNot(Char::isWhitespace).startsWith(previous.filterNot(Char::isWhitespace))

/** The chosen pick, confirmed: gradient glass, a check, its name and detail. */
@Composable
private fun Chosen(choice: DebugDisplayChoice) {
    val appear = remember(choice) { Animatable(0f) }
    LaunchedEffect(choice) { appear.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) }
    val (title, detail) = DebugA2uiChoice(choice.label, choice.recommended).titleAndDetail()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = appear.value
                val grown = 0.94f + 0.06f * appear.value
                scaleX = grown
                scaleY = grown
            }
            .background(OverlayPalette.chosenFill, ChosenShape)
            .border(1.5.dp, OverlayPalette.signature, ChosenShape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(38.dp).background(OverlayPalette.signature, CircleShape),
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = OverlayPalette.onSignature, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("已选 · 第${choice.ordinal}个", color = OverlayPalette.glow, fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
            Text(title, color = OverlayPalette.text, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, color = OverlayPalette.secondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun Picks(choices: List<DebugDisplayChoice>, icons: Map<String, String>, wide: Boolean, onPick: (DebugDisplayChoice) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("选一个", color = OverlayPalette.faint, fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Text("说「第二个」也可以", color = OverlayPalette.faint, fontSize = 13.sp)
        }
        if (wide && choices.size >= OVERLAY_GRID_MIN_CHOICES) {
            // Column-major, like a numbered list read top to bottom: 1–4 left, 5–8 right.
            val split = (choices.size + 1) / 2
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DebugOverlayA2uiChoices(choices.take(split), onPick, Modifier.weight(1f), icons)
                DebugOverlayA2uiChoices(choices.drop(split), onPick, Modifier.weight(1f), icons)
            }
        } else {
            DebugOverlayA2uiChoices(choices, onPick, icons = icons)
        }
    }
}

@Composable
private fun Details(hud: DebugOverlayHud, ui: DebugOverlayUi, actions: DebugOverlayActions) {
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(OverlayPalette.glassInset, InsetShape)
            .border(1.dp, OverlayPalette.hairline, InsetShape)
            .padding(18.dp),
    ) {
        val meta = listOf(
            if (ui.clientMode) "A2A 客户端" else "本地运行",
            hud.elapsedLabel.takeIf { it != "—" },
            hud.providerLabel.takeIf { it.isNotBlank() },
        ).filterNotNull().joinToString("  ·  ")
        Text(meta, color = OverlayPalette.faint, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Panel(if (hud.planTitle == "Speech") "播报" else "计划", Modifier.weight(1f)) {
                if (hud.planRows.isEmpty()) Text(hud.planEmptyText ?: "暂无", color = OverlayPalette.faint, fontSize = 14.sp)
                hud.planRows.forEach { row -> PlanLine(row) }
            }
            Panel("工具调用", Modifier.weight(1f)) {
                if (hud.toolLines.isEmpty()) Text("暂无", color = OverlayPalette.faint, fontSize = 14.sp)
                hud.toolLines.asReversed().forEach { line ->
                    ToolLine(line, expanded = line.id in ui.expandedToolIds) { actions.onToggleTool(line.id) }
                }
            }
        }
    }
}

@Composable
private fun InputBar(onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    fun send() {
        val value = text.trim()
        if (value.isNotEmpty()) {
            text = ""
            onSubmit(value)
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(OverlayPalette.tile, InputShape)
            .border(1.dp, OverlayPalette.tileEdge, InputShape)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) Text("想让我做什么？", color = OverlayPalette.faint, fontSize = 18.sp)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = OverlayPalette.text, fontSize = 18.sp),
                cursorBrush = SolidColor(OverlayPalette.glow),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                maxLines = 2,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        SendButton(enabled = text.isNotBlank(), onClick = ::send)
    }
    FocusOnceWindowFocused(focus)
}

@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(if (enabled) OverlayPalette.signature else SolidColor(OverlayPalette.tile))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Send,
            contentDescription = "发送",
            tint = if (enabled) OverlayPalette.onSignature else OverlayPalette.faint,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * The card window becomes focusable only while the input bar is open, a moment after it composes: ask for focus and
 * the keyboard once the window has it, or the request lands on a window that cannot take input yet.
 */
@Composable
private fun FocusOnceWindowFocused(focus: FocusRequester) {
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(windowFocused) {
        if (windowFocused) {
            focus.requestFocus()
            keyboard?.show()
        }
    }
}

/** A step line that breathes while waiting, so it reads as progress without a bar; alpha is a layer property. */
@Composable
private fun Shimmer(text: String, fontSize: TextUnit) {
    val glow by rememberInfiniteTransition(label = "step").animateFloat(
        0.5f,
        1f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "glow",
    )
    Text(
        text,
        color = OverlayPalette.text,
        fontSize = fontSize,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.graphicsLayer { alpha = glow },
    )
}

@Composable
private fun GlassIcon(icon: ImageVector, description: String, tint: Color = OverlayPalette.secondary, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(44.dp).clip(CircleShape).background(OverlayPalette.tile).clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(OverlayPalette.warm.copy(alpha = 0.16f))
            .border(1.dp, OverlayPalette.warm.copy(alpha = 0.5f), CircleShape)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.size(13.dp).background(OverlayPalette.warm, RoundedCornerShape(3.dp)))
    }
}

@Composable
private fun Panel(title: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        Text(title, color = OverlayPalette.faint, fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.sp)
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
            content = content,
        )
    }
}

@Composable
private fun PlanLine(row: DebugOverlayBoardRow) {
    val tone = when (row.planStatus) {
        DebugOverlayPlanStatus.ACTIVE -> OverlayPalette.glow
        DebugOverlayPlanStatus.DONE -> OverlayPalette.live
        DebugOverlayPlanStatus.FAILED -> OverlayPalette.warm
        else -> OverlayPalette.faint
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(7.dp).background(tone, CircleShape))
        Text(row.text, color = OverlayPalette.secondary, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ToolLine(line: DebugOverlayToolLine, expanded: Boolean, onToggle: () -> Unit) {
    val tone = when {
        line.running -> OverlayPalette.glow
        line.isError -> OverlayPalette.warm
        else -> OverlayPalette.live
    }
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onToggle).padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(7.dp).background(tone, CircleShape))
            Text(
                line.name,
                color = OverlayPalette.secondary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            line.durationLabel?.let { Text(it, color = OverlayPalette.faint, fontSize = 12.sp) }
        }
        if (expanded) {
            line.argumentsJson?.takeIf { it.isNotBlank() }?.let { Payload(it) }
            line.resultJson?.takeIf { it.isNotBlank() }?.let { Payload(it) }
        }
    }
}

@Composable
private fun Payload(raw: String) {
    Text(
        prettyToolPayload(raw),
        color = OverlayPalette.faint,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 12,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 17.dp, top = 6.dp),
    )
}

/** How the assistant looks for a state: 小博's act, whose colour also tints the glass. */
private class Mood(val action: MascotAction)

private fun DebugOverlayHud.mood(ui: DebugOverlayUi): Mood = Mood(ui.mascotPreview ?: mascotAction(ui))

private fun DebugOverlayHud.statusLine(ui: DebugOverlayUi): String = when {
    choices.isNotEmpty() && ui.choicesAnswered -> "收到，正在处理"
    choices.isNotEmpty() -> "请选择"
    controls.isNotEmpty() && ui.panelAnswered -> "收到，正在处理"
    controls.isNotEmpty() -> "调好点确定"
    phase == DebugOverlayPhase.LIVE && headline.startsWith("等待回答") -> "等你回答"
    phase == DebugOverlayPhase.LIVE -> "正在处理"
    phase == DebugOverlayPhase.DONE && headline == "等待你的回复" -> "等你回复"
    phase == DebugOverlayPhase.DONE && headline == PARTLY_DONE_HEADLINE -> "部分完成"
    phase == DebugOverlayPhase.DONE -> "已完成"
    phase == DebugOverlayPhase.FAIL -> "没有完成"
    phase == DebugOverlayPhase.STOP -> "已停止"
    ui.voiceShown -> "我在听"
    else -> "随时为你服务"
}

/**
 * A step in words a user would use. Tool identifiers ("Running navigation_poi_search") become the kind of
 * work being done; natural text (a plan item, a client status) stays as written; a bare "Working" says nothing new.
 */
internal fun String.stepPhrase(): String? {
    val step = removePrefix("Running ").trim()
    if (step.isEmpty() || step.equals("Working", ignoreCase = true)) return null
    if (step.equals("Thinking", ignoreCase = true)) return "正在思考"
    if (!TOOL_IDENTIFIER.matches(step)) return step
    val tool = step.lowercase()
    return STEP_PHRASES.firstOrNull { (keys, _) -> keys.any { it in tool } }?.second ?: "正在处理"
}

private val TOOL_IDENTIFIER = Regex("""[A-Za-z0-9_.:\-]+""")
private val STEP_PHRASES = listOf(
    listOf("search_tools") to "正在准备",
    listOf("user_memory") to "正在记下来",
    listOf("plan_manage") to "正在安排计划",
    listOf("image_gen", "draw") to "正在作画",
    listOf("navigation", "poi", "route", "map") to "正在查询导航",
    listOf("media", "music", "video", "radio", "audio") to "正在查找音乐",
    listOf("hvac", "light", "volume", "brightness", "setting") to "正在调整设置",
    listOf("system", "setting", "brightness", "volume", "display") to "正在调整设置",
    listOf("read_file", "list_dir", "grep") to "正在查阅资料",
    listOf("update_todo") to "正在规划步骤",
    listOf("web") to "正在联网查询",
    listOf("a2a") to "正在联系其他助手",
)

private val CardShape = RoundedCornerShape(32.dp)
/** Room around the glass inside the window, in the stage's dp; the service adds it to the window size. */
internal val STAGE_MARGIN = 6.dp
/** Critically damped: the card settles without overshoot, which on a moving card reads as wobble. */
private val CardMotion = spring<IntSize>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
private val InsetShape = RoundedCornerShape(20.dp)
private val InputShape = RoundedCornerShape(26.dp)
private val ChosenShape = RoundedCornerShape(20.dp)
private val PictureShape = RoundedCornerShape(22.dp)
/** Large enough to read at a glance from arm's length, small enough to leave the words beside it. */
private val PICTURE_SIZE = 320.dp
/** The card's own horizontal padding, as set on its content column. */
private val CARD_PADDING_HORIZONTAL = 28.dp
/** The narrowest card whose header holds every button at once, 停止 among them; the portrait card is 420. */
private val ALL_HEADER_ICONS_WIDTH = 480.dp
private val CARD_PADDING_VERTICAL = 24.dp
/** How small the card is when folded into 小博, before it fades out entirely. */
private const val FOLDED_SCALE = 0.2f
/** Header, quoted request, gaps and padding above and below an expanded picture. */
private val EXPANDED_PICTURE_CHROME = 200.dp
/** Decode size: the frame is 432px at the device's 1.35x zoom, so a 1024px source halves cleanly. */
private const val PICTURE_PX = 512
/** The drawing light: one sweep including its unseen run-up and run-out; a unit direction slanted about 27°. */
private const val DRAWING_SWEEP_MS = 1800
private const val SWEEP_X = 0.894f
private const val SWEEP_Y = 0.447f
private const val SWEEP_BAND_SHARE = 0.35f
/** The empty frame: the signature colours as a faint wash. */
private val PICTURE_BASE = Brush.linearGradient(
    listOf(OverlayPalette.sage.copy(alpha = 0.20f), OverlayPalette.glow.copy(alpha = 0.10f), OverlayPalette.heather.copy(alpha = 0.18f)),
)
private val PARAGRAPH_GAP = Regex("""\n\s*\n""")
/** A card's reply caption: it was spoken, the card says the rest. */
private const val CARD_CAPTION_LINES = 3

/** About a fast reader's pace: a short reply lands in under half a second, a long one never takes over two. */
private const val REVEAL_MS_PER_CHAR = 28
private const val REVEAL_MIN_MS = 300
private const val REVEAL_MAX_MS = 2200
private const val REVEAL_EDGE_CHARS = 4f

private val OverlayColorScheme = darkColorScheme(
    primary = OverlayPalette.sage,
    onPrimary = OverlayPalette.onSignature,
    surface = Color.Transparent,
    onSurface = OverlayPalette.text,
    surfaceVariant = Color.Transparent,
    onSurfaceVariant = OverlayPalette.secondary,
    background = Color.Transparent,
    onBackground = OverlayPalette.text,
    outline = OverlayPalette.hairline,
)
