package cc.noharry.bothy.app.console
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.DEFAULT_DEBUG_VARIANT_ID
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.ProviderEditState
import cc.noharry.agent.appserver.protocol.RunActivity
import cc.noharry.agent.appserver.protocol.RunResult
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.variantChoices
import cc.noharry.agent.appserver.protocol.view.headerWords
import cc.noharry.agent.appserver.protocol.view.pickableReply
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.a2ui.QuestionCards
import cc.noharry.bothy.app.console.ui.PopupDot
import cc.noharry.bothy.app.console.ui.PopupMenu
import cc.noharry.bothy.app.console.ui.PopupMenuItem
import cc.noharry.bothy.app.console.ui.PopupSectionLabel
import cc.noharry.bothy.app.console.ui.SelectorChip
import cc.noharry.bothy.app.console.ui.ShimmerText
import cc.noharry.bothy.app.console.ui.StatusDot
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.app.usage.ContextUsageIndicator
import cc.noharry.bothy.app.voice.VoiceMicButton
import cc.noharry.bothy.app.voice.VoiceStrip
import cc.noharry.bothy.protocol.VoiceRequests
import cc.noharry.bothy.protocol.voice
import kotlinx.coroutines.delay

@Composable
internal fun ConversationPane(
    state: ServerState,
    pendingInput: String,
    onPendingInputConsumed: () -> Unit,
    actions: ConsoleActions,
    modifier: Modifier = Modifier,
    useCondensedChrome: Boolean = false,
) {
    var input by remember { mutableStateOf("") }
    LaunchedEffect(pendingInput) {
        if (pendingInput.isNotBlank()) {
            input = pendingInput
            onPendingInputConsumed()
        }
    }
    val submit = {
        val text = input.trim()
        if (text.isNotEmpty()) {
            input = ""
            if (state.isRunning) actions.steer(text) else actions.submit(text)
        }
    }
    BoxWithConstraints(modifier) {
        // A phone held sideways leaves the pane little height: the composer folds to two rows.
        val short = maxHeight < SHORT_PANE_HEIGHT
        Column(Modifier.fillMaxSize()) {
            QuestionCards(state, actions)
            if (useCondensedChrome) {
                CondensedConversationPane(input, { input = it }, submit, state, actions, Modifier.weight(1f))
            } else {
                RegularConversationPane(input, { input = it }, submit, state, actions, short, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RegularConversationPane(
    input: String,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    state: ServerState,
    actions: ConsoleActions,
    short: Boolean,
    modifier: Modifier,
) {
    PanelBox(stringResource(R.string.console_conversation), modifier) {
        WakeMigrationNote()
        ConversationStatus(state)
        Spacer(Modifier.height(Dimens.spaceSm))
        val listState = rememberLazyListState()
        val renderItems = remember(state.items) { state.items.toRenderItems() }
        // reverseLayout: newest grows at the bottom; short threads sit above the composer
        // instead of jumping to the top of the viewport (Codex / Claude Code feel).
        val bottomFirstItems = remember(renderItems) { renderItems.asReversed() }
        // Numbered oldest first, so a new repeat never renames the rows already shown.
        val bottomFirstKeys = remember(bottomFirstItems) { bottomFirstItems.asReversed().map { it.key }.uniqueKeys().asReversed() }
        val streamingThinkingId = remember(state.items, state.isRunning) {
            streamingThinkingItemId(state.items, state.isRunning)
        }
        // The same open picks as the overlay card, drawn under the reply that offers them.
        val pickable = remember(state) { state.pickableReply() }
        val latestScrollKey = renderItems.lastOrNull()?.autoScrollKey(actions.isFolded)
        LaunchedEffect(latestScrollKey, state.isRunning) {
            if (bottomFirstItems.isNotEmpty()) {
                // Index 0 is the visual bottom when reverseLayout is true.
                listState.animateScrollToItem(0)
            }
        }
        // Dividers sit before turns 2..N in stream order, so a sequential scan numbers them.
        val turnNumbers = remember(renderItems) {
            val numbers = HashMap<String, Int>()
            var turn = 1
            renderItems.forEach { item ->
                if (item is ConversationRenderItem.TurnDivider) {
                    turn += 1
                    numbers[item.key] = turn
                }
            }
            numbers
        }
        if (renderItems.isEmpty() && !state.isRunning) {
            ConversationEmptyState(Modifier.weight(1f).fillMaxWidth())
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
            ) {
                itemsIndexed(bottomFirstItems, key = { index, _ -> bottomFirstKeys[index] }) { _, item ->
                    ConversationRenderRow(
                        item = item,
                        actions = actions,
                        streamingThinkingId = streamingThinkingId,
                        turnNumber = turnNumbers[item.key] ?: 0,
                        pickable = pickable,
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = state.isRunning || state.lastRun != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Column {
                Spacer(Modifier.height(6.dp))
                if (state.isRunning) {
                    ActiveStatusBar(state)
                } else {
                    CompletedStatusBar(state.lastRun)
                }
            }
        }
        Spacer(Modifier.height(Dimens.spaceSm))
        ConversationInputBar(
            text = input,
            onTextChange = onInputChange,
            onSubmit = onSubmit,
            state = state,
            actions = actions,
            short = short,
        )
    }
}

@Composable
private fun ConversationEmptyState(modifier: Modifier = Modifier) {
    val colors = ConsoleColors
    Column(
        modifier = modifier.padding(vertical = Dimens.spaceLg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 小博 by its bothy at dusk: the same picture as the README's, so a fresh session opens on the project's face.
        Image(
            painter = painterResource(R.drawable.bothy_welcome),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .widthIn(max = WELCOME_MAX_WIDTH)
                .fillMaxWidth()
                .aspectRatio(WELCOME_ASPECT)
                .clip(RoundedCornerShape(Dimens.radiusLg)),
        )
        Spacer(Modifier.height(Dimens.spaceLg))
        Text(text = stringResource(R.string.console_empty_title), style = ConsoleType.hero, color = colors.text)
        Spacer(Modifier.height(Dimens.spaceXs))
        Text(
            text = stringResource(R.string.console_empty_body),
            style = ConsoleType.body,
            color = colors.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = WELCOME_MAX_WIDTH),
        )
    }
}

@Composable
internal fun WakeMigrationNote() {
    val server = LocalServer.current
    if (!server.state.voice.wakeMigrated) return
    Row(
        Modifier.fillMaxWidth().padding(bottom = Dimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.voice_wake_migrated_off),
            style = ConsoleType.bodySmall,
            color = ConsoleColors.text,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { server.send(VoiceRequests.CLEAR_WAKE_MIGRATION, Empty) }) { Text(stringResource(R.string.common_ok)) }
    }
}

private val WELCOME_MAX_WIDTH = 560.dp
private const val WELCOME_ASPECT = 2f

@Composable
private fun CondensedConversationPane(
    input: String,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    state: ServerState,
    actions: ConsoleActions,
    modifier: Modifier,
) {
    val colors = ConsoleColors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = colors.panel,
        border = BorderStroke(1.dp, colors.border),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp)) {
            val showStatus = maxHeight >= CONDENSED_STATUS_MIN_HEIGHT
            Column(Modifier.fillMaxSize()) {
                WakeMigrationNote()
                if (showStatus) {
                    ConversationStatus(state)
                    Spacer(Modifier.height(2.dp))
                }
                MessageField(input, onInputChange, true, Modifier.fillMaxWidth().height(CONDENSED_INPUT_HEIGHT))
                CondensedComposerActions(input, onSubmit, state, actions)
            }
        }
    }
}

@Composable
private fun ConversationStatus(state: ServerState) {
    val colors = ConsoleColors
    // Keep a single quiet meta line; live Working / Worked-for live in the bar above the composer.
    val run = when {
        state.isRunning -> statusHeader(state)
        else -> state.lastRun?.let { runResultText(it) } ?: stringResource(R.string.console_ready)
    }
    val text = "$run · ${state.providerLabel} · ${streamText(state.stream)}"
    Text(
        text = text,
        style = ConsoleType.meta,
        color = colors.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ModelMenu(editor: ProviderEditState, actions: ConsoleActions, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val options = editor.modelOptions.ifEmpty { listOf(editor.model).filter { it.isNotBlank() } }
    val selected = editor.model
    Box(modifier.widthIn(max = MODEL_BUTTON_MAX_WIDTH), contentAlignment = Alignment.BottomEnd) {
        SelectorChip(
            label = selected.ifBlank { stringResource(R.string.console_choose_model) },
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            placeholder = selected.isBlank(),
        )
        PopupMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            minWidth = MODEL_MENU_MIN_WIDTH,
            maxWidth = MODEL_MENU_MAX_WIDTH,
        ) {
            PopupSectionLabel(stringResource(R.string.console_model))
            if (options.isEmpty()) {
                PopupMenuItem(
                    label = stringResource(R.string.console_no_models),
                    selected = false,
                    onClick = { expanded = false },
                )
            } else {
                options.forEach { model ->
                    PopupMenuItem(
                        label = model,
                        selected = model == selected,
                        onClick = {
                            expanded = false
                            actions.selectModel(model)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun VariantMenu(editor: ProviderEditState, actions: ConsoleActions, modifier: Modifier = Modifier) {
    if (editor.variantOptions.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val (options, selected) = editor.variantChoices()
    Box(modifier.widthIn(max = VARIANT_BUTTON_MAX_WIDTH), contentAlignment = Alignment.BottomEnd) {
        SelectorChip(
            label = selected.variantLabel(),
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        )
        PopupMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            minWidth = VARIANT_MENU_MIN_WIDTH,
            maxWidth = VARIANT_MENU_MAX_WIDTH,
        ) {
            PopupSectionLabel(stringResource(R.string.console_variant))
            options.forEach { variant ->
                PopupMenuItem(
                    label = variant.variantLabel(),
                    selected = variant == selected,
                    onClick = {
                        expanded = false
                        actions.selectVariant(variant)
                    },
                )
            }
        }
    }
}

@Composable
private fun ConversationInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    state: ServerState,
    actions: ConsoleActions,
    short: Boolean,
) {
    val colors = ConsoleColors
    // Claude-quiet composer dock: soft panel, weak border; activity only on Steer.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.radiusLg),
        color = colors.elevated,
        border = BorderStroke(1.dp, colors.borderSubtle),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(if (short) Dimens.spaceSm else Dimens.spaceMd)) {
            VoiceStrip()
            if (short) {
                MessageField(text, onTextChange, true, Modifier.fillMaxWidth())
                CondensedComposerActions(text, onSubmit, state, actions)
                return@Column
            }
            MessageField(text, onTextChange, false, Modifier.fillMaxWidth())
            Spacer(Modifier.height(Dimens.spaceSm))
            ConfirmationModeSelector(state.confirmationMode, actions)
            Spacer(Modifier.height(Dimens.spaceSm))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < COMPACT_COMPOSER_WIDTH) {
                    CompactComposerActions(text, onSubmit, state, actions)
                } else {
                    WideComposerActions(text, onSubmit, state, actions)
                }
            }
        }
    }
}

@Composable
internal fun MessageField(
    text: String,
    onTextChange: (String) -> Unit,
    singleLine: Boolean,
    modifier: Modifier,
    placeholder: String = stringResource(R.string.console_message_placeholder),
) {
    val colors = ConsoleColors
    // Borderless field fused into the composer dock; the dock Surface owns the outline.
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = modifier,
        minLines = if (singleLine) 1 else 2,
        maxLines = if (singleLine) 1 else 5,
        singleLine = singleLine,
        textStyle = ConsoleType.body,
        placeholder = { Text(placeholder, style = ConsoleType.body, color = colors.muted) },
        shape = RoundedCornerShape(Dimens.radiusSm),
        colors = outlinedTextFieldColors().copy(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        ),
    )
}

@Composable
private fun CondensedComposerActions(
    text: String,
    onSubmit: () -> Unit,
    state: ServerState,
    actions: ConsoleActions,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(CONDENSED_ACTIONS_HEIGHT)) {
        val showSecondaryActions = maxWidth >= CONDENSED_SECONDARY_ACTIONS_WIDTH
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            ConfirmationModeSelector(
                state.confirmationMode,
                actions,
                Modifier.weight(1.4f),
                showLabel = false,
                compactMenuItems = true,
            )
            if (showSecondaryActions) {
                TextButton(onClick = actions.openOverlay) { Text(stringResource(R.string.console_float)) }
            }
            ContextUsageIndicator(state)
            ModelMenu(state.configEditor, actions, Modifier.weight(1f))
            if (showSecondaryActions) VariantMenu(state.configEditor, actions)
            VoiceMicButton(SUBMIT_BUTTON_SIZE, SUBMIT_ICON_SIZE)
            SubmitButton(text, onSubmit, state.isRunning)
        }
    }
}

@Composable
private fun CompactComposerActions(
    text: String,
    onSubmit: () -> Unit,
    state: ServerState,
    actions: ConsoleActions,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ContextUsageIndicator(state)
            ModelMenu(state.configEditor, actions, Modifier.weight(1f))
            VariantMenu(state.configEditor, actions)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ComposerOverflowMenu(actions)
            Spacer(Modifier.weight(1f))
            if (state.isRunning) TextButton(onClick = actions.stop) { Text(stringResource(R.string.console_stop)) }
            VoiceMicButton(SUBMIT_BUTTON_SIZE, SUBMIT_ICON_SIZE)
            SubmitButton(text, onSubmit, state.isRunning)
        }
    }
}

@Composable
private fun WideComposerActions(
    text: String,
    onSubmit: () -> Unit,
    state: ServerState,
    actions: ConsoleActions,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ComposerOverflowMenu(actions)
        Spacer(Modifier.weight(1f))
        ContextUsageIndicator(state)
        ModelMenu(state.configEditor, actions)
        VariantMenu(state.configEditor, actions)
        if (state.isRunning) TextButton(onClick = actions.stop) { Text(stringResource(R.string.console_stop)) }
        VoiceMicButton(SUBMIT_BUTTON_SIZE, SUBMIT_ICON_SIZE)
        SubmitButton(text, onSubmit, state.isRunning)
    }
}

