package cc.noharry.bothy.app.console
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.a2a.endpointLabel
import cc.noharry.bothy.app.console.ui.SectionLabel
import cc.noharry.bothy.app.model.FetchFallback
import cc.noharry.bothy.app.model.ProviderConfig
import cc.noharry.bothy.app.model.ProviderDetail
import cc.noharry.bothy.app.model.ProviderDraft
import cc.noharry.bothy.app.model.ProviderEditState
import cc.noharry.bothy.app.model.ProviderProfileOption
import cc.noharry.bothy.app.model.SearchBackend
import cc.noharry.bothy.app.model.hasDeepSeekKey
import cc.noharry.bothy.app.session.ClearRuntimeDataDialog
import cc.noharry.bothy.app.session.UiState

internal enum class SettingsPage {
    OVERVIEW,
    PROVIDERS,
    PROVIDER_DETAIL,
    NEW_PROVIDER,
    RUNTIME_SKILLS,
    BASIC_TOOLS,
    JEV,
}

internal data class SettingsNavigationState(
    val page: SettingsPage = SettingsPage.OVERVIEW,
    val requestedProfileId: String? = null,
) {
    fun openProviders(): SettingsNavigationState = copy(page = SettingsPage.PROVIDERS, requestedProfileId = null)

    fun openProvider(profileId: String): SettingsNavigationState =
        copy(page = SettingsPage.PROVIDER_DETAIL, requestedProfileId = profileId)

    fun openNewProvider(): SettingsNavigationState = copy(page = SettingsPage.NEW_PROVIDER, requestedProfileId = null)

    fun back(): SettingsNavigationState? =
        when (page) {
            SettingsPage.OVERVIEW -> null
            SettingsPage.PROVIDERS,
            SettingsPage.RUNTIME_SKILLS,
            SettingsPage.BASIC_TOOLS,
            SettingsPage.JEV,
            -> SettingsNavigationState()
            SettingsPage.PROVIDER_DETAIL,
            SettingsPage.NEW_PROVIDER,
            -> openProviders()
        }

    fun showsProviderDetail(twoPane: Boolean): Boolean =
        twoPane && page in PROVIDER_PAGES || page == SettingsPage.PROVIDER_DETAIL || page == SettingsPage.NEW_PROVIDER
}

@Composable
internal fun SettingsPane(
    state: UiState,
    actions: ConsoleActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsShell(state, actions, onBack, modifier)
}

