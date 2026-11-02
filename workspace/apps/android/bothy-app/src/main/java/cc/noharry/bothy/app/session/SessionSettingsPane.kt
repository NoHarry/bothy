package cc.noharry.bothy.app.session
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.SettingsGroup
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.switchColors

/** 设置 → 通用 → 会话: when a wake starts a new conversation, and how many are kept ([SessionPolicy]). */
@Composable
internal fun SettingsSessionGroup() {
    val context = LocalContext.current
    val policy by SessionSettings.policy.collectAsState()
    fun save(next: SessionPolicy) = SessionSettings.save(context, next)
    SettingsGroup(
        title = stringResource(R.string.session_settings_title),
        subtitle = stringResource(R.string.session_settings_subtitle),
    ) {
        Row(Modifier.fillMaxWidth().padding(Dimens.spaceSm), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.session_every_wake), style = ConsoleType.body, color = ConsoleColors.text)
                val note = if (policy.everyWake) R.string.session_every_wake_on else R.string.session_every_wake_off
                Text(
                    stringResource(note),
                    style = ConsoleType.meta,
                    color = ConsoleColors.muted,
                )
            }
            Switch(checked = policy.everyWake, onCheckedChange = { save(policy.copy(everyWake = it)) }, colors = switchColors())
        }
        SessionDivider()
        SessionStepperRow(
            title = stringResource(R.string.session_idle_title),
            note = stringResource(if (policy.everyWake) R.string.session_idle_unused else R.string.session_idle_note),
            value = pluralStringResource(R.plurals.session_minutes, policy.idleMinutes, policy.idleMinutes),
            enabled = !policy.everyWake,
            onStep = { up -> save(policy.copy(idleMinutes = stepped(policy.idleMinutes, IDLE_MINUTE_STEPS, up))) },
        )
        SessionDivider()
        SessionStepperRow(
            title = stringResource(R.string.session_max_title),
            note = stringResource(R.string.session_max_note),
            value = pluralStringResource(R.plurals.session_count, policy.maxSessions, policy.maxSessions),
            enabled = true,
            onStep = { up -> save(policy.copy(maxSessions = stepped(policy.maxSessions, SESSION_COUNT_STEPS, up))) },
        )
    }
}

@Composable
private fun SessionStepperRow(title: String, note: String, value: String, enabled: Boolean, onStep: (up: Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(Dimens.spaceSm), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = ConsoleType.body, color = if (enabled) ConsoleColors.text else ConsoleColors.muted)
            Text(note, style = ConsoleType.meta, color = ConsoleColors.muted)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            StepButton("−", enabled) { onStep(false) }
            Text(
                value,
                style = ConsoleType.body,
                color = if (enabled) ConsoleColors.text else ConsoleColors.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 64.dp),
            )
            StepButton("+", enabled) { onStep(true) }
        }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(enabled = enabled, onClick = onClick) {
        Text(label, style = ConsoleType.body.copy(fontSize = 20.sp), color = if (enabled) ConsoleColors.accent else ConsoleColors.muted)
    }
}

@Composable
private fun SessionDivider() {
    HorizontalDivider(color = ConsoleColors.borderSubtle, modifier = Modifier.padding(horizontal = Dimens.spaceMd))
}

/** The next of [steps] above [value] (or below it), else [value] where it is already at an end. */
internal fun stepped(value: Int, steps: List<Int>, up: Boolean): Int =
    if (up) steps.firstOrNull { it > value } ?: value else steps.lastOrNull { it < value } ?: value

internal val IDLE_MINUTE_STEPS = listOf(1, 2, 3, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240)
internal val SESSION_COUNT_STEPS = listOf(1, 3, 5, 10, 20, 30, 50, 100, 200, 500)
