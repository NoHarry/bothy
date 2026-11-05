package cc.noharry.bothy.app.phone

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.Plan
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.nextDueMillis
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.BothyAlertDialog
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.switchColors
import cc.noharry.bothy.app.plans.describeOnScreen
import java.time.ZoneId
import kotlinx.coroutines.delay

/**
 * Plans on a phone, as the phone design draws them: one line on what they are for, then a card for each, its name,
 * when it comes due and its switch. A long press deletes one, after asking. Going plans come first, by when they are
 * next due; ended ones last.
 */
@Composable
internal fun PhonePlans(state: ServerState, actions: ConsoleActions, onBack: () -> Unit) {
    val context = LocalContext.current
    val plans = state.plans
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var deleting by remember { mutableStateOf<Plan?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(CLOCK_TICK_MS)
        }
    }
    val ordered = remember(plans, now) { plans.sortedWith(compareBy<Plan>({ it.end != null }, { !it.enabled }, { it.nextDueMillis(now, ZoneId.systemDefault()) ?: Long.MAX_VALUE })) }
    PhonePage(stringResource(R.string.plans_title), onBack) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "intro") {
                Text(stringResource(R.string.phone_plans_intro), style = ConsoleType.body, color = ConsoleColors.muted, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            }
            if (plans.isEmpty()) item(key = "empty") { EmptyPlans() }
            items(ordered, key = { it.id }) { plan -> PlanCard(plan, now, onSwitch = { on -> actions.setPlanEnabled(plan.id, on) }) { deleting = plan } }
        }
    }
    deleting?.let { plan ->
        BothyAlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.plans_delete_title)) },
            text = { Text(stringResource(R.string.plans_delete_text, plan.title, plan.trigger.describeOnScreen(context, now, ZoneId.systemDefault()))) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    actions.deletePlan(plan.id)
                }) { Text(stringResource(R.string.common_delete), color = ConsoleColors.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlanCard(plan: Plan, now: Long, onSwitch: (Boolean) -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val colors = ConsoleColors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(Dimens.radiusLg))
            .background(colors.elevated)
            .combinedClickable(onClick = {}, onLongClick = onDelete)
            .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(plan.title, style = ConsoleType.body, color = if (plan.active) colors.text else colors.muted, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(plan.trigger.describeOnScreen(context, now, ZoneId.systemDefault()), style = ConsoleType.meta, color = colors.muted, maxLines = 2)
        }
        Switch(
            checked = plan.active,
            onCheckedChange = onSwitch,
            enabled = plan.end == null,
            colors = switchColors(),
        )
    }
}

@Composable
private fun EmptyPlans() {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.radiusLg)).background(ConsoleColors.elevated).padding(Dimens.spaceLg),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
    ) {
        Text(stringResource(R.string.plans_empty_title), style = ConsoleType.body, color = ConsoleColors.text, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.phone_plans_empty), style = ConsoleType.meta, color = ConsoleColors.muted)
    }
}

private const val CLOCK_TICK_MS = 30_000L
