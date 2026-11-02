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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.SettingsSection
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.overlay.OverlayControl
import cc.noharry.bothy.app.voice.WakeWord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings → 检查: one tap checks the selected models, voice, permissions and services, each line with what was found
 * and, when something is off, the way to fix it (a Settings section, a system screen, or switching it on here).
 */
@Composable
internal fun HealthPane(openSection: (SettingsSection) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf<Map<String, CheckResult>>(emptyMap()) }
    var running by remember { mutableStateOf(false) }
    var checkedAt by remember { mutableStateOf("") }
    val checking = stringResource(R.string.checks_running)
    val runChecks = { checks: List<HealthCheck> ->
        running = true
        results = results + checks.associate { it.id to CheckResult(CheckState.RUNNING, checking) }
        scope.launch {
            runHealthChecks(context, checks) { id, result -> results = results + (id to result) }
            running = false
            checkedAt = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        }
        Unit
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        runChecks(DEBUG_CHECKS.filter { check -> check.id in PERMISSION_CHECK_IDS })
    }
    val fix = { item: CheckFix ->
        applyCheckFix(context, item, openSection, requestPermission = { permission.launch(it) }) {
            // The overlay starts asynchronously; look again once it has had a moment to attach the wake word.
            scope.launch {
                delay(WAKE_RECHECK_DELAY_MS)
                runChecks(DEBUG_CHECKS.filter { check -> check.id == "wake" })
            }
        }
    }
    Column(
        Modifier.fillMaxHeight().widthIn(max = 880.dp).verticalScroll(rememberScrollState()).padding(Dimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
    ) {
        CheckHeader(results, running, checkedAt) { runChecks(DEBUG_CHECKS) }
        DEBUG_CHECKS.groupBy { it.group }.forEach { (group, checks) ->
            CheckGroup(group, checks, results, fix)
        }
    }
}

@Composable
private fun CheckHeader(results: Map<String, CheckResult>, running: Boolean, checkedAt: String, onRun: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.checks_title), style = ConsoleType.panelTitle, color = ConsoleColors.text)
            Text(checkSummary(results, running, checkedAt), style = ConsoleType.bodySmall, color = ConsoleColors.muted)
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
private fun checkSummary(results: Map<String, CheckResult>, running: Boolean, checkedAt: String): String {
    if (results.isEmpty()) return stringResource(R.string.checks_not_yet)
    val counts = results.values.groupingBy { it.state }.eachCount()
    val parts = listOfNotNull(
        counts[CheckState.OK]?.let { pluralStringResource(R.plurals.checks_count_ok, it, it) },
        counts[CheckState.WARN]?.let { pluralStringResource(R.plurals.checks_count_warn, it, it) },
        counts[CheckState.FAIL]?.let { pluralStringResource(R.plurals.checks_count_fail, it, it) },
        counts[CheckState.OFF]?.let { pluralStringResource(R.plurals.checks_count_off, it, it) },
        counts[CheckState.RUNNING]?.let { pluralStringResource(R.plurals.checks_count_running, it, it) },
    )
    return parts.joinToString(" · ") + if (!running && checkedAt.isNotEmpty()) " · $checkedAt" else ""
}

@Composable
private fun CheckGroup(@StringRes group: Int, checks: List<HealthCheck>, results: Map<String, CheckResult>, fix: (CheckFix) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.radiusMd),
        color = ConsoleColors.panel,
        border = BorderStroke(1.dp, ConsoleColors.borderSubtle),
    ) {
        Column(Modifier.padding(vertical = Dimens.spaceXs)) {
            Text(stringResource(group), style = ConsoleType.label, color = ConsoleColors.muted, modifier = Modifier.padding(horizontal = Dimens.spaceMd, vertical = Dimens.spaceXs))
            checks.forEachIndexed { index, check ->
                if (index > 0) HorizontalDivider(color = ConsoleColors.borderSubtle, modifier = Modifier.padding(horizontal = Dimens.spaceMd))
                CheckRow(check, results[check.id], fix)
            }
        }
    }
}

@Composable
private fun CheckRow(check: HealthCheck, result: CheckResult?, fix: (CheckFix) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceMd, vertical = Dimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (result?.state == CheckState.RUNNING) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = ConsoleColors.muted)
        } else {
            Text("●", style = ConsoleType.bodySmall, color = stateColor(result?.state))
        }
        Column(Modifier.weight(1f).padding(start = Dimens.spaceSm)) {
            Text(stringResource(check.title), style = ConsoleType.body, color = ConsoleColors.text)
            Text(result?.detail ?: stringResource(R.string.checks_not_yet), style = ConsoleType.bodySmall, color = if (result?.state == CheckState.FAIL) ConsoleColors.danger else ConsoleColors.muted)
        }
        result?.fix?.takeIf { result.state != CheckState.OK && result.state != CheckState.RUNNING }?.let { item ->
            Spacer(Modifier.width(Dimens.spaceSm))
            TextButton(onClick = { fix(item) }) { Text(stringResource(item.label)) }
        }
    }
}

@Composable
private fun stateColor(state: CheckState?): Color = when (state) {
    CheckState.OK -> ConsoleColors.success
    CheckState.WARN -> ConsoleColors.warning
    CheckState.FAIL -> ConsoleColors.danger
    else -> ConsoleColors.muted
}

/** Settings sections open in place; permissions are asked for; system screens open with this app selected. */
private fun applyCheckFix(
    context: Context,
    fix: CheckFix,
    openSection: (SettingsSection) -> Unit,
    requestPermission: (String) -> Unit,
    recheckWake: () -> Unit,
) {
    val packageUri = Uri.fromParts("package", context.packageName, null)
    when (fix) {
        CheckFix.MODELS -> openSection(SettingsSection.MODELS)
        CheckFix.IMAGE_MODEL -> openSection(SettingsSection.IMAGE_MODEL)
        CheckFix.VOICE -> openSection(SettingsSection.VOICE)
        CheckFix.RECORD_PERMISSION -> requestPermission(Manifest.permission.RECORD_AUDIO)
        CheckFix.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermission(Manifest.permission.POST_NOTIFICATIONS)
        }
        CheckFix.OVERLAY -> openSystemScreen(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri))
        CheckFix.ACCESSIBILITY -> openSystemScreen(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        CheckFix.WAKE_ON -> {
            WakeWord.setEnabled(context, true)
            recheckWake()
        }
        CheckFix.OVERLAY_START -> {
            OverlayControl.ensureRunning(context)
            recheckWake()
        }
    }
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

private val PERMISSION_CHECK_IDS = setOf("record_permission", "notifications", "mic", "wake")
private const val WAKE_RECHECK_DELAY_MS = 1_500L
