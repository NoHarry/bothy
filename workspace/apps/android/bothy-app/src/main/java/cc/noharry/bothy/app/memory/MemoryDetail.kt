package cc.noharry.bothy.app.memory
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.console.BothyAlertDialog
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.SettingsNote
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.loadRuntimeMemoryEvidence
import cc.noharry.bothy.app.console.outlinedTextFieldColors
import cc.noharry.bothy.app.evidence.RuntimeMemoryEvidence
import cc.noharry.bothy.app.session.LessonEntry
import cc.noharry.bothy.app.usage.UsageCard
import cc.noharry.bothy.app.usage.UsageSegmented
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One memory's own page: what it says, where it came from and when, then changing or deleting it. */
@Composable
internal fun MemoryDetail(memory: UserMemory, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    TextButton(onClick = onBack) { Text("‹ 记忆") }
    UsageCard(null) {
        CategoryPill(memory.category)
        Text(memory.content, style = ConsoleType.hero, color = ConsoleColors.text)
        HorizontalDivider(color = ConsoleColors.borderSubtle)
        InfoRow("来源", memory.source.label)
        memory.said?.let { InfoRow("原话", "「$it」") }
        InfoRow("记下时间", fullTime(memory.createdAtMillis))
        if (memory.updatedAtMillis != memory.createdAtMillis) InfoRow("最后修改", fullTime(memory.updatedAtMillis))
    }
    UsageCard("修改") {
        MemoryEditor(memory.category, memory.content, saveLabel = "保存修改", resetOnSave = false) { category, content ->
            UserMemoryStore.write(context) { it.update(memory.id, content, category, MemorySource.TYPED) }
        }
    }
    TextButton(onClick = { confirmDelete = true }) { Text("删除这条记忆", color = ConsoleColors.danger) }
    if (confirmDelete) {
        BothyAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条记忆？") },
            text = { Text("「${memory.content}」\n小博会忘掉这件事。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { withContext(Dispatchers.IO) { UserMemoryStore.write(context) { it.forget(memory.id) } } }
                }) { Text("删除", color = ConsoleColors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

/** Typing a memory by hand into [category] (changeable here); back to the profile once saved. */
@Composable
internal fun MemoryAdd(category: MemoryCategory, onDone: () -> Unit) {
    val context = LocalContext.current
    TextButton(onClick = onDone) { Text("‹ 记忆") }
    Text("添加记忆", style = ConsoleType.hero, color = ConsoleColors.text)
    Text("写一句完整的话，比如「家在望京花园 3 号楼」「开空调一般 22 度」。小博之后每次对话都会知道。", style = ConsoleType.bodySmall, color = ConsoleColors.muted)
    UsageCard(null) {
        MemoryEditor(category, "", saveLabel = "添加", resetOnSave = true) { kind, content ->
            UserMemoryStore.write(context) { it.remember(kind, content, MemorySource.TYPED) }.also { result ->
                if (result is MemoryWrite.Saved) withContext(Dispatchers.Main) { onDone() }
            }
        }
    }
}

/** The kind as pills and the words in a field; [save] runs off the main thread and its refusal shows under the field. */
@Composable
private fun MemoryEditor(
    initialCategory: MemoryCategory,
    initialContent: String,
    saveLabel: String,
    resetOnSave: Boolean,
    save: suspend (MemoryCategory, String) -> MemoryWrite,
) {
    val scope = rememberCoroutineScope()
    var category by remember(initialCategory) { mutableStateOf(initialCategory) }
    var content by remember(initialContent) { mutableStateOf(initialContent) }
    var note by remember { mutableStateOf(SettingsNote()) }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        UsageSegmented(MemoryCategory.entries, category, MemoryCategory::label) { category = it }
    }
    OutlinedTextField(
        value = content,
        onValueChange = { content = it.take(MAX_MEMORY_CHARS) },
        label = { Text("内容（${content.length}/$MAX_MEMORY_CHARS）") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        colors = outlinedTextFieldColors(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) { save(category, content) }
                    note = editorNote(result)
                    if (result is MemoryWrite.Saved && resetOnSave) content = ""
                }
            },
            enabled = content.isNotBlank() && (category != initialCategory || content.trim() != initialContent),
            shape = RoundedCornerShape(Dimens.radiusMd),
            colors = ButtonDefaults.buttonColors(containerColor = ConsoleColors.accent, contentColor = Color.White),
        ) { Text(saveLabel) }
        if (note.text.isNotEmpty()) {
            Text(note.text, Modifier.padding(start = Dimens.spaceMd), style = ConsoleType.bodySmall, color = if (note.error) ConsoleColors.danger else ConsoleColors.success)
        }
    }
}

private fun editorNote(result: MemoryWrite): SettingsNote = when (result) {
    is MemoryWrite.Saved -> SettingsNote(if (result.created) "已添加" else "已保存")
    is MemoryWrite.Removed -> SettingsNote("已删除")
    is MemoryWrite.Refused -> SettingsNote(result.reason, error = true)
}

@Composable
private fun CategoryPill(category: MemoryCategory) {
    Text(
        category.label,
        style = ConsoleType.meta,
        color = ConsoleColors.accent,
        modifier = Modifier.background(ConsoleColors.accentSoft, RoundedCornerShape(Dimens.radiusFull))
            .padding(horizontal = Dimens.spaceMd, vertical = Dimens.spaceXs),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(label, Modifier.width(INFO_LABEL_WIDTH), style = ConsoleType.bodySmall, color = ConsoleColors.muted)
        Text(value, Modifier.weight(1f), style = ConsoleType.bodySmall, color = ConsoleColors.text)
    }
}

/**
 * 操作经验: the runtime's lessons about doing tasks on this car (which tool answers a kind of request fastest, which keeps
 * failing), per scene. Read-only here; they are not about the user, and only lessons seen in several runs are used.
 */
@Composable
internal fun MemoryLessonsTab() {
    val context = LocalContext.current
    var evidence by remember { mutableStateOf<RuntimeMemoryEvidence?>(null) }
    LaunchedEffect(Unit) { evidence = withContext(Dispatchers.IO) { runCatching { loadRuntimeMemoryEvidence(context) }.getOrNull() } }
    Text(
        "小博办完事后总结的操作经验，比如某类请求用哪个接口最快、哪个接口一直失败。同一条经验在几次任务里都得到验证才会生效，" +
            "只用来把事办得更快更稳，和你的个人信息无关。",
        style = ConsoleType.bodySmall,
        color = ConsoleColors.muted,
    )
    val loaded = evidence
    when {
        loaded == null -> Text("正在读取…", style = ConsoleType.bodySmall, color = ConsoleColors.tertiary)
        loaded.active.isEmpty() && loaded.pending.isEmpty() -> Text("还没有操作经验", style = ConsoleType.body, color = ConsoleColors.muted)
        else -> {
            LessonCard("已生效 · ${loaded.activeCount}", loaded.active)
            LessonCard("待验证 · ${loaded.pendingCount}", loaded.pending)
        }
    }
}

@Composable
private fun LessonCard(title: String, lessons: List<LessonEntry>) {
    if (lessons.isEmpty()) return
    UsageCard(title) {
        lessons.sortedByDescending { it.hitCount }.forEachIndexed { index, lesson ->
            if (index > 0) HorizontalDivider(color = ConsoleColors.borderSubtle)
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXxs)) {
                Text(lesson.content, style = ConsoleType.bodySmall, color = ConsoleColors.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    "${lesson.scene} · 可信度 ${(lesson.confidence * PERCENT).toInt()}% · 验证 ${lesson.hitCount} 次",
                    style = ConsoleType.meta,
                    color = ConsoleColors.tertiary,
                )
            }
        }
    }
}

private fun fullTime(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(millis))

private val INFO_LABEL_WIDTH = 72.dp
private const val PERCENT = 100
