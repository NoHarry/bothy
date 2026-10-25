package cc.noharry.bothy.playground

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The playground's one screen; it also starts [PlaygroundService], which serves the world to Bothy. */
class PlaygroundActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        HeadUnitModes.load(this)
        AmapKey.apply(this)
        super.onCreate(savedInstanceState)
        // A demo screen: it stays on while people watch the assistant work.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // A phone is a small head unit, held sideways (owner 2026-10-23: 手机都固定横屏): the head unit is laid out wide.
        val phone = resources.configuration.smallestScreenWidthDp < PHONE_SHORT_SIDE_DP
        if (phone) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // 60 Hz is smooth for a driving map; at the tablet's 120 Hz its every-frame drawing kept a whole core busy, and a
        // preferred rate alone lost to the map surface's own vote: the display's 60 Hz mode is asked for outright.
        @Suppress("DEPRECATION") val screen = windowManager.defaultDisplay
        screen.supportedModes.filter { it.physicalWidth == screen.mode.physicalWidth && it.physicalHeight == screen.mode.physicalHeight }
            .minByOrNull { kotlin.math.abs(it.refreshRate - SCREEN_HZ) }
            ?.let { mode -> window.attributes = window.attributes.apply { preferredDisplayModeId = mode.modeId } }
        window.statusBarColor = PlaygroundColors.bg.toArgb()
        window.navigationBarColor = PlaygroundColors.bg.toArgb()
        hideSystemBars()
        // Whatever brings them back for good (another app's window taking focus, the map going full screen), they go
        // again a moment later.
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
            if (insets.isVisible(WindowInsetsCompat.Type.systemBars())) {
                view.removeCallbacks(rehide)
                view.postDelayed(rehide, REHIDE_MS)
            }
            ViewCompat.onApplyWindowInsets(view, insets)
        }
        PlaygroundService.start(this)
        setContent { PhoneScale(phone) { PlaygroundApp(Playground.store) } }
        if (intent?.getBooleanExtra(StockNavi.EXTRA, false) == true) StockNavi.open(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(StockNavi.EXTRA, false)) StockNavi.open(this)
    }

    private val rehide = Runnable { hideSystemBars() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** A head unit has no phone status bar: the system bars stay hidden, a swipe brings them back for a moment. */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private companion object {
        const val REHIDE_MS = 2_500L
        /** Android's line between phones and tablets, in dp of the smallest width. */
        const val PHONE_SHORT_SIDE_DP = 600
    }
}

/**
 * A phone draws the head unit at [PHONE_SCALE]: sideways it is 851×393 dp, and the tablet's layout wants about 1105×692;
 * scaled it is 1135×524, its type still readable.
 */
@Composable
private fun PhoneScale(phone: Boolean, content: @Composable () -> Unit) {
    if (!phone) return content()
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density * PHONE_SCALE, base.fontScale), content = content)
}

private const val PHONE_SCALE = 0.75f

internal enum class PlaygroundScene(val label: String, val server: String) { CABIN("虚拟座舱", "cabin"), HOME("虚拟小家", "home") }

