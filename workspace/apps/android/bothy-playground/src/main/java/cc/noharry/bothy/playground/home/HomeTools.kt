package cc.noharry.bothy.playground.home
import cc.noharry.bothy.playground.Highlight
import cc.noharry.bothy.playground.ToolOutcome
import cc.noharry.bothy.playground.cabin.ALL_WORDS
import cc.noharry.bothy.playground.cabin.ClimateMode
import cc.noharry.bothy.playground.cabin.climateLine
import cc.noharry.bothy.playground.cabin.climateModeNames
import cc.noharry.bothy.playground.cabin.deg
import cc.noharry.bothy.playground.cabin.summary
import cc.noharry.bothy.playground.headunit.home
import cc.noharry.bothy.playground.headunit.on
import cc.noharry.bothy.playground.link.open
import cc.noharry.bothy.playground.mcp.McpTool
import cc.noharry.bothy.playground.mcp.ToolArgs
import cc.noharry.bothy.playground.mcp.ToolRefusal
import cc.noharry.bothy.playground.mcp.objectSchema
import cc.noharry.bothy.playground.mcp.property
import kotlin.math.roundToInt

/** The home server's tools: a fictional flat, so nothing here reaches a real device. */
internal val HOME_TOOLS: List<McpTool> by lazy {
    listOf(homeStatusTool(), lightTool(), curtainTool(), homeClimateTool(), frontDoorTool(), vacuumTool(), sceneTool())
}

private const val ROOM_HINT = "living 客厅、bedroom 卧室、kitchen 厨房、study 书房、all 全部"

private fun homeStatusTool() = McpTool(
    name = "get_home_status",
    title = "查看家里状态",
    description = "查看虚拟小家现在的状态：每个房间的温度、湿度、灯、窗帘和空调，门锁，扫地机，当前场景。",
    inputSchema = objectSchema(),
    readOnly = true,
) { world, _ -> ToolOutcome(world, world.home.summary(world.userAt == UserPlace.HOME)) }

private fun lightTool() = McpTool(
    name = "set_light",
    title = "灯",
    description = "开关房间的灯、调亮度和色调。给了亮度或色调就会开灯。",
    inputSchema = objectSchema(
        "room" to property("string", "哪个房间：$ROOM_HINT"),
        "power" to property("boolean", "开或关"),
        "brightness" to property("integer", "亮度百分比", min = 1, max = 100),
        "tone" to property("string", "色调：warm 暖光、neutral 自然光、cool 冷白", LightTone.entries.map { it.id }),
        required = listOf("room"),
    ),
) { world, args ->
    val rooms = args.rooms("room")
    val power = args.bool("power")
    val brightness = args.int("brightness", 1..100)
    val tone = args.choice("tone", LightTone.entries) { listOf(it.id, it.label) + toneAliases(it) }
    if (power == null && brightness == null && tone == null) throw ToolRefusal("至少给出 power、brightness、tone 中的一项")
    val home = rooms.fold(world.home) { home, room ->
        home.withRoom(room) { state ->
            val light = state.light
            state.copy(light = light.copy(on = power ?: true, brightness = brightness ?: light.brightness, tone = tone ?: light.tone))
        }
    }.copy(scene = null)
    val text = rooms.joinToString("；") { "${it.label}${home.room(it).light.line()}" } + "。"
    ToolOutcome(world.copy(home = home), text, rooms.mapTo(mutableSetOf(), Highlight::room))
}

private fun curtainTool() = McpTool(
    name = "set_curtain",
    title = "窗帘",
    description = "开关窗帘。open 是打开的程度：0 全关、100 全开。",
    inputSchema = objectSchema(
        "room" to property("string", "哪个房间：$ROOM_HINT"),
        "open" to property("integer", "打开的百分比", min = 0, max = 100),
        required = listOf("room", "open"),
    ),
) { world, args ->
    val rooms = args.rooms("room")
    val open = args.int("open", 0..100) ?: throw ToolRefusal("给出 open（0–100）")
    val home = rooms.fold(world.home) { home, room -> home.withRoom(room) { it.copy(curtainOpen = open) } }.copy(scene = null)
    val state = when (open) {
        0 -> "窗帘已关上"
        100 -> "窗帘已拉开"
        else -> "窗帘开到 $open%"
    }
    ToolOutcome(world.copy(home = home), rooms.joinToString("、") { it.label } + state + "。", rooms.mapTo(mutableSetOf(), Highlight::curtain))
}

