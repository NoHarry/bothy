package cc.noharry.bothy.playground.home
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.playground.Highlight
import cc.noharry.bothy.playground.PlaygroundStore
import cc.noharry.bothy.playground.cabin.deg
import cc.noharry.bothy.playground.cabin.roundOne
import cc.noharry.bothy.playground.headunit.Choices
import cc.noharry.bothy.playground.headunit.CommitSlider
import cc.noharry.bothy.playground.headunit.ControlRow
import cc.noharry.bothy.playground.headunit.MiniPill
import cc.noharry.bothy.playground.headunit.SmallSwitch
import cc.noharry.bothy.playground.headunit.Stepper
import cc.noharry.bothy.playground.headunit.SubTitle
import cc.noharry.bothy.playground.headunit.changeRoom
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
    SubTitle(room.label) {
        MiniPill(if (state.presence) "有人" else "没人", state.presence) {
            store.changeRoom(room, if (state.presence) "没人了" else "有人了", Highlight.room(room)) { it.copy(presence = !it.presence) }
        }
    }
    ControlRow("灯") {
        SmallSwitch(state.light.on) { on -> store.changeRoom(room, if (on) "开灯" else "关灯", Highlight.room(room)) { it.copy(light = it.light.copy(on = on)) } }
        CommitSlider(state.light.brightness.toFloat(), 1f..100f, { "${it.roundToInt()}%" }) { value ->
            store.changeRoom(room, "灯亮度 ${value.roundToInt()}%", Highlight.room(room)) { it.copy(light = it.light.copy(on = true, brightness = value.roundToInt())) }
        }
    }
    ControlRow("色调") {
        Choices(LightTone.entries.map { it.label to it }, state.light.tone) { tone ->
            store.changeRoom(room, "灯换成${tone.label}", Highlight.room(room)) { it.copy(light = it.light.copy(on = true, tone = tone)) }
        }
    }
    ControlRow("窗帘") {
        CommitSlider(state.curtainOpen.toFloat(), 0f..100f, { "${it.roundToInt()}%" }) { value ->
            val open = value.roundToInt()
            store.changeRoom(room, if (open == 0) "窗帘关上" else "窗帘开到 $open%", Highlight.curtain(room)) { it.copy(curtainOpen = open) }
        }
    }
    if (room.hasClimate) RoomClimateControls(room, state, store)
    ControlRow("室温") {
        CommitSlider(state.tempC.toFloat(), 14f..38f, { it.toDouble().deg() }) { value ->
            store.changeRoom(room, "室温设为 ${value.roundOne().deg()}", Highlight.room(room)) { it.copy(tempC = value.roundOne()) }
        }
    }
}

@Composable
private fun RoomClimateControls(room: Room, state: RoomState, store: PlaygroundStore) {
    ControlRow("空调") {
        SmallSwitch(state.climate.on) { on ->
            store.changeRoom(room, if (on) "空调打开" else "空调关闭", Highlight.room(room)) { it.copy(climate = it.climate.copy(on = on)) }
        }
        fun set(temp: Double) = store.changeRoom(room, "空调设到 ${temp.deg()}", Highlight.room(room)) {
            it.copy(climate = it.climate.copy(on = true, targetTemp = temp))
        }
        val target = state.climate.targetTemp
        Stepper(target.deg(), { set((target - 1).coerceAtLeast(16.0)) }, { set((target + 1).coerceAtMost(30.0)) })
    }
}

@Composable
private fun HouseControls(home: Home, store: PlaygroundStore) {
    SubTitle("全屋") {
        MiniPill(if (home.doorLocked) "门已锁" else "门开着", home.doorLocked) {
            store.home(if (home.doorLocked) "门锁打开" else "门锁上", Highlight.DOOR) { it.copy(doorLocked = !it.doorLocked) }
        }
    }
    ControlRow("扫地机") {
        val vacuum = home.vacuum
        MiniPill("全屋清扫", vacuum.status == VacuumStatus.CLEANING) {
            store.home("扫地机开始全屋清扫", Highlight.VACUUM) {
                it.copy(vacuum = it.vacuum.copy(status = VacuumStatus.CLEANING, room = Room.LIVING, queue = Room.entries.drop(1), progress = 0.0))
            }
        }
        MiniPill("暂停", vacuum.status == VacuumStatus.PAUSED, enabled = vacuum.status == VacuumStatus.CLEANING) {
            store.home("扫地机暂停", Highlight.VACUUM) { it.copy(vacuum = it.vacuum.copy(status = VacuumStatus.PAUSED)) }
        }
        MiniPill("回充", false, enabled = vacuum.status != VacuumStatus.DOCKED) {
            store.home("扫地机回充", Highlight.VACUUM) { it.copy(vacuum = it.vacuum.copy(status = VacuumStatus.RETURNING, queue = emptyList(), progress = 0.0)) }
        }
    }
    ControlRow("场景") {
        Choices(HomeScene.entries.map { it.label to it }, HomeScene.entries.firstOrNull { it.label == home.scene }) { scene ->
            store.home("切到「${scene.label}」场景", *scene.highlights.toTypedArray()) { scene.apply(it).copy(scene = scene.label) }
        }
    }
    ControlRow("室外") {
        CommitSlider(home.outdoorTempC.toFloat(), -5f..42f, { it.toDouble().deg() }) { value ->
            store.home("室外温度设为 ${value.roundOne().deg()}") { it.copy(outdoorTempC = value.roundOne()) }
        }
    }
}