@Composable
private fun SettingsProfileModeGroup(editor: ProviderEditState, actions: ConsoleActions) {
    SettingsGroup(title = "Profile mode", subtitle = "Applies immediately.") {
        Box(Modifier.padding(Dimens.spaceMd)) {
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
internal fun SettingsRuntimeGroup(state: UiState, actions: ConsoleActions) {
    SettingsGroup(title = "Runtime", subtitle = "Applies immediately.") {
        Box(Modifier.padding(Dimens.spaceXs)) {
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
internal fun SettingsWorkspaceGroup(state: UiState, actions: ConsoleActions) {
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
internal fun SettingsAdvancedGroup(state: UiState, actions: ConsoleActions) {
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
internal fun SettingsRuntimeDataGroup(actions: ConsoleActions) {
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
    val colors = ConsoleColors
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
        SettingsSectionHeader(title = title, subtitle = subtitle.orEmpty())
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Dimens.radiusMd),
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
        color = ConsoleColors.borderSubtle,
        modifier = Modifier.padding(horizontal = Dimens.spaceMd),
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
    val colors = ConsoleColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SETTINGS_ROW_HEIGHT)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = ConsoleType.body, color = colors.text, fontWeight = FontWeight.Medium)
            if (!value.isNullOrBlank()) {
                Text(value, style = ConsoleType.meta, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(Dimens.spaceMd))
        SettingsStatusChip(label = actionLabel, danger = actionDanger)
    }
}

@Composable
internal fun ProviderSettingsContent(
    state: UiState,
    actions: ConsoleActions,
    navigation: SettingsNavigationState,
    twoPane: Boolean,
    onNavigationChange: (SettingsNavigationState) -> Unit,
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
            Spacer(Modifier.width(Dimens.spaceMd))
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
        SettingsPage.PROVIDERS -> ProviderListPane(
            editor = state.configEditor,
            actions = actions,
            onSelect = { id -> onNavigationChange(navigation.openProvider(id)) },
            onAdd = { onNavigationChange(navigation.openNewProvider()) },
            modifier = Modifier.fillMaxSize(),
        )
        SettingsPage.PROVIDER_DETAIL,
        SettingsPage.NEW_PROVIDER,
        -> ProviderDetailPane(
            editor = state.configEditor,
            actions = actions,
            navigation = navigation,
            onDone = { onNavigationChange(navigation.openProviders()) },
            onCreated = { id -> onNavigationChange(navigation.openProvider(id)) },
            modifier = Modifier.fillMaxSize(),
        )
        SettingsPage.OVERVIEW,
        SettingsPage.RUNTIME_SKILLS,
        SettingsPage.BASIC_TOOLS,
        SettingsPage.JEV,
        -> Unit
    }
}

@Composable
private fun ProviderListPane(
    editor: ProviderEditState,
    actions: ConsoleActions,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ConsoleColors
    Column(modifier) {
        SettingsSectionHeader(
            title = "服务商",
            subtitle = "选择服务后编辑连接和模型。",
        )
        Spacer(Modifier.height(Dimens.spaceSm))
        SettingsGroup(title = "Profile mode", subtitle = null) {
            Box(Modifier.padding(Dimens.spaceMd)) {
                ProfileModeSelector(editor, actions)
            }
        }
        Spacer(Modifier.height(Dimens.spaceMd))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${editor.profileOptions.size} profiles",
                style = ConsoleType.meta,
                color = colors.muted,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = actions.reloadConfig) { Text("Reload") }
            TextButton(onClick = onAdd) { Text("+ 添加服务") }
        }
        Spacer(Modifier.height(Dimens.spaceXsMd))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
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
    editor: ProviderEditState,
    actions: ConsoleActions,
    navigation: SettingsNavigationState,
    onDone: () -> Unit,
    onCreated: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ConsoleColors
    // Cap form width so trailing controls stay within scan distance on ultra-wide panels.
    Box(modifier) {
        Column(Modifier.widthIn(max = SETTINGS_CONTENT_MAX_WIDTH).fillMaxHeight()) {
            when {
                navigation.page == SettingsPage.NEW_PROVIDER -> ProviderDetail(
                    ProviderDraft.new(), actions, isNew = true, onDone = onDone, modifier = Modifier.weight(1f), onCreated = onCreated,
                )
                navigation.requestedProfileId != null && navigation.requestedProfileId != editor.profileId -> {
                    Text("Loading provider…", style = ConsoleType.meta, color = colors.muted)
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
    val colors = ConsoleColors
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(NAV_BACK_TOUCH_SIZE)
                .clip(RoundedCornerShape(Dimens.radiusFull))
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
        Spacer(Modifier.width(Dimens.spaceSm))
        Column(Modifier.weight(1f)) {
            Text(title, style = ConsoleType.panelTitle, color = colors.text)
            val sub = listOfNotNull(breadcrumb, hint).joinToString(" · ").ifBlank { null }
            if (!sub.isNullOrBlank()) {
                Text(
                    text = sub,
                    style = ConsoleType.meta,
                    color = colors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun SettingsNavigationState.pageTitle(): String =
    when (page) {
        SettingsPage.OVERVIEW -> "Settings"
        SettingsPage.PROVIDERS -> "Providers"
        SettingsPage.PROVIDER_DETAIL -> "Edit provider"
        SettingsPage.NEW_PROVIDER -> "New provider"
        SettingsPage.RUNTIME_SKILLS -> "Runtime skills"
        SettingsPage.BASIC_TOOLS -> "基础工具 / Basic tools"
        SettingsPage.JEV -> "JEV tool discovery"
    }

private fun SettingsNavigationState.breadcrumb(): String? =
    when (page) {
        SettingsPage.OVERVIEW -> null
        SettingsPage.PROVIDERS -> "Settings › Providers"
        SettingsPage.PROVIDER_DETAIL -> "Settings › Providers › Edit"
        SettingsPage.NEW_PROVIDER -> "Settings › Providers › New"
        SettingsPage.RUNTIME_SKILLS -> "Settings › Runtime skills"
        SettingsPage.BASIC_TOOLS -> "Settings › Basic tools"
        SettingsPage.JEV -> "Settings › JEV"
    }

private fun SettingsNavigationState.navHint(): String? =
    when (page) {
        SettingsPage.OVERVIEW -> "Provider, MCP, workspace and runtime options."
        SettingsPage.PROVIDERS -> "Tap a profile to edit"
        SettingsPage.PROVIDER_DETAIL -> "Save keeps changes on this profile"
        SettingsPage.NEW_PROVIDER -> "Fill fields, then Save"
        SettingsPage.RUNTIME_SKILLS -> "Toggles apply to the next request"
        SettingsPage.BASIC_TOOLS -> "Toggles apply to the next request"
        SettingsPage.JEV -> "Save applies to the next request"
    }

@Composable
private fun SettingsSectionHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXxs)) {
        SectionLabel(title)
        Text(subtitle, style = ConsoleType.meta, color = ConsoleColors.muted)
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
    val colors = ConsoleColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SETTINGS_ROW_HEIGHT)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
            Text(title, style = ConsoleType.body, color = colors.text, fontWeight = FontWeight.Medium)
            Text(
                value,
                style = ConsoleType.meta,
                color = colors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Dimens.spaceMd))
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
    val colors = ConsoleColors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.radiusSm))
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.spaceSm, vertical = Dimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = ConsoleType.body,
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
    val colors = ConsoleColors
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
        shape = RoundedCornerShape(Dimens.radiusFull),
        color = bg,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Text(
            label,
            style = ConsoleType.meta,
            color = fg,
            maxLines = 1,
            modifier = Modifier.padding(
                horizontal = Dimens.spaceMd,
                vertical = Dimens.spaceXxs,
            ),
        )
    }
}

/** 显示思考过程: the card's in-between notes, plan and step text, and the console's Thinking (see [DisplaySettings]). */
@Composable
private fun ShowThinkingRow() {
    val context = LocalContext.current
    val show by DisplaySettings.showThinking.collectAsState()
    Row(Modifier.fillMaxWidth().padding(Dimens.spaceSm), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("显示思考过程", style = ConsoleType.body, color = ConsoleColors.text)
            Text(
                if (show) "开：卡片上显示模型的中间想法、计划和当前步骤，控制台显示 Thinking"
                else "关：卡片只显示说给用户的进度和结果，控制台不显示 Thinking",
                style = ConsoleType.meta,
                color = ConsoleColors.muted,
            )
        }
        Switch(checked = show, onCheckedChange = { DisplaySettings.setShowThinking(context, it) }, colors = switchColors())
    }
}

/** 联网搜索 for the active profile: the provider's server-side search, or DeepSeek's Search API with visible results. */
@Composable
private fun SearchBackendRow() {
    val context = LocalContext.current
    val profile = remember { ProviderConfig.load(context.applicationContext).activeProfile }
    val usesApi by SearchBackend.usesApi.collectAsState()
    LaunchedEffect(Unit) { SearchBackend.load(context) }
    val api = usesApi[profile.id] == true
    Row(Modifier.fillMaxWidth().padding(Dimens.spaceSm), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("联网搜索用 DeepSeek Search 接口", style = ConsoleType.body, color = ConsoleColors.text)
            Text(
                if (api) "开：搜索词和结果都看得到，按次计费（5 元/千次），需要一个 DeepSeek 的 key"
                else "关：用「${profile.displayLabel}」自带的服务端搜索，看不到搜了什么",
                style = ConsoleType.meta,
                color = ConsoleColors.muted,
            )
        }
        Switch(checked = api, onCheckedChange = { SearchBackend.set(context, profile.id, it) }, colors = switchColors())
    }
}

/** Pages that refuse the device (携程 answers it with 432) read through DeepSeek's Fetch API, for every agent. */
@Composable
private fun FetchFallbackRow() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(FetchFallback.enabled(context)) }
    val keyed = remember { ProviderConfig.load(context.applicationContext).hasDeepSeekKey(context) }
    Row(Modifier.fillMaxWidth().padding(Dimens.spaceSm), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("网页打不开时用 DeepSeek Fetch", style = ConsoleType.body, color = ConsoleColors.text)
            Text(
                when {
                    on && !keyed -> "开，但没有 DeepSeek 的 key，暂不生效"
                    on -> "开：网站拦住设备的请求或打不开时，改由 Fetch 接口读取（每次 ¥0.005，只在被拦时花）；主 agent 和子 agent 都用，服务端搜索时主 agent 也能打开网页"
                    else -> "关：打不开就是打不开；服务端搜索时主 agent 不能打开网页"
                },
                style = ConsoleType.meta,
                color = ConsoleColors.muted,
            )
        }
        Switch(checked = on, onCheckedChange = {
            FetchFallback.set(context, it)
            on = it
        }, colors = switchColors())
    }
}