private fun homeClimateTool() = McpTool(
    name = "set_climate",
    title = "空调",
    description = "开关房间的空调、设温度和模式（厨房没有空调）。给了温度或模式就会打开。",
    inputSchema = objectSchema(
        "room" to property("string", "哪个房间：living 客厅、bedroom 卧室、study 书房、all 全部"),
        "power" to property("boolean", "开或关"),
        "temperature" to property("number", "设定温度，摄氏度", min = 16, max = 30),
        "mode" to property("string", "模式：auto 自动、cool 制冷、heat 制热、vent 只送风", ClimateMode.entries.map { it.id }),
        required = listOf("room"),
    ),
) { world, args ->
    val rooms = args.rooms("room").filter { it.hasClimate }.ifEmpty { throw ToolRefusal("厨房没有空调") }
    val power = args.bool("power")
    val temp = args.double("temperature", 16.0..30.0)
    val mode = args.choice("mode", ClimateMode.entries) { climateModeNames(it) }
    if (power == null && temp == null && mode == null) throw ToolRefusal("至少给出 power、temperature、mode 中的一项")
    val home = rooms.fold(world.home) { home, room ->
        home.withRoom(room) { state ->
            val c = state.climate
            state.copy(climate = c.copy(on = power ?: true, targetTemp = temp ?: c.targetTemp, mode = mode ?: c.mode))
        }
    }.copy(scene = null)
    val text = rooms.joinToString("；") { "${it.label}${home.room(it).climateLine()}，现在 ${home.room(it).tempC.deg()}" } + "。"
    ToolOutcome(world.copy(home = home), text, rooms.mapTo(mutableSetOf(), Highlight::room))
}

private fun frontDoorTool() = McpTool(
    name = "set_door_lock",
    title = "门锁",
    description = "锁上或打开入户门锁。开锁前先向用户确认。",
    inputSchema = objectSchema("locked" to property("boolean", "true 上锁、false 开锁"), required = listOf("locked")),
    destructive = true,
) { world, args ->
    val locked = args.bool("locked") ?: throw ToolRefusal("给出 locked")
    ToolOutcome(world.copy(home = world.home.copy(doorLocked = locked)), if (locked) "门已上锁。" else "门锁已打开。", setOf(Highlight.DOOR))
}

private fun vacuumTool() = McpTool(
    name = "control_vacuum",
    title = "扫地机",
    description = "让扫地机开始清扫（指定房间，或 all 依次扫完全屋）、暂停、继续或回充。",
    inputSchema = objectSchema(
        "action" to property("string", "start 开始、pause 暂停、resume 继续、dock 回充", listOf("start", "pause", "resume", "dock")),
        "room" to property("string", "start 时扫哪里：$ROOM_HINT；默认全屋"),
        required = listOf("action"),
    ),
) { world, args ->
    val action = args.choice("action", listOf("start", "pause", "resume", "dock")) { vacuumActionNames(it) }!!
    val old = world.home.vacuum
    val vacuum = when (action) {
        "start" -> args.rooms("room", Room.entries).let { rooms -> old.copy(status = VacuumStatus.CLEANING, room = rooms.first(), queue = rooms.drop(1), progress = 0.0) }
        "pause" -> if (old.status == VacuumStatus.CLEANING) old.copy(status = VacuumStatus.PAUSED) else throw ToolRefusal("扫地机没有在清扫")
        "resume" -> if (old.status == VacuumStatus.PAUSED) old.copy(status = VacuumStatus.CLEANING) else throw ToolRefusal("扫地机没有暂停")
        else -> if (old.status == VacuumStatus.DOCKED) old else old.copy(status = VacuumStatus.RETURNING, queue = emptyList(), progress = 0.0)
    }
    ToolOutcome(world.copy(home = world.home.copy(vacuum = vacuum)), "扫地机${vacuum.line()}。", setOf(Highlight.VACUUM))
}

private fun sceneTool() = McpTool(
    name = "run_scene",
    title = "场景",
    description = "一次调好几样设备：home 回家、away 离家（关灯关空调、拉上窗帘、锁门、开始扫地）、sleep 睡觉、movie 观影、morning 起床。",
    inputSchema = objectSchema(
        "scene" to property("string", "场景", HomeScene.entries.map { it.id }),
        required = listOf("scene"),
    ),
) { world, args ->
    val scene = args.choice("scene", HomeScene.entries) { listOf(it.id, it.label) + it.aliases }!!
    val home = scene.apply(world.home).copy(scene = scene.label)
    ToolOutcome(world.copy(home = home), "已切到「${scene.label}」场景：${scene.effect}。", scene.highlights)
}

/** The scenes [sceneTool] runs: each a few settings at once, as a smart-home app's would. */
internal enum class HomeScene(val id: String, val label: String, val aliases: List<String>, val effect: String) {
    HOME("home", "回家", listOf("到家", "回来"), "客厅和厨房开暖光，客厅空调 26°C") {
        override fun apply(home: Home) = home.lights(80, LightTone.WARM, Room.LIVING, Room.KITCHEN).climate(Room.LIVING, 26.0)
    },
    AWAY("away", "离家", listOf("出门", "离开"), "全屋关灯、关空调、拉上窗帘、锁门，扫地机开始清扫全屋") {
        override fun apply(home: Home) = home.lights(0, null).curtains(0).copy(doorLocked = true).climateOff()
            .copy(vacuum = home.vacuum.copy(status = VacuumStatus.CLEANING, room = Room.LIVING, queue = Room.entries.drop(1), progress = 0.0))
    },
    SLEEP("sleep", "睡觉", listOf("睡眠", "晚安", "我要睡了"), "只留卧室 10% 暖光，全屋拉上窗帘，卧室空调 26°C，锁门") {
        override fun apply(home: Home) = home.lights(0, null).lights(10, LightTone.WARM, Room.BEDROOM).curtains(0)
            .climate(Room.BEDROOM, 26.0).copy(doorLocked = true)
    },
    MOVIE("movie", "观影", listOf("看电影", "电影"), "客厅调到 20% 暖光、拉上窗帘，其他房间关灯") {
        override fun apply(home: Home) = home.lights(0, null).lights(20, LightTone.WARM, Room.LIVING).withRoom(Room.LIVING) { it.copy(curtainOpen = 0) }
    },
    MORNING("morning", "起床", listOf("早上好", "起床了"), "拉开全屋窗帘，卧室和厨房开自然光") {
        override fun apply(home: Home) = home.curtains(100).lights(70, LightTone.NEUTRAL, Room.BEDROOM, Room.KITCHEN)
    };

