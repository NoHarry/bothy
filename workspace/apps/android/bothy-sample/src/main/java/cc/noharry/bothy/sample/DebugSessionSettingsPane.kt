package cc.noharry.bothy.sample

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 设置 → 通用 → 会话: when a wake starts a new conversation, and how many are kept ([DebugSessionPolicy]). */
@Composable
internal fun SettingsSessionGroup() {
    val context = LocalContext.current
    val policy by DebugSessionSettings.policy.collectAsState()
    fun save(next: DebugSessionPolicy) = DebugSessionSettings.save(context, next)
    SettingsGroup(title = "会话", subtitle = "唤醒时什么时候开新会话，最多留几个") {
        Row(Modifier.fillMaxWidth().padding(DebugDimens.spaceSm), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("每次唤醒都开新会话", style = DebugType.body, color = DebugColors.text)
                Text(
                    if (policy.everyWake) "开：每次唤醒都从新会话开始，不接上一轮（后台任务还没回报的会话除外）"
                    else "关：隔一段时间没说话再唤醒才开新会话，之内接着聊，「再高一点」还能接上",
                    style = DebugType.meta,
                    color = DebugColors.muted,
                )
            }
            Switch(checked = policy.everyWake, onCheckedChange = { save(policy.copy(everyWake = it)) }, colors = debugSwitchColors())
        }
        SessionDivider()
        SessionStepperRow(
            title = "多久没说话再唤醒开新会话",
            note = if (policy.everyWake) "每次唤醒都开新会话时不用" else "离上一轮超过这么久，唤醒就开新会话",
            value = "${policy.idleMinutes} 分钟",
            enabled = !policy.everyWake,
            onStep = { up -> save(policy.copy(idleMinutes = stepped(policy.idleMinutes, IDLE_MINUTE_STEPS, up))) },
        )
        SessionDivider()
        SessionStepperRow(
            title = "最多保留会话",
            note = "超过时，开新会话那一刻删掉最久没用的，连同记录和生成的图片；正在运行或后台任务没回报的保留",
            value = "${policy.maxSessions} 个",
            enabled = true,
            onStep = { up -> save(policy.copy(maxSessions = stepped(policy.maxSessions, SESSION_COUNT_STEPS, up))) },
        )
    }
}

@Composable
private fun SessionStepperRow(title: String, note: String, value: String, enabled: Boolean, onStep: (up: Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(DebugDimens.spaceSm), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = DebugType.body, color = if (enabled) DebugColors.text else DebugColors.muted)
            Text(note, style = DebugType.meta, color = DebugColors.muted)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            StepButton("−", enabled) { onStep(false) }
            Text(
                value,
                style = DebugType.body,
                color = if (enabled) DebugColors.text else DebugColors.muted,
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
        Text(label, style = DebugType.body.copy(fontSize = 20.sp), color = if (enabled) DebugColors.accent else DebugColors.muted)
    }
}

@Composable
private fun SessionDivider() {
    HorizontalDivider(color = DebugColors.borderSubtle, modifier = Modifier.padding(horizontal = DebugDimens.spaceMd))
}

/** The next of [steps] above [value] (or below it), else [value] where it is already at an end. */
internal fun stepped(value: Int, steps: List<Int>, up: Boolean): Int =
    if (up) steps.firstOrNull { it > value } ?: value else steps.lastOrNull { it < value } ?: value

internal val IDLE_MINUTE_STEPS = listOf(1, 2, 3, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240)
internal val SESSION_COUNT_STEPS = listOf(1, 3, 5, 10, 20, 30, 50, 100, 200, 500)
