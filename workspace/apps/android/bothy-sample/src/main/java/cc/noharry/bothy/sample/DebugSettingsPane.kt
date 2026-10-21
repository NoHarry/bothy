package cc.noharry.bothy.sample

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState

internal enum class DebugSettingsPage {
    OVERVIEW,
    PROVIDERS,
    PROVIDER_DETAIL,
    NEW_PROVIDER,
    RUNTIME_SKILLS,
    BASIC_TOOLS,
    JEV,
}

internal data class DebugSettingsNavigationState(
    val page: DebugSettingsPage = DebugSettingsPage.OVERVIEW,
    val requestedProfileId: String? = null,
) {
    fun openProviders(): DebugSettingsNavigationState = copy(page = DebugSettingsPage.PROVIDERS, requestedProfileId = null)

    fun openProvider(profileId: String): DebugSettingsNavigationState =
        copy(page = DebugSettingsPage.PROVIDER_DETAIL, requestedProfileId = profileId)

    fun openNewProvider(): DebugSettingsNavigationState = copy(page = DebugSettingsPage.NEW_PROVIDER, requestedProfileId = null)

    fun back(): DebugSettingsNavigationState? =
        when (page) {
            DebugSettingsPage.OVERVIEW -> null
            DebugSettingsPage.PROVIDERS,
            DebugSettingsPage.RUNTIME_SKILLS,
            DebugSettingsPage.BASIC_TOOLS,
            DebugSettingsPage.JEV,
            -> DebugSettingsNavigationState()
            DebugSettingsPage.PROVIDER_DETAIL,
            DebugSettingsPage.NEW_PROVIDER,
            -> openProviders()
        }

    fun showsProviderDetail(twoPane: Boolean): Boolean =
        twoPane && page in PROVIDER_PAGES || page == DebugSettingsPage.PROVIDER_DETAIL || page == DebugSettingsPage.NEW_PROVIDER
}

@Composable
internal fun DebugSettingsPane(
    state: DebugUiState,
    actions: DebugConsoleActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DebugSettingsShell(state, actions, onBack, modifier)
}

@Composable
private fun SettingsProfileModeGroup(editor: DebugProviderEditState, actions: DebugConsoleActions) {
    SettingsGroup(title = "Profile mode", subtitle = "Applies immediately.") {
        Box(Modifier.padding(DebugDimens.spaceMd)) {
            ProfileModeSelector(editor, actions)
        }
    }
}

@Composable
internal fun SettingsFeaturePages(onOpenSkills: () -> Unit, onOpenJev: () -> Unit, onOpenBasicTools: () -> Unit) {
    SettingsGroup(title = "Runtime capabilities", subtitle = "Independent switches · applies to the next request.") {
        SettingsDestinationRow("基础工具 / Basic tools", "Choose tools including exec_js", "Manage", onOpenBasicTools)
        SettingsRowDivider()
        SettingsDestinationRow("Runtime skills", "Select and preview loaded skills", "Manage", onOpenSkills)
        SettingsRowDivider()
        SettingsDestinationRow("JEV tool discovery", "Configure model, connection and selection", "Configure", onOpenJev)
    }
}

@Composable
internal fun SettingsRuntimeGroup(state: DebugUiState, actions: DebugConsoleActions) {
    SettingsGroup(title = "Runtime", subtitle = "Applies immediately.") {
        Box(Modifier.padding(DebugDimens.spaceXs)) {
            Column {
                SettingsStateRow(title = "Stream", value = state.streamLabel, onClick = actions.toggleStream)
                SettingsStateRow(
                    title = "Backend",
                    value = state.backendLabel,
                    onClick = { actions.selectBackend(false) },
                )
                ShowThinkingRow()
                SearchBackendRow()
                FetchFallbackRow()
            }
        }
    }
}

