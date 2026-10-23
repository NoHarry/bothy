package cc.noharry.bothy.sample

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun DebugConversationPane(
    state: DebugUiState,
    pendingInput: String,
    onPendingInputConsumed: () -> Unit,
    actions: DebugConsoleActions,
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
            DebugQuestionCards(state, actions)
            if (useCondensedChrome) {
                DebugCondensedConversationPane(input, { input = it }, submit, state, actions, Modifier.weight(1f))
            } else {
                DebugRegularConversationPane(input, { input = it }, submit, state, actions, short, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DebugRegularConversationPane(
    input: String,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    state: DebugUiState,
    actions: DebugConsoleActions,
    short: Boolean,
    modifier: Modifier,
) {
    DebugPanelBox("Conversation", modifier) {
        ConversationStatus(state)
        Spacer(Modifier.height(DebugDimens.spaceSm))
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
        val latestScrollKey = renderItems.lastOrNull()?.autoScrollKey()
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
                if (item is DebugConversationRenderItem.TurnDivider) {
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
                verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceXs),
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
            visible = state.isRunning || !state.lastRunSummary.isNullOrBlank(),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Column {
                Spacer(Modifier.height(6.dp))
                if (state.isRunning) {
                    ActiveStatusBar(state)
                } else {
                    CompletedStatusBar(state.lastRunSummary.orEmpty())
                }
            }
        }
        Spacer(Modifier.height(DebugDimens.spaceSm))
        DebugInputBar(
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
    val colors = DebugColors
    Column(
        modifier = modifier.padding(vertical = DebugDimens.spaceLg),
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
                .clip(RoundedCornerShape(DebugDimens.radiusLg)),
        )
        Spacer(Modifier.height(DebugDimens.spaceLg))
        Text(text = "小博在小屋里等你", style = DebugType.hero, color = colors.text)
        Spacer(Modifier.height(DebugDimens.spaceXs))
        Text(
            text = "问点什么，或者先到「连接应用」接一个免 key 的公开 MCP。每一步用了什么工具、拿回了什么，都会列在这里。",
            style = DebugType.body,
            color = colors.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = WELCOME_MAX_WIDTH),
        )
    }
}

private val WELCOME_MAX_WIDTH = 560.dp
private const val WELCOME_ASPECT = 2f

@Composable
private fun DebugCondensedConversationPane(
    input: String,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    state: DebugUiState,
    actions: DebugConsoleActions,
    modifier: Modifier,
) {
    val colors = DebugColors
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
                if (showStatus) {
                    ConversationStatus(state)
                    Spacer(Modifier.height(2.dp))
                }
                DebugMessageField(input, onInputChange, true, Modifier.fillMaxWidth().height(CONDENSED_INPUT_HEIGHT))
                CondensedComposerActions(input, onSubmit, state, actions)
            }
        }
    }
}

