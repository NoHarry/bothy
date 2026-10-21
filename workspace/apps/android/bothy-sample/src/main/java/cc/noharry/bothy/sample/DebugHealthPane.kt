package cc.noharry.bothy.sample

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.unit.dp
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
internal fun DebugHealthPane(openSection: (DebugSettingsSection) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf<Map<String, DebugCheckResult>>(emptyMap()) }
    var running by remember { mutableStateOf(false) }
    var checkedAt by remember { mutableStateOf("") }
    val runChecks = { checks: List<DebugCheck> ->
        running = true
        results = results + checks.associate { it.id to DebugCheckResult(DebugCheckState.RUNNING, "检查中…") }
        scope.launch {
            runDebugChecks(context, checks) { id, result -> results = results + (id to result) }
            running = false
            checkedAt = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        }
        Unit
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        runChecks(DEBUG_CHECKS.filter { check -> check.id in PERMISSION_CHECK_IDS })
    }
    val fix = { item: DebugCheckFix ->
        applyCheckFix(context, item, openSection, requestPermission = { permission.launch(it) }) {
            // The overlay starts asynchronously; look again once it has had a moment to attach the wake word.
            scope.launch {
                delay(WAKE_RECHECK_DELAY_MS)
                runChecks(DEBUG_CHECKS.filter { check -> check.id == "wake" })
            }
        }
    }
    Column(
        Modifier.fillMaxHeight().widthIn(max = 880.dp).verticalScroll(rememberScrollState()).padding(DebugDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceMd),
    ) {
        CheckHeader(results, running, checkedAt) { runChecks(DEBUG_CHECKS) }
        DEBUG_CHECKS.groupBy { it.group }.forEach { (group, checks) ->
            CheckGroup(group, checks, results, fix)
        }
    }
}

@Composable
private fun CheckHeader(results: Map<String, DebugCheckResult>, running: Boolean, checkedAt: String, onRun: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceMd)) {
        Column(Modifier.weight(1f)) {
            Text("一键检查", style = DebugType.panelTitle, color = DebugColors.text)
            Text(checkSummary(results, running, checkedAt), style = DebugType.bodySmall, color = DebugColors.muted)
        }
        Button(
            onClick = onRun,
            enabled = !running,
            shape = RoundedCornerShape(DebugDimens.radiusMd),
            colors = ButtonDefaults.buttonColors(containerColor = DebugColors.accent, contentColor = if (DebugColors.isDark) DebugColors.bg else Color.White),
        ) { Text(if (running) "检查中…" else "开始检查") }
    }
    Text(
        "会调用一次当前对话模型和生图模型的模型列表、百炼、Jev（约 2k token）和 MiMo（合成两个字，不播放）；当前是 DeepSeek 模型时查官方余额，经中转的检测一次官方能力（可能搜一次，¥0.005）；并录 0.5 秒声音（只在内存里）。",
        style = DebugType.bodySmall,
        color = DebugColors.muted,
    )
}

private fun checkSummary(results: Map<String, DebugCheckResult>, running: Boolean, checkedAt: String): String {
    if (results.isEmpty()) return "还没检查"
    val counts = results.values.groupingBy { it.state }.eachCount()
    val parts = listOfNotNull(
        counts[DebugCheckState.OK]?.let { "$it 项正常" },
        counts[DebugCheckState.WARN]?.let { "$it 项提醒" },
        counts[DebugCheckState.FAIL]?.let { "$it 项不可用" },
        counts[DebugCheckState.OFF]?.let { "$it 项没开" },
        counts[DebugCheckState.RUNNING]?.let { "$it 项检查中" },
    )
    return parts.joinToString(" · ") + if (!running && checkedAt.isNotEmpty()) " · $checkedAt" else ""
}

@Composable
private fun CheckGroup(group: String, checks: List<DebugCheck>, results: Map<String, DebugCheckResult>, fix: (DebugCheckFix) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DebugDimens.radiusMd),
        color = DebugColors.panel,
        border = BorderStroke(1.dp, DebugColors.borderSubtle),
    ) {
        Column(Modifier.padding(vertical = DebugDimens.spaceXs)) {
            Text(group, style = DebugType.label, color = DebugColors.muted, modifier = Modifier.padding(horizontal = DebugDimens.spaceMd, vertical = DebugDimens.spaceXs))
            checks.forEachIndexed { index, check ->
                if (index > 0) HorizontalDivider(color = DebugColors.borderSubtle, modifier = Modifier.padding(horizontal = DebugDimens.spaceMd))
                CheckRow(check, results[check.id], fix)
            }
        }
    }
}

@Composable
private fun CheckRow(check: DebugCheck, result: DebugCheckResult?, fix: (DebugCheckFix) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = DebugDimens.spaceMd, vertical = DebugDimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (result?.state == DebugCheckState.RUNNING) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = DebugColors.muted)
        } else {
            Text("●", style = DebugType.bodySmall, color = stateColor(result?.state))
        }
        Column(Modifier.weight(1f).padding(start = DebugDimens.spaceSm)) {
            Text(check.title, style = DebugType.body, color = DebugColors.text)
            Text(result?.detail ?: "还没检查", style = DebugType.bodySmall, color = if (result?.state == DebugCheckState.FAIL) DebugColors.danger else DebugColors.muted)
        }
        result?.fix?.takeIf { result.state != DebugCheckState.OK && result.state != DebugCheckState.RUNNING }?.let { item ->
            Spacer(Modifier.width(DebugDimens.spaceSm))
            TextButton(onClick = { fix(item) }) { Text(item.label) }
        }
    }
}

@Composable
private fun stateColor(state: DebugCheckState?): Color = when (state) {
    DebugCheckState.OK -> DebugColors.success
    DebugCheckState.WARN -> DebugColors.warning
    DebugCheckState.FAIL -> DebugColors.danger
    else -> DebugColors.muted
}

/** Settings sections open in place; permissions are asked for; system screens open with this app selected. */
private fun applyCheckFix(
    context: Context,
    fix: DebugCheckFix,
    openSection: (DebugSettingsSection) -> Unit,
    requestPermission: (String) -> Unit,
    recheckWake: () -> Unit,
) {
    val packageUri = Uri.fromParts("package", context.packageName, null)
    when (fix) {
        DebugCheckFix.MODELS -> openSection(DebugSettingsSection.MODELS)
        DebugCheckFix.IMAGE_MODEL -> openSection(DebugSettingsSection.IMAGE_MODEL)
        DebugCheckFix.VOICE -> openSection(DebugSettingsSection.VOICE)
        DebugCheckFix.RECORD_PERMISSION -> requestPermission(Manifest.permission.RECORD_AUDIO)
        DebugCheckFix.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermission(Manifest.permission.POST_NOTIFICATIONS)
        }
        DebugCheckFix.OVERLAY -> openSystemScreen(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri))
        DebugCheckFix.ACCESSIBILITY -> openSystemScreen(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        DebugCheckFix.WAKE_ON -> {
            DebugWakeWord.setEnabled(context, true)
            recheckWake()
        }
        DebugCheckFix.OVERLAY_START -> {
            DebugOverlayControl.ensureRunning(context)
            recheckWake()
        }
    }
}

/** A device may lack a settings screen; then the app's own details page, where its permissions are. */
private fun openSystemScreen(context: Context, intent: Intent) {
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
