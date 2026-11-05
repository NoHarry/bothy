package cc.noharry.bothy.app
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.noharry.agent.appserver.protocol.CapabilityRequests
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.FlagParams
import cc.noharry.agent.appserver.protocol.TextParams
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.evidence.openSystemScreen
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.protocol.OnboardingRequests
import cc.noharry.bothy.protocol.OnboardingView
import cc.noharry.bothy.protocol.PLAYGROUND_PACKAGE
import kotlinx.coroutines.launch

@Composable
internal fun OnboardingScreen(onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(ConsoleColors.bg).verticalScroll(rememberScrollState()).padding(Dimens.spaceXl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = ONBOARDING_WIDTH), verticalArrangement = Arrangement.spacedBy(Dimens.spaceLg)) {
            Text(stringResource(R.string.onboarding_hello), style = ConsoleType.hero, color = ConsoleColors.text)
            Text(stringResource(R.string.onboarding_intro), style = ConsoleType.body, color = ConsoleColors.muted)
            ModelKeyStep()
            DoubaoKeyStep()
            PermissionsStep()
            FirstWordsStep(onDone)
        }
    }
}

/**
 * Keys typed on the setup screen. They live for this process, so a rotation keeps a key that has not been saved yet,
 * and they are not written into the instance state.
 */
internal object OnboardingDraft {
    val modelKey = mutableStateOf("")
    val doubaoKey = mutableStateOf("")

    fun clear() {
        modelKey.value = ""
        doubaoKey.value = ""
    }
}

/** 1. The DeepSeek key, saved to the selected model service and checked against its model list. */
@Composable
private fun ModelKeyStep() {
    val server = LocalServer.current
    val resources = LocalResources.current
    var key by OnboardingDraft.modelKey
    var status by remember { mutableStateOf<Pair<String, Color>?>(null) }
    val good = ConsoleColors.success
    val bad = ConsoleColors.danger
    val waiting = ConsoleColors.muted
    val checking = stringResource(R.string.onboarding_checking)
    OnboardingStep(stringResource(R.string.onboarding_step_model_key), done = status?.second == good) {
        Text(
            stringResource(R.string.onboarding_model_key_note),
            style = ConsoleType.bodySmall,
            color = ConsoleColors.muted,
        )
        LinkButton(stringResource(R.string.onboarding_deepseek_link), DEEPSEEK_KEYS_URL)
        KeyField("DeepSeek API key", key) { key = it }
        Button(enabled = key.isNotBlank(), onClick = {
            status = checking to waiting
            server.send(
                OnboardingRequests.SAVE_MODEL_KEY,
                TextParams(key),
                onRefused = { status = it.message.orEmpty() to bad },
            ) { checked -> status = resources.words(checked.finding) to if (checked.failed) bad else good }
        }) { Text(stringResource(R.string.onboarding_save_and_check)) }
        status?.let { (text, color) -> Text(text, style = ConsoleType.bodySmall, color = color) }
    }
}

/** 2. A 豆包语音 key: 小博's voice, and the 端到端 voice mode. Without it the device's own voice speaks. */
@Composable
private fun DoubaoKeyStep() {
    val server = LocalServer.current
    val resources = LocalResources.current
    var key by OnboardingDraft.doubaoKey
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { server.send(OnboardingRequests.READ, Empty) { saved = saved || it.doubaoKeySaved } }
    var problem by remember { mutableStateOf<String?>(null) }
    OnboardingStep(stringResource(R.string.onboarding_step_doubao_key), done = saved) {
        Text(
            stringResource(R.string.onboarding_doubao_key_note),
            style = ConsoleType.bodySmall,
            color = ConsoleColors.muted,
        )
        LinkButton(stringResource(R.string.onboarding_doubao_link), DOUBAO_CONSOLE_URL)
        KeyField(stringResource(R.string.onboarding_doubao_key_label), key) { key = it }
        Button(enabled = key.isNotBlank(), onClick = {
            server.send(OnboardingRequests.SAVE_DOUBAO_KEY, TextParams(key)) { result ->
                problem = result.error?.let(resources::words)
                saved = result.error == null
            }
        }) { Text(stringResource(R.string.common_save)) }
        when {
            problem != null -> Text(problem.orEmpty(), style = ConsoleType.bodySmall, color = ConsoleColors.danger)
            saved -> Text(stringResource(R.string.onboarding_doubao_saved), style = ConsoleType.bodySmall, color = ConsoleColors.success)
        }
    }
}

