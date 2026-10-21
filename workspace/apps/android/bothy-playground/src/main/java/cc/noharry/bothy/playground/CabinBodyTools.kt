package cc.noharry.bothy.playground

/** The cabin's body and screen: doors, the trunk, the centre screen's brightness, the volume. */
internal val CABIN_BODY_TOOLS: List<McpTool> by lazy { listOf(doorOpenTool(), trunkTool(), screenTool(), volumeTool()) }

private fun doorOpenTool() = McpTool(
    name = "set_door",
    title = "车门开关",
    description = "打开或关上车门。开门会先解锁；车在行驶时不能开门。上锁、解锁用 set_door_lock。",
    inputSchema = objectSchema(
        "door" to property("string", "哪扇车门：$SEAT_HINT"),
        "open" to property("boolean", "true 打开、false 关上"),
        required = listOf("door", "open"),
    ),
) { world, args ->
    val doors = args.seats("door", world.cabin)
    val open = args.bool("open") ?: throw ToolRefusal("给出 open")
    if (open && world.cabin.moving) throw ToolRefusal("车在行驶，不能开车门")
    val cabin = world.cabin.copy(
        doorsOpen = if (open) world.cabin.doorsOpen + doors else world.cabin.doorsOpen - doors.toSet(),
        doorsLocked = if (open) false else world.cabin.doorsLocked,
    )
    val where = if (doors.size == Seat.entries.size) "四个车门" else doors.joinToString("、") { it.label + "车门" }
    val unlocked = if (open && world.cabin.doorsLocked) "（先解了锁）" else ""
    ToolOutcome(world.copy(cabin = cabin), "$where${if (open) "已打开$unlocked" else "已关上"}。", doors.mapTo(mutableSetOf(), Highlight::door))
}

private fun trunkTool() = McpTool(
    name = "set_trunk",
    title = "后备箱",
    description = "打开或关上后备箱（电动尾门）。车在行驶时不能打开。",
    inputSchema = objectSchema("open" to property("boolean", "true 打开、false 关上"), required = listOf("open")),
) { world, args ->
    val open = args.bool("open") ?: throw ToolRefusal("给出 open")
    if (open && world.cabin.moving) throw ToolRefusal("车在行驶，不能打开后备箱")
    ToolOutcome(world.copy(cabin = world.cabin.copy(trunkOpen = open)), if (open) "后备箱已打开。" else "后备箱已关上。", setOf(Highlight.TRUNK))
}

private fun screenTool() = McpTool(
    name = "set_screen",
    title = "中控屏亮度",
    description = "调中控屏亮度：brightness 是 0–100 的亮度（0 熄屏），或者用 step 在现在的基础上加减（比如 -20 暗一点、+20 亮一点）。",
    inputSchema = objectSchema(
        "brightness" to property("integer", "亮度百分比，0 熄屏", min = 0, max = 100),
        "step" to property("integer", "在现在的亮度上加减多少", min = -100, max = 100),
    ),
) { world, args ->
    val now = world.cabin.screenBrightness
    val brightness = args.int("brightness", 0..100) ?: args.int("step", -100..100)?.let { (now + it).coerceIn(0, 100) }
        ?: throw ToolRefusal("给出 brightness 或 step")
    val text = if (brightness == 0) "中控屏已熄屏。" else "中控屏亮度 $brightness%。"
    ToolOutcome(world.copy(cabin = world.cabin.copy(screenBrightness = brightness)), text, setOf(Highlight.SCREEN))
}

private fun volumeTool() = McpTool(
    name = "set_volume",
    title = "音量",
    description = "调音量：volume 是 0–30 的音量，或者用 step 在现在的基础上加减（比如 -3 小一点、+3 大一点），mute=true 静音。",
    inputSchema = objectSchema(
        "volume" to property("integer", "音量", min = 0, max = 30),
        "step" to property("integer", "在现在的音量上加减多少", min = -30, max = 30),
        "mute" to property("boolean", "true 静音"),
    ),
) { world, args ->
    val now = world.cabin.media.volume
    val volume = when {
        args.bool("mute") == true -> 0
        else -> args.int("volume", 0..30) ?: args.int("step", -30..30)?.let { (now + it).coerceIn(0, 30) }
    } ?: throw ToolRefusal("给出 volume、step 或 mute")
    val media = world.cabin.media.copy(volume = volume)
    ToolOutcome(world.copy(cabin = world.cabin.copy(media = media)), if (volume == 0) "已静音。" else "音量 $volume。", setOf(Highlight.SCREEN))
}
