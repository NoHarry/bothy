package cc.noharry.bothy.app.phone

import android.app.TimePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.FeatureRequests
import cc.noharry.agent.appserver.protocol.Plan
import cc.noharry.agent.appserver.protocol.PlanDraft
import cc.noharry.agent.appserver.protocol.PlanTrigger
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.nextDueMillis
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.BothyAlertDialog
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.switchColors
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.app.plans.describeOnScreen
import cc.noharry.bothy.app.words
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Plans on a phone, as the design draws them: a card for each (its name, when it comes due, its switch), a new plan
 * from the "+" ([NewPlanSheet]). A long press deletes one, after asking. Going plans come first, by when they are next
 * due; ended ones last.
 */
@Composable
internal fun PhonePlans(state: ServerState, actions: ConsoleActions, onBack: () -> Unit) {
    val context = LocalContext.current
    val plans = state.plans
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var deleting by remember { mutableStateOf<Plan?>(null) }
    var making by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(CLOCK_TICK_MS)
        }
    }
    val ordered = remember(plans, now) { plans.sortedWith(compareBy<Plan>({ it.end != null }, { !it.enabled }, { it.nextDueMillis(now, ZoneId.systemDefault()) ?: Long.MAX_VALUE })) }
    PhonePage(stringResource(R.string.plans_title), onBack, action = { RoundButton(Icons.Filled.Add, stringResource(R.string.phone_plan_new)) { making = true } }) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "intro") {
                Text(stringResource(R.string.phone_plans_intro), style = PhoneType.body, color = ConsoleColors.muted, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp))
            }
            if (plans.isEmpty()) item(key = "empty") { EmptyPlans() }
            items(ordered, key = { it.id }) { plan -> PlanCard(plan, now, onSwitch = { on -> actions.setPlanEnabled(plan.id, on) }) { deleting = plan } }
        }
    }
    if (making) NewPlanSheet { making = false }
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
    Surface(shape = RoundedCornerShape(CARD_RADIUS), color = colors.elevated, border = BorderStroke(1.dp, colors.border), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.combinedClickable(onClick = {}, onLongClick = onDelete).heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (plan.active) colors.accentSoft else colors.panel), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.DateRange, contentDescription = null, tint = if (plan.active) colors.accent else colors.muted, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(plan.title, style = PhoneType.reply, color = if (plan.active) colors.text else colors.muted, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(plan.trigger.describeOnScreen(context, now, ZoneId.systemDefault()), style = PhoneType.body, color = colors.muted, maxLines = 2)
            }
            Switch(
                checked = plan.active,
                onCheckedChange = onSwitch,
                enabled = plan.end == null,
                colors = switchColors(),
                modifier = Modifier.semantics { contentDescription = plan.title },
            )
        }
    }
}

@Composable
private fun EmptyPlans() {
    val colors = ConsoleColors
    Surface(shape = RoundedCornerShape(CARD_RADIUS), color = colors.elevated, border = BorderStroke(1.dp, colors.border), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.plans_empty_title), style = PhoneType.reply, color = colors.text, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.phone_plans_empty), style = PhoneType.body, color = colors.muted)
        }
    }
}

/**
 * What the new-plan sheet holds while it is filled in, kept across a rotation: its name; once (today or tomorrow) or
 * every week on some days, at a time; a reminder, or something for Bothy to do.
 */
private class PlanForm {
    var title by mutableStateOf("")
    var weekly by mutableStateOf(false)
    var tomorrow by mutableStateOf(true)
    var days by mutableStateOf(WORKDAYS)
    var minute by mutableIntStateOf(DEFAULT_MINUTE)
    var doIt by mutableStateOf(false)
    var what by mutableStateOf("")

    fun onceAt(zone: ZoneId): Long =
        LocalDate.now(zone).plusDays(if (tomorrow) 1 else 0).atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant().toEpochMilli()

    fun past(zone: ZoneId): Boolean = !weekly && onceAt(zone) <= System.currentTimeMillis()

    fun ready(zone: ZoneId): Boolean = title.isNotBlank() && what.isNotBlank() && !past(zone) && (!weekly || days.isNotEmpty())

    fun draft(zone: ZoneId): PlanDraft = PlanDraft(
        title = title.trim(),
        message = if (doIt) "" else what.trim(),
        instruction = if (doIt) what.trim() else null,
        trigger = if (weekly) PlanTrigger.Repeat(minute, days) else PlanTrigger.Once(onceAt(zone)),
    )

    companion object {
        val Saver = listSaver<PlanForm, Any>(
            save = { listOf(it.title, it.weekly, it.tomorrow, ArrayList(it.days), it.minute, it.doIt, it.what) },
            restore = { saved ->
                PlanForm().apply {
                    title = saved[0] as String
                    weekly = saved[1] as Boolean
                    tomorrow = saved[2] as Boolean
                    @Suppress("UNCHECKED_CAST")
                    days = (saved[3] as List<Int>).toSet()
                    minute = saved[4] as Int
                    doIt = saved[5] as Boolean
                    what = saved[6] as String
                }
            },
        )
    }
}

/**
 * A new plan, from the bottom: its name, when ([WhenFields]), then a reminder in Bothy's words or something for Bothy
 * to do. A plan that starts when something changes is said to Bothy instead.
 */
