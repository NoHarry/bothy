package cc.noharry.bothy.playground.headunit
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.playground.CallRecord
import cc.noharry.bothy.playground.PlaygroundScene
import cc.noharry.bothy.playground.PlaygroundStore
import cc.noharry.bothy.playground.R
import cc.noharry.bothy.playground.ServerState
import cc.noharry.bothy.playground.ServerStatus
import cc.noharry.bothy.playground.cabin.CabinControls
import cc.noharry.bothy.playground.cabin.summary
import cc.noharry.bothy.playground.home.HomeControls
import cc.noharry.bothy.playground.home.World
import cc.noharry.bothy.playground.home.summary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Beside the scene: the latest calls and changes, what to say, every control by hand (which also reads out the state),
 * and how to reach the servers from Bothy.
 */
@Composable
internal fun SidePanel(scene: PlaygroundScene, world: World, calls: List<CallRecord>, server: ServerState, flash: Float, store: PlaygroundStore, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(stringResource(R.string.headunit_recent)) {
            val none = stringResource(R.string.headunit_no_calls, stringResource(scene.labelRes))
            if (calls.isEmpty()) Text(none, color = PlaygroundColors.muted, fontSize = 13.sp)
            calls.take(RECENT_CALLS).forEachIndexed { i, call -> CallRow(call, if (i == 0) flash else 0f) }
        }
        Card(stringResource(R.string.headunit_controls_title)) {
            if (scene == PlaygroundScene.CABIN) CabinControls(world.cabin, store) else HomeControls(world.home, store)
        }
        Card(stringResource(R.string.headunit_try_saying_to)) {
            EXAMPLES.getValue(scene).forEach {
                val example = stringResource(R.string.headunit_quoted, stringResource(it))
                Text(example, color = PlaygroundColors.text, fontSize = 14.sp, lineHeight = 21.sp)
            }
            val gestures = stringResource(GESTURES.getValue(scene))
            Text(gestures, color = PlaygroundColors.muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Card(stringResource(R.string.headunit_connection)) {
            Text(
                if (server.status == ServerStatus.RUNNING) {
                    stringResource(R.string.headunit_connect_hint_address, server.address)
                } else {
                    stringResource(R.string.headunit_connect_hint)
                },
                color = PlaygroundColors.muted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PlaygroundColors.panel).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = PlaygroundColors.sage, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        content()
    }
}

@Composable
private fun CallRow(call: CallRecord, flash: Float) {
    val back = PlaygroundColors.glow.copy(alpha = 0.18f * flash)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (flash > 0f) back else Color.Transparent).padding(vertical = 3.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(TIME.get()!!.format(Date(call.atMillis)), color = PlaygroundColors.faint, fontSize = 12.sp)
        val (tag, color) = when {
            call.fromAssistant -> R.string.headunit_xiaobo to PlaygroundColors.glow
            call.tool == PlaygroundStore.MANUAL -> R.string.headunit_manual to PlaygroundColors.sage
            else -> R.string.headunit_simulated to PlaygroundColors.faint
        }
        Text(stringResource(tag), color = color, fontSize = 12.sp)
        Text(call.summary, color = PlaygroundColors.text, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

private val EXAMPLES = mapOf(
    PlaygroundScene.CABIN to listOf(
        R.string.headunit_example_cold,
        R.string.headunit_example_seat_heat,
        R.string.headunit_example_ambient_green,
        R.string.headunit_example_window_half,
        R.string.headunit_say_relaxing_music,
        R.string.headunit_example_west_lake,
        R.string.headunit_example_close_windows_below,
        R.string.headunit_example_hot_side,
        R.string.headunit_say_open_trunk,
        R.string.headunit_example_screen_volume,
        R.string.headunit_example_open_door,
        R.string.headunit_example_rear_ventilation,
    ),
    PlaygroundScene.HOME to listOf(
        R.string.headunit_example_bedtime,
        R.string.headunit_example_dim_living,
        R.string.headunit_example_bedroom_curtains,
        R.string.headunit_example_vacuum_kitchen,
        R.string.headunit_example_living_ac,
        R.string.headunit_example_heading_out,
        R.string.headunit_example_bedroom_light,
    ),
)

private val GESTURES = mapOf(
    PlaygroundScene.CABIN to R.string.headunit_gestures_cabin,
    PlaygroundScene.HOME to R.string.headunit_gestures_home,
)

private const val RECENT_CALLS = 4
private val TIME = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
}
