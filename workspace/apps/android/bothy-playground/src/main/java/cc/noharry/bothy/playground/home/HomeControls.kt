package cc.noharry.bothy.playground.home
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.playground.Highlight
import cc.noharry.bothy.playground.PlaygroundStore
import cc.noharry.bothy.playground.R
import cc.noharry.bothy.playground.cabin.deg
import cc.noharry.bothy.playground.cabin.roundOne
import cc.noharry.bothy.playground.headunit.Choices
import cc.noharry.bothy.playground.headunit.CommitSlider
import cc.noharry.bothy.playground.headunit.ControlRow
import cc.noharry.bothy.playground.headunit.MiniPill
import cc.noharry.bothy.playground.headunit.SmallSwitch
import cc.noharry.bothy.playground.headunit.Stepper
import cc.noharry.bothy.playground.headunit.SubTitle
import cc.noharry.bothy.playground.headunit.home
import cc.noharry.bothy.playground.headunit.on
import cc.noharry.bothy.playground.link.open
import kotlin.math.roundToInt

/**
 * Everything at home by hand: each room's light, curtain, air conditioner, temperature and motion sensor, the door,
 * the vacuum and the scenes, so an automation such as 「卧室有人就开灯」 can be tried by walking someone in.
 */
@Composable
internal fun HomeControls(home: Home, store: PlaygroundStore) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Room.entries.forEach { RoomControls(it, home.room(it), store) }
        HouseControls(home, store)
    }
}

@Composable
private fun RoomControls(room: Room, state: RoomState, store: PlaygroundStore) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val name = stringResource(room.labelRes)
    SubTitle(name) {
        MiniPill(stringResource(if (state.presence) R.string.home_occupied else R.string.home_empty), state.presence) {
            val summary = if (state.presence) R.string.home_log_empty else R.string.home_log_occupied
            store.room(room, resources.getString(summary, name), Highlight.room(room)) {
                it.copy(presence = !it.presence)
            }
        }
    }
    ControlRow(stringResource(R.string.home_row_light)) {
        SmallSwitch(state.light.on) { on ->
            val summary = resources.getString(if (on) R.string.home_log_light_on else R.string.home_log_light_off, name)
            store.room(room, summary, Highlight.room(room)) { it.copy(light = it.light.copy(on = on)) }
        }
        CommitSlider(state.light.brightness.toFloat(), 1f..100f, { "${it.roundToInt()}%" }) { value ->
            val level = value.roundToInt()
            store.room(room, resources.getString(R.string.home_log_light_brightness, name, level), Highlight.room(room)) {
                it.copy(light = it.light.copy(on = true, brightness = level))
            }
        }
    }
    ControlRow(stringResource(R.string.home_row_tone)) {
        Choices(LightTone.entries.map { stringResource(it.labelRes) to it }, state.light.tone) { tone ->
            val summary = resources.getString(R.string.home_log_light_tone, name, resources.getString(tone.labelRes))
            store.room(room, summary, Highlight.room(room)) { it.copy(light = it.light.copy(on = true, tone = tone)) }
        }
    }
    ControlRow(stringResource(R.string.home_row_curtain)) {
        CommitSlider(state.curtainOpen.toFloat(), 0f..100f, { "${it.roundToInt()}%" }) { value ->
            val open = value.roundToInt()
            val summary = if (open == 0) {
                resources.getString(R.string.home_log_curtain_closed, name)
            } else {
                resources.getString(R.string.home_log_curtain_open, name, open)
            }
            store.room(room, summary, Highlight.curtain(room)) { it.copy(curtainOpen = open) }
        }
    }
    if (room.hasClimate) RoomClimateControls(room, state, store)
    ControlRow(stringResource(R.string.home_row_room_temp)) {
        CommitSlider(state.tempC.toFloat(), 14f..38f, { it.toDouble().deg() }) { value ->
            val summary = resources.getString(R.string.home_log_room_temp, name, value.roundOne().deg())
            store.room(room, summary, Highlight.room(room)) { it.copy(tempC = value.roundOne()) }
        }
    }
}

