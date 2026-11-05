package cc.noharry.bothy.app.evidence
import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.FlagParams
import cc.noharry.agent.appserver.protocol.IdsParams
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.clockSeconds
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.SettingsSection
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.app.overlay.OverlayControl
import cc.noharry.bothy.app.words
import cc.noharry.bothy.protocol.CheckFixKind
import cc.noharry.bothy.protocol.CheckGroup
import cc.noharry.bothy.protocol.CheckId
import cc.noharry.bothy.protocol.CheckRequests
import cc.noharry.bothy.protocol.CheckStatus
import cc.noharry.bothy.protocol.CheckView
import cc.noharry.bothy.protocol.ChecksView
import cc.noharry.bothy.protocol.VoiceRequests
import cc.noharry.bothy.protocol.checks
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings → 检查: one tap checks the selected models, voice, permissions and services, each line with what was found
 * and, when something is off, the way to fix it (a Settings section, a system screen, or switching it on here).
 */
@Composable
internal fun HealthPane(openSection: (SettingsSection) -> Unit) {
    val context = LocalContext.current
    val server = LocalServer.current
    val scope = rememberCoroutineScope()
    val checks = server.state.checks
    val runChecks = { ids: Set<CheckId> -> server.send(CheckRequests.RUN, IdsParams(ids.map { it.name })) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        runChecks(PERMISSION_CHECK_IDS)
    }
    val fix = { item: CheckFixKind ->
        applyCheckFix(context, item, openSection, requestPermission = { permission.launch(it) }) {
            if (item == CheckFixKind.WAKE_ON) server.send(VoiceRequests.SET_WAKE_WORD, FlagParams(true))
            // The overlay starts asynchronously; look again once it has had a moment to attach the wake word.
            scope.launch {
                delay(WAKE_RECHECK_DELAY_MS)
                runChecks(setOf(CheckId.WAKE))
            }
        }
    }
    Column(
        Modifier.fillMaxHeight().widthIn(max = 880.dp).verticalScroll(rememberScrollState()).padding(Dimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
    ) {
        CheckHeader(checks) { runChecks(emptySet()) }
        checks.checks.groupBy { it.group }.forEach { (group, inGroup) -> CheckGroup(stringResource(group.title), inGroup, fix) }
    }
}

@Composable
private fun CheckHeader(checks: ChecksView, onRun: () -> Unit) {
    val running = checks.running
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.checks_title), style = ConsoleType.panelTitle, color = ConsoleColors.text)
            Text(checkSummary(checks), style = ConsoleType.bodySmall, color = ConsoleColors.muted)
        }
        Button(
            onClick = onRun,
            enabled = !running,
            shape = RoundedCornerShape(Dimens.radiusMd),
            colors = ButtonDefaults.buttonColors(containerColor = ConsoleColors.accent, contentColor = if (ConsoleColors.isDark) ConsoleColors.bg else Color.White),
        ) { Text(stringResource(if (running) R.string.checks_running else R.string.checks_start)) }
    }
    Text(
        stringResource(R.string.checks_about),
        style = ConsoleType.bodySmall,
        color = ConsoleColors.muted,
    )
}

@Composable
private fun checkSummary(checks: ChecksView): String {
    val states = checks.checks.mapNotNull { it.status }
    if (states.isEmpty()) return stringResource(R.string.checks_not_yet)
    val counts = states.groupingBy { it }.eachCount()
    val parts = listOfNotNull(
        counts[CheckStatus.OK]?.let { pluralStringResource(R.plurals.checks_count_ok, it, it) },
        counts[CheckStatus.WARN]?.let { pluralStringResource(R.plurals.checks_count_warn, it, it) },
        counts[CheckStatus.FAIL]?.let { pluralStringResource(R.plurals.checks_count_fail, it, it) },
        counts[CheckStatus.OFF]?.let { pluralStringResource(R.plurals.checks_count_off, it, it) },
        counts[CheckStatus.RUNNING]?.let { pluralStringResource(R.plurals.checks_count_running, it, it) },
    )
    val checkedAt = checks.checkedAtEpochMillis?.takeIf { !checks.running }?.let { " · ${clockSeconds(it)}" }.orEmpty()
    return parts.joinToString(" · ") + checkedAt
}

@Composable
private fun CheckGroup(group: String, checks: List<CheckView>, fix: (CheckFixKind) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.radiusMd),
        color = ConsoleColors.panel,
        border = BorderStroke(1.dp, ConsoleColors.borderSubtle),
    ) {
        Column(Modifier.padding(vertical = Dimens.spaceXs)) {
            Text(group, style = ConsoleType.label, color = ConsoleColors.muted, modifier = Modifier.padding(horizontal = Dimens.spaceMd, vertical = Dimens.spaceXs))
            checks.forEachIndexed { index, check ->
                if (index > 0) HorizontalDivider(color = ConsoleColors.borderSubtle, modifier = Modifier.padding(horizontal = Dimens.spaceMd))
                CheckRow(check, fix)
            }
        }
    }
}