@Composable
internal fun PlaygroundApp(store: PlaygroundStore) {
    val world by store.world.collectAsState()
    val calls by store.calls.collectAsState()
    val server by store.server.collectAsState()
    val mode by HeadUnitModes.mode.collectAsState()
    var scene by rememberSaveable { mutableStateOf(PlaygroundScene.CABIN) }
    val latest = calls.firstOrNull()
    val level = remember { Animatable(0f) }
    LaunchedEffect(latest?.id) {
        if (latest == null) return@LaunchedEffect
        // Follow the assistant: a call to the other scene's server brings that scene up.
        if (latest.fromAssistant) PlaygroundScene.entries.firstOrNull { it.server == latest.server }?.let { scene = it }
        level.snapTo(1f)
        level.animateTo(0f, tween(FLASH_MS))
    }
    val flash = Flash(latest?.highlights.orEmpty(), level.asState())
    MaterialTheme(colorScheme = darkColorScheme(primary = PlaygroundColors.sage, background = PlaygroundColors.bg, surface = PlaygroundColors.panel)) {
        // Real navigation has no home to show: the head unit only.
        if (scene == PlaygroundScene.CABIN || mode.realNav) {
            HeadUnit(world, calls, server, flash, level.value, store) { scene = PlaygroundScene.HOME }
            AMapConsent()
            return@MaterialTheme
        }
        AMapConsent()
        Column(Modifier.fillMaxSize().background(PlaygroundColors.bg).padding(horizontal = 20.dp, vertical = 14.dp)) {
            TopBar(scene, { scene = it }, server, world.cabin.drive.moving, store)
            Spacer(Modifier.height(12.dp))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth > WIDE_LAYOUT
                val sceneView: @Composable (Modifier) -> Unit = { modifier ->
                    when (scene) {
                        PlaygroundScene.CABIN -> CabinScene(world.cabin, flash, modifier, store::tapCabin, store::holdCabin)
                        PlaygroundScene.HOME -> HomeScene(world.home, flash, modifier, store::tapHome, store::holdHome)
                    }
                }
                if (wide) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        sceneView(Modifier.weight(1f).fillMaxHeight())
                        SidePanel(scene, world, calls, server, level.value, store, Modifier.width(SIDE_WIDTH).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        sceneView(Modifier.weight(1f).fillMaxWidth())
                        SidePanel(scene, world, calls, server, level.value, store, Modifier.fillMaxWidth().height(NARROW_PANEL_HEIGHT))
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(scene: PlaygroundScene, onScene: (PlaygroundScene) -> Unit, server: ServerState, driving: Boolean, store: PlaygroundStore) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(painterResource(R.drawable.bothy_mark), contentDescription = null, modifier = Modifier.size(38.dp))
        Column(Modifier.weight(1f)) {
            Text("Bothy Playground", color = PlaygroundColors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(server.headline(), color = if (server.status == ServerStatus.FAILED) PlaygroundColors.ember else PlaygroundColors.muted, fontSize = 12.sp, maxLines = 1)
        }
        ModeSwitch()
        PlaceSwitch(store)
        SceneTabs(scene, onScene)
        if (scene == PlaygroundScene.CABIN) {
            Text("模拟行驶", color = PlaygroundColors.muted, fontSize = 13.sp)
            Switch(
                checked = driving,
                onCheckedChange = { store.setDriving(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = PlaygroundColors.sage, checkedThumbColor = PlaygroundColors.bg),
            )
        }
        Pill("重置", selected = false) { store.reset() }
    }
}

/** Where the user is, so 「我这边」 means the car or the home; one of the two at a time. */
@Composable
internal fun PlaceSwitch(store: PlaygroundStore) {
    val world by store.world.collectAsState()
    Text("你在", color = PlaygroundColors.muted, fontSize = 13.sp)
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(PlaygroundColors.elevated).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        UserPlace.entries.forEach { place ->
            Pill(place.label, selected = world.userAt == place) {
                store.manual("playground", "你现在在${place.label}") { it.copy(userAt = place) }
            }
        }
    }
}

@Composable
private fun SceneTabs(scene: PlaygroundScene, onScene: (PlaygroundScene) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(PlaygroundColors.elevated).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        PlaygroundScene.entries.forEach { entry -> Pill(entry.label, selected = entry == scene) { onScene(entry) } }
    }
}

@Composable
internal fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) PlaygroundColors.sage else PlaygroundColors.elevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (selected) PlaygroundColors.bg else PlaygroundColors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

private fun ServerState.headline(): String = when (status) {
    ServerStatus.STARTING -> "MCP 服务启动中…"
    ServerStatus.RUNNING -> "MCP 服务运行中 · $address/cabin/mcp · $address/home/mcp"
    ServerStatus.FAILED -> "MCP 服务没能启动：$problem"
}

private const val FLASH_MS = 1800
private val WIDE_LAYOUT = 840.dp
private val SIDE_WIDTH = 420.dp
private val NARROW_PANEL_HEIGHT = 300.dp

/** The head unit's refresh rate. */
private const val SCREEN_HZ = 60f
