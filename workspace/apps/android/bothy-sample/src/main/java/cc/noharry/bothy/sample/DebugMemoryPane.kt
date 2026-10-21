package cc.noharry.bothy.sample

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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * tasks on this car. Writes go through [DebugUserMemoryStore], the same book 小博's `user_memory` tool writes. The foot
 * clears the memories alone or, through [onClearAll] (清除运行数据 with the memories, once every run has stopped),
 * every conversation with them; it reports whether all of it went.
 */
@Composable
internal fun DebugMemoryPane(onClearAll: (done: (Boolean) -> Unit) -> Unit) {
    val context = LocalContext.current
    val memories by DebugUserMemoryStore.memories.collectAsState()
    var tab by rememberSaveable { mutableStateOf(MemoryTab.PROFILE.name) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var addingTo by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { DebugUserMemoryStore.book(context) } }
    // A memory forgotten meanwhile (by voice, or deleted on its page) closes its page.
    val open = openId?.let { id -> memories.firstOrNull { it.id == id } }
    val adding = DebugMemoryCategory.byId(addingTo)
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(DebugDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceLg),
    ) {
        when {
            open != null -> DebugMemoryDetail(open, onBack = { openId = null })
            adding != null -> DebugMemoryAdd(adding, onDone = { addingTo = null })
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceLg)) {
                    Text("记忆", style = DebugType.hero.copy(fontSize = MEMORY_TITLE_SIZE), color = DebugColors.text)
                    UsageSegmented(MemoryTab.entries, MemoryTab.valueOf(tab), MemoryTab::label, framed = false) { tab = it.name }
                }
                when (MemoryTab.valueOf(tab)) {
                    MemoryTab.PROFILE -> ProfileTab(memories, { openId = it.id }, { addingTo = it.id }, onClearAll)
                    MemoryTab.LESSONS -> DebugMemoryLessonsTab()
                }
            }
        }
    }
}

private enum class MemoryTab(val label: String) { PROFILE("用户画像"), LESSONS("操作经验") }

@Composable
private fun ProfileTab(
    memories: List<DebugUserMemory>,
    onOpen: (DebugUserMemory) -> Unit,
    onAdd: (DebugMemoryCategory) -> Unit,
    onClearAll: (done: (Boolean) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(debugUserMemoryEnabled(context)) }
    ProfileHeader(memories, enabled) { on ->
        setDebugUserMemoryEnabled(context, on)
        enabled = on
    }
    if (!enabled) {
        Text("记忆已关闭：小博不会再记新的事，也不会用已经记住的；记下的内容都还在，打开后照常使用。", style = DebugType.bodySmall, color = DebugColors.warning)
    } else if (memories.isEmpty()) {
        Text(
            "还没有记住什么。对小博说「记住我家在望京花园」「我开空调一般 22 度」「叫我老王」，或者在下面手动添加。",
            style = DebugType.bodySmall,
            color = DebugColors.muted,
        )
    }
    CategoryGrid(memories, onOpen, onAdd)
    ProfileFooter(memories, onClearAll)
}

/** Who 小博 thinks it talks to: a letter avatar, the name if told, the counts, and the switch for the whole of 记忆. */
@Composable
private fun ProfileHeader(memories: List<DebugUserMemory>, enabled: Boolean, onEnabled: (Boolean) -> Unit) {
    val summary = memorySummary(memories, System.currentTimeMillis())
    UsageCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(AVATAR_SIZE).clip(CircleShape).background(DebugColors.ink), contentAlignment = Alignment.Center) {
                Text(summary.name?.take(1) ?: "你", color = if (DebugColors.isDark) DebugColors.bg else Color.White, fontSize = AVATAR_FONT, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(DebugDimens.spaceLg))
            Column(Modifier.weight(1f)) {
                Text(summary.name ?: "小博记得的你", style = DebugType.hero, color = DebugColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(summary.line, style = DebugType.bodySmall, color = DebugColors.muted)
            }
            Column(horizontalAlignment = Alignment.End) {
                Switch(enabled, onEnabled, colors = debugSwitchColors())
                Text("让小博记住你说的事", style = DebugType.meta, color = DebugColors.muted)
            }
        }
        HorizontalDivider(color = DebugColors.borderSubtle)
        Row {
            summary.metrics.forEach { (value, label) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(value, style = DebugType.hero, color = DebugColors.text, maxLines = 1)
                    Text(label, style = DebugType.bodySmall, color = DebugColors.muted, maxLines = 1)
                }
            }
        }
    }
}

/** A card per kind, two to a row when there is room; empty kinds show what they hold, to say what can be told. */
@Composable
private fun CategoryGrid(memories: List<DebugUserMemory>, onOpen: (DebugUserMemory) -> Unit, onAdd: (DebugMemoryCategory) -> Unit) {
    val byCategory = memories.groupBy { it.category }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= TWO_COLUMNS) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceMd)) {
            DebugMemoryCategory.entries.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceMd)) {
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
    category: DebugMemoryCategory,
    items: List<DebugUserMemory>,
    onOpen: (DebugUserMemory) -> Unit,
    onAdd: (DebugMemoryCategory) -> Unit,
) {
    val add: @Composable () -> Unit = {
        if (items.size < MAX_PER_CATEGORY) TextButton(onClick = { onAdd(category) }) { Text("+ 添加") }
    }
    UsageCard("${category.label}${if (items.isEmpty()) "" else " · ${items.size}"}", add) {
        if (items.isEmpty()) Text(category.hint, style = DebugType.bodySmall, color = DebugColors.tertiary)
        items.sortedByDescending { it.updatedAtMillis }.forEach { memory -> MemoryRow(memory) { onOpen(memory) } }
    }
}

