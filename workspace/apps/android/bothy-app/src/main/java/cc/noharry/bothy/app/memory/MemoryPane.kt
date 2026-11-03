package cc.noharry.bothy.app.memory
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.BothyAlertDialog
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.switchColors
import cc.noharry.bothy.app.model.summary
import cc.noharry.bothy.app.usage.UsageCard
import cc.noharry.bothy.app.usage.UsageSegmented
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → 记忆, beside 用量与费用: what 小博 remembers about the user as a profile (a header, then a card per kind;
 * a memory tapped opens its own page to change or delete it) and, on 操作经验, what the runtime learned about doing
 * tasks on this car. Writes go through [UserMemoryStore], the same book 小博's `user_memory` tool writes. The foot
 * clears the memories alone or, through [onClearAll] (清除运行数据 with the memories, once every run has stopped),
 * every conversation with them; it reports whether all of it went.
 */
@Composable
internal fun MemoryPane(onClearAll: (done: (Boolean) -> Unit) -> Unit) {
    val context = LocalContext.current
    val memories by UserMemoryStore.memories.collectAsState()
    var tab by rememberSaveable { mutableStateOf(MemoryTab.PROFILE.name) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var addingTo by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { UserMemoryStore.book(context) } }
    // A memory forgotten meanwhile (by voice, or deleted on its page) closes its page.
    val open = openId?.let { id -> memories.firstOrNull { it.id == id } }
    val adding = MemoryCategory.byId(addingTo)
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Dimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
    ) {
        when {
            open != null -> MemoryDetail(open, onBack = { openId = null })
            adding != null -> MemoryAdd(adding, onDone = { addingTo = null })
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceLg)) {
                    Text(
                        stringResource(R.string.memory_title),
                        style = ConsoleType.hero.copy(fontSize = MEMORY_TITLE_SIZE),
                        color = ConsoleColors.text,
                    )
                    val tabLabels = MemoryTab.entries.associateWith { stringResource(it.label) }
                    UsageSegmented(
                        MemoryTab.entries,
                        MemoryTab.valueOf(tab),
                        { tabLabels.getValue(it) },
                        framed = false,
                    ) { tab = it.name }
                }
                when (MemoryTab.valueOf(tab)) {
                    MemoryTab.PROFILE -> ProfileTab(memories, { openId = it.id }, { addingTo = it.id }, onClearAll)
                    MemoryTab.LESSONS -> MemoryLessonsTab()
                }
            }
        }
    }
}

private enum class MemoryTab(@StringRes val label: Int) {
    PROFILE(R.string.memory_tab_profile),
    LESSONS(R.string.memory_tab_lessons),
}

@Composable
private fun ProfileTab(
    memories: List<UserMemory>,
    onOpen: (UserMemory) -> Unit,
    onAdd: (MemoryCategory) -> Unit,
    onClearAll: (done: (Boolean) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(userMemoryEnabled(context)) }
    ProfileHeader(memories, enabled) { on ->
        setUserMemoryEnabled(context, on)
        enabled = on
    }
    if (!enabled) {
        Text(stringResource(R.string.memory_off_note), style = ConsoleType.bodySmall, color = ConsoleColors.warning)
    } else if (memories.isEmpty()) {
        Text(
            stringResource(R.string.memory_empty),
            style = ConsoleType.bodySmall,
            color = ConsoleColors.muted,
        )
    }
    CategoryGrid(memories, onOpen, onAdd)
    ProfileFooter(memories, onClearAll)
}

