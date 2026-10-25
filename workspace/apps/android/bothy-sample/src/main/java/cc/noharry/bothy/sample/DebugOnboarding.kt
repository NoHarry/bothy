package cc.noharry.bothy.sample

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.noharry.agent.androiduse.AndroidUseAccessibility
import cc.noharry.agent.device.DeviceMediaMcpProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The first launch without a model key (owner 2026-10-25: someone who installs it should need one model key and nothing
 * more to try 小博 on their own tablet): the DeepSeek key, checked at once; a 豆包语音 key, recommended, skippable; the
 * permissions, each with its status and the screen that grants it; and what to say first. Done, it never shows again.
 */
internal object DebugOnboarding {
    /**
     * Whether the setup shows: on a launch without a model key, and from then on until 开始使用, so leaving it for a
     * settings screen (the app maybe recycled meanwhile) comes back to it, not to a console with permissions missing.
     */
    fun needed(context: Context): Boolean {
        val prefs = prefs(context)
        if (prefs.getBoolean(DONE, false)) return false
        if (prefs.getBoolean(STARTED, false)) return true
        val config = DebugProviderConfig.load(context)
        if (config.isReady(config.activeProfile)) return false
        prefs.edit().putBoolean(STARTED, true).apply()
        return true
    }

    /**
     * 开始使用: what the last step says works from here on does — 「你好小博」 is heard (语音唤醒 on, with the microphone
     * granted) and 小博 floats over other apps. Off, a fresh install had neither, and the head unit no cards.
     */
    fun finish(context: Context) {
        prefs(context).edit().putBoolean(DONE, true).apply()
        val mic = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (mic) DebugWakeWord.setEnabled(context, true) else DebugOverlayControl.ensureRunning(context)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "debug_onboarding"
    private const val DONE = "done"
    private const val STARTED = "started"
}

@Composable
internal fun DebugOnboardingScreen(onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(DebugColors.bg).verticalScroll(rememberScrollState()).padding(DebugDimens.spaceXl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = ONBOARDING_WIDTH), verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceLg)) {
            Text("你好，我是小博", style = DebugType.hero, color = DebugColors.text)
            Text("填一个模型 key 就能开始，其余的都可以跳过、以后再在设置里补。", style = DebugType.body, color = DebugColors.muted)
            ModelKeyStep()
            DoubaoKeyStep()
            PermissionsStep()
            FirstWordsStep(onDone)
        }
    }
}

/** 1. The DeepSeek key, saved to the selected model service and checked against its model list. */
@Composable
private fun ModelKeyStep() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<Pair<String, Color>?>(null) }
    val good = DebugColors.success
    val bad = DebugColors.danger
    val waiting = DebugColors.muted
    OnboardingStep("1 · 模型 key（必填）", done = status?.second == good) {
        Text("小博用 DeepSeek 思考和办事，按量计费、很便宜。到 DeepSeek 开放平台创建一个 API key，粘贴到下面。", style = DebugType.bodySmall, color = DebugColors.muted)
        LinkButton("去 DeepSeek 开放平台申请", DEEPSEEK_KEYS_URL)
        KeyField("DeepSeek API key", key) { key = it }
        Button(enabled = key.isNotBlank(), onClick = {
            status = "正在检查…" to waiting
            scope.launch {
                val profile = DebugProviderConfig.load(context).activeProfile
                DebugRuntimeSession.saveProviderOverride(context, profile.provider, key.trim(), profile.model, profile.baseUrl, profile.endpoint)
                val result = withContext(Dispatchers.IO) { checkChatModel(context) }
                status = if (result.state == DebugCheckState.OK) "可以用了 · ${result.detail}" to good else result.detail to bad
            }
        }) { Text("保存并检查") }
        status?.let { (text, color) -> Text(text, style = DebugType.bodySmall, color = color) }
    }
}