@Composable
private fun ComposerOverflowMenu(actions: ConsoleActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SelectorChip(label = stringResource(R.string.console_more), onClick = { expanded = true })
        PopupMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            minWidth = 180.dp,
            maxWidth = 240.dp,
        ) {
            PopupSectionLabel(stringResource(R.string.console_actions))
            PopupMenuItem(
                label = stringResource(R.string.console_floating_card),
                selected = false,
                onClick = {
                    expanded = false
                    actions.openOverlay()
                },
            )
            PopupMenuItem(
                label = stringResource(R.string.console_toggle_stream),
                selected = false,
                onClick = {
                    expanded = false
                    actions.toggleStream()
                },
            )
        }
    }
}

@Composable
private fun SubmitButton(text: String, onSubmit: () -> Unit, isRunning: Boolean) {
    val colors = ConsoleColors
    val enabled = text.isNotBlank()
    // Icon-only send button; the Run/Steer contentDescription is load-bearing for adb/UI automation.
    Box(
        modifier = Modifier
            .size(SUBMIT_BUTTON_SIZE)
            .clip(CircleShape)
            .background(if (enabled) colors.accent else colors.accent.copy(alpha = SUBMIT_DISABLED_ALPHA))
            .clickable(enabled = enabled, onClick = onSubmit)
            .semantics { contentDescription = if (isRunning) "Steer" else "Run" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Send,
            contentDescription = null,
            tint = if (colors.isDark) colors.bg else Color.White,
            modifier = Modifier.size(SUBMIT_ICON_SIZE),
        )
    }
}

