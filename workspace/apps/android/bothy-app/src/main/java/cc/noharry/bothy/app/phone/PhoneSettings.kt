package cc.noharry.bothy.app.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.A2aClientState
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.a2a.A2aClientActions
import cc.noharry.bothy.app.a2a.A2aClientPane
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleAppearance
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.FrontEnd
import cc.noharry.bothy.app.console.SettingsSection
import cc.noharry.bothy.app.console.SettingsSectionContent
import cc.noharry.bothy.app.console.labelRes
import cc.noharry.bothy.app.console.switchColors
import cc.noharry.bothy.protocol.voice

/**
 * Settings on a phone, as the phone design draws them: what is changed most (the theme, holding the mic, the wake
 * word, the model, the connections) on one page, then every section, each opened on a page of its own with the
 * tablet's own settings for it ([SettingsSectionContent]).
 */
@Composable
internal fun PhoneSettings(state: ServerState, actions: ConsoleActions, onBack: () -> Unit) {
    var sectionName by rememberSaveable { mutableStateOf<String?>(null) }
    val section = sectionName?.let { SettingsSection.valueOf(it) }
    val open = { next: SettingsSection -> sectionName = next.name }
    BackHandler(enabled = section != null) { sectionName = null }
    if (section == null) {
        PhonePage(stringResource(R.string.common_settings), onBack) {
            SettingsHome(state, actions, Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), open)
        }
    } else {
        PhonePage(stringResource(section.label), onBack = { sectionName = null }) {
            Box(Modifier.weight(1f).fillMaxWidth()) { SettingsSectionContent(section, state, actions, open) }
        }
    }
}

/** A2A is the client mode itself: its page is the A2A client, with its own input over A2A. */
@Composable
internal fun PhoneA2a(state: A2aClientState, actions: A2aClientActions, onBack: () -> Unit) {
    PhonePage(stringResource(R.string.console_view_a2a), onBack) {
        // The floating card is the tablet's: a phone has no resting 小博 to open it on.
        A2aClientPane(state = state, actions = actions.copy(openOverlay = null), header = {}, modifier = Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun SettingsHome(state: ServerState, actions: ConsoleActions, modifier: Modifier, onOpen: (SettingsSection) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
        HomeGroup(stringResource(R.string.settings_group_appearance)) {
            val context = LocalContext.current
            val themeNote = stringResource(R.string.settings_theme_short_note)
            SegmentCard(stringResource(R.string.settings_theme), themeNote, ConsoleAppearance.Mode.entries, ConsoleAppearance.current, { it.labelRes() }) {
                ConsoleAppearance.set(context, it)
            }
            // Tablet swaps to the console at once; it has the same setting to come back.
            SegmentCard(stringResource(R.string.settings_layout), stringResource(R.string.settings_layout_short_note), FrontEnd.Choice.entries, FrontEnd.choice, { it.labelRes() }) {
                FrontEnd.set(context, it)
            }
        }
        HomeGroup(stringResource(R.string.settings_section_voice)) {
            HomeRow(stringResource(R.string.voice_mic_hold), stringResource(R.string.voice_hold_to_talk)) {
                Text(stringResource(R.string.common_on), style = ConsoleType.meta, color = ConsoleColors.accent, fontWeight = FontWeight.SemiBold)
            }
            WakeRow(state, actions)
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
        Column(Modifier.weight(1f).padding(end = Dimens.spaceMd)) {
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
private fun WakeRow(state: ServerState, actions: ConsoleActions) {
    HomeRow(stringResource(R.string.settings_wake_word), stringResource(R.string.settings_wake_word_note)) {
        Switch(checked = state.voice.wakeWordEnabled, onCheckedChange = actions.setWakeWord, colors = switchColors())
    }
}

/** A setting as a few choices side by side: the theme, the layout. */
@Composable
private fun <T> SegmentCard(title: String, note: String, options: List<T>, selected: T, label: (T) -> Int, onPick: (T) -> Unit) {
    Column(Modifier.padding(Dimens.spaceMd), verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        Text(title, style = ConsoleType.body, color = ConsoleColors.text, fontWeight = FontWeight.SemiBold)
        Text(note, style = ConsoleType.meta, color = ConsoleColors.muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
            options.forEach { mode ->
                val on = mode == selected
                Text(
                    text = stringResource(label(mode)),
                    style = ConsoleType.meta,
                    maxLines = 1,
                    color = if (on) ConsoleColors.accent else ConsoleColors.text,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(Dimens.radiusFull))
                        .background(if (on) ConsoleColors.accentSoft else ConsoleColors.panel)
                        .selectable(selected = on, role = Role.RadioButton) { onPick(mode) }
                        .padding(vertical = 11.dp),
                )
            }
        }
    }
}