@Composable
internal fun SettingsWorkspaceGroup(state: DebugUiState, actions: DebugConsoleActions) {
    SettingsGroup(title = "Workspace", subtitle = state.workspaceSelection.label) {
        SettingsActionRow(
            title = "SAF workspace",
            value = if (state.workspaceSelection.safEnabled) "Linked" else "Not linked",
            actionLabel = "Pick workspace",
            onClick = actions.pickSafWorkspace,
        )
        if (state.workspaceSelection.safEnabled) {
            SettingsRowDivider()
            SettingsActionRow(
                title = "Unlink SAF workspace",
                value = null,
                actionLabel = "Clear",
                actionDanger = true,
                onClick = actions.clearSafWorkspace,
            )
        }
    }
}

@Composable
internal fun SettingsAdvancedGroup(state: DebugUiState, actions: DebugConsoleActions) {
    SettingsGroup(title = "Advanced", subtitle = state.configLabel) {
        SettingsActionRow(
            title = "Provider configuration",
            value = null,
            actionLabel = "Reload",
            onClick = actions.reloadConfig,
        )
    }
}

@Composable
internal fun SettingsRuntimeDataGroup(actions: DebugConsoleActions) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    SettingsGroup(
        title = "Runtime data",
        subtitle = "Sessions, generated images, workspace memory, run evidence, and debug logs. " +
            "Does not delete provider or image model API keys, or MCP settings.",
    ) {
        SettingsActionRow(
            title = "Clear runtime data",
            value = null,
            actionLabel = "Clear all",
            actionDanger = true,
            onClick = { confirmClear = true },
        )
    }
    if (confirmClear) {
        ClearRuntimeDataDialog(
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                actions.clearRuntimeData()
            },
        )
    }
}

/** Group card: section label + subtitle + unified panel with hairline-separated rows. */
@Composable
internal fun SettingsGroup(
    title: String,
    subtitle: String?,
    content: @Composable () -> Unit,
) {
    val colors = DebugColors
    Column(verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceXs)) {
        SettingsSectionHeader(title = title, subtitle = subtitle.orEmpty())
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(DebugDimens.radiusMd),
            color = colors.panel,
            border = BorderStroke(1.dp, colors.borderSubtle),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}

@Composable
private fun SettingsRowDivider() {
    HorizontalDivider(
        color = DebugColors.borderSubtle,
        modifier = Modifier.padding(horizontal = DebugDimens.spaceMd),
    )
}

/** Standard action row: title (+ optional value) on the left, chip action on the right. */
@Composable
private fun SettingsActionRow(
    title: String,
    value: String?,
    actionLabel: String,
    onClick: () -> Unit,
    actionDanger: Boolean = false,
) {
    val colors = DebugColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SETTINGS_ROW_HEIGHT)
            .clickable(onClick = onClick)
            .padding(horizontal = DebugDimens.spaceLg, vertical = DebugDimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = DebugType.body, color = colors.text, fontWeight = FontWeight.Medium)
            if (!value.isNullOrBlank()) {
                Text(value, style = DebugType.meta, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(DebugDimens.spaceMd))
        SettingsStatusChip(label = actionLabel, danger = actionDanger)
    }
}