@Composable
private fun RoomClimateControls(room: Room, state: RoomState, store: PlaygroundStore) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val name = stringResource(room.labelRes)
    ControlRow(stringResource(R.string.home_row_ac)) {
        SmallSwitch(state.climate.on) { on ->
            val summary = resources.getString(if (on) R.string.home_log_ac_on else R.string.home_log_ac_off, name)
            store.room(room, summary, Highlight.room(room)) { it.copy(climate = it.climate.copy(on = on)) }
        }
        fun set(temp: Double) {
            store.room(room, resources.getString(R.string.home_log_ac_temp, name, temp.deg()), Highlight.room(room)) {
                it.copy(climate = it.climate.copy(on = true, targetTemp = temp))
            }
        }
        val target = state.climate.targetTemp
        Stepper(target.deg(), { set((target - 1).coerceAtLeast(16.0)) }, { set((target + 1).coerceAtMost(30.0)) })
    }
}

@Composable
private fun HouseControls(home: Home, store: PlaygroundStore) {
    val context = LocalContext.current
    val resources = LocalResources.current
    SubTitle(stringResource(R.string.home_whole_home)) {
        val door = stringResource(if (home.doorLocked) R.string.home_door_locked else R.string.home_door_unlocked)
        MiniPill(door, home.doorLocked) {
            val summary = if (home.doorLocked) R.string.home_log_door_unlocked else R.string.home_log_door_locked
            store.home(resources.getString(summary), Highlight.DOOR) { it.copy(doorLocked = !it.doorLocked) }
        }
    }
    ControlRow(stringResource(R.string.home_row_vacuum)) {
        val vacuum = home.vacuum
        MiniPill(stringResource(R.string.home_clean_all), vacuum.status == VacuumStatus.CLEANING) {
            store.home(resources.getString(R.string.home_log_vacuum_clean_all), Highlight.VACUUM) {
                it.copy(vacuum = it.vacuum.copy(status = VacuumStatus.CLEANING, room = Room.LIVING, queue = Room.entries.drop(1), progress = 0.0))
            }
        }
        val paused = vacuum.status == VacuumStatus.PAUSED
        MiniPill(stringResource(R.string.home_vacuum_pause), paused, enabled = vacuum.status == VacuumStatus.CLEANING) {
            store.home(resources.getString(R.string.home_log_vacuum_paused), Highlight.VACUUM) {
                it.copy(vacuum = it.vacuum.copy(status = VacuumStatus.PAUSED))
            }
        }
        MiniPill(stringResource(R.string.home_vacuum_dock), false, enabled = vacuum.status != VacuumStatus.DOCKED) {
            store.home(resources.getString(R.string.home_log_vacuum_dock), Highlight.VACUUM) {
                it.copy(vacuum = it.vacuum.copy(status = VacuumStatus.RETURNING, queue = emptyList(), progress = 0.0))
            }
        }
    }
    ControlRow(stringResource(R.string.home_row_scene)) {
        val scenes = HomeScene.entries.map { stringResource(it.labelRes) to it }
        Choices(scenes, HomeScene.entries.firstOrNull { it.label == home.scene }) { scene ->
            val summary = resources.getString(R.string.home_log_scene, resources.getString(scene.labelRes))
            store.home(summary, *scene.highlights.toTypedArray()) { scene.apply(it).copy(scene = scene.label) }
        }
    }
    ControlRow(stringResource(R.string.home_row_outdoor)) {
        CommitSlider(home.outdoorTempC.toFloat(), -5f..42f, { it.toDouble().deg() }) { value ->
            store.home(resources.getString(R.string.home_log_outdoor_temp, value.roundOne().deg())) {
                it.copy(outdoorTempC = value.roundOne())
            }
        }
    }
}

/** A manual change to [room], logged as 手动 with [summary], which names the room in the screen's language. */
private fun PlaygroundStore.room(
    room: Room,
    summary: String,
    vararg highlights: String,
    edit: (RoomState) -> RoomState,
) = home(summary, *highlights) { it.withRoom(room, edit) }
