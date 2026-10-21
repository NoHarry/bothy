package cc.noharry.bothy.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Add a skill by its SKILL.md text, or edit one added before ([editing] is its name): 检查格式 shows what
 * [checkCustomSkill] finds, and 保存 runs the same check, saving only a skill without errors.
 */
@Composable
internal fun CustomSkillForm(editing: String?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var text by rememberSaveable(editing) { mutableStateOf(editing?.let { DebugCustomSkills.text(context, it) } ?: CUSTOM_SKILL_TEMPLATE) }
    var check by remember(editing) { mutableStateOf<DebugSkillCheck?>(null) }
    var saveError by remember(editing) { mutableStateOf<String?>(null) }
    val checkNow = { checkCustomSkill(text, DebugCustomSkills.takenNames(context, editing)).also { check = it } }
    Column(modifier.widthIn(max = 880.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceMd),
        ) {
            Text(
                "粘贴或输入 SKILL.md：开头用 --- 包住的 YAML 头写 name（小写字母、数字和 -）和 description（一行，写清做什么、什么时候用），" +
                    "下面是给小博的做法。保存后默认开启，下一次请求生效：description 进技能目录，用到时再读全文。",
                style = DebugType.bodySmall,
                color = DebugColors.muted,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; check = null; saveError = null },
                label = { Text("SKILL.md") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = DebugType.code,
                minLines = 14,
                colors = debugOutlinedTextFieldColors(),
            )
            check?.let { SkillCheckReport(it) }
            saveError?.takeIf { check == null }?.let { Text(it, style = DebugType.bodySmall, color = DebugColors.danger) }
        }
        Spacer(Modifier.height(DebugDimens.spaceSm))
        HorizontalDivider(color = DebugColors.border)
        Spacer(Modifier.height(DebugDimens.spaceXs))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDone) { Text("取消") }
            Spacer(Modifier.width(DebugDimens.spaceSm))
            TextButton(onClick = { checkNow() }) { Text("检查格式") }
            Spacer(Modifier.width(DebugDimens.spaceSm))
            Button(
                onClick = {
                    if (checkNow().errors.isEmpty()) {
                        val error = DebugCustomSkills.save(context, text, editing)
                        if (error == null) onDone() else { check = null; saveError = error }
                    }
                },
                shape = RoundedCornerShape(DebugDimens.radiusMd),
            ) { Text("保存") }
        }
    }
}

@Composable
private fun SkillCheckReport(check: DebugSkillCheck) {
    Column(verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceXs)) {
        if (check.errors.isEmpty()) {
            Text("格式没问题：${check.name}", style = DebugType.body, color = DebugColors.success)
        } else {
            Text("有 ${check.errors.size} 处要改，改好才能保存：", style = DebugType.body, color = DebugColors.danger)
            check.errors.forEach { Text("• $it", style = DebugType.bodySmall, color = DebugColors.danger) }
        }
        check.warnings.forEach { Text("• $it", style = DebugType.bodySmall, color = DebugColors.warning) }
    }
}