@Composable
internal fun ProviderSettingsContent(
    state: DebugUiState,
    actions: DebugConsoleActions,
    navigation: DebugSettingsNavigationState,
    twoPane: Boolean,
    onNavigationChange: (DebugSettingsNavigationState) -> Unit,
) {
    if (twoPane) {
        Row(Modifier.fillMaxSize()) {
            ProviderListPane(
                editor = state.configEditor,
                actions = actions,
                onSelect = { id -> onNavigationChange(navigation.openProvider(id)) },
                onAdd = { onNavigationChange(navigation.openNewProvider()) },
                modifier = Modifier.width(PROVIDER_LIST_PANE_WIDTH).fillMaxHeight(),
            )
            Spacer(Modifier.width(DebugDimens.spaceMd))
            ProviderDetailPane(
                editor = state.configEditor,
                actions = actions,
                navigation = navigation,
                onDone = { onNavigationChange(navigation.openProviders()) },
                onCreated = { id -> onNavigationChange(navigation.openProvider(id)) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        return
    }
    when (navigation.page) {
        DebugSettingsPage.PROVIDERS -> ProviderListPane(
            editor = state.configEditor,
            actions = actions,
            onSelect = { id -> onNavigationChange(navigation.openProvider(id)) },
            onAdd = { onNavigationChange(navigation.openNewProvider()) },
            modifier = Modifier.fillMaxSize(),
        )
        DebugSettingsPage.PROVIDER_DETAIL,
        DebugSettingsPage.NEW_PROVIDER,
        -> ProviderDetailPane(
            editor = state.configEditor,
            actions = actions,
            navigation = navigation,
            onDone = { onNavigationChange(navigation.openProviders()) },
            onCreated = { id -> onNavigationChange(navigation.openProvider(id)) },
            modifier = Modifier.fillMaxSize(),
        )
        DebugSettingsPage.OVERVIEW,
        DebugSettingsPage.RUNTIME_SKILLS,
        DebugSettingsPage.BASIC_TOOLS,
        DebugSettingsPage.JEV,
        -> Unit
    }
}

@Composable
private fun ProviderListPane(
    editor: DebugProviderEditState,
    actions: DebugConsoleActions,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DebugColors
    Column(modifier) {
        SettingsSectionHeader(
            title = "服务商",
            subtitle = "选择服务后编辑连接和模型。",
        )
        Spacer(Modifier.height(DebugDimens.spaceSm))
        SettingsGroup(title = "Profile mode", subtitle = null) {
            Box(Modifier.padding(DebugDimens.spaceMd)) {
                ProfileModeSelector(editor, actions)
            }
        }
        Spacer(Modifier.height(DebugDimens.spaceMd))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${editor.profileOptions.size} profiles",
                style = DebugType.meta,
                color = colors.muted,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = actions.reloadConfig) { Text("Reload") }
            TextButton(onClick = onAdd) { Text("+ 添加服务") }
        }
        Spacer(Modifier.height(DebugDimens.spaceXsMd))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm),
        ) {
            items(editor.profileOptions, key = { it.id }) { option ->
                ProviderProfileRow(
                    option = option,
                    onClick = {
                        actions.selectProfile(option.id)
                        onSelect(option.id)
                    },
                    onDelete = { actions.deleteProfile(option.id) },
                )
            }
        }
    }
}

@Composable
private fun ProviderDetailPane(
    editor: DebugProviderEditState,
    actions: DebugConsoleActions,
    navigation: DebugSettingsNavigationState,
    onDone: () -> Unit,
    onCreated: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DebugColors
    // Cap form width so trailing controls stay within scan distance on ultra-wide panels.
    Box(modifier) {
        Column(Modifier.widthIn(max = SETTINGS_CONTENT_MAX_WIDTH).fillMaxHeight()) {
            when {
                navigation.page == DebugSettingsPage.NEW_PROVIDER -> ProviderDetail(
                    ProviderDraft.new(), actions, isNew = true, onDone = onDone, modifier = Modifier.weight(1f), onCreated = onCreated,
                )
                navigation.requestedProfileId != null && navigation.requestedProfileId != editor.profileId -> {
                    Text("Loading provider…", style = DebugType.meta, color = colors.muted)
                }
                else -> ProviderDetail(
                    ProviderDraft.from(editor), actions, isNew = false, onDone = onDone, modifier = Modifier.weight(1f),
                )
            }
        }
}
}


/**
 * Single nav pattern for all Settings levels (and MCP child page):
 * [‹] Title
 *     breadcrumb / hint
 * Leading edge only — never trailing-edge Exit/Back.
 */
