package cc.noharry.bothy.playground.cabin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.playground.Highlight
import cc.noharry.bothy.playground.PlaygroundStore
import cc.noharry.bothy.playground.headunit.Choices
import cc.noharry.bothy.playground.headunit.CommitSlider
import cc.noharry.bothy.playground.headunit.ControlRow
import cc.noharry.bothy.playground.headunit.MediaControls
import cc.noharry.bothy.playground.headunit.MiniPill
import cc.noharry.bothy.playground.headunit.PlaygroundColors
import cc.noharry.bothy.playground.headunit.SmallSwitch
import cc.noharry.bothy.playground.headunit.Stepper
import cc.noharry.bothy.playground.headunit.SubTitle
import cc.noharry.bothy.playground.headunit.Swatch
import cc.noharry.bothy.playground.headunit.cabin
import cc.noharry.bothy.playground.headunit.on
import cc.noharry.bothy.playground.headunit.toggleDoor
import cc.noharry.bothy.playground.headunit.toggleTrunk
import cc.noharry.bothy.playground.link.open
import kotlin.math.roundToInt

/**
 * Everything in the cabin by hand: who sits where and who is speaking, and each device, plus the readings a plan
 * might wait for (battery, the temperature inside and out), so an automation can be tried without a drive.
 */
@Composable
internal fun CabinControls(cabin: Cabin, store: PlaygroundStore) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PeopleControls(cabin, store)
        ClimateControls(cabin.climate, store)
        SeatControls(cabin, store)
        WindowControls(cabin, store)
        BodyControls(cabin, store)
        ScreenSoundControls(cabin, store)
        AmbientControls(cabin.ambient, store)
        MediaControls(cabin, store)
        VehicleControls(cabin, store)
    }
}

@Composable
private fun PeopleControls(cabin: Cabin, store: PlaygroundStore) {
    SubTitle("乘客")
    ControlRow("有人") {
        Seat.entries.forEach { seat ->
            val aboard = seat in cabin.occupants
            MiniPill(seat.label, aboard) {
                store.cabin(if (aboard) "${seat.label}的人下车了" else "${seat.label}坐上一个人", Highlight.seat(seat)) { it.withOccupant(seat, !aboard) }
            }
        }
    }
    ControlRow("说话") {
        Seat.entries.forEach { seat ->
            MiniPill(seat.label, cabin.speaker == seat) { store.cabin("现在是${seat.label}在说话", Highlight.seat(seat)) { it.withSpeaker(seat) } }
        }
    }
}

@Composable
private fun ClimateControls(climate: CabinClimate, store: PlaygroundStore) {
    SubTitle("空调") {
        SmallSwitch(climate.on) { on -> store.cabin(if (on) "空调打开" else "空调关闭", Highlight.CLIMATE) { it.copy(climate = it.climate.copy(on = on)) } }
    }
    ControlRow("温度") {
        fun set(temp: Double) = store.cabin("空调设到 ${temp.deg()}", Highlight.CLIMATE) {
            it.copy(climate = it.climate.copy(on = true, driverTemp = temp, passengerTemp = temp))
        }
        Stepper(climate.driverTemp.deg(), { set((climate.driverTemp - 1).coerceAtLeast(16.0)) }, { set((climate.driverTemp + 1).coerceAtMost(30.0)) })
        fun fan(level: Int) = store.cabin("风量 $level", Highlight.CLIMATE) { it.copy(climate = it.climate.copy(on = true, fan = level)) }
        Stepper("风 ${climate.fan}", { fan((climate.fan - 1).coerceAtLeast(1)) }, { fan((climate.fan + 1).coerceAtMost(7)) })
    }
    ControlRow("模式") {
        Choices(ClimateMode.entries.map { it.label to it }, climate.mode) { mode ->
            store.cabin("空调${mode.label}", Highlight.CLIMATE) { it.copy(climate = it.climate.copy(on = true, mode = mode)) }
        }
    }
}

@Composable
private fun SeatControls(cabin: Cabin, store: PlaygroundStore) {
    SubTitle("座椅")
    Seat.entries.forEach { seat ->
        ControlRow(seat.label) {
            Choices(SEAT_LEVELS, cabin.seat(seat)) { comfort ->
                store.cabin("${seat.label}座椅${comfort.comfortLine()}", Highlight.seat(seat)) { it.copy(seats = it.seats + (seat to comfort)) }
            }
        }
    }
}

@Composable
private fun WindowControls(cabin: Cabin, store: PlaygroundStore) {
    SubTitle("车窗")
    Seat.entries.forEach { seat ->
        ControlRow(seat.label) {
            CommitSlider(cabin.window(seat).toFloat(), 0f..100f, { "${it.roundToInt()}%" }) { value ->
                val open = value.roundToInt()
                store.cabin("${seat.label}车窗${if (open == 0) "关上" else "开到 $open%"}", Highlight.window(seat)) { it.copy(windows = it.windows + (seat to open)) }
            }
        }
    }
}