/** 3. What the system grants, each read again on return from its settings screen. */
@Composable
private fun PermissionsStep() {
    val context = LocalContext.current
    val server = LocalServer.current
    var round by remember { mutableIntStateOf(0) }
    val granted = grantsAsRead(round)
    ReadAgainOnResume { round++ }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { round++ }
    val packageUri = Uri.fromParts("package", context.packageName, null)
    OnboardingStep(stringResource(R.string.onboarding_step_permissions), done = false) {
        // Read again after each return from a settings screen.
        key(round) {
            PermissionRow(
                stringResource(R.string.onboarding_permission_microphone),
                stringResource(R.string.onboarding_permission_microphone_note),
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            ) {
                ask.launch(Manifest.permission.RECORD_AUDIO)
            }
            PermissionRow(
                stringResource(R.string.onboarding_permission_overlay),
                stringResource(R.string.onboarding_permission_overlay_note),
                Settings.canDrawOverlays(context),
            ) {
                openSystemScreen(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri))
            }
            PermissionRow(
                stringResource(R.string.onboarding_permission_notifications),
                stringResource(R.string.onboarding_permission_notifications_note),
                granted.notificationAccess,
            ) {
                openSystemScreen(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            PermissionRow(
                stringResource(R.string.onboarding_permission_accessibility),
                stringResource(R.string.onboarding_permission_accessibility_note),
                granted.accessibilityEnabled,
            ) {
                server.send(CapabilityRequests.SET_ANDROID_USE, FlagParams(true))
                openSystemScreen(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            PermissionRow(
                stringResource(R.string.onboarding_permission_background),
                stringResource(R.string.onboarding_permission_background_note),
                ignoresBatteryLimits(context),
            ) {
                openSystemScreen(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
            }
        }
    }
}

/** What the server reads back of the grants, again for each [round]. */
@Composable
private fun grantsAsRead(round: Int): OnboardingView {
    val server = LocalServer.current
    var granted by remember { mutableStateOf(OnboardingView()) }
    LaunchedEffect(round) { server.send(OnboardingRequests.READ, Empty) { granted = it } }
    return granted
}

/** 4. What to say first, and the virtual head unit when it is installed. */
@Composable
private fun FirstWordsStep(onDone: () -> Unit) {
    val context = LocalContext.current
    val playground = remember { context.packageManager.getLaunchIntentForPackage(PLAYGROUND_PACKAGE) }
    OnboardingStep(stringResource(R.string.onboarding_step_first_words), done = false) {
        Text(
            stringResource(R.string.onboarding_first_words_note),
            style = ConsoleType.bodySmall,
            color = ConsoleColors.muted,
        )
        FIRST_WORDS.forEach { Text("· ${stringResource(it)}", style = ConsoleType.body, color = ConsoleColors.text) }
        if (playground != null) {
            Text(
                stringResource(R.string.onboarding_playground_note),
                style = ConsoleType.bodySmall,
                color = ConsoleColors.muted,
            )
            TextButton(onClick = {
                runCatching { context.startActivity(playground.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) {
                Text(stringResource(R.string.onboarding_open_playground))
            }
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_get_started))
        }
    }
}

@Composable
private fun OnboardingStep(title: String, done: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(Dimens.radiusLg),
        color = ConsoleColors.panel,
        border = BorderStroke(1.dp, if (done) ConsoleColors.success else ConsoleColors.borderSubtle),
    ) {
        Column(Modifier.fillMaxWidth().padding(Dimens.spaceLg), verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
            Text(if (done) "$title  ✓" else title, style = ConsoleType.panelTitle, color = ConsoleColors.text)
            content()
        }
    }
}

@Composable
private fun KeyField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun LinkButton(label: String, url: String) {
    val context = LocalContext.current
    TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
        Text("$label ↗")
    }
}

@Composable
private fun PermissionRow(title: String, what: String, granted: Boolean, onGrant: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = ConsoleType.body, color = ConsoleColors.text)
            Text(what, style = ConsoleType.meta, color = ConsoleColors.muted)
        }
        if (granted) {
            Text(stringResource(R.string.onboarding_permission_granted), style = ConsoleType.meta, color = ConsoleColors.success)
        } else {
            TextButton(onClick = onGrant) { Text(stringResource(R.string.onboarding_permission_grant)) }
        }
    }
}

@Composable
private fun ReadAgainOnResume(onResume: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) onResume() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

private fun ignoresBatteryLimits(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

private val ONBOARDING_WIDTH = 640.dp
private const val DEEPSEEK_KEYS_URL = "https://platform.deepseek.com/api_keys"
private const val DOUBAO_CONSOLE_URL = "https://console.volcengine.com/speech/new/setting/activate?projectName=default"
private val FIRST_WORDS = listOf(
    R.string.onboarding_try_weather,
    R.string.onboarding_try_reminder,
    R.string.onboarding_try_song,
    R.string.onboarding_try_calculator,
)