@Composable
internal fun SettingsNavBar(
    title: String,
    breadcrumb: String?,
    backLabel: String,
    onBack: () -> Unit,
    hint: String? = null,
) {
    val colors = DebugColors
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(NAV_BACK_TOUCH_SIZE)
                .clip(RoundedCornerShape(DebugDimens.radiusFull))
                .clickable(onClick = onBack)
                .semantics { role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = backLabel,
                tint = colors.muted,
                modifier = Modifier.size(NAV_BACK_ICON_SIZE),
            )
        }
        Spacer(Modifier.width(DebugDimens.spaceSm))
        Column(Modifier.weight(1f)) {
            Text(title, style = DebugType.panelTitle, color = colors.text)
            val sub = listOfNotNull(breadcrumb, hint).joinToString(" · ").ifBlank { null }
            if (!sub.isNullOrBlank()) {
                Text(
                    text = sub,
                    style = DebugType.meta,
                    color = colors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun DebugSettingsNavigationState.pageTitle(): String =
    when (page) {
        DebugSettingsPage.OVERVIEW -> "Settings"
        DebugSettingsPage.PROVIDERS -> "Providers"
        DebugSettingsPage.PROVIDER_DETAIL -> "Edit provider"
        DebugSettingsPage.NEW_PROVIDER -> "New provider"
        DebugSettingsPage.RUNTIME_SKILLS -> "Runtime skills"
        DebugSettingsPage.BASIC_TOOLS -> "基础工具 / Basic tools"
        DebugSettingsPage.JEV -> "JEV tool discovery"
    }

private fun DebugSettingsNavigationState.breadcrumb(): String? =
    when (page) {
        DebugSettingsPage.OVERVIEW -> null
        DebugSettingsPage.PROVIDERS -> "Settings › Providers"
        DebugSettingsPage.PROVIDER_DETAIL -> "Settings › Providers › Edit"
        DebugSettingsPage.NEW_PROVIDER -> "Settings › Providers › New"
        DebugSettingsPage.RUNTIME_SKILLS -> "Settings › Runtime skills"
        DebugSettingsPage.BASIC_TOOLS -> "Settings › Basic tools"
        DebugSettingsPage.JEV -> "Settings › JEV"
    }

private fun DebugSettingsNavigationState.navHint(): String? =
    when (page) {
        DebugSettingsPage.OVERVIEW -> "Provider, MCP, workspace and runtime options."
        DebugSettingsPage.PROVIDERS -> "Tap a profile to edit"
        DebugSettingsPage.PROVIDER_DETAIL -> "Save keeps changes on this profile"
        DebugSettingsPage.NEW_PROVIDER -> "Fill fields, then Save"
        DebugSettingsPage.RUNTIME_SKILLS -> "Toggles apply to the next request"
        DebugSettingsPage.BASIC_TOOLS -> "Toggles apply to the next request"
        DebugSettingsPage.JEV -> "Save applies to the next request"
    }

@Composable
private fun SettingsSectionHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceXxs)) {
        DebugSectionLabel(title)
        Text(subtitle, style = DebugType.meta, color = DebugColors.muted)
    }
}

/** Group of navigable rows — whole row is tappable, trailing chevron signals the jump. */
@Composable
private fun SettingsDestinationRow(
    title: String,
    value: String,
    actionLabel: String,
    onClick: () -> Unit,
) {
    val colors = DebugColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SETTINGS_ROW_HEIGHT)
            .clickable(onClick = onClick)
            .padding(horizontal = DebugDimens.spaceLg, vertical = DebugDimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceXs)) {
            Text(title, style = DebugType.body, color = colors.text, fontWeight = FontWeight.Medium)
            Text(
                value,
                style = DebugType.meta,
                color = colors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(DebugDimens.spaceMd))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = actionLabel,
            tint = colors.muted,
            modifier = Modifier.size(MANAGE_CHEVRON_SIZE),
        )
    }
}

/** Standard settings row: body title + accentSoft status chip; the whole row is the tap target. */
@Composable
private fun SettingsStateRow(title: String, value: String, onClick: () -> Unit) {
    val colors = DebugColors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DebugDimens.radiusSm))
            .clickable(onClick = onClick)
            .padding(horizontal = DebugDimens.spaceSm, vertical = DebugDimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = DebugType.body,
            color = colors.text,
            modifier = Modifier.weight(1f),
        )
        SettingsStatusChip(value)
    }
}