@Composable
private fun ConfirmationModeSelector(
    selected: ConfirmationMode,
    actions: ConsoleActions,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    compactMenuItems: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = ConsoleColors
    val modeLabel = stringResource(if (compactMenuItems) selected.shortLabelRes() else selected.labelRes())
    Box(modifier.fillMaxWidth()) {
        // Warning modes keep the amber fill/border/dot; others read as a quiet chip.
        SelectorChip(
            label = if (showLabel) stringResource(R.string.console_approval_chip, modeLabel) else modeLabel,
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            leadingDot = if (selected.warning) colors.warning else colors.accent,
            warning = selected.warning,
        )
        PopupMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            minWidth = APPROVAL_MENU_MIN_WIDTH,
            maxWidth = APPROVAL_MENU_MAX_WIDTH,
        ) {
            PopupSectionLabel(stringResource(R.string.console_approval))
            ConfirmationMode.entries.forEach { mode ->
                val modeAccent = if (mode.warning) colors.warning else colors.ink
                PopupMenuItem(
                    label = stringResource(mode.labelRes()),
                    selected = mode == selected,
                    hint = stringResource(mode.menuHint()),
                    accent = modeAccent,
                    compact = compactMenuItems,
                    onClick = {
                        expanded = false
                        actions.selectConfirmationMode(mode)
                    },
                    leading = {
                        PopupDot(active = mode == selected, color = modeAccent)
                    },
                )
            }
        }
    }
}