    abstract fun apply(home: Home): Home

    val highlights: Set<String> get() = Room.entries.flatMapTo(mutableSetOf()) { listOf(Highlight.room(it), Highlight.curtain(it)) } + Highlight.DOOR
}

/** [rooms] (all when none named) to [brightness] (0 turns them off) in [tone]. */
private fun Home.lights(brightness: Int, tone: LightTone?, vararg rooms: Room): Home =
    (rooms.ifEmpty { Room.entries.toTypedArray() }).fold(this) { home, room ->
        home.withRoom(room) { s -> s.copy(light = if (brightness == 0) s.light.copy(on = false) else s.light.copy(on = true, brightness = brightness, tone = tone ?: s.light.tone)) }
    }

private fun Home.curtains(open: Int): Home = Room.entries.fold(this) { home, room -> home.withRoom(room) { it.copy(curtainOpen = open) } }

private fun Home.climate(room: Room, temp: Double): Home =
    withRoom(room) { it.copy(climate = it.climate.copy(on = true, targetTemp = temp, mode = ClimateMode.AUTO)) }

private fun Home.climateOff(): Home = Room.entries.fold(this) { home, room -> home.withRoom(room) { it.copy(climate = it.climate.copy(on = false)) } }

/** [name]'s rooms: one by id or label, or all; [default] when it is not given (refused when there is none). */
private fun ToolArgs.rooms(name: String, default: List<Room>? = null): List<Room> {
    val said = string(name)?.lowercase() ?: return default ?: throw ToolRefusal("给出 $name")
    if (said in ALL_WORDS) return Room.entries
    return listOf(choice(name, Room.entries) { roomNames(it) }!!)
}

private fun roomNames(room: Room): List<String> = listOf(room.id, room.label) + when (room) {
    Room.LIVING -> listOf("起居室", "大厅", "living room")
    Room.BEDROOM -> listOf("主卧", "睡房")
    Room.KITCHEN -> listOf("餐厅", "饭厅", "dining")
    Room.STUDY -> listOf("工作间", "办公室", "office")
}

private fun toneAliases(tone: LightTone): List<String> = when (tone) {
    LightTone.WARM -> listOf("暖", "暖色", "黄光")
    LightTone.NEUTRAL -> listOf("自然", "中性", "白光")
    LightTone.COOL -> listOf("冷", "冷光", "白")
}

private fun vacuumActionNames(action: String): List<String> = when (action) {
    "start" -> listOf("start", "开始", "清扫", "扫地", "clean")
    "pause" -> listOf("pause", "暂停", "停")
    "resume" -> listOf("resume", "继续")
    else -> listOf("dock", "回充", "回去", "充电", "home", "stop")
}

internal fun RoomLight.line(): String = if (on) "灯开着，${tone.label} $brightness%" else "灯关着"

internal fun RoomState.climateLine(): String = if (climate.on) "空调开着，${climate.mode.label} ${climate.targetTemp.deg()}" else "空调关着"

internal fun Vacuum.line(): String = when (status) {
    VacuumStatus.CLEANING -> "正在清扫${room?.label.orEmpty()}" + if (queue.isEmpty()) "" else "，接着扫${queue.joinToString("、") { it.label }}"
    VacuumStatus.PAUSED -> "在${room?.label.orEmpty()}暂停了"
    VacuumStatus.RETURNING -> "正在回充"
    VacuumStatus.DOCKED -> "在充电座，电量 ${battery.roundToInt()}%"
}

internal fun Home.summary(userHere: Boolean): String = buildString {
    append(if (userHere) "用户现在在家。" else "用户现在不在家（在车上）。")
    append("室外 ${outdoorTempC.deg()}。")
    Room.entries.forEach { room ->
        val s = room(room)
        append("${room.label} ${s.tempC.deg()}，${s.light.line()}，窗帘${if (s.curtainOpen == 0) "关着" else "开 ${s.curtainOpen}%"}")
        if (room.hasClimate) append("，${s.climateLine()}")
        append("。")
    }
    append(if (doorLocked) "门锁着。" else "门锁开着。")
    append("扫地机${vacuum.line()}。")
    scene?.let { append("当前场景：$it。") }
}