@Composable
internal fun SettingsStatusChip(
    label: String,
    active: Boolean = false,
    danger: Boolean = false,
) {
    val colors = DebugColors
    val bg = when {
        danger -> colors.danger.copy(alpha = 0.12f)
        active -> colors.activitySoft
        else -> colors.accentSoft
    }
    val fg = when {
        danger -> colors.danger
        active -> colors.activity
        else -> colors.accent
    }
    Surface(
        shape = RoundedCornerShape(DebugDimens.radiusFull),
        color = bg,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Text(
            label,
            style = DebugType.meta,
            color = fg,
            maxLines = 1,
            modifier = Modifier.padding(
                horizontal = DebugDimens.spaceMd,
                vertical = DebugDimens.spaceXxs,
            ),
        )
    }
}

/** 显示思考过程: the card's in-between notes, plan and step text, and the console's Thinking (see [DebugDisplaySettings]). */
@Composable
private fun ShowThinkingRow() {
    val context = LocalContext.current
    val show by DebugDisplaySettings.showThinking.collectAsState()
    Row(Modifier.fillMaxWidth().padding(DebugDimens.spaceSm), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("显示思考过程", style = DebugType.body, color = DebugColors.text)
            Text(
                if (show) "开：卡片上显示模型的中间想法、计划和当前步骤，控制台显示 Thinking"
                else "关：卡片只显示说给用户的进度和结果，控制台不显示 Thinking",
                style = DebugType.meta,
                color = DebugColors.muted,
            )
        }
        Switch(checked = show, onCheckedChange = { DebugDisplaySettings.setShowThinking(context, it) }, colors = debugSwitchColors())
    }
}

/** 联网搜索 for the active profile: the provider's server-side search, or DeepSeek's Search API with visible results. */
@Composable
private fun SearchBackendRow() {
    val context = LocalContext.current
    val profile = remember { DebugProviderConfig.load(context.applicationContext).activeProfile }
    val usesApi by DebugSearchBackend.usesApi.collectAsState()
    LaunchedEffect(Unit) { DebugSearchBackend.load(context) }
    val api = usesApi[profile.id] == true
    Row(Modifier.fillMaxWidth().padding(DebugDimens.spaceSm), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("联网搜索用 DeepSeek Search 接口", style = DebugType.body, color = DebugColors.text)
            Text(
                if (api) "开：搜索词和结果都看得到，按次计费（5 元/千次），需要一个 DeepSeek 的 key"
                else "关：用「${profile.displayLabel}」自带的服务端搜索，看不到搜了什么",
                style = DebugType.meta,
                color = DebugColors.muted,
            )
        }
        Switch(checked = api, onCheckedChange = { DebugSearchBackend.set(context, profile.id, it) }, colors = debugSwitchColors())
    }
}

/** Pages that refuse the device (携程 answers it with 432) read through DeepSeek's Fetch API, for every agent. */
@Composable
private fun FetchFallbackRow() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(DebugFetchFallback.enabled(context)) }
    val keyed = remember { DebugProviderConfig.load(context.applicationContext).hasDeepSeekKey(context) }
    Row(Modifier.fillMaxWidth().padding(DebugDimens.spaceSm), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("网页打不开时用 DeepSeek Fetch", style = DebugType.body, color = DebugColors.text)
            Text(
                when {
                    on && !keyed -> "开，但没有 DeepSeek 的 key，暂不生效"
                    on -> "开：网站拦住设备的请求或打不开时，改由 Fetch 接口读取（每次 ¥0.005，只在被拦时花）；主 agent 和子 agent 都用，服务端搜索时主 agent 也能打开网页"
                    else -> "关：打不开就是打不开；服务端搜索时主 agent 不能打开网页"
                },
                style = DebugType.meta,
                color = DebugColors.muted,
            )
        }
        Switch(checked = on, onCheckedChange = {
            DebugFetchFallback.set(context, it)
            on = it
        }, colors = debugSwitchColors())
    }
}