@Composable
private fun NewPlanSheet(onDismiss: () -> Unit) {
    val colors = ConsoleColors
    val context = LocalContext.current
    val server = LocalServer.current
    val form = rememberSaveable(saver = PlanForm.Saver) { PlanForm() }
    var refused by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val ready = form.ready(zone) && !saving
    PhoneSheet(onDismiss, stringResource(R.string.phone_plan_new)) {
        SheetField(form.title, { form.title = it.take(PLAN_TITLE_MAX) }, stringResource(R.string.phone_plan_name))
        Spacer(Modifier.size(12.dp))
        WhenFields(form, past = form.past(zone))
        Spacer(Modifier.size(16.dp))
        Segments(listOf(stringResource(R.string.phone_plan_remind), stringResource(R.string.phone_plan_do)), if (form.doIt) 1 else 0) { form.doIt = it == 1 }
        Spacer(Modifier.size(12.dp))
        SheetField(form.what, { form.what = it }, stringResource(if (form.doIt) R.string.phone_plan_do_hint else R.string.phone_plan_say_hint), lines = 3)
        Text(stringResource(R.string.phone_plan_watch_note), style = PhoneType.small, color = colors.muted, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        refused?.let { Text(it, style = PhoneType.label, color = colors.danger, modifier = Modifier.padding(start = 4.dp, top = 8.dp)) }
        Spacer(Modifier.size(20.dp))
        PillButton(stringResource(R.string.common_save), fill = colors.accent.copy(alpha = if (ready) 1f else DISABLED_ALPHA), text = onAccent(), Modifier.fillMaxWidth()) {
            // Looked at again on the tap: the time may have passed while the sheet was open.
            if (saving || !form.ready(zone)) return@PillButton
            saving = true
            refused = null
            server.send(
                FeatureRequests.PLAN_CREATE,
                form.draft(zone),
                onRefused = {
                    saving = false
                    refused = context.getString(R.string.plans_problem_not_saved)
                },
            ) { result ->
                saving = false
                result.error?.let { refused = context.resources.words(it) } ?: onDismiss()
            }
        }
    }
}

/** When the plan comes due: once or every week, the day or days, and the time; a time already past says so. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhenFields(form: PlanForm, past: Boolean) {
    val colors = ConsoleColors
    val context = LocalContext.current
    Segments(listOf(stringResource(R.string.phone_plan_once), stringResource(R.string.phone_plan_weekly)), if (form.weekly) 1 else 0) { form.weekly = it == 1 }
    Spacer(Modifier.size(12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!form.weekly) {
            Choice(stringResource(R.string.console_drawer_today), !form.tomorrow) { form.tomorrow = false }
            Choice(stringResource(R.string.phone_plan_tomorrow), form.tomorrow) { form.tomorrow = true }
        } else {
            DayOfWeek.entries.forEach { day ->
                Choice(day.getDisplayName(TextStyle.SHORT, Locale.getDefault()), day.value in form.days) {
                    form.days = if (day.value in form.days) form.days - day.value else form.days + day.value
                }
            }
        }
    }
    Spacer(Modifier.size(12.dp))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.elevated).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.phone_plan_time), style = PhoneType.body, color = colors.muted, modifier = Modifier.weight(1f))
        Row(
            Modifier
                .clip(CircleShape)
                .background(colors.panel)
                .clickable(role = Role.Button) { TimePickerDialog(context, { _, h, m -> form.minute = h * 60 + m }, form.minute / 60, form.minute % 60, true).show() }
                .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("%02d:%02d".format(form.minute / 60, form.minute % 60), style = PhoneType.label, color = colors.text, fontWeight = FontWeight.SemiBold)
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
        }
    }
    if (past) Text(stringResource(R.string.phone_plan_past), style = PhoneType.small, color = colors.danger, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
}

/** A text the sheet asks for, on a white rounded field. */
@Composable
private fun SheetField(value: String, onChange: (String) -> Unit, hint: String, lines: Int = 1) {
    val colors = ConsoleColors
    BasicTextField(
        value = value,
        onValueChange = onChange,
        maxLines = lines,
        minLines = 1,
        textStyle = PhoneType.reply.copy(color = colors.text),
        cursorBrush = SolidColor(colors.accent),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.elevated).heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 14.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, style = PhoneType.reply, color = colors.muted)
                inner()
            }
        },
    )
}

/** A choice between a few, side by side; the chosen one filled. */
@Composable
private fun Segments(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val colors = ConsoleColors
    Row(Modifier.fillMaxWidth().clip(CircleShape).background(colors.elevated).padding(4.dp).selectableGroup()) {
        labels.forEachIndexed { index, label ->
            val on = index == selected
            Text(
                label,
                style = PhoneType.label,
                color = if (on) onAccent() else colors.inkSoft,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (on) colors.accent else colors.elevated)
                    .selectable(selected = on, role = Role.RadioButton) { onPick(index) }
                    .padding(vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun Choice(label: String, on: Boolean, onClick: () -> Unit) {
    val colors = ConsoleColors
    Text(
        label,
        style = PhoneType.label,
        color = if (on) colors.accent else colors.inkSoft,
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (on) colors.accentSoft else colors.elevated)
            .toggleable(value = on, role = Role.Checkbox) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** As long as a plan's name may be (the server's limit). */
private const val PLAN_TITLE_MAX = 20
private const val CLOCK_TICK_MS = 30_000L
private const val DEFAULT_MINUTE = 8 * 60
private const val DISABLED_ALPHA = 0.35f
/** ISO day numbers, Monday 1 to Friday 5. */
private val WORKDAYS = setOf(1, 2, 3, 4, 5)