@StringRes
internal fun ConfirmationMode.menuHint(): Int =
    when (this) {
        ConfirmationMode.ASK_EVERY_TIME -> R.string.console_approval_ask_hint
        ConfirmationMode.ALLOW_CONFIRMED_DOMAINS -> R.string.console_approval_domains_hint
        ConfirmationMode.ALLOW_ALL_SESSION -> R.string.console_approval_allow_all_hint
        ConfirmationMode.DENY_ALL -> R.string.console_approval_deny_all_hint
    }

/** The screen's name for a mode; the runtime's status and transcript lines keep [ConfirmationMode.label]. */
@StringRes
internal fun ConfirmationMode.labelRes(): Int =
    when (this) {
        ConfirmationMode.ASK_EVERY_TIME -> R.string.console_approval_ask
        ConfirmationMode.ALLOW_CONFIRMED_DOMAINS -> R.string.console_approval_domains
        ConfirmationMode.ALLOW_ALL_SESSION -> R.string.console_approval_allow_all
        ConfirmationMode.DENY_ALL -> R.string.console_approval_deny_all
    }

@StringRes
private fun ConfirmationMode.shortLabelRes(): Int =
    when (this) {
        ConfirmationMode.ASK_EVERY_TIME -> R.string.console_approval_ask_short
        ConfirmationMode.ALLOW_CONFIRMED_DOMAINS -> R.string.console_approval_domains_short
        ConfirmationMode.ALLOW_ALL_SESSION -> R.string.console_approval_allow_all_short
        ConfirmationMode.DENY_ALL -> R.string.console_approval_deny_all_short
    }

