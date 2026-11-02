package cc.noharry.bothy.playground.cabin
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.playground.Highlight
import cc.noharry.bothy.playground.PlaygroundStore
import cc.noharry.bothy.playground.R
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
    val context = LocalContext.current
    val resources = LocalResources.current
    SubTitle(stringResource(R.string.cabin_passengers))
    ControlRow(stringResource(R.string.cabin_row_seated)) {
        Seat.entries.forEach { seat ->
            val aboard = seat in cabin.occupants
            MiniPill(stringResource(seat.labelRes), aboard) {
                val summary = if (aboard) R.string.cabin_log_got_out else R.string.cabin_log_got_in
                store.cabin(resources.getString(summary, context.seatName(seat)), Highlight.seat(seat)) {
                    it.withOccupant(seat, !aboard)
                }
            }
        }
    }
    ControlRow(stringResource(R.string.cabin_row_speaker)) {
        Seat.entries.forEach { seat ->
            MiniPill(stringResource(seat.labelRes), cabin.speaker == seat) {
                val summary = resources.getString(R.string.cabin_log_speaker, context.seatName(seat))
                store.cabin(summary, Highlight.seat(seat)) { it.withSpeaker(seat) }
            }
        }
    }
}

@Composable
private fun ClimateControls(climate: CabinClimate, store: PlaygroundStore) {
    val context = LocalContext.current
    val resources = LocalResources.current
    SubTitle(stringResource(R.string.cabin_climate)) {
        SmallSwitch(climate.on) { on ->
            val summary = resources.getString(if (on) R.string.cabin_log_climate_on else R.string.cabin_log_climate_off)
            store.cabin(summary, Highlight.CLIMATE) { it.copy(climate = it.climate.copy(on = on)) }
        }
    }
    ControlRow(stringResource(R.string.cabin_row_temp)) {
        fun set(temp: Double) {
            store.cabin(resources.getString(R.string.cabin_log_climate_temp, temp.deg()), Highlight.CLIMATE) {
                it.copy(climate = it.climate.copy(on = true, driverTemp = temp, passengerTemp = temp))
            }
        }
        Stepper(climate.driverTemp.deg(), { set((climate.driverTemp - 1).coerceAtLeast(16.0)) }, { set((climate.driverTemp + 1).coerceAtMost(30.0)) })
        fun fan(level: Int) = store.cabin(resources.getString(R.string.cabin_log_fan, level), Highlight.CLIMATE) {
            it.copy(climate = it.climate.copy(on = true, fan = level))
        }
        val fanLevel = stringResource(R.string.cabin_fan_level, climate.fan)
        Stepper(fanLevel, { fan((climate.fan - 1).coerceAtLeast(1)) }, { fan((climate.fan + 1).coerceAtMost(7)) })
    }
    ControlRow(stringResource(R.string.cabin_row_mode)) {
        Choices(ClimateMode.entries.map { stringResource(it.labelRes) to it }, climate.mode) { mode ->
            val summary = resources.getString(R.string.cabin_log_climate_mode, resources.getString(mode.labelRes))
            store.cabin(summary, Highlight.CLIMATE) { it.copy(climate = it.climate.copy(on = true, mode = mode)) }
        }
    }
}

@Composable
private fun SeatControls(cabin: Cabin, store: PlaygroundStore) {
    val context = LocalContext.current
    SubTitle(stringResource(R.string.cabin_seats))
    val levels = seatLevels()
    Seat.entries.forEach { seat ->
        ControlRow(stringResource(seat.labelRes)) {
            Choices(levels, cabin.seat(seat)) { comfort ->
                store.cabin(context.seatComfortLog(seat, comfort), Highlight.seat(seat)) {
                    it.copy(seats = it.seats + (seat to comfort))
                }
            }
        }
    }
}

