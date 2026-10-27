package cc.noharry.bothy.app.skills
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
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.outlinedTextFieldColors

/**
 * Add a skill by its SKILL.md text, or edit one added before ([editing] is its name): 检查格式 shows what
 * [checkCustomSkill] finds, and 保存 runs the same check, saving only a skill without errors.
 */
@Composable
internal fun CustomSkillForm(editing: String?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var text by rememberSaveable(editing) { mutableStateOf(editing?.let { CustomSkills.text(context, it) } ?: CUSTOM_SKILL_TEMPLATE) }
    var check by remember(editing) { mutableStateOf<SkillCheck?>(null) }
    var saveError by remember(editing) { mutableStateOf<String?>(null) }
    val checkNow = { checkCustomSkill(text, CustomSkills.takenNames(context, editing)).also { check = it } }
    Column(modifier.widthIn(max = 880.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
        ) {
            Text(
                "粘贴或输入 SKILL.md：开头用 --- 包住的 YAML 头写 name（小写字母、数字和 -）和 description（一行，写清做什么、什么时候用），" +
                    "下面是给小博的做法。保存后默认开启，下一次请求生效：description 进技能目录，用到时再读全文。",
                style = ConsoleType.bodySmall,
                color = ConsoleColors.muted,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; check = null; saveError = null },
                label = { Text("SKILL.md") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = ConsoleType.code,
                minLines = 14,
                colors = outlinedTextFieldColors(),
            )
            check?.let { SkillCheckReport(it) }
            saveError?.takeIf { check == null }?.let { Text(it, style = ConsoleType.bodySmall, color = ConsoleColors.danger) }
        }
        Spacer(Modifier.height(Dimens.spaceSm))
        HorizontalDivider(color = ConsoleColors.border)
        Spacer(Modifier.height(Dimens.spaceXs))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDone) { Text("取消") }
            Spacer(Modifier.width(Dimens.spaceSm))
            TextButton(onClick = { checkNow() }) { Text("检查格式") }
            Spacer(Modifier.width(Dimens.spaceSm))
            Button(
                onClick = {
                    if (checkNow().errors.isEmpty()) {
                        val error = CustomSkills.save(context, text, editing)
                        if (error == null) onDone() else { check = null; saveError = error }
                    }
                },
                shape = RoundedCornerShape(Dimens.radiusMd),
            ) { Text("保存") }
        }
    }
}

@Composable
private fun SkillCheckReport(check: SkillCheck) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
        if (check.errors.isEmpty()) {
            Text("格式没问题：${check.name}", style = ConsoleType.body, color = ConsoleColors.success)
        } else {
            Text("有 ${check.errors.size} 处要改，改好才能保存：", style = ConsoleType.body, color = ConsoleColors.danger)
            check.errors.forEach { Text("• $it", style = ConsoleType.bodySmall, color = ConsoleColors.danger) }
        }
        check.warnings.forEach { Text("• $it", style = ConsoleType.bodySmall, color = ConsoleColors.warning) }
    }
}