@Composable
private fun ConversationStatus(state: DebugUiState) {
    val colors = DebugColors
    // Keep a single quiet meta line; live Working / Worked-for live in the bar above the composer.
    val run = when {
        state.isRunning -> statusHeader(state)
        !state.lastRunSummary.isNullOrBlank() -> state.lastRunSummary
        else -> "Ready"
    }
    val text = "$run · ${state.providerLabel} · ${state.streamLabel}"
    Text(
        text = text,
        style = DebugType.meta,
        color = colors.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ModelMenu(editor: DebugProviderEditState, actions: DebugConsoleActions, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val options = editor.modelOptions.ifEmpty { listOf(editor.model).filter { it.isNotBlank() } }
    val selected = editor.model
    Box(modifier.widthIn(max = MODEL_BUTTON_MAX_WIDTH), contentAlignment = Alignment.BottomEnd) {
        DebugSelectorChip(
            label = selected.ifBlank { "选择模型" },
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            placeholder = selected.isBlank(),
        )
        DebugPopupMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            minWidth = MODEL_MENU_MIN_WIDTH,
            maxWidth = MODEL_MENU_MAX_WIDTH,
        ) {
            DebugPopupSectionLabel("Model")
            if (options.isEmpty()) {
                DebugPopupMenuItem(
                    label = "请在设置中拉取或添加模型",
                    selected = false,
                    onClick = { expanded = false },
                )
            } else {
                options.forEach { model ->
                    DebugPopupMenuItem(
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
private fun VariantMenu(editor: DebugProviderEditState, actions: DebugConsoleActions, modifier: Modifier = Modifier) {
    if (editor.variantOptions.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val (options, selected) = editor.variantChoices()
    Box(modifier.widthIn(max = VARIANT_BUTTON_MAX_WIDTH), contentAlignment = Alignment.BottomEnd) {
        DebugSelectorChip(
            label = selected.variantLabel(),
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        )
        DebugPopupMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            minWidth = VARIANT_MENU_MIN_WIDTH,
            maxWidth = VARIANT_MENU_MAX_WIDTH,
        ) {
            DebugPopupSectionLabel("Variant")
            options.forEach { variant ->
                DebugPopupMenuItem(
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
private fun DebugInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    state: DebugUiState,
    actions: DebugConsoleActions,
    short: Boolean,
) {
    val colors = DebugColors
    // Claude-quiet composer dock: soft panel, weak border; activity only on Steer.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DebugDimens.radiusLg),
        color = colors.elevated,
        border = BorderStroke(1.dp, colors.borderSubtle),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(if (short) DebugDimens.spaceSm else DebugDimens.spaceMd)) {
            DebugVoiceStrip()
            if (short) {
                DebugMessageField(text, onTextChange, true, Modifier.fillMaxWidth())
                CondensedComposerActions(text, onSubmit, state, actions)
                return@Column
            }
            DebugMessageField(text, onTextChange, false, Modifier.fillMaxWidth())
            Spacer(Modifier.height(DebugDimens.spaceSm))
            ConfirmationModeSelector(state.confirmationMode, actions)
            Spacer(Modifier.height(DebugDimens.spaceSm))
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
internal fun DebugMessageField(
    text: String,
    onTextChange: (String) -> Unit,
    singleLine: Boolean,
    modifier: Modifier,
    placeholder: String = "Message the agent…",
) {
    val colors = DebugColors
    // Borderless field fused into the composer dock; the dock Surface owns the outline.
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = modifier,
        minLines = if (singleLine) 1 else 2,
        maxLines = if (singleLine) 1 else 5,
        singleLine = singleLine,
        textStyle = DebugType.body,
        placeholder = { Text(placeholder, style = DebugType.body, color = colors.muted) },
        shape = RoundedCornerShape(DebugDimens.radiusSm),
        colors = debugOutlinedTextFieldColors().copy(
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
    state: DebugUiState,
    actions: DebugConsoleActions,
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
            if (showSecondaryActions) TextButton(onClick = actions.openOverlay) { Text("Float") }
            DebugContextUsageIndicator(state)
            ModelMenu(state.configEditor, actions, Modifier.weight(1f))
            if (showSecondaryActions) VariantMenu(state.configEditor, actions)
            DebugVoiceMicButton(SUBMIT_BUTTON_SIZE, SUBMIT_ICON_SIZE)
            SubmitButton(text, onSubmit, state.isRunning)
        }
    }
}

@Composable
private fun CompactComposerActions(
    text: String,
    onSubmit: () -> Unit,
    state: DebugUiState,
    actions: DebugConsoleActions,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DebugContextUsageIndicator(state)
            ModelMenu(state.configEditor, actions, Modifier.weight(1f))
            VariantMenu(state.configEditor, actions)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ComposerOverflowMenu(actions)
            Spacer(Modifier.weight(1f))
            if (state.isRunning) TextButton(onClick = actions.stop) { Text("Stop") }
            DebugVoiceMicButton(SUBMIT_BUTTON_SIZE, SUBMIT_ICON_SIZE)
            SubmitButton(text, onSubmit, state.isRunning)
        }
    }
}

@Composable
private fun WideComposerActions(
    text: String,
    onSubmit: () -> Unit,
    state: DebugUiState,
    actions: DebugConsoleActions,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ComposerOverflowMenu(actions)
        Spacer(Modifier.weight(1f))
        DebugContextUsageIndicator(state)
        ModelMenu(state.configEditor, actions)
        VariantMenu(state.configEditor, actions)
        if (state.isRunning) TextButton(onClick = actions.stop) { Text("Stop") }
        DebugVoiceMicButton(SUBMIT_BUTTON_SIZE, SUBMIT_ICON_SIZE)
        SubmitButton(text, onSubmit, state.isRunning)
    }
}

@Composable
private fun ComposerOverflowMenu(actions: DebugConsoleActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        DebugSelectorChip(label = "More", onClick = { expanded = true })
        DebugPopupMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            minWidth = 180.dp,
            maxWidth = 240.dp,
        ) {
            DebugPopupSectionLabel("Actions")
            DebugPopupMenuItem(
                label = "Float window",
                selected = false,
                onClick = {
                    expanded = false
                    actions.openOverlay()
                },
            )
            DebugPopupMenuItem(
                label = "Toggle stream",
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
    val colors = DebugColors
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
    selected: DebugConfirmationMode,
    actions: DebugConsoleActions,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    compactMenuItems: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = DebugColors
    val modeLabel = if (compactMenuItems) selected.shortLabel else selected.label
    Box(modifier.fillMaxWidth()) {
        // Warning modes keep the amber fill/border/dot; others read as a quiet chip.
        DebugSelectorChip(
            label = if (showLabel) "Approval · $modeLabel" else modeLabel,
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            leadingDot = if (selected.warning) colors.warning else colors.accent,
            warning = selected.warning,
        )
        DebugPopupMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            minWidth = APPROVAL_MENU_MIN_WIDTH,
            maxWidth = APPROVAL_MENU_MAX_WIDTH,
        ) {
            DebugPopupSectionLabel("Approval")
            DebugConfirmationMode.entries.forEach { mode ->
                val modeAccent = if (mode.warning) colors.warning else colors.ink
                DebugPopupMenuItem(
                    label = mode.label,
                    selected = mode == selected,
                    hint = mode.menuHint(),
                    accent = modeAccent,
                    compact = compactMenuItems,
                    onClick = {
                        expanded = false
                        actions.selectConfirmationMode(mode)
                    },
                    leading = {
                        DebugPopupDot(active = mode == selected, color = modeAccent)
                    },
                )
            }
        }
    }
}

private fun DebugConfirmationMode.menuHint(): String =
    when (this) {
        DebugConfirmationMode.ASK_EVERY_TIME -> "Prompt for each network tool"
        DebugConfirmationMode.ALLOW_CONFIRMED_DOMAINS -> "Auto-allow previously approved hosts"
        DebugConfirmationMode.ALLOW_ALL_SESSION -> "Skip prompts for this session"
        DebugConfirmationMode.DENY_ALL -> "Block network tools"
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

private fun String.variantLabel(): String =
    when {
        isBlank() || equals(DEFAULT_DEBUG_VARIANT_ID, ignoreCase = true) -> "Default"
        else -> replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

/**
 * Codex-style run status above the composer:
 * `[●] ShimmerHeader · detail          (elapsed)`
 */
@Composable
private fun ActiveStatusBar(state: DebugUiState) {
    val colors = DebugColors
    val elapsedSec = rememberElapsedSeconds(state.runStartedAtEpochMillis)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DebugDimens.radiusSm),
        color = colors.activitySoft.copy(alpha = if (colors.isDark) 0.55f else 0.85f),
        border = BorderStroke(1.dp, colors.activity.copy(alpha = 0.22f)),
    ) {
        ActiveStatusBarContent(
            header = statusHeader(state),
            detail = statusDetail(state),
            elapsedLabel = formatElapsedCompact(elapsedSec),
        )
    }
}

@Composable
private fun CompletedStatusBar(summary: String) {
    val colors = DebugColors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DebugDimens.radiusSm),
        color = colors.panel.copy(alpha = if (colors.isDark) 0.72f else 0.92f),
        border = BorderStroke(1.dp, colors.borderSubtle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DebugStatusDot(color = colors.success.copy(alpha = 0.85f))
            Spacer(Modifier.width(10.dp))
            Text(
                text = summary,
                style = DebugType.body,
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
    val colors = DebugColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DebugStatusDot(color = colors.activity, pulsing = true)
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
                    style = DebugType.meta,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(text = elapsedLabel, style = DebugType.meta, color = colors.muted, maxLines = 1)
    }
}

private fun statusDetail(state: DebugUiState): String? {
    val status = state.status.trim()
    return when {
        status.startsWith("Tool running:", ignoreCase = true) -> null
        status.startsWith("Thinking", ignoreCase = true) -> state.providerLabel.takeIf { it.isNotBlank() }
        status.contains("confirmation", ignoreCase = true) -> "Respond in the timeline card"
        status.isBlank() || status.equals("Running", ignoreCase = true) -> null
        status.equals(statusHeader(state), ignoreCase = true) -> null
        else -> status.takeIf { it.length <= 72 }
    }
}

private fun formatElapsedCompact(seconds: Long): String =
    formatWorkedDuration(seconds.coerceAtLeast(0L) * 1000L)

internal fun statusHeader(state: DebugUiState): String {
    val status = state.status
    return when {
        status.startsWith("Tool running:", ignoreCase = true) -> {
            // Codex uses the verb "Running" + tool name
            val toolName = status.substringAfter(":").trim()
            "Running $toolName"
        }
        status.startsWith("Tool finished:", ignoreCase = true) -> "Working"
        status.startsWith("Thinking", ignoreCase = true) -> "Thinking"
        status.startsWith("Started", ignoreCase = true) -> "Working"
        status.startsWith("Worked for", ignoreCase = true) -> status
        status.startsWith("Failed after", ignoreCase = true) -> "Failed"
        status.startsWith("Stopped after", ignoreCase = true) -> "Stopped"
        status.startsWith("Failed", ignoreCase = true) -> "Failed"
        // AgentEvent.Finished sets "Finished <runId>" while tools may still be winding down.
        status.startsWith("Finished", ignoreCase = true) -> "Working"
        status.contains("confirmation", ignoreCase = true) -> "Waiting for approval"
        status.contains("blocked", ignoreCase = true) -> "Blocked"
        else -> "Working"
    }
}
