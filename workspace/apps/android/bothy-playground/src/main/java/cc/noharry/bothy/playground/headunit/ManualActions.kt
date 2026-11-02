package cc.noharry.bothy.playground.headunit
import android.content.Context
import androidx.compose.ui.geometry.Offset
import cc.noharry.bothy.playground.Highlight
import cc.noharry.bothy.playground.PlaygroundStore
import cc.noharry.bothy.playground.R
import cc.noharry.bothy.playground.ToolOutcome
import cc.noharry.bothy.playground.cabin.Cabin
import cc.noharry.bothy.playground.cabin.Seat
import cc.noharry.bothy.playground.cabin.summary
import cc.noharry.bothy.playground.home.Home
import cc.noharry.bothy.playground.home.Room
import cc.noharry.bothy.playground.home.RoomState
import cc.noharry.bothy.playground.home.World
import cc.noharry.bothy.playground.home.summary
import cc.noharry.bothy.playground.link.open

/**
 * A change made by hand in the playground, logged as 手动: what a passenger or someone at home did, so an automation
 * watching the state (a Bothy plan) sees it as it would a real one. The log's line ([summary]) is in the screen's
 * language: the changes below word it with the [Context] they are given.
 */
internal fun PlaygroundStore.manual(server: String, summary: String, highlights: Set<String> = emptySet(), edit: (World) -> World) =
    change(server, PlaygroundStore.MANUAL, fromAssistant = false) { world -> ToolOutcome(edit(world), summary, highlights) }

internal fun PlaygroundStore.setDriving(context: Context, on: Boolean): ToolOutcome {
    val summary = context.getString(if (on) R.string.headunit_log_driving_started else R.string.headunit_log_parked)
    return manual("cabin", summary) { world ->
        world.copy(cabin = world.cabin.copy(drive = world.cabin.drive.copy(moving = on)))
    }
}

/** A manual change to the cabin, logged as 手动 with [summary], flashing [highlights]. */
internal fun PlaygroundStore.cabin(summary: String, vararg highlights: String, edit: (Cabin) -> Cabin) =
    manual("cabin", summary, highlights.toSet()) { it.copy(cabin = edit(it.cabin)) }

internal fun PlaygroundStore.home(summary: String, vararg highlights: String, edit: (Home) -> Home) =
    manual("home", summary, highlights.toSet()) { it.copy(home = edit(it.home)) }

/** A manual change to [room]; [summary] names the room. */
internal fun PlaygroundStore.changeRoom(room: Room, summary: String, vararg highlights: String, edit: (RoomState) -> RoomState) =
    home(summary, *highlights) { it.withRoom(room, edit) }

/** A tap on the cabin picture: a seat takes or loses a passenger, the trunk or a window opens or closes all the way. */
internal fun PlaygroundStore.tapCabin(context: Context, at: Offset) {
    val now = read().cabin
    CabinSpots.seatAt(at)?.let { seat ->
        val aboard = seat !in now.occupants
        val line = if (aboard) R.string.cabin_log_got_in else R.string.cabin_log_got_out
        val summary = context.getString(line, context.named(seat))
        cabin(summary, Highlight.seat(seat)) { it.withOccupant(seat, aboard) }
        return
    }
    if (CabinSpots.trunkAt(at)) {
        toggleTrunk(context)
        return
    }
    CabinSpots.windowAt(at)?.let { seat ->
        val open = if (now.window(seat) == 0) 100 else 0
        val line = if (open == 0) R.string.cabin_log_window_closed else R.string.headunit_log_window_full
        val summary = context.getString(line, context.named(seat))
        cabin(summary, Highlight.window(seat)) { it.copy(windows = it.windows + (seat to open)) }
    }
}

/** A long press: on a seat, whoever sits there is the one speaking now; on a door's edge, the door opens or closes. */
internal fun PlaygroundStore.holdCabin(context: Context, at: Offset) {
    CabinSpots.seatAt(at)?.let { seat ->
        val summary = context.getString(R.string.cabin_log_speaker, context.named(seat))
        cabin(summary, Highlight.seat(seat)) { it.withSpeaker(seat) }
        return
    }
    CabinSpots.windowAt(at)?.let { seat -> toggleDoor(context, seat) }
}

internal fun PlaygroundStore.toggleDoor(context: Context, seat: Seat) {
    val open = seat !in read().cabin.doorsOpen
    val line = if (open) R.string.headunit_log_door_opened else R.string.headunit_log_door_closed
    val summary = context.getString(line, context.named(seat))
    cabin(summary, Highlight.door(seat)) {
        it.copy(doorsOpen = if (open) it.doorsOpen + seat else it.doorsOpen - seat, doorsLocked = if (open) false else it.doorsLocked)
    }
}

internal fun PlaygroundStore.toggleTrunk(context: Context) {
    val open = !read().cabin.trunkOpen
    val summary =
        context.getString(if (open) R.string.headunit_log_trunk_opened else R.string.headunit_log_trunk_closed)
    cabin(summary, Highlight.TRUNK) { it.copy(trunkOpen = open) }
}

/** A tap on the flat: the door locks or unlocks, a curtain opens or closes, a room's light goes on or off. */
internal fun PlaygroundStore.tapHome(context: Context, at: Offset) {
    val now = read().home
    if (HomeSpots.doorAt(at)) {
        val summary =
            context.getString(if (now.doorLocked) R.string.home_log_door_unlocked else R.string.home_log_door_locked)
        home(summary, Highlight.DOOR) { it.copy(doorLocked = !it.doorLocked) }
        return
    }
    HomeSpots.curtainAt(at)?.let { room ->
        val open = if (now.room(room).curtainOpen == 0) 100 else 0
        val line = if (open == 0) R.string.home_log_curtain_closed else R.string.headunit_log_curtain_opened
        val summary = context.getString(line, context.getString(room.labelRes))
        changeRoom(room, summary, Highlight.curtain(room)) { it.copy(curtainOpen = open) }
        return
    }
    HomeSpots.roomAt(at)?.let { room ->
        val on = !now.room(room).light.on
        val line = if (on) R.string.home_log_light_on else R.string.home_log_light_off
        val summary = context.getString(line, context.getString(room.labelRes))
        changeRoom(room, summary, Highlight.room(room)) { it.copy(light = it.light.copy(on = on)) }
    }
}

/** A long press on a room: someone walks in, or the room empties (its motion sensor). */
internal fun PlaygroundStore.holdHome(context: Context, at: Offset) {
    val room = HomeSpots.roomAt(at) ?: return
    val present = !read().home.room(room).presence
    val line = if (present) R.string.home_log_occupied else R.string.home_log_empty
    val summary = context.getString(line, context.getString(room.labelRes))
    changeRoom(room, summary, Highlight.room(room)) { it.copy(presence = present) }
}

/** [seat] as the screen names it. */
private fun Context.named(seat: Seat): String = getString(seat.labelRes)