@Composable
private fun ProfileModeSelector(editor: DebugProviderEditState, actions: DebugConsoleActions) {
    val colors = DebugColors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Manual profile switching", style = DebugType.body, color = colors.text)
            Text(
                if (editor.manualProfileEnabled) {
                    "On — use the selected profile below instead of config default"
                } else {
                    "Off — follow the default profile from configuration"
                },
                style = DebugType.meta,
                color = colors.muted,
            )
        }
        Switch(
            checked = editor.manualProfileEnabled,
            onCheckedChange = actions.setManualProfileEnabled,
            colors = debugSwitchColors(),
        )
    }
}

@Composable
private fun ProviderProfileRow(option: DebugProviderProfileOption, onClick: () -> Unit, onDelete: () -> Unit) {
    val colors = DebugColors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DebugDimens.radiusSm),
        color = if (option.selected) colors.accentSoft else colors.elevated,
        border = BorderStroke(1.dp, colors.borderSubtle),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(Modifier.fillMaxWidth()) {
            if (option.selected) {
                // Same selected pill as SessionRow / NavRail (3dp x 24dp, leading edge).
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .width(PROFILE_SELECTED_BAR_WIDTH)
                        .height(PROFILE_SELECTED_BAR_HEIGHT)
                        .background(colors.accent, RoundedCornerShape(DebugDimens.radiusFull)),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = PROFILE_SELECTED_BAR_WIDTH + DebugDimens.spaceMd,
                        end = DebugDimens.spaceXs,
                        top = DebugDimens.spaceMd,
                        bottom = DebugDimens.spaceMd,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProviderProfileMain(option = option, onClick = onClick, modifier = Modifier.weight(1f))
                if (option.canDelete) {
                    TextButton(onClick = onDelete) { Text("Delete", color = colors.danger) }
                }
            }
        }
    }
}

@Composable
private fun ProviderProfileMain(
    option: DebugProviderProfileOption,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DebugColors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(DebugDimens.radiusSm))
            .clickable(onClick = onClick)
            .padding(vertical = DebugDimens.spaceXxs, horizontal = DebugDimens.spaceXxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (option.selected) {
            Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = colors.accent) {}
            Spacer(Modifier.width(DebugDimens.spaceSm))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceXxs)) {
            ProviderProfileTitle(option)
            Text(
                "${option.providerLabel} · ${option.endpointLabel} · ${option.model.ifBlank { "no model" }}",
                style = DebugType.meta,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(DebugDimens.spaceSm))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Edit",
            tint = colors.muted,
            modifier = Modifier.size(MANAGE_CHEVRON_SIZE),
        )
    }
}

@Composable
private fun ProviderProfileTitle(option: DebugProviderProfileOption) {
    val colors = DebugColors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            option.label,
            style = DebugType.body,
            color = colors.text,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (option.selected) {
            Spacer(Modifier.width(DebugDimens.spaceSm))
            Text("Active", style = DebugType.label, color = colors.accent, fontWeight = FontWeight.SemiBold)
        }
    }
}

private val PROVIDER_PAGES = setOf(
    DebugSettingsPage.PROVIDERS,
    DebugSettingsPage.PROVIDER_DETAIL,
    DebugSettingsPage.NEW_PROVIDER,
)
private val SETTINGS_TWO_PANE_MIN_WIDTH = 680.dp
private val SETTINGS_OVERVIEW_TWO_COLUMN_MIN_WIDTH = 1500.dp
private val SETTINGS_CONTENT_MAX_WIDTH = 920.dp
private val PROVIDER_LIST_PANE_WIDTH = 240.dp
private val SETTINGS_ROW_HEIGHT = 56.dp
private val MANAGE_CHEVRON_SIZE = 20.dp
private val PROFILE_SELECTED_BAR_WIDTH = 3.dp
private val PROFILE_SELECTED_BAR_HEIGHT = 24.dp
private val NAV_BACK_ICON_SIZE = 20.dp
private val NAV_BACK_TOUCH_SIZE = 32.dp
