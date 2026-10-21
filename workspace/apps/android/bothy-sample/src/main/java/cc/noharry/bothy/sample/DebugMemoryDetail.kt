package cc.noharry.bothy.sample

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One memory's own page: what it says, where it came from and when, then changing or deleting it. */
@Composable
internal fun DebugMemoryDetail(memory: DebugUserMemory, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    TextButton(onClick = onBack) { Text("‹ 记忆") }
    UsageCard(null) {
        CategoryPill(memory.category)
        Text(memory.content, style = DebugType.hero, color = DebugColors.text)
        HorizontalDivider(color = DebugColors.borderSubtle)
        InfoRow("来源", memory.source.label)
        memory.said?.let { InfoRow("原话", "「$it」") }
        InfoRow("记下时间", fullTime(memory.createdAtMillis))
        if (memory.updatedAtMillis != memory.createdAtMillis) InfoRow("最后修改", fullTime(memory.updatedAtMillis))
    }
    UsageCard("修改") {
        MemoryEditor(memory.category, memory.content, saveLabel = "保存修改", resetOnSave = false) { category, content ->
            DebugUserMemoryStore.write(context) { it.update(memory.id, content, category, DebugMemorySource.TYPED) }
        }
    }
    TextButton(onClick = { confirmDelete = true }) { Text("删除这条记忆", color = DebugColors.danger) }
    if (confirmDelete) {
        DebugAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条记忆？") },
            text = { Text("「${memory.content}」\n小博会忘掉这件事。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { withContext(Dispatchers.IO) { DebugUserMemoryStore.write(context) { it.forget(memory.id) } } }
                }) { Text("删除", color = DebugColors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

/** Typing a memory by hand into [category] (changeable here); back to the profile once saved. */
@Composable
internal fun DebugMemoryAdd(category: DebugMemoryCategory, onDone: () -> Unit) {
    val context = LocalContext.current
    TextButton(onClick = onDone) { Text("‹ 记忆") }
    Text("添加记忆", style = DebugType.hero, color = DebugColors.text)
    Text("写一句完整的话，比如「家在望京花园 3 号楼」「开空调一般 22 度」。小博之后每次对话都会知道。", style = DebugType.bodySmall, color = DebugColors.muted)
    UsageCard(null) {
        MemoryEditor(category, "", saveLabel = "添加", resetOnSave = true) { kind, content ->
            DebugUserMemoryStore.write(context) { it.remember(kind, content, DebugMemorySource.TYPED) }.also { result ->
                if (result is DebugMemoryWrite.Saved) withContext(Dispatchers.Main) { onDone() }
            }
        }
    }
}

/** The kind as pills and the words in a field; [save] runs off the main thread and its refusal shows under the field. */
@Composable
private fun MemoryEditor(
    initialCategory: DebugMemoryCategory,
    initialContent: String,
    saveLabel: String,
    resetOnSave: Boolean,
    save: suspend (DebugMemoryCategory, String) -> DebugMemoryWrite,
) {
    val scope = rememberCoroutineScope()
    var category by remember(initialCategory) { mutableStateOf(initialCategory) }
    var content by remember(initialContent) { mutableStateOf(initialContent) }
    var note by remember { mutableStateOf(SettingsNote()) }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        UsageSegmented(DebugMemoryCategory.entries, category, DebugMemoryCategory::label) { category = it }
    }
    OutlinedTextField(
        value = content,
        onValueChange = { content = it.take(MAX_MEMORY_CHARS) },
        label = { Text("内容（${content.length}/$MAX_MEMORY_CHARS）") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        colors = debugOutlinedTextFieldColors(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) { save(category, content) }
                    note = editorNote(result)
                    if (result is DebugMemoryWrite.Saved && resetOnSave) content = ""
                }
            },
            enabled = content.isNotBlank() && (category != initialCategory || content.trim() != initialContent),
            shape = RoundedCornerShape(DebugDimens.radiusMd),
            colors = ButtonDefaults.buttonColors(containerColor = DebugColors.accent, contentColor = Color.White),
        ) { Text(saveLabel) }
        if (note.text.isNotEmpty()) {
            Text(note.text, Modifier.padding(start = DebugDimens.spaceMd), style = DebugType.bodySmall, color = if (note.error) DebugColors.danger else DebugColors.success)
        }
    }
}

private fun editorNote(result: DebugMemoryWrite): SettingsNote = when (result) {
    is DebugMemoryWrite.Saved -> SettingsNote(if (result.created) "已添加" else "已保存")
    is DebugMemoryWrite.Removed -> SettingsNote("已删除")
    is DebugMemoryWrite.Refused -> SettingsNote(result.reason, error = true)
}

@Composable
private fun CategoryPill(category: DebugMemoryCategory) {
    Text(
        category.label,
        style = DebugType.meta,
        color = DebugColors.accent,
        modifier = Modifier.background(DebugColors.accentSoft, RoundedCornerShape(DebugDimens.radiusFull))
            .padding(horizontal = DebugDimens.spaceMd, vertical = DebugDimens.spaceXs),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(label, Modifier.width(INFO_LABEL_WIDTH), style = DebugType.bodySmall, color = DebugColors.muted)
        Text(value, Modifier.weight(1f), style = DebugType.bodySmall, color = DebugColors.text)
    }
}

/**
 * 操作经验: the runtime's lessons about doing tasks on this car (which tool answers a kind of request fastest, which keeps
 * failing), per scene. Read-only here; they are not about the user, and only lessons seen in several runs are used.
 */
@Composable
internal fun DebugMemoryLessonsTab() {
    val context = LocalContext.current
    var evidence by remember { mutableStateOf<DebugRuntimeMemoryEvidence?>(null) }
    LaunchedEffect(Unit) { evidence = withContext(Dispatchers.IO) { runCatching { loadDebugRuntimeMemoryEvidence(context) }.getOrNull() } }
    Text(
        "小博办完事后总结的操作经验，比如某类请求用哪个接口最快、哪个接口一直失败。同一条经验在几次任务里都得到验证才会生效，" +
            "只用来把事办得更快更稳，和你的个人信息无关。",
        style = DebugType.bodySmall,
        color = DebugColors.muted,
    )
    val loaded = evidence
    when {
        loaded == null -> Text("正在读取…", style = DebugType.bodySmall, color = DebugColors.tertiary)
        loaded.active.isEmpty() && loaded.pending.isEmpty() -> Text("还没有操作经验", style = DebugType.body, color = DebugColors.muted)
        else -> {
            LessonCard("已生效 · ${loaded.activeCount}", loaded.active)
            LessonCard("待验证 · ${loaded.pendingCount}", loaded.pending)
        }
    }
}

@Composable
private fun LessonCard(title: String, lessons: List<DebugLessonEntry>) {
    if (lessons.isEmpty()) return
    UsageCard(title) {
        lessons.sortedByDescending { it.hitCount }.forEachIndexed { index, lesson ->
            if (index > 0) HorizontalDivider(color = DebugColors.borderSubtle)
            Column(verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceXxs)) {
                Text(lesson.content, style = DebugType.bodySmall, color = DebugColors.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    "${lesson.scene} · 可信度 ${(lesson.confidence * PERCENT).toInt()}% · 验证 ${lesson.hitCount} 次",
                    style = DebugType.meta,
                    color = DebugColors.tertiary,
                )
            }
        }
    }
}

private fun fullTime(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(millis))

private val INFO_LABEL_WIDTH = 72.dp
private const val PERCENT = 100
