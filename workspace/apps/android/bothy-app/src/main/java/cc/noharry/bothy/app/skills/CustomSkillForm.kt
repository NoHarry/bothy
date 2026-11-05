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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.CapabilityRequests
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.IdParams
import cc.noharry.agent.appserver.protocol.SkillCheckView
import cc.noharry.agent.appserver.protocol.SkillTextParams
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.outlinedTextFieldColors
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.app.words

/**
 * Add a skill by its SKILL.md text, or edit one added before ([editing] is its name): 检查格式 shows what the
 * server's check finds, and 保存 runs the same check, saving only a skill without errors.
 */
@Composable
internal fun CustomSkillForm(editing: String?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val server = LocalServer.current
    // The skill as saved, or the template for a new one, comes from the server once.
    var loaded by rememberSaveable(editing) { mutableStateOf<String?>(null) }
    LaunchedEffect(editing) {
        if (loaded == null) server.send(CapabilityRequests.SKILL_TEXT, IdParams(editing.orEmpty())) { loaded = it.text }
    }
    val text = loaded ?: return
    var check by remember(editing) { mutableStateOf<SkillCheckView?>(null) }
    var saveError by remember(editing) { mutableStateOf<Finding?>(null) }
    val checkNow = { then: (SkillCheckView) -> Unit ->
        server.send(CapabilityRequests.CHECK_SKILL, SkillTextParams(text, editing)) { check = it; then(it) }
    }
    Column(modifier.widthIn(max = 880.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
        ) {
            Text(
                stringResource(R.string.skills_custom_hint),
                style = ConsoleType.bodySmall,
                color = ConsoleColors.muted,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { loaded = it; check = null; saveError = null },
                label = { Text("SKILL.md") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = ConsoleType.code,
                minLines = 14,
                colors = outlinedTextFieldColors(),
            )
            check?.let { SkillCheckReport(it) }
            saveError?.takeIf { check == null }?.let { Text(words(it), style = ConsoleType.bodySmall, color = ConsoleColors.danger) }
        }
        SkillFormButtons(onCancel = onDone, onCheck = { checkNow {} }) {
            checkNow { found ->
                if (found.errors.isEmpty()) {
                    server.send(CapabilityRequests.SAVE_SKILL, SkillTextParams(text, editing)) { saved ->
                        val error = saved.error
                        if (error == null) onDone() else { check = null; saveError = error }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillFormButtons(onCancel: () -> Unit, onCheck: () -> Unit, onSave: () -> Unit) {
    Spacer(Modifier.height(Dimens.spaceSm))
    HorizontalDivider(color = ConsoleColors.border)
    Spacer(Modifier.height(Dimens.spaceXs))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        Spacer(Modifier.width(Dimens.spaceSm))
        TextButton(onClick = onCheck) { Text(stringResource(R.string.skills_custom_check)) }
        Spacer(Modifier.width(Dimens.spaceSm))
        Button(onClick = onSave, shape = RoundedCornerShape(Dimens.radiusMd)) { Text(stringResource(R.string.common_save)) }
    }
}

@Composable
private fun SkillCheckReport(check: SkillCheckView) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
        if (check.errors.isEmpty()) {
            Text(
                stringResource(R.string.skills_custom_ok, check.name.orEmpty()),
                style = ConsoleType.body,
                color = ConsoleColors.success,
            )
        } else {
            Text(
                pluralStringResource(R.plurals.skills_custom_errors, check.errors.size, check.errors.size),
                style = ConsoleType.body,
                color = ConsoleColors.danger,
            )
            check.errors.forEach {
                Text("• ${words(it)}", style = ConsoleType.bodySmall, color = ConsoleColors.danger)
            }
        }
        check.warnings.forEach {
            Text("• ${words(it)}", style = ConsoleType.bodySmall, color = ConsoleColors.warning)
        }
    }
}
