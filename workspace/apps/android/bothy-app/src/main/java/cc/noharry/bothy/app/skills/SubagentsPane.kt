package cc.noharry.bothy.app.skills
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.SettingsGroup
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.switchColors
import cc.noharry.bothy.app.model.ProviderConfig
import cc.noharry.bothy.app.model.hasDeepSeekKey

/**
 * Settings → 子 agent (owner 2026-10-17: 子 agent 在设置里面单列一条): whether the agent starts subagents, how they
 * search and whether they may control the device, then the kinds of subagent it may name, each with its tools; tapping one
 * opens its page ([SubagentKindPage]). Each applies from the next spawn.
 */
@Composable
internal fun SubagentsPane() {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    open?.let { id ->
        SubagentKindPage(id, onBack = { open = null })
        return
    }
    SubagentsOverview(onOpen = { open = it })
}

@Composable
private fun SubagentsOverview(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val settings by BothySubagents.settings.collectAsState()
    val save = { next: SubagentSettings -> saveSubagentSettings(context, next) }
    SettingsGroup(title = "子 agent", subtitle = "下一次派子 agent 时生效。") {
        Column {
            SubagentSwitchRow(
                "AI 自主开子 agent", settings.enabled,
                "开：可以并行的耗时任务，由 AI 自己决定交给后台子 agent，卡片上显示进度",
                "关：AI 自己一步步做，不开子 agent；已经在跑的会照常完成并回报",
            ) { save(settings.copy(enabled = it)) }
            SubagentSwitchRow(
                "用 DeepSeek Search 接口搜索", settings.searchApi,
                "开：子 agent 用 Search 接口（每次 ¥0.005），结果短、输入 token 少；主 agent 照旧",
                "关：子 agent 和主 agent 一样搜索",
            ) { save(settings.copy(searchApi = it)) }
            SubagentSwitchRow(
                "允许子 agent 操作设备", settings.allowDeviceControl,
                "开：子 agent 可以执行任务要求的设备操作",
                "关：子 agent 只能查询，要操作的写进结果，由主 agent 决定",
            ) { save(settings.copy(allowDeviceControl = it)) }
            Text(
                "模型：${settings.model.ifBlank { "跟主 agent 一样" }} · 每个子 agent 打开网页和搜索最多 $MAX_CHILD_LOOKUPS 次",
                Modifier.padding(Dimens.spaceSm),
                style = ConsoleType.meta,
                color = ConsoleColors.muted,
            )
        }
    }
    SettingsGroup(
        title = "子 agent 类型",
        subtitle = "开着的类型会出现在 spawn_agent 里，主 agent 按类型派活、只写具体信息；关掉后技能会把要求写全，交给通用子 agent。点开一个类型可以改它能用的工具。",
    ) {
        SubagentKinds(onOpen)
    }
}

@Composable
private fun SubagentSwitchRow(title: String, checked: Boolean, on: String, off: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(Dimens.spaceSm), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = ConsoleType.body, color = ConsoleColors.text)
            Text(if (checked) on else off, style = ConsoleType.meta, color = ConsoleColors.muted)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = switchColors())
    }
}

/** Each kind with its switch and the tools it may call; tapping one opens its page. */
@Composable
private fun SubagentKinds(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    var kinds by remember { mutableStateOf(loadSubagentKinds(context)) }
    val deepSeekLookup = remember { ProviderConfig.load(context.applicationContext).hasDeepSeekKey(context) }
    Column {
        kinds.forEachIndexed { index, kind ->
            if (index > 0) HorizontalDivider(color = ConsoleColors.borderSubtle)
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(kind.id) }.padding(Dimens.spaceSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
                    Text("${kind.label} · ${kind.id}", style = ConsoleType.body, color = ConsoleColors.text)
                    SubagentToolChips(kind.tools)
                    val loaded = when {
                        !kind.enabled -> "未加载"
                        kind.requiresDeepSeekLookup && !deepSeekLookup -> "需要 DeepSeek 的 key（官方接口或检测通过的中转），现在不会派"
                        else -> "已加载"
                    }
                    Text(
                        "最多查 ${kind.maxLookups} 次 · $loaded",
                        style = ConsoleType.meta,
                        color = ConsoleColors.muted,
                    )
                }
                Switch(kind.enabled, onCheckedChange = {
                    saveSubagentKind(context, kind.id, it)
                    kinds = loadSubagentKinds(context)
                }, colors = switchColors())
                Text("›", Modifier.padding(start = Dimens.spaceMd), style = ConsoleType.panelTitle, color = ConsoleColors.muted)
            }
        }
    }
}

/** A type's tools as small tags; a red dot marks the ones that change something (as on the type's page). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubagentToolChips(tools: Set<SubagentToolGroup>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
        SubagentToolGroup.entries.filter { it in tools }.forEach { group ->
            Row(
                Modifier
                    .background(ConsoleColors.accentSoft, RoundedCornerShape(Dimens.radiusSm))
                    .padding(horizontal = Dimens.spaceXsMd, vertical = Dimens.spaceXxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXxs),
            ) {
                Text(group.label, style = ConsoleType.meta, color = ConsoleColors.accent)
                if (group.changes) ChangesDot()
            }
        }
        if (tools.isEmpty()) Text("不能用任何工具", style = ConsoleType.meta, color = ConsoleColors.danger)
    }
}

/** The red dot beside a tool that changes something. */
@Composable
internal fun ChangesDot() {
    Box(Modifier.size(Dimens.spaceXsMd).background(ConsoleColors.danger, CircleShape))
}