@Composable
private fun CheckRow(check: CheckView, fix: (CheckFixKind) -> Unit) {
    val state = check.status
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceMd, vertical = Dimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state == CheckStatus.RUNNING) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = ConsoleColors.muted)
        } else {
            Text("●", style = ConsoleType.bodySmall, color = stateColor(state))
        }
        Column(Modifier.weight(1f).padding(start = Dimens.spaceSm)) {
            Text(stringResource(check.id.title), style = ConsoleType.body, color = ConsoleColors.text)
            Text(
                when {
                    state == CheckStatus.RUNNING -> stringResource(R.string.checks_running)
                    else -> check.finding?.let { words(it) } ?: stringResource(R.string.checks_not_yet)
                },
                style = ConsoleType.bodySmall,
                color = if (state == CheckStatus.FAIL) ConsoleColors.danger else ConsoleColors.muted,
            )
        }
        check.fix?.takeIf { state != CheckStatus.OK && state != CheckStatus.RUNNING }?.let { item ->
            Spacer(Modifier.width(Dimens.spaceSm))
            TextButton(onClick = { fix(item) }) { Text(stringResource(item.label)) }
        }
    }
}

@Composable
private fun stateColor(state: CheckStatus?): Color = when (state) {
    CheckStatus.OK -> ConsoleColors.success
    CheckStatus.WARN -> ConsoleColors.warning
    CheckStatus.FAIL -> ConsoleColors.danger
    else -> ConsoleColors.muted
}

/**
 * Settings sections open in place; permissions are asked for; system screens open with this app selected. The wake
 * word ([CheckFixKind.WAKE_ON], switched on by [afterWake]) and the overlay are looked at again after a moment.
 */
private fun applyCheckFix(
    context: Context,
    fix: CheckFixKind,
    openSection: (SettingsSection) -> Unit,
    requestPermission: (String) -> Unit,
    afterWake: () -> Unit,
) {
    val packageUri = Uri.fromParts("package", context.packageName, null)
    when (fix) {
        CheckFixKind.MODELS -> openSection(SettingsSection.MODELS)
        CheckFixKind.IMAGE_MODEL -> openSection(SettingsSection.IMAGE_MODEL)
        CheckFixKind.VOICE -> openSection(SettingsSection.VOICE)
        CheckFixKind.RECORD_PERMISSION -> requestPermission(Manifest.permission.RECORD_AUDIO)
        CheckFixKind.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermission(Manifest.permission.POST_NOTIFICATIONS)
        }
        CheckFixKind.OVERLAY -> openSystemScreen(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri))
        CheckFixKind.ACCESSIBILITY -> openSystemScreen(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        CheckFixKind.WAKE_ON -> afterWake()
        CheckFixKind.OVERLAY_START -> {
            OverlayControl.ensureRunning(context)
            afterWake()
        }
    }
}

/** The fix's button. */
@get:StringRes
private val CheckFixKind.label: Int
    get() = when (this) {
        CheckFixKind.MODELS -> R.string.checks_fix_models
        CheckFixKind.IMAGE_MODEL -> R.string.checks_fix_image_model
        CheckFixKind.VOICE -> R.string.checks_fix_voice
        CheckFixKind.RECORD_PERMISSION, CheckFixKind.NOTIFICATIONS -> R.string.checks_fix_allow
        CheckFixKind.OVERLAY -> R.string.checks_fix_allow_in_settings
        CheckFixKind.ACCESSIBILITY -> R.string.checks_fix_turn_on
        CheckFixKind.WAKE_ON -> R.string.checks_fix_wake_on
        CheckFixKind.OVERLAY_START -> R.string.checks_fix_overlay_start
    }

/** A device may lack a settings screen; then the app's own details page, where its permissions are. */
internal fun openSystemScreen(context: Context, intent: Intent) {
    val opened = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    if (!opened) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

private val PERMISSION_CHECK_IDS = setOf(CheckId.RECORD_PERMISSION, CheckId.NOTIFICATIONS, CheckId.MIC, CheckId.WAKE)
private const val WAKE_RECHECK_DELAY_MS = 1_500L

/** A check's name on the page. */
@get:StringRes
private val CheckId.title: Int
    get() = when (this) {
        CheckId.CHAT -> R.string.checks_chat
        CheckId.DEEPSEEK -> R.string.checks_deepseek
        CheckId.IMAGE -> R.string.checks_image
        CheckId.TOOLS -> R.string.checks_tools
        CheckId.ASR -> R.string.checks_asr
        CheckId.JEV -> R.string.checks_jev
        CheckId.TTS -> R.string.checks_tts
        CheckId.WAKE -> R.string.checks_wake
        CheckId.MIC -> R.string.checks_mic
        CheckId.COMPANION -> R.string.checks_companion
        CheckId.REALTIME -> R.string.checks_realtime
        CheckId.RECORD_PERMISSION -> R.string.checks_record_permission
        CheckId.OVERLAY -> R.string.checks_overlay
        CheckId.ACCESSIBILITY -> R.string.checks_accessibility
        CheckId.NOTIFICATIONS -> R.string.checks_notifications
    }

@get:StringRes
private val CheckGroup.title: Int
    get() = when (this) {
        CheckGroup.MODELS -> R.string.checks_group_models
        CheckGroup.VOICE -> R.string.checks_group_voice
        CheckGroup.PERMISSIONS -> R.string.checks_group_permissions
    }