/** Who 小博 thinks it talks to: a letter avatar, the name if told, the counts, and the switch for the whole of 记忆. */
@Composable
private fun ProfileHeader(memories: List<UserMemory>, enabled: Boolean, onEnabled: (Boolean) -> Unit) {
    val summary = memorySummary(LocalContext.current, memories, System.currentTimeMillis())
    UsageCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(AVATAR_SIZE).clip(CircleShape).background(ConsoleColors.ink), contentAlignment = Alignment.Center) {
                Text(
                    summary.name?.take(1) ?: stringResource(R.string.memory_avatar_you),
                    color = if (ConsoleColors.isDark) ConsoleColors.bg else Color.White,
                    fontSize = AVATAR_FONT,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(Dimens.spaceLg))
            Column(Modifier.weight(1f)) {
                Text(
                    summary.name ?: stringResource(R.string.memory_name_fallback),
                    style = ConsoleType.hero,
                    color = ConsoleColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(summary.line, style = ConsoleType.bodySmall, color = ConsoleColors.muted)
            }
            Column(horizontalAlignment = Alignment.End) {
                Switch(enabled, onEnabled, colors = switchColors())
                // Kept narrow, wrapping under the switch: on a phone it would squeeze the summary to a letter's width.
                Text(
                    stringResource(R.string.memory_switch_label),
                    style = ConsoleType.meta,
                    color = ConsoleColors.muted,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(max = SWITCH_LABEL_WIDTH),
                )
            }
        }
        HorizontalDivider(color = ConsoleColors.borderSubtle)
        Row {
            summary.metrics.forEach { (value, label) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(value, style = ConsoleType.hero, color = ConsoleColors.text, maxLines = 1)
                    Text(label, style = ConsoleType.bodySmall, color = ConsoleColors.muted, maxLines = 2, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** A card per kind, two to a row when there is room; empty kinds show what they hold, to say what can be told. */
@Composable
private fun CategoryGrid(memories: List<UserMemory>, onOpen: (UserMemory) -> Unit, onAdd: (MemoryCategory) -> Unit) {
    val byCategory = memories.groupBy { it.category }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= TWO_COLUMNS) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
            MemoryCategory.entries.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
                    row.forEach { category ->
                        Box(Modifier.weight(1f)) { CategoryCard(category, byCategory[category].orEmpty(), onOpen, onAdd) }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(
    category: MemoryCategory,
    items: List<UserMemory>,
    onOpen: (UserMemory) -> Unit,
    onAdd: (MemoryCategory) -> Unit,
) {
    val add: @Composable () -> Unit = {
        if (items.size < MAX_PER_CATEGORY) {
            TextButton(onClick = { onAdd(category) }) { Text(stringResource(R.string.memory_add_button)) }
        }
    }
    val title = stringResource(category.title)
    UsageCard(if (items.isEmpty()) title else "$title · ${items.size}", add) {
        if (items.isEmpty()) {
            Text(stringResource(category.hint), style = ConsoleType.bodySmall, color = ConsoleColors.tertiary)
        }
        items.sortedByDescending { it.updatedAtMillis }.forEach { memory -> MemoryRow(memory) { onOpen(memory) } }
    }
}

/** One memory in its card: the words, then who wrote it and when; tapped for its page. */
@Composable
private fun MemoryRow(memory: UserMemory, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Dimens.radiusMd)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(ConsoleColors.bg, shape).border(1.dp, ConsoleColors.borderSubtle, shape)
            .clickable(onClick = onClick).semantics { role = Role.Button }
            .padding(horizontal = Dimens.spaceMd, vertical = Dimens.spaceSm),
    ) {
        Text(memory.content, style = ConsoleType.body, color = ConsoleColors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            "${stringResource(memory.source.label)} · ${shortDay(memory.updatedAtMillis)}",
            style = ConsoleType.meta,
            color = ConsoleColors.tertiary,
        )
    }
}

/** The two ways to clear: the memories alone, or with every conversation too ([MemoryClear.ALL] is 清除运行数据). */
private enum class MemoryClear(@StringRes val label: Int) {
    MEMORIES(R.string.memory_clear_memories),
    ALL(R.string.memory_clear_all),
}

@Composable
private fun ProfileFooter(memories: List<UserMemory>, onClearAll: (done: (Boolean) -> Unit) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<MemoryClear?>(null) }
    // The words and whether they report success.
    var note by remember { mutableStateOf("" to true) }
    val clearing = stringResource(R.string.memory_clearing)
    val clearedAll = stringResource(R.string.memory_cleared_all)
    val clearAllFailed = stringResource(R.string.memory_clear_all_failed)
    val cleared = stringResource(R.string.memory_cleared)
    val clearFailed = stringResource(R.string.memory_clear_failed)
    Text(
        stringResource(R.string.memory_footer, MAX_MEMORIES, MAX_PER_CATEGORY),
        style = ConsoleType.bodySmall,
        color = ConsoleColors.tertiary,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        OutlinedButton(onClick = { confirm = MemoryClear.MEMORIES }, enabled = memories.isNotEmpty(), shape = RoundedCornerShape(Dimens.radiusMd)) {
            Text(
                stringResource(MemoryClear.MEMORIES.label),
                color = if (memories.isNotEmpty()) ConsoleColors.danger else ConsoleColors.tertiary,
            )
        }
        Button(
            onClick = { confirm = MemoryClear.ALL },
            shape = RoundedCornerShape(Dimens.radiusMd),
            colors = ButtonDefaults.buttonColors(containerColor = ConsoleColors.danger, contentColor = Color.White),
        ) { Text(stringResource(MemoryClear.ALL.label)) }
        val (words, ok) = note
        if (words.isNotEmpty()) Text(words, style = ConsoleType.bodySmall, color = if (ok) ConsoleColors.success else ConsoleColors.danger)
    }
    confirm?.let { kind ->
        ClearDialog(kind, memories.size, onDismiss = { confirm = null }) {
            confirm = null
            if (kind == MemoryClear.ALL) {
                note = clearing to true
                onClearAll { done -> note = if (done) clearedAll to true else clearAllFailed to false }
            } else {
                scope.launch {
                    val done = withContext(Dispatchers.IO) { UserMemoryStore.clear(context.applicationContext) }
                    note = if (done) cleared to true else clearFailed to false
                }
            }
        }
    }
}

@Composable
private fun ClearDialog(kind: MemoryClear, count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val text = when (kind) {
        MemoryClear.MEMORIES -> pluralStringResource(R.plurals.memory_clear_memories_text, count, count)
        MemoryClear.ALL -> pluralStringResource(R.plurals.memory_clear_all_text, count, count)
    }
    val label = stringResource(kind.label)
    BothyAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_clear_title, label)) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(label, color = ConsoleColors.danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** The header's words: the name the user asked to be called, a line, and four counts. */
internal data class MemorySummary(val name: String?, val line: String, val metrics: List<Pair<String, String>>)

internal fun memorySummary(context: Context, memories: List<UserMemory>, now: Long): MemorySummary {
    val name = memories.filter { it.category == MemoryCategory.IDENTITY }.sortedByDescending { it.updatedAtMillis }
        .firstNotNullOfOrNull { CALL_ME.find(it.content)?.groupValues?.get(1)?.replace(NAME_TAIL, "")?.trim()?.ifEmpty { null } }
    val first = memories.minOfOrNull { it.createdAtMillis }
    val days = first?.let { (TimeUnit.MILLISECONDS.toDays((now - it).coerceAtLeast(0)) + 1).toInt() }
    val res = context.resources
    val line = if (days == null) {
        context.getString(R.string.memory_summary_empty)
    } else {
        res.getQuantityString(R.plurals.memory_summary_count, memories.size, memories.size) + " · " +
            res.getQuantityString(R.plurals.memory_summary_days, days, days)
    }
    val latest = memories.maxOfOrNull { it.updatedAtMillis }
    return MemorySummary(
        name = name?.takeIf(String::isNotEmpty),
        line = line,
        metrics = listOf(
            "${memories.size}" to context.getString(R.string.memory_metric_count),
            "${memories.count { it.source == MemorySource.SAID }}" to context.getString(R.string.memory_metric_said),
            "${memories.map { it.category }.distinct().size}" to context.getString(R.string.memory_metric_kinds),
            (latest?.let(::shortDay) ?: "—") to context.getString(R.string.memory_metric_latest),
        ),
    )
}

internal fun shortDay(millis: Long): String = SimpleDateFormat("M/d", Locale.ROOT).format(Date(millis))

/**
 * 「叫我老王」「我叫王磊」「称呼为老王」「希望被称为老王」「用户名叫杜强」: the name to show; the newest such memory of
 * 称呼与身份 wins. The tool asks for 「叫我…」, but models wrote 「称呼为老王」 and 「用户名叫杜强」 on the bench (2026-10-15).
 * 「别叫我老王」「不用叫我…」「不要再叫我…」 name nobody; 「是」 ends the name (「我叫王磊是程序员」), and a trailing 「吧」「就行」 is not part of it.
 */
private val CALL_ME = Regex("""(?<!别|不要|不|不用|再)(?:叫我|我叫|喊我|名叫|名字(?:是|叫)|称呼(?:我)?(?:为|是)?|称为)[：:\s「“]*([^，。,.；;！!？?\s」”是]{1,8})""")
private val NAME_TAIL = Regex("""(?:吧|就行|就好|就可以|好了|啊|呀|哦|嘛|了)+$""")
private val MEMORY_TITLE_SIZE = 24.sp
private val AVATAR_SIZE = 56.dp
private val SWITCH_LABEL_WIDTH = 140.dp
private val AVATAR_FONT = 24.sp
private val TWO_COLUMNS = 640.dp
