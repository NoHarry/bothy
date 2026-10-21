package cc.noharry.bothy.playground

import androidx.compose.ui.geometry.Offset

/**
 * A change made by hand in the playground, logged as 手动: what a passenger or someone at home did, so an automation
 * watching the state (a Bothy plan) sees it as it would a real one.
 */
internal fun PlaygroundStore.manual(server: String, summary: String, highlights: Set<String> = emptySet(), edit: (World) -> World) =
    change(server, PlaygroundStore.MANUAL, fromAssistant = false) { world -> ToolOutcome(edit(world), summary, highlights) }

internal fun PlaygroundStore.setDriving(on: Boolean) = manual("cabin", if (on) "开始模拟行驶" else "停车") { world ->
    world.copy(cabin = world.cabin.copy(drive = world.cabin.drive.copy(moving = on)))
}

/** A manual change to the cabin, logged as 手动 with [summary], flashing [highlights]. */
internal fun PlaygroundStore.cabin(summary: String, vararg highlights: String, edit: (Cabin) -> Cabin) =
    manual("cabin", summary, highlights.toSet()) { it.copy(cabin = edit(it.cabin)) }

internal fun PlaygroundStore.home(summary: String, vararg highlights: String, edit: (Home) -> Home) =
    manual("home", summary, highlights.toSet()) { it.copy(home = edit(it.home)) }

internal fun PlaygroundStore.changeRoom(room: Room, summary: String, vararg highlights: String, edit: (RoomState) -> RoomState) =
    home("${room.label}$summary", *highlights) { it.withRoom(room, edit) }

/** A tap on the cabin picture: a seat takes or loses a passenger, the trunk or a window opens or closes all the way. */
internal fun PlaygroundStore.tapCabin(at: Offset) {
    val now = read().cabin
    CabinSpots.seatAt(at)?.let { seat ->
        val aboard = seat !in now.occupants
        cabin(if (aboard) "${seat.label}坐上一个人" else "${seat.label}的人下车了", Highlight.seat(seat)) { it.withOccupant(seat, aboard) }
        return
    }
    if (CabinSpots.trunkAt(at)) {
        toggleTrunk()
        return
    }
    CabinSpots.windowAt(at)?.let { seat ->
        val open = if (now.window(seat) == 0) 100 else 0
        cabin("${seat.label}车窗${if (open == 0) "关上" else "全开"}", Highlight.window(seat)) { it.copy(windows = it.windows + (seat to open)) }
    }
}

/** A long press: on a seat, whoever sits there is the one speaking now; on a door's edge, the door opens or closes. */
internal fun PlaygroundStore.holdCabin(at: Offset) {
    CabinSpots.seatAt(at)?.let { seat ->
        cabin("现在是${seat.label}在说话", Highlight.seat(seat)) { it.withSpeaker(seat) }
        return
    }
    CabinSpots.windowAt(at)?.let { seat -> toggleDoor(seat) }
}

internal fun PlaygroundStore.toggleDoor(seat: Seat) {
    val open = seat !in read().cabin.doorsOpen
    cabin("${seat.label}车门${if (open) "打开" else "关上"}", Highlight.door(seat)) {
        it.copy(doorsOpen = if (open) it.doorsOpen + seat else it.doorsOpen - seat, doorsLocked = if (open) false else it.doorsLocked)
    }
}

internal fun PlaygroundStore.toggleTrunk() {
    val open = !read().cabin.trunkOpen
    cabin(if (open) "后备箱打开" else "后备箱关上", Highlight.TRUNK) { it.copy(trunkOpen = open) }
}

/** A tap on the flat: the door locks or unlocks, a curtain opens or closes, a room's light goes on or off. */
internal fun PlaygroundStore.tapHome(at: Offset) {
    val now = read().home
    if (HomeSpots.doorAt(at)) {
        home(if (now.doorLocked) "门锁打开" else "门锁上", Highlight.DOOR) { it.copy(doorLocked = !it.doorLocked) }
        return
    }
    HomeSpots.curtainAt(at)?.let { room ->
        val open = if (now.room(room).curtainOpen == 0) 100 else 0
        changeRoom(room, if (open == 0) "窗帘关上" else "窗帘拉开", Highlight.curtain(room)) { it.copy(curtainOpen = open) }
        return
    }
    HomeSpots.roomAt(at)?.let { room ->
        val on = !now.room(room).light.on
        changeRoom(room, if (on) "开灯" else "关灯", Highlight.room(room)) { it.copy(light = it.light.copy(on = on)) }
    }
}

/** A long press on a room: someone walks in, or the room empties (its motion sensor). */
internal fun PlaygroundStore.holdHome(at: Offset) {
    val room = HomeSpots.roomAt(at) ?: return
    val present = !read().home.room(room).presence
    changeRoom(room, if (present) "有人了" else "没人了", Highlight.room(room)) { it.copy(presence = present) }
}
