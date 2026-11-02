package cc.noharry.bothy.app.memory
import android.content.Context
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.R
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
    TextButton(onClick = onBack) { Text(stringResource(R.string.memory_back)) }
    UsageCard(null) {
        CategoryPill(memory.category)
        Text(memory.content, style = ConsoleType.hero, color = ConsoleColors.text)
        HorizontalDivider(color = ConsoleColors.borderSubtle)
        InfoRow(stringResource(R.string.memory_info_source), stringResource(memory.source.label))
        memory.said?.let { said ->
            InfoRow(stringResource(R.string.memory_info_said), stringResource(R.string.memory_quoted, said))
        }
        InfoRow(stringResource(R.string.memory_info_created), fullTime(memory.createdAtMillis))
        if (memory.updatedAtMillis != memory.createdAtMillis) {
            InfoRow(stringResource(R.string.memory_info_updated), fullTime(memory.updatedAtMillis))
        }
    }
    UsageCard(stringResource(R.string.memory_edit_title)) {
        val saveLabel = stringResource(R.string.memory_save_changes)
        MemoryEditor(memory.category, memory.content, saveLabel = saveLabel, resetOnSave = false) { category, content ->
            UserMemoryStore.write(context) { it.update(memory.id, content, category, MemorySource.TYPED) }
        }
    }
    TextButton(onClick = { confirmDelete = true }) {
        Text(stringResource(R.string.memory_delete), color = ConsoleColors.danger)
    }
    if (confirmDelete) {
        BothyAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.memory_delete_title)) },
            text = { Text(stringResource(R.string.memory_delete_text, memory.content)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { withContext(Dispatchers.IO) { UserMemoryStore.write(context) { it.forget(memory.id) } } }
                }) { Text(stringResource(R.string.common_delete), color = ConsoleColors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/** Typing a memory by hand into [category] (changeable here); back to the profile once saved. */
@Composable
internal fun MemoryAdd(category: MemoryCategory, onDone: () -> Unit) {
    val context = LocalContext.current
    TextButton(onClick = onDone) { Text(stringResource(R.string.memory_back)) }
    Text(stringResource(R.string.memory_add_title), style = ConsoleType.hero, color = ConsoleColors.text)
    Text(stringResource(R.string.memory_add_note), style = ConsoleType.bodySmall, color = ConsoleColors.muted)
    UsageCard(null) {
        val saveLabel = stringResource(R.string.common_add)
        MemoryEditor(category, "", saveLabel = saveLabel, resetOnSave = true) { kind, content ->
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var category by remember(initialCategory) { mutableStateOf(initialCategory) }
    var content by remember(initialContent) { mutableStateOf(initialContent) }
    var note by remember { mutableStateOf(SettingsNote()) }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        val labels = MemoryCategory.entries.associateWith { stringResource(it.title) }
        UsageSegmented(MemoryCategory.entries, category, { labels.getValue(it) }) { category = it }
    }
    OutlinedTextField(
        value = content,
        onValueChange = { content = it.take(MAX_MEMORY_CHARS) },
        label = { Text(stringResource(R.string.memory_content_label, content.length, MAX_MEMORY_CHARS)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        colors = outlinedTextFieldColors(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) { save(category, content) }
                    note = editorNote(context, result)
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

private fun editorNote(context: Context, result: MemoryWrite): SettingsNote = when (result) {
    is MemoryWrite.Saved ->
        SettingsNote(context.getString(if (result.created) R.string.memory_note_added else R.string.memory_note_saved))
    is MemoryWrite.Removed -> SettingsNote(context.getString(R.string.memory_note_deleted))
    is MemoryWrite.Refused -> SettingsNote(refusalText(context, result), error = true)
}

/** A refused write in the page's language; [MemoryWrite.Refused.reason] is 小博's. */
internal fun refusalText(context: Context, refused: MemoryWrite.Refused): String = when (val why = refused.why) {
    MemoryRefusal.Empty -> context.getString(R.string.memory_refused_empty)
    MemoryRefusal.TooLong -> context.getString(R.string.memory_refused_too_long, MAX_MEMORY_CHARS)
    MemoryRefusal.Secret -> context.getString(R.string.memory_refused_secret)
    MemoryRefusal.LongNumber -> context.getString(R.string.memory_refused_long_number)
    MemoryRefusal.Full -> context.getString(R.string.memory_refused_full, MAX_MEMORIES)
    is MemoryRefusal.CategoryFull -> {
        val title = context.getString(why.category.title)
        context.getString(R.string.memory_refused_category_full, title, MAX_PER_CATEGORY)
    }
    is MemoryRefusal.Missing -> context.getString(R.string.memory_refused_missing, why.id)
    MemoryRefusal.NotSaved -> context.getString(R.string.memory_refused_not_saved)
    MemoryRefusal.Arguments -> refused.reason
}

@Composable
private fun CategoryPill(category: MemoryCategory) {
    Text(
        stringResource(category.title),
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
        stringResource(R.string.memory_lessons_note),
        style = ConsoleType.bodySmall,
        color = ConsoleColors.muted,
    )
    val loaded = evidence
    when {
        loaded == null -> {
            val loading = stringResource(R.string.memory_lessons_loading)
            Text(loading, style = ConsoleType.bodySmall, color = ConsoleColors.tertiary)
        }
        loaded.active.isEmpty() && loaded.pending.isEmpty() ->
            Text(stringResource(R.string.memory_lessons_empty), style = ConsoleType.body, color = ConsoleColors.muted)
        else -> {
            LessonCard(stringResource(R.string.memory_lessons_active, loaded.activeCount), loaded.active)
            LessonCard(stringResource(R.string.memory_lessons_pending, loaded.pendingCount), loaded.pending)
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
                    pluralStringResource(
                        R.plurals.memory_lesson_meta,
                        lesson.hitCount,
                        lesson.scene,
                        (lesson.confidence * PERCENT).toInt(),
                        lesson.hitCount,
                    ),
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
