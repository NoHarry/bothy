package cc.noharry.bothy.playground.home

import cc.noharry.bothy.playground.cabin.Cabin
import cc.noharry.bothy.playground.cabin.ClimateMode
import cc.noharry.bothy.playground.headunit.cabin
import cc.noharry.bothy.playground.headunit.home
import cc.noharry.bothy.playground.headunit.on
/** The flat's four rooms; the kitchen has no air conditioner. */
enum class Room(val id: String, val label: String, val hasClimate: Boolean) {
    LIVING("living", "客厅", true),
    BEDROOM("bedroom", "卧室", true),
    KITCHEN("kitchen", "厨房", false),
    STUDY("study", "书房", true),
}

enum class LightTone(val id: String, val label: String, val color: Long) {
    WARM("warm", "暖光", 0xFFFFC27A),
    NEUTRAL("neutral", "自然光", 0xFFFFF1D6),
    COOL("cool", "冷白", 0xFFDCEBFF),
}

data class RoomLight(val on: Boolean = false, val brightness: Int = 80, val tone: LightTone = LightTone.WARM)

data class RoomClimate(val on: Boolean = false, val targetTemp: Double = 26.0, val mode: ClimateMode = ClimateMode.AUTO)

/** [curtainOpen] is 0 (drawn shut) to 100 (fully open); [presence] is the room's motion sensor. */
data class RoomState(
    val light: RoomLight = RoomLight(),
    val curtainOpen: Int = 100,
    val climate: RoomClimate = RoomClimate(),
    val tempC: Double = 28.5,
    val humidity: Int = 58,
    val presence: Boolean = false,
)

enum class VacuumStatus(val id: String, val label: String) {
    DOCKED("docked", "在充电座"),
    CLEANING("cleaning", "正在清扫"),
    PAUSED("paused", "已暂停"),
    RETURNING("returning", "正在回充"),
}

/** [queue] is what is left to clean after [room]; [progress] runs 0…1 over a room, or over the way back. */
data class Vacuum(
    val status: VacuumStatus = VacuumStatus.DOCKED,
    val room: Room? = null,
    val queue: List<Room> = emptyList(),
    val progress: Double = 0.0,
    val battery: Double = 100.0,
)

/** A small flat on a hot evening: lights off, curtains open, the air conditioners off, the rooms warm, someone in the living room. */
data class Home(
    val rooms: Map<Room, RoomState> = Room.entries.associateWith { RoomState(presence = it == Room.LIVING) },
    val doorLocked: Boolean = true,
    val vacuum: Vacuum = Vacuum(),
    val scene: String? = null,
    val outdoorTempC: Double = 30.0,
) {
    fun room(room: Room): RoomState = rooms.getValue(room)

    fun withRoom(room: Room, change: (RoomState) -> RoomState): Home = copy(rooms = rooms + (room to change(room(room))))
}

/** Where the user is: in the car or at home, never both; picked in the playground. */
enum class UserPlace(val id: String, val label: String) {
    CABIN("cabin", "车上"),
    HOME("home", "家里"),
}

/**
 * Everything the playground simulates; [Playground] holds the one instance the app and its MCP server share. [userAt]
 * tells an assistant connected to both servers which one 「我这边」 means.
 */
data class World(val cabin: Cabin = Cabin(), val home: Home = Home(), val userAt: UserPlace = UserPlace.CABIN)
