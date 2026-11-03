package cc.noharry.bothy.app.console

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.session.UiState
import cc.noharry.bothy.app.voice.WakeWord

/**
 * Settings on a phone, as the phone design draws them: what is changed most (the theme, holding the mic, the wake
 * word, the model, the connections) on one page, then every section, each opened on its own ([onOpen]).
 */
@Composable
internal fun SettingsPhoneHome(state: UiState, modifier: Modifier, onOpen: (SettingsSection) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
        HomeGroup(stringResource(R.string.settings_group_appearance)) { ThemeCard() }
        HomeGroup(stringResource(R.string.settings_section_voice)) {
            HomeRow(stringResource(R.string.voice_mic_hold), stringResource(R.string.voice_hold_to_talk)) {
                Text(stringResource(R.string.common_on), style = ConsoleType.meta, color = ConsoleColors.accent, fontWeight = FontWeight.SemiBold)
            }
            WakeRow()
        }
        HomeGroup(stringResource(R.string.settings_group_model)) {
            val model = state.configEditor.model.ifBlank { stringResource(R.string.console_choose_model) }
            HomeRow(model, stringResource(R.string.settings_model_in_use), onClick = { onOpen(SettingsSection.MODELS) }) { Chevron() }
        }
        HomeGroup(stringResource(R.string.settings_section_connections)) {
            HomeRow(
                stringResource(R.string.settings_connections_note),
                stringResource(R.string.settings_connections_action),
                onClick = { onOpen(SettingsSection.CONNECTIONS) },
            ) { Chevron() }
        }
        HomeGroup(stringResource(R.string.settings_all)) {
            SettingsSection.entries.forEach { section -> SectionRow(stringResource(section.label)) { onOpen(section) } }
        }
    }
}

@Composable
private fun HomeGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, style = ConsoleType.meta, color = ConsoleColors.muted, modifier = Modifier.padding(start = 4.dp, top = Dimens.spaceMd, bottom = 4.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.radiusLg)).background(ConsoleColors.elevated),
        content = content,
    )
}

@Composable
private fun HomeRow(title: String, note: String, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = Dimens.spaceMd, vertical = Dimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = ConsoleType.body, color = ConsoleColors.text, fontWeight = FontWeight.SemiBold)
            Text(note, style = ConsoleType.meta, color = ConsoleColors.muted)
        }
        trailing()
    }
}

@Composable
private fun SectionRow(title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = Dimens.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = ConsoleType.body, color = ConsoleColors.text, modifier = Modifier.weight(1f))
        Chevron()
    }
}

@Composable
private fun Chevron() {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = ConsoleColors.muted)
}

/** The wake word, applied at once as in 设置 → 语音. */
@Composable
private fun WakeRow() {
    val context = LocalContext.current
    val enabled by WakeWord.enabled.collectAsState()
    LaunchedEffect(Unit) { WakeWord.load(context) }
    HomeRow(stringResource(R.string.settings_wake_word), stringResource(R.string.settings_wake_word_note)) {
        Switch(checked = enabled, onCheckedChange = { WakeWord.setEnabled(context, it) }, colors = switchColors())
    }
}

/** The theme as three choices side by side. */
@Composable
private fun ThemeCard() {
    val context = LocalContext.current
    val selected = ConsoleAppearance.current
    Column(Modifier.padding(Dimens.spaceMd), verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        Text(stringResource(R.string.settings_theme), style = ConsoleType.body, color = ConsoleColors.text, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.settings_theme_short_note), style = ConsoleType.meta, color = ConsoleColors.muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
            ConsoleAppearance.Mode.entries.forEach { mode ->
                val on = mode == selected
                Text(
                    text = stringResource(mode.labelRes()),
                    style = ConsoleType.body,
                    color = if (on) ConsoleColors.accent else ConsoleColors.text,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(Dimens.radiusFull))
                        .background(if (on) ConsoleColors.accentSoft else ConsoleColors.panel)
                        .selectable(selected = on, role = Role.RadioButton) { ConsoleAppearance.set(context, mode) }
                        .padding(vertical = 11.dp),
                )
            }
        }
    }
}