private val MODEL_BUTTON_MAX_WIDTH = 180.dp
private val MODEL_MENU_MIN_WIDTH = 220.dp
private val MODEL_MENU_MAX_WIDTH = 300.dp
private val VARIANT_BUTTON_MAX_WIDTH = 104.dp
private val VARIANT_MENU_MIN_WIDTH = 120.dp
private val VARIANT_MENU_MAX_WIDTH = 180.dp
private val APPROVAL_MENU_MIN_WIDTH = 260.dp
private val APPROVAL_MENU_MAX_WIDTH = 340.dp
private val COMPACT_COMPOSER_WIDTH = 560.dp
/** Under this the conversation pane takes the two-row composer: a phone held sideways gives it about 330 dp. */
private val SHORT_PANE_HEIGHT = 420.dp
private val CONDENSED_INPUT_HEIGHT = 52.dp
private val CONDENSED_ACTIONS_HEIGHT = 48.dp
private val CONDENSED_STATUS_MIN_HEIGHT = 140.dp
private val CONDENSED_SECONDARY_ACTIONS_WIDTH = 600.dp
private val SUBMIT_BUTTON_SIZE = 36.dp
private val SUBMIT_ICON_SIZE = 20.dp
private const val SUBMIT_DISABLED_ALPHA = 0.35f

