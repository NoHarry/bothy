package cc.noharry.bothy.playground
import cc.noharry.bothy.playground.cabin.Seat
import cc.noharry.bothy.playground.cabin.summary
import cc.noharry.bothy.playground.home.Room
import cc.noharry.bothy.playground.home.World
import cc.noharry.bothy.playground.home.summary
import cc.noharry.bothy.playground.nav.AMapNavEngine
import cc.noharry.bothy.playground.nav.MapCue
import cc.noharry.bothy.playground.nav.NavController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What one tool call changed: the new world, what to answer, and which spots the scene should flash ([Highlight]). */
data class ToolOutcome(val world: World, val text: String, val highlights: Set<String> = emptySet(), val isError: Boolean = false)

/** One line of the call log the scene shows: who asked for what, and what came of it. */
data class CallRecord(
    val id: Long,
    val atMillis: Long,
    val server: String,
    val tool: String,
    val summary: String,
    val highlights: Set<String>,
    val fromAssistant: Boolean,
)

enum class ServerStatus { STARTING, RUNNING, FAILED }

data class ServerState(val status: ServerStatus = ServerStatus.STARTING, val address: String = "", val problem: String = "")

/**
 * The playground's one world, shared by the scene and the MCP server in the same process. Every change goes through
 * [change] under one lock, so a tool call and a tick never lose each other's update.
 */
class PlaygroundStore(initial: World = World(), private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private val worldState = MutableStateFlow(initial)
    private val callLog = CallLog(clock)
    private val serverState = MutableStateFlow(ServerState())

    val world: StateFlow<World> = worldState.asStateFlow()
    val calls: StateFlow<List<CallRecord>> = callLog.calls.asStateFlow()
    val server: StateFlow<ServerState> = serverState.asStateFlow()

    /** Runs [action] on the world as it is now; a change that is not an error is logged as [server]'s [tool]. */
    fun change(server: String, tool: String, fromAssistant: Boolean = true, action: (World) -> ToolOutcome): ToolOutcome =
        synchronized(lock) {
            val outcome = action(worldState.value)
            if (!outcome.isError) {
                worldState.value = outcome.world
                callLog.add(server, tool, outcome.text, outcome.highlights, fromAssistant)
            }
            outcome
        }

    fun read(): World = worldState.value

    /** A change nobody asked for in words (navigation readings every second): no log line, no flash. */
    fun quietly(edit: (World) -> World) = synchronized(lock) { worldState.value = edit(worldState.value) }

    /** A call that changed the world itself ([McpTool.direct]), logged afterwards. */
    fun record(server: String, tool: String, summary: String, highlights: Set<String>, fromAssistant: Boolean = true) =
        synchronized(lock) { callLog.add(server, tool, summary, highlights, fromAssistant) }

    /** The world moving on by [seconds]; what happened on its own goes to the log as notes. */
    fun tick(seconds: Double) = synchronized(lock) {
        val notes = mutableListOf<String>()
        worldState.value = worldState.value.tick(seconds, notes = notes)
        notes.forEach { callLog.add("playground", "simulation", it, emptySet(), fromAssistant = false) }
    }

    fun reset() = synchronized(lock) {
        worldState.value = World()
        callLog.calls.value = emptyList()
    }

    fun setServer(state: ServerState) = serverState.update { state }

    companion object {
        /** The tool name a manual change is logged under. */
        const val MANUAL = "manual"
    }
}

/** What was done to the world, newest first; only the latest [MAX_CALLS]. */
private class CallLog(private val clock: () -> Long) {
    private var nextId = 1L
    val calls = MutableStateFlow<List<CallRecord>>(emptyList())

    fun add(server: String, tool: String, summary: String, highlights: Set<String>, fromAssistant: Boolean) {
        val record = CallRecord(nextId++, clock(), server, tool, summary, highlights, fromAssistant)
        calls.value = (listOf(record) + calls.value).take(MAX_CALLS)
    }
}

private const val MAX_CALLS = 40

/** The process's world: the activity draws it, the service ticks it and serves it, with the car's navigation. */
object Playground {
    val store = PlaygroundStore()

    /** Set by [PlaygroundService] once it starts: the AMap engine and the controller the tools and the screen share. */
    @Volatile var engine: AMapNavEngine? = null
    @Volatile var navigation: NavController? = null

    /** What the navigation found, in order, for the head unit's map to play one after another; dropped when nobody shows it. */
    val mapCues = kotlinx.coroutines.flow.MutableSharedFlow<MapCue>(extraBufferCapacity = MAP_CUES_KEPT)

    /** The head unit is up and plays [mapCues]; the navigation then plans only once it has shown the places. */
    @Volatile var mapShowing = false

    /** The scene Bothy asked to show (its 「回到车机」, `playground_open`), taken by the screen once shown. */
    internal val sceneAsked = kotlinx.coroutines.flow.MutableStateFlow<PlaygroundScene?>(null)
}

private const val MAP_CUES_KEPT = 64

/** Spot keys a call flashes in the scene, e.g. `seat:driver`, `window:rear_left`, `room:living`, `door`. */
object Highlight {
    fun seat(seat: Seat) = "seat:${seat.id}"
    fun window(seat: Seat) = "window:${seat.id}"
    fun door(seat: Seat) = "door:${seat.id}"
    const val TRUNK = "trunk"
    const val NAV = "nav"
    fun room(room: Room) = "room:${room.id}"
    fun curtain(room: Room) = "curtain:${room.id}"
    const val CLIMATE = "climate"
    const val AMBIENT = "ambient"
    const val SCREEN = "screen"
    const val DOOR = "door"
    const val VACUUM = "vacuum"
}