/** One memory in its card: the words, then who wrote it and when; tapped for its page. */
@Composable
private fun MemoryRow(memory: DebugUserMemory, onClick: () -> Unit) {
    val shape = RoundedCornerShape(DebugDimens.radiusMd)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(DebugColors.bg, shape).border(1.dp, DebugColors.borderSubtle, shape)
            .clickable(onClick = onClick).semantics { role = Role.Button }
            .padding(horizontal = DebugDimens.spaceMd, vertical = DebugDimens.spaceSm),
    ) {
        Text(memory.content, style = DebugType.body, color = DebugColors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("${memory.source.label} · ${shortDay(memory.updatedAtMillis)}", style = DebugType.meta, color = DebugColors.tertiary)
    }
}

/** The two ways to clear: the memories alone, or with every conversation too ([MemoryClear.ALL] is 清除运行数据). */
private enum class MemoryClear(val label: String) { MEMORIES("只清除记忆"), ALL("清除记忆和对话记录") }

@Composable
private fun ProfileFooter(memories: List<DebugUserMemory>, onClearAll: (done: (Boolean) -> Unit) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<MemoryClear?>(null) }
    // The words and whether they report success.
    var note by remember { mutableStateOf("" to true) }
    Text(
        "小博只记你亲口说过、关于你自己的事，不记密码、验证码、证件号和银行卡号；最多 $MAX_MEMORIES 件，每类 $MAX_PER_CATEGORY 件。" +
            "记忆只存在这台设备上，记忆本身不写进日志（对话里说到的话和其他对话一样会记下），「清除运行数据」不会删掉它。" +
            "「只清除记忆」保留以前的对话，小博翻看历史对话时仍可能看到你说过的话；设备换了人用，用「清除记忆和对话记录」。",
        style = DebugType.bodySmall,
        color = DebugColors.tertiary,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm)) {
        OutlinedButton(onClick = { confirm = MemoryClear.MEMORIES }, enabled = memories.isNotEmpty(), shape = RoundedCornerShape(DebugDimens.radiusMd)) {
            Text(MemoryClear.MEMORIES.label, color = if (memories.isNotEmpty()) DebugColors.danger else DebugColors.tertiary)
        }
        Button(
            onClick = { confirm = MemoryClear.ALL },
            shape = RoundedCornerShape(DebugDimens.radiusMd),
            colors = ButtonDefaults.buttonColors(containerColor = DebugColors.danger, contentColor = Color.White),
        ) { Text(MemoryClear.ALL.label) }
        val (words, ok) = note
        if (words.isNotEmpty()) Text(words, style = DebugType.bodySmall, color = if (ok) DebugColors.success else DebugColors.danger)
    }
    confirm?.let { kind ->
        ClearDialog(kind, memories.size, onDismiss = { confirm = null }) {
            confirm = null
            if (kind == MemoryClear.ALL) {
                note = "正在停止任务并清除…" to true
                onClearAll { done -> note = if (done) "已清除记忆和对话记录" to true else "没清除完：有任务没停下来或存储出错，请再试一次" to false }
            } else {
                scope.launch {
                    val done = withContext(Dispatchers.IO) { DebugUserMemoryStore.clear(context.applicationContext) }
                    note = if (done) "已清除记忆" to true else "没清除：设备存储出错了" to false
                }
            }
        }
    }
}

@Composable
private fun ClearDialog(kind: MemoryClear, count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val text = when (kind) {
        MemoryClear.MEMORIES -> "小博会忘掉记住的 $count 件事，不能恢复。以前的对话记录保留，小博翻看历史对话时仍可能看到你说过的话。"
        MemoryClear.ALL -> "会删除记住的 $count 件事和全部对话记录，连同操作经验、生成的图片和运行日志（和「清除运行数据」一样），" +
            "正在进行的任务会停止。模型服务、密钥和各项设置保留。不能恢复。"
    }
    DebugAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${kind.label}？") },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(kind.label, color = DebugColors.danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** The header's words: the name the user asked to be called, a line, and four counts. */
internal data class DebugMemorySummary(val name: String?, val line: String, val metrics: List<Pair<String, String>>)

internal fun memorySummary(memories: List<DebugUserMemory>, now: Long): DebugMemorySummary {
    val name = memories.filter { it.category == DebugMemoryCategory.IDENTITY }.sortedByDescending { it.updatedAtMillis }
        .firstNotNullOfOrNull { CALL_ME.find(it.content)?.groupValues?.get(1)?.replace(NAME_TAIL, "")?.trim()?.ifEmpty { null } }
    val first = memories.minOfOrNull { it.createdAtMillis }
    val days = first?.let { TimeUnit.MILLISECONDS.toDays((now - it).coerceAtLeast(0)) + 1 }
    val line = if (first == null) "还没有记住什么" else "小博记住了 ${memories.size} 件事 · 认识你 $days 天"
    val latest = memories.maxOfOrNull { it.updatedAtMillis }
    return DebugMemorySummary(
        name = name?.takeIf(String::isNotEmpty),
        line = line,
        metrics = listOf(
            "${memories.size}" to "记住的事",
            "${memories.count { it.source == DebugMemorySource.SAID }}" to "你说的",
            "${memories.map { it.category }.distinct().size}" to "涉及方面",
            (latest?.let(::shortDay) ?: "—") to "最近更新",
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
private val AVATAR_FONT = 24.sp
private val TWO_COLUMNS = 640.dp
