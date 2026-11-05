package cc.noharry.bothy.app.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.assist.assistOffered
import cc.noharry.bothy.app.assist.isAssistant
import cc.noharry.bothy.app.assist.openAssistantSettings
import cc.noharry.bothy.app.console.AppLanguages
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleAppearance
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.SettingsLanguageGroup
import cc.noharry.bothy.app.console.SettingsLayoutGroup
import cc.noharry.bothy.app.console.SettingsSection
import cc.noharry.bothy.app.console.SettingsSectionContent
import cc.noharry.bothy.app.console.SettingsThemeGroup
import cc.noharry.bothy.app.console.label
import cc.noharry.bothy.app.console.labelRes
import cc.noharry.bothy.app.console.ui.IconMic
import cc.noharry.bothy.protocol.voice

/** Where the phone's settings are: their first page, the rest of them, a page of the phone's own, or a section. */
private sealed interface SettingsPlace {
    data object Home : SettingsPlace
    data object More : SettingsPlace
    data object Appearance : SettingsPlace
    data object Language : SettingsPlace
    data class Section(val section: SettingsSection) : SettingsPlace
}

/**
 * Settings on a phone, as the design groups them: common (the model, voice, appearance, language), 小博 (memory,
 * tools and skills, subagents), connections, and advanced (usage, then the rest). Each row opens a page with the
 * tablet's own settings for it ([SettingsSectionContent]).
 */
@Composable
internal fun PhoneSettings(state: ServerState, actions: ConsoleActions, onBack: () -> Unit, startAt: SettingsSection? = null) {
    var placeName by rememberSaveable { mutableStateOf(startAt?.name ?: "home") }
    val place = settingsPlace(placeName)
    val open = { next: SettingsSection -> placeName = next.name }
    val up = { placeName = if (place is SettingsPlace.Section && place.section in MORE_SECTIONS) "more" else "home" }
    BackHandler(enabled = place != SettingsPlace.Home, onBack = up)
    when (place) {
        SettingsPlace.Home -> PhonePage(stringResource(R.string.common_settings), onBack) {
            SettingsHome(state, Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) { placeName = it }
        }
        SettingsPlace.More -> PhonePage(stringResource(R.string.phone_settings_more), up) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                RowsCard(MORE_SECTIONS.map { section -> SettingsRowSpec(painterResource(R.drawable.ic_phone_tune), stringResource(section.label)) { open(section) } })
            }
        }
        SettingsPlace.Appearance -> PhonePage(stringResource(R.string.phone_settings_appearance), up) {
            ConsoleTypeOnPhone {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    SettingsThemeGroup()
                    SettingsLayoutGroup()
                }
            }
        }
        SettingsPlace.Language -> PhonePage(stringResource(R.string.settings_language), up) {
            ConsoleTypeOnPhone { Column(Modifier.weight(1f).fillMaxWidth()) { SettingsLanguageGroup() } }
        }
        is SettingsPlace.Section -> PhonePage(stringResource(place.section.label), up) {
            ConsoleTypeOnPhone { Box(Modifier.weight(1f).fillMaxWidth()) { SettingsSectionContent(place.section, state, actions, open) } }
        }
    }
}

private fun settingsPlace(name: String): SettingsPlace = when (name) {
    "home" -> SettingsPlace.Home
    "more" -> SettingsPlace.More
    "appearance" -> SettingsPlace.Appearance
    "language" -> SettingsPlace.Language
    else -> SettingsSection.entries.firstOrNull { it.name == name }?.let { SettingsPlace.Section(it) } ?: SettingsPlace.Home
}