@Composable
private fun WindowControls(cabin: Cabin, store: PlaygroundStore) {
    val context = LocalContext.current
    val resources = LocalResources.current
    SubTitle(stringResource(R.string.cabin_windows))
    Seat.entries.forEach { seat ->
        ControlRow(stringResource(seat.labelRes)) {
            CommitSlider(cabin.window(seat).toFloat(), 0f..100f, { "${it.roundToInt()}%" }) { value ->
                val open = value.roundToInt()
                val summary = if (open == 0) {
                    resources.getString(R.string.cabin_log_window_closed, context.seatName(seat))
                } else {
                    resources.getString(R.string.cabin_log_window_open, context.seatName(seat), open)
                }
                store.cabin(summary, Highlight.window(seat)) { it.copy(windows = it.windows + (seat to open)) }
            }
        }
    }
}

@Composable
private fun BodyControls(cabin: Cabin, store: PlaygroundStore) {
    val context = LocalContext.current
    val resources = LocalResources.current
    SubTitle(stringResource(R.string.cabin_doors_and_trunk)) {
        val lock = stringResource(if (cabin.doorsLocked) R.string.cabin_locked else R.string.cabin_unlocked)
        Text(lock, color = PlaygroundColors.muted, fontSize = 13.sp)
        SmallSwitch(cabin.doorsLocked) { locked ->
            val summary = if (locked) R.string.cabin_log_doors_locked else R.string.cabin_log_doors_unlocked
            store.cabin(resources.getString(summary), Highlight.DOOR) { it.copy(doorsLocked = locked) }
        }
    }
    ControlRow(stringResource(R.string.cabin_row_open_doors)) {
        Seat.entries.forEach { seat ->
            MiniPill(stringResource(seat.labelRes), seat in cabin.doorsOpen) { store.toggleDoor(context, seat) }
        }
    }
    ControlRow(stringResource(R.string.cabin_row_trunk)) {
        val trunk = stringResource(if (cabin.trunkOpen) R.string.cabin_trunk_open else R.string.cabin_trunk_shut)
        MiniPill(trunk, cabin.trunkOpen) { store.toggleTrunk(context) }
    }
}

@Composable
private fun ScreenSoundControls(cabin: Cabin, store: PlaygroundStore) {
    val context = LocalContext.current
    val resources = LocalResources.current
    SubTitle(stringResource(R.string.cabin_screen_and_sound))
    ControlRow(stringResource(R.string.cabin_row_brightness)) {
        val off = stringResource(R.string.cabin_slider_screen_off)
        CommitSlider(cabin.screenBrightness.toFloat(), 0f..100f, { if (it < 0.5f) off else "${it.roundToInt()}%" }) { value ->
            val level = value.roundToInt()
            val summary = if (level == 0) {
                resources.getString(R.string.cabin_log_screen_off)
            } else {
                resources.getString(R.string.cabin_log_screen_brightness, level)
            }
            store.cabin(summary, Highlight.SCREEN) { it.copy(screenBrightness = level) }
        }
    }
    ControlRow(stringResource(R.string.cabin_row_volume)) {
        val muted = stringResource(R.string.cabin_muted)
        CommitSlider(cabin.media.volume.toFloat(), 0f..30f, { if (it < 0.5f) muted else "${it.roundToInt()}" }) { value ->
            val level = value.roundToInt()
            val summary = if (level == 0) muted else resources.getString(R.string.cabin_log_volume, level)
            store.cabin(summary, Highlight.SCREEN) { it.copy(media = it.media.copy(volume = level)) }
        }
    }
}

@Composable
private fun AmbientControls(ambient: AmbientLight, store: PlaygroundStore) {
    val context = LocalContext.current
    val resources = LocalResources.current
    SubTitle(stringResource(R.string.cabin_ambient)) {
        SmallSwitch(ambient.on) { on ->
            val summary = resources.getString(if (on) R.string.cabin_log_ambient_on else R.string.cabin_log_ambient_off)
            store.cabin(summary, Highlight.AMBIENT) { it.copy(ambient = it.ambient.copy(on = on)) }
        }
    }
    ControlRow(stringResource(R.string.cabin_row_color)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AMBIENT_COLORS.forEach { (name, color, _) ->
                Swatch(Color(color), ambient.on && ambient.colorName == name) {
                    val shown = AMBIENT_COLOR_LABELS[name]?.let { resources.getString(it) } ?: name
                    store.cabin(resources.getString(R.string.cabin_log_ambient_color, shown), Highlight.AMBIENT) {
                        it.copy(ambient = it.ambient.copy(on = true, colorName = name, color = color))
                    }
                }
            }
        }
    }
    ControlRow(stringResource(R.string.cabin_row_brightness)) {
        CommitSlider(ambient.brightness.toFloat(), 1f..100f, { "${it.roundToInt()}%" }) { value ->
            val level = value.roundToInt()
            store.cabin(resources.getString(R.string.cabin_log_ambient_brightness, level), Highlight.AMBIENT) {
                it.copy(ambient = it.ambient.copy(on = true, brightness = level))
            }
        }
    }
}