@Composable
internal fun String.variantLabel(): String =
    when {
        isBlank() || equals(DEFAULT_DEBUG_VARIANT_ID, ignoreCase = true) ->
            stringResource(R.string.console_variant_default)
        else -> replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

/**
 * Codex-style run status above the composer:
 * `[●] ShimmerHeader · detail          (elapsed)`
 */
@Composable
private fun ActiveStatusBar(state: ServerState) {
    val colors = ConsoleColors
    val elapsedSec = rememberElapsedSeconds(state.runStartedAtEpochMillis)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.radiusSm),
        color = colors.activitySoft.copy(alpha = if (colors.isDark) 0.55f else 0.85f),
        border = BorderStroke(1.dp, colors.activity.copy(alpha = 0.22f)),
    ) {
        ActiveStatusBarContent(
            header = statusHeader(state),
            detail = statusDetail(state),
            elapsedLabel = durationText(elapsedSec.coerceAtLeast(0L) * 1000L),
        )
    }
}

@Composable
private fun CompletedStatusBar(result: RunResult?) {
    val summaryText = result?.let { runResultText(it) }.orEmpty()
    val colors = ConsoleColors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.radiusSm),
        color = colors.panel.copy(alpha = if (colors.isDark) 0.72f else 0.92f),
        border = BorderStroke(1.dp, colors.borderSubtle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(color = colors.success.copy(alpha = 0.85f))
            Spacer(Modifier.width(10.dp))
            Text(
                text = summaryText,
                style = ConsoleType.body,
                fontWeight = FontWeight.Medium,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun rememberElapsedSeconds(startedAtEpochMillis: Long?): Long {
    val startedAt = startedAtEpochMillis ?: remember { System.currentTimeMillis() }
    var now by remember(startedAt) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    return ((now - startedAt) / 1000).coerceAtLeast(0L)
}

@Composable
private fun ActiveStatusBarContent(header: String, detail: String?, elapsedLabel: String) {
    val colors = ConsoleColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = colors.activity, pulsing = true)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            ShimmerText(
                text = header,
                baseColor = colors.muted,
                highlightColor = colors.activity,
                fontWeight = FontWeight.Medium,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = ConsoleType.meta,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(text = elapsedLabel, style = ConsoleType.meta, color = colors.muted, maxLines = 1)
    }
}

@Composable
internal fun statusDetail(state: ServerState): String? = when (state.activity) {
    RunActivity.THINKING -> state.providerLabel.takeIf { it.isNotBlank() }
    RunActivity.WAITING_FOR_APPROVAL -> stringResource(R.string.console_status_respond_in_card)
    RunActivity.STARTING -> stringResource(R.string.console_status_running_profile, state.providerLabel, streamText(state.stream))
    RunActivity.RUNNING_TOOL -> null
    // Otherwise the runtime's own line (an event's name) as the debug console's detail, when it says more than the header.
    else -> state.runtimeStatus.trim().takeIf {
        it.isNotBlank() && it.length <= 72 && !it.equals("Running", ignoreCase = true) &&
            !it.equals(state.activity.headerWords(state.activityTool), ignoreCase = true)
    }
}

/** The run's status header in the screen's language. */
@Composable
internal fun statusHeader(state: ServerState): String =
    LocalContext.current.resources.statusHeaderText(state.activity, state.activityTool)

/** What the run is doing in the screen's language, for code that shows it outside Compose; [tool] is the tool running. */
internal fun Resources.statusHeaderText(activity: RunActivity, tool: String): String = when (activity) {
    RunActivity.THINKING -> getString(R.string.console_status_thinking)
    RunActivity.FAILED -> getString(R.string.console_status_failed)
    RunActivity.STOPPED -> getString(R.string.console_status_stopped)
    RunActivity.WAITING_FOR_APPROVAL -> getString(R.string.console_status_waiting_for_approval)
    RunActivity.BLOCKED -> getString(R.string.console_status_blocked)
    RunActivity.RUNNING_TOOL -> getString(R.string.console_status_running_tool, tool)
    RunActivity.IDLE, RunActivity.STARTING, RunActivity.WORKING -> getString(R.string.console_status_working)
}