@Composable
private fun BodyControls(cabin: Cabin, store: PlaygroundStore) {
    SubTitle("车门和后备箱") {
        Text(if (cabin.doorsLocked) "已锁" else "没锁", color = PlaygroundColors.muted, fontSize = 13.sp)
        SmallSwitch(cabin.doorsLocked) { locked -> store.cabin(if (locked) "车门上锁" else "车门解锁", Highlight.DOOR) { it.copy(doorsLocked = locked) } }
    }
    ControlRow("开门") {
        Seat.entries.forEach { seat -> MiniPill(seat.label, seat in cabin.doorsOpen) { store.toggleDoor(seat) } }
    }
    ControlRow("后备箱") {
        MiniPill(if (cabin.trunkOpen) "开着" else "关着", cabin.trunkOpen) { store.toggleTrunk() }
    }
}

@Composable
private fun ScreenSoundControls(cabin: Cabin, store: PlaygroundStore) {
    SubTitle("屏幕和声音")
    ControlRow("亮度") {
        CommitSlider(cabin.screenBrightness.toFloat(), 0f..100f, { if (it < 0.5f) "熄屏" else "${it.roundToInt()}%" }) { value ->
            val level = value.roundToInt()
            store.cabin(if (level == 0) "中控屏熄屏" else "中控屏亮度 $level%", Highlight.SCREEN) { it.copy(screenBrightness = level) }
        }
    }
    ControlRow("音量") {
        CommitSlider(cabin.media.volume.toFloat(), 0f..30f, { if (it < 0.5f) "静音" else "${it.roundToInt()}" }) { value ->
            val level = value.roundToInt()
            store.cabin(if (level == 0) "静音" else "音量 $level", Highlight.SCREEN) { it.copy(media = it.media.copy(volume = level)) }
        }
    }
}

@Composable
private fun AmbientControls(ambient: AmbientLight, store: PlaygroundStore) {
    SubTitle("氛围灯") {
        SmallSwitch(ambient.on) { on -> store.cabin(if (on) "氛围灯打开" else "氛围灯关闭", Highlight.AMBIENT) { it.copy(ambient = it.ambient.copy(on = on)) } }
    }
    ControlRow("颜色") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AMBIENT_COLORS.forEach { (name, color, _) ->
                Swatch(Color(color), ambient.on && ambient.colorName == name) {
                    store.cabin("氛围灯换成$name", Highlight.AMBIENT) { it.copy(ambient = it.ambient.copy(on = true, colorName = name, color = color)) }
                }
            }
        }
    }
    ControlRow("亮度") {
        CommitSlider(ambient.brightness.toFloat(), 1f..100f, { "${it.roundToInt()}%" }) { value ->
            store.cabin("氛围灯亮度 ${value.roundToInt()}%", Highlight.AMBIENT) { it.copy(ambient = it.ambient.copy(on = true, brightness = value.roundToInt())) }
        }
    }
}

@Composable
private fun MediaControls(cabin: Cabin, store: PlaygroundStore) {
    SubTitle("音乐")
    ControlRow("音乐") {
        val playing = cabin.media.playing
        MiniPill(if (playing) "暂停" else "播放", playing) {
            val media = cabin.media.played(if (playing) "pause" else "resume", null)
            store.cabin(if (playing) "音乐暂停" else "播放《${media.title}》", Highlight.SCREEN) { it.copy(media = media) }
        }
        MiniPill("下一首", false) {
            val media = cabin.media.played("next", null)
            store.cabin("播放《${media.title}》", Highlight.SCREEN) { it.copy(media = media) }
        }
    }
}

@Composable
private fun VehicleControls(cabin: Cabin, store: PlaygroundStore) {
    SubTitle("车辆读数")
    ControlRow("电量") {
        CommitSlider(cabin.drive.batteryPct.toFloat(), 0f..100f, { "${it.roundToInt()}%" }) { value ->
            store.cabin("电量设为 ${value.roundToInt()}%") { it.copy(drive = it.drive.copy(batteryPct = value.roundToInt().toDouble())) }
        }
    }
    ControlRow("车内") {
        CommitSlider(cabin.insideTempC.toFloat(), 10f..40f, { it.toDouble().deg() }) { value ->
            store.cabin("车内温度设为 ${value.roundOne().deg()}", Highlight.CLIMATE) { it.copy(insideTempC = value.roundOne()) }
        }
    }
    ControlRow("车外") {
        CommitSlider(cabin.drive.outsideTempC.toFloat(), -10f..45f, { it.toDouble().deg() }) { value ->
            store.cabin("车外温度设为 ${value.roundOne().deg()}") { it.copy(drive = it.drive.copy(outsideTempC = value.roundOne())) }
        }
    }
}

internal fun Float.roundOne(): Double = (this * 10).roundToInt() / 10.0

private val SEAT_LEVELS: List<Pair<String, SeatComfort>> =
    listOf("关" to SeatComfort()) + (1..3).map { "热$it" to SeatComfort(heat = it) } + (1..3).map { "风$it" to SeatComfort(ventilation = it) }