@Composable
private fun ProfileModeSelector(editor: ProviderEditState, actions: ConsoleActions) {
    val colors = ConsoleColors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Manual profile switching", style = ConsoleType.body, color = colors.text)
            Text(
                if (editor.manualProfileEnabled) {
                    "On — use the selected profile below instead of config default"
                } else {
                    "Off — follow the default profile from configuration"
                },
                style = ConsoleType.meta,
                color = colors.muted,
            )
        }
        Switch(
            checked = editor.manualProfileEnabled,
            onCheckedChange = actions.setManualProfileEnabled,
            colors = switchColors(),
        )
    }
}

@Composable
private fun ProviderProfileRow(option: ProviderProfileOption, onClick: () -> Unit, onDelete: () -> Unit) {
    val colors = ConsoleColors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.radiusSm),
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
                        .background(colors.accent, RoundedCornerShape(Dimens.radiusFull)),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = PROFILE_SELECTED_BAR_WIDTH + Dimens.spaceMd,
                        end = Dimens.spaceXs,
                        top = Dimens.spaceMd,
                        bottom = Dimens.spaceMd,
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
    option: ProviderProfileOption,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ConsoleColors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Dimens.radiusSm))
            .clickable(onClick = onClick)
            .padding(vertical = Dimens.spaceXxs, horizontal = Dimens.spaceXxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (option.selected) {
            Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = colors.accent) {}
            Spacer(Modifier.width(Dimens.spaceSm))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXxs)) {
            ProviderProfileTitle(option)
            Text(
                "${option.providerLabel} · ${option.endpointLabel} · ${option.model.ifBlank { "no model" }}",
                style = ConsoleType.meta,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Dimens.spaceSm))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Edit",
            tint = colors.muted,
            modifier = Modifier.size(MANAGE_CHEVRON_SIZE),
        )
    }
}

@Composable
private fun ProviderProfileTitle(option: ProviderProfileOption) {
    val colors = ConsoleColors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            option.label,
            style = ConsoleType.body,
            color = colors.text,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (option.selected) {
            Spacer(Modifier.width(Dimens.spaceSm))
            Text("Active", style = ConsoleType.label, color = colors.accent, fontWeight = FontWeight.SemiBold)
        }
    }
}

private val PROVIDER_PAGES = setOf(
    SettingsPage.PROVIDERS,
    SettingsPage.PROVIDER_DETAIL,
    SettingsPage.NEW_PROVIDER,
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