/** 2. A 豆包语音 key: 小博's voice, and the 端到端 voice mode. Without it the device's own voice speaks. */
@Composable
private fun DoubaoKeyStep() {
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(loadDebugDoubaoTtsKey(context).isNotEmpty()) }
    var problem by remember { mutableStateOf<String?>(null) }
    OnboardingStep("2 · 豆包语音 key（推荐，可跳过）", done = saved) {
        Text(
            "有了它，小博用豆包的声音说话，还能打开「端到端」语音模式，像打电话一样连续对话。不填也能用：小博用设备自带的系统语音说话。" +
                "在火山引擎豆包语音控制台的「开通管理」里开通「语音合成2.0」和「端到端实时语音」，再到左侧「API Key」创建一个 key，粘贴到下面。",
            style = DebugType.bodySmall,
            color = DebugColors.muted,
        )
        LinkButton("去豆包语音控制台开通", DOUBAO_CONSOLE_URL)
        KeyField("豆包语音 API key", key) { key = it }
        Button(enabled = key.isNotBlank(), onClick = {
            problem = saveDebugDoubaoTtsKey(context, key)
            saved = problem == null
        }) { Text("保存") }
        when {
            problem != null -> Text(problem.orEmpty(), style = DebugType.bodySmall, color = DebugColors.danger)
            saved -> Text("已保存：小博会用豆包的声音说话", style = DebugType.bodySmall, color = DebugColors.success)
        }
    }
}

/** 3. What the system grants, each read again on return from its settings screen. */
@Composable
private fun PermissionsStep() {
    val context = LocalContext.current
    var round by remember { mutableIntStateOf(0) }
    ReadAgainOnResume { round++ }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { round++ }
    val packageUri = Uri.fromParts("package", context.packageName, null)
    OnboardingStep("3 · 权限", done = false) {
        // Read again after each return from a settings screen.
        key(round) {
            PermissionRow("麦克风（必需）", "说「你好小博」唤醒、语音对话", context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                ask.launch(Manifest.permission.RECORD_AUDIO)
            }
            PermissionRow("悬浮窗（必需）", "小博浮在其它 App 上面，随时唤醒", Settings.canDrawOverlays(context)) {
                openSystemScreen(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri))
            }
            PermissionRow("通知使用权（推荐）", "看到并控制网易云等 App 在放的音乐", DeviceMediaMcpProvider.notificationAccessGranted(context)) {
                openSystemScreen(context, DeviceMediaMcpProvider.notificationAccessIntent())
            }
            PermissionRow("无障碍（可选）", "让小博帮你操作其它 App（打开、点按、输入）", AndroidUseAccessibility.isEnabled(context)) {
                saveDebugAndroidUseEnabled(context, true)
                openSystemScreen(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            PermissionRow("后台运行（可选）", "不被系统省电清理；有的系统还要在这里打开「自启动」", ignoresBatteryLimits(context)) {
                openSystemScreen(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
            }
        }
    }
}

/** 4. What to say first, and the virtual head unit when it is installed. */
@Composable
private fun FirstWordsStep(onDone: () -> Unit) {
    val context = LocalContext.current
    val playground = remember { context.packageManager.getLaunchIntentForPackage(PLAYGROUND_PACKAGE) }
    OnboardingStep("4 · 试试这样说", done = false) {
        Text("说「你好小博」，或点悬浮的小博，然后：", style = DebugType.bodySmall, color = DebugColors.muted)
        FIRST_WORDS.forEach { Text("· $it", style = DebugType.body, color = DebugColors.text) }
        if (playground != null) {
            Text("装了 Bothy Playground：打开它放在前台，小博就在虚拟车机里，可以开空调、开车窗、导航。", style = DebugType.bodySmall, color = DebugColors.muted)
            TextButton(onClick = { runCatching { context.startActivity(playground.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("打开 Bothy Playground") }
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("开始使用") }
    }
}

@Composable
private fun OnboardingStep(title: String, done: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(DebugDimens.radiusLg),
        color = DebugColors.panel,
        border = BorderStroke(1.dp, if (done) DebugColors.success else DebugColors.borderSubtle),
    ) {
        Column(Modifier.fillMaxWidth().padding(DebugDimens.spaceLg), verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm)) {
            Text(if (done) "$title  ✓" else title, style = DebugType.panelTitle, color = DebugColors.text)
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
            Text(title, style = DebugType.body, color = DebugColors.text)
            Text(what, style = DebugType.meta, color = DebugColors.muted)
        }
        if (granted) Text("已开启", style = DebugType.meta, color = DebugColors.success) else TextButton(onClick = onGrant) { Text("去开启") }
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
    "明天深圳天气怎么样",
    "明早八点提醒我带伞",
    "现在在放什么歌（要先开通知使用权）",
    "打开计算器算一下 23 乘以 47（要先开无障碍）",
)