@Composable
private fun SettingsHome(state: ServerState, modifier: Modifier, onOpen: (String) -> Unit) {
    Column(modifier) {
        CommonGroup(state, onOpen)
        Group(stringResource(R.string.phone_settings_bothy))
        RowsCard(
            listOf(
                SettingsRowSpec(painterResource(R.drawable.ic_phone_memory), stringResource(R.string.settings_section_memory)) { onOpen(SettingsSection.MEMORY.name) },
                SettingsRowSpec(rememberVectorPainter(Icons.Filled.Build), stringResource(R.string.settings_section_capabilities)) { onOpen(SettingsSection.CAPABILITIES.name) },
                SettingsRowSpec(rememberVectorPainter(Icons.Filled.Face), stringResource(R.string.settings_section_subagents)) { onOpen(SettingsSection.SUBAGENTS.name) },
            ),
        )
        Group(stringResource(R.string.settings_section_connections))
        RowsCard(
            listOf(
                SettingsRowSpec(rememberVectorPainter(Icons.Filled.Share), stringResource(R.string.phone_settings_connections)) { onOpen(SettingsSection.CONNECTIONS.name) },
            ),
        )
        Group(stringResource(R.string.phone_settings_advanced))
        RowsCard(
            listOf(
                SettingsRowSpec(painterResource(R.drawable.ic_phone_usage), stringResource(R.string.settings_section_usage)) { onOpen(SettingsSection.USAGE.name) },
                SettingsRowSpec(painterResource(R.drawable.ic_phone_tune), stringResource(R.string.phone_settings_more)) { onOpen("more") },
            ),
        )
        Text(BRAND, style = PhoneType.label, color = ConsoleColors.tertiary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
    }
}

/** A group's name over its card. */
@Composable
private fun Group(title: String) {
    Text(title, style = PhoneType.small, color = ConsoleColors.muted, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp))
}

/** A row of the settings: its icon, its name, what it is set to, and that it opens. */
private class SettingsRowSpec(val icon: Painter, val title: String, val value: String? = null, val onClick: () -> Unit)

/** Model, voice, the power key (where Bothy can be the assistant), appearance and language. */
@Composable
private fun CommonGroup(state: ServerState, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val voice = state.voice
    // Picked in the system's settings: read again each time the page comes back from there.
    var assistant by remember { mutableStateOf(isAssistant(context)) }
    LifecycleResumeEffect(Unit) {
        assistant = isAssistant(context)
        onPauseOrDispose {}
    }
    Group(stringResource(R.string.phone_settings_common))
    RowsCard(
        listOfNotNull(
            SettingsRowSpec(painterResource(R.drawable.ic_phone_model), stringResource(R.string.phone_settings_model), state.providerLabel) { onOpen(SettingsSection.MODELS.name) },
            SettingsRowSpec(
                rememberVectorPainter(IconMic),
                stringResource(R.string.settings_section_voice),
                stringResource(if (voice.wakeWordEnabled) R.string.phone_settings_wake_on else R.string.voice_mic_hold),
            ) { onOpen(SettingsSection.VOICE.name) },
            if (assistOffered) {
                SettingsRowSpec(
                    painterResource(R.drawable.ic_phone_power),
                    stringResource(R.string.phone_settings_power_key),
                    stringResource(if (assistant) R.string.phone_settings_power_key_on else R.string.phone_settings_power_key_off),
                ) { openAssistantSettings(context) }
            } else {
                null
            },
            SettingsRowSpec(painterResource(R.drawable.ic_phone_palette), stringResource(R.string.phone_settings_appearance), stringResource(ConsoleAppearance.current.labelRes())) { onOpen("appearance") },
            SettingsRowSpec(painterResource(R.drawable.ic_phone_language), stringResource(R.string.settings_language), AppLanguages.current(context).label()) { onOpen("language") },
        ),
    )
}

/** Rows on one white card, a hairline between them. */
@Composable
private fun RowsCard(rows: List<SettingsRowSpec>) {
    val colors = ConsoleColors
    Surface(shape = RoundedCornerShape(CARD_RADIUS), color = colors.elevated, border = BorderStroke(1.dp, colors.border), modifier = Modifier.fillMaxWidth()) {
        Column {
            rows.forEachIndexed { index, row ->
                if (index > 0) Box(Modifier.padding(start = 52.dp).fillMaxWidth().height(1.dp).background(colors.borderSubtle))
                SettingsRow(row)
            }
        }
    }
}

@Composable
private fun SettingsRow(row: SettingsRowSpec) {
    val colors = ConsoleColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = row.onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(row.icon, contentDescription = null, tint = colors.inkSoft, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(row.title, style = PhoneType.reply, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        row.value?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = PhoneType.body, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp).widthIn(max = VALUE_MAX_WIDTH))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.tertiary, modifier = Modifier.padding(start = 4.dp).size(20.dp))
    }
}

/** The sections behind "More settings", each the tablet's page for it. */
private val MORE_SECTIONS = listOf(
    SettingsSection.GENERAL,
    SettingsSection.IMAGE_MODEL,
    SettingsSection.COMPANION,
    SettingsSection.REALTIME,
    SettingsSection.DATA,
    SettingsSection.CHECKS,
)

/** Bothy's name, the same in every language. */
private const val BRAND = "Bothy"
private val VALUE_MAX_WIDTH = 140.dp