@Composable
private fun MediaControls(cabin: Cabin, store: PlaygroundStore) {
    val context = LocalContext.current
    val resources = LocalResources.current
    SubTitle(stringResource(R.string.cabin_music))
    ControlRow(stringResource(R.string.cabin_music)) {
        val playing = cabin.media.playing
        MiniPill(stringResource(if (playing) R.string.cabin_pause else R.string.cabin_play), playing) {
            val media = cabin.media.played(if (playing) "pause" else "resume", null)
            val summary = if (playing) {
                resources.getString(R.string.cabin_log_music_paused)
            } else {
                resources.getString(R.string.cabin_log_playing, media.title)
            }
            store.cabin(summary, Highlight.SCREEN) { it.copy(media = media) }
        }
        MiniPill(stringResource(R.string.cabin_next_track), false) {
            val media = cabin.media.played("next", null)
            store.cabin(resources.getString(R.string.cabin_log_playing, media.title), Highlight.SCREEN) {
                it.copy(media = media)
            }
        }
    }
}

@Composable
private fun VehicleControls(cabin: Cabin, store: PlaygroundStore) {
    val context = LocalContext.current
    val resources = LocalResources.current
    SubTitle(stringResource(R.string.cabin_readings))
    ControlRow(stringResource(R.string.cabin_row_battery)) {
        CommitSlider(cabin.drive.batteryPct.toFloat(), 0f..100f, { "${it.roundToInt()}%" }) { value ->
            store.cabin(resources.getString(R.string.cabin_log_battery, value.roundToInt())) {
                it.copy(drive = it.drive.copy(batteryPct = value.roundToInt().toDouble()))
            }
        }
    }
    ControlRow(stringResource(R.string.cabin_row_inside)) {
        CommitSlider(cabin.insideTempC.toFloat(), 10f..40f, { it.toDouble().deg() }) { value ->
            store.cabin(resources.getString(R.string.cabin_log_inside_temp, value.roundOne().deg()), Highlight.CLIMATE) {
                it.copy(insideTempC = value.roundOne())
            }
        }
    }
    ControlRow(stringResource(R.string.cabin_row_outside)) {
        CommitSlider(cabin.drive.outsideTempC.toFloat(), -10f..45f, { it.toDouble().deg() }) { value ->
            store.cabin(resources.getString(R.string.cabin_log_outside_temp, value.roundOne().deg())) {
                it.copy(drive = it.drive.copy(outsideTempC = value.roundOne()))
            }
        }
    }
}

internal fun Float.roundOne(): Double = (this * 10).roundToInt() / 10.0

@Composable
private fun seatLevels(): List<Pair<String, SeatComfort>> =
    listOf(stringResource(R.string.common_off) to SeatComfort()) +
        (1..3).map { stringResource(R.string.cabin_level_heat, it) to SeatComfort(heat = it) } +
        (1..3).map { stringResource(R.string.cabin_level_vent, it) to SeatComfort(ventilation = it) }

private fun Context.seatName(seat: Seat): String = getString(seat.labelRes)

/** The log line for [seat]'s heating or ventilation set by hand to [comfort], in this context's language. */
internal fun Context.seatComfortLog(seat: Seat, comfort: SeatComfort): String = when {
    comfort.heat > 0 -> getString(R.string.cabin_log_seat_heat, seatName(seat), comfort.heat)
    comfort.ventilation > 0 -> getString(R.string.cabin_log_seat_vent, seatName(seat), comfort.ventilation)
    else -> getString(R.string.cabin_log_seat_off, seatName(seat))
}

