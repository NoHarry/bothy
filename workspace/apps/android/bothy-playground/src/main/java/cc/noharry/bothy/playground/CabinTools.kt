package cc.noharry.bothy.playground

import kotlin.math.abs
import kotlin.math.roundToInt

/** The cabin server's tools: a fictional car, so nothing here reaches a real vehicle. */
internal val CABIN_TOOLS: List<McpTool> by lazy {
    listOf(cabinStatusTool(), climateTool(), seatTool(), windowTool(), ambientTool(), musicTool(), doorTool()) + CABIN_BODY_TOOLS
}

private fun cabinStatusTool() = McpTool(
    name = "get_cabin_status",
    title = "查看车内状态",
    description = "查看虚拟座舱现在的状态：谁坐在哪、谁在说话，车内外温度、空调、座椅加热和通风、车窗、车门和后备箱、车门锁、" +
        "中控屏亮度、音量、氛围灯、音乐、电量和续航，以及导航的概况（细节用 get_route）。",
    inputSchema = objectSchema(),
    readOnly = true,
) { world, _ -> ToolOutcome(world, world.cabin.summary(world.userAt == UserPlace.CABIN)) }

private fun climateTool() = McpTool(
    name = "set_climate",
    title = "空调",
    description = "开关空调、设温度、风量和模式。给了温度、风量或模式就会打开空调。zone 只改主驾或副驾的温度，默认两边一起。",
    inputSchema = objectSchema(
        "power" to property("boolean", "开（true）或关（false）"),
        "temperature" to property("number", "设定温度，摄氏度", min = 16, max = 30),
        "zone" to property("string", "温度改哪边", listOf("all", "driver", "passenger")),
        "fan" to property("integer", "风量", min = 1, max = 7),
        "mode" to property("string", "模式：auto 自动、cool 制冷、heat 制热、vent 只通风", ClimateMode.entries.map { it.id }),
    ),
) { world, args ->
    val power = args.bool("power")
    val temp = args.double("temperature", 16.0..30.0)
    val zone = args.choice("zone", listOf("all", "driver", "passenger")) { zoneNames(it) } ?: "all"
    val fan = args.int("fan", 1..7)
    val mode = args.choice("mode", ClimateMode.entries) { climateModeNames(it) }
    if (power == null && temp == null && fan == null && mode == null) throw ToolRefusal("至少给出 power、temperature、fan、mode 中的一项")
    val old = world.cabin.climate
    val climate = old.copy(
        on = power ?: true,
        driverTemp = if (zone != "passenger") temp ?: old.driverTemp else old.driverTemp,
        passengerTemp = if (zone != "driver") temp ?: old.passengerTemp else old.passengerTemp,
        fan = fan ?: old.fan,
        mode = mode ?: old.mode,
    )
    val cabin = world.cabin.copy(climate = climate)
    ToolOutcome(world.copy(cabin = cabin), cabin.climateLine() + "车内现在 ${cabin.insideTempC.deg()}。", setOf(Highlight.CLIMATE))
}

private fun seatTool() = McpTool(
    name = "set_seat",
    title = "座椅加热和通风",
    description = "调座椅加热或通风，0 是关，1–3 档。同一个座椅加热和通风不能同时开，开一个会关掉另一个。",
    inputSchema = objectSchema(
        "seat" to property("string", "哪个座椅：$SEAT_HINT"),
        "heat" to property("integer", "加热档位", min = 0, max = 3),
        "ventilation" to property("integer", "通风档位", min = 0, max = 3),
        required = listOf("seat"),
    ),
) { world, args ->
    val seats = args.seats("seat", world.cabin)
    val heat = args.int("heat", 0..3)
    val vent = args.int("ventilation", 0..3)
    if (heat == null && vent == null) throw ToolRefusal("给出 heat 或 ventilation")
    if ((heat ?: 0) > 0 && (vent ?: 0) > 0) throw ToolRefusal("同一个座椅加热和通风不能同时开")
    val changed = seats.associateWith { seat ->
        val old = world.cabin.seat(seat)
        when {
            (heat ?: 0) > 0 -> SeatComfort(heat = heat!!, ventilation = 0)
            (vent ?: 0) > 0 -> SeatComfort(heat = 0, ventilation = vent!!)
            else -> old.copy(heat = if (heat != null) 0 else old.heat, ventilation = if (vent != null) 0 else old.ventilation)
        }
    }
    val cabin = world.cabin.copy(seats = world.cabin.seats + changed)
    val text = seats.joinToString("；") { "${it.label}座椅${cabin.seat(it).comfortLine()}" + if (it in cabin.occupants) "" else "（这个座位没人）" } + "。"
    ToolOutcome(world.copy(cabin = cabin), text, seats.mapTo(mutableSetOf(), Highlight::seat))
}

private fun windowTool() = McpTool(
    name = "set_window",
    title = "车窗",
    description = "开关车窗。open 是开度：0 关闭、100 全开。",
    inputSchema = objectSchema(
        "window" to property("string", "哪扇车窗：$SEAT_HINT"),
        "open" to property("integer", "开度百分比", min = 0, max = 100),
        required = listOf("window", "open"),
    ),
) { world, args ->
    val windows = args.seats("window", world.cabin)
    val open = args.int("open", 0..100) ?: throw ToolRefusal("给出 open（0–100）")
    val cabin = world.cabin.copy(windows = world.cabin.windows + windows.associateWith { open })
    val where = if (windows.size == Seat.entries.size) "全部车窗" else windows.joinToString("、") { it.label + "车窗" }
    val state = if (open == 0) "已关闭" else "开到 $open%"
    val hint = if (open > 0 && cabin.climate.on) "（空调开着，开窗会让降温变慢）" else ""
    ToolOutcome(world.copy(cabin = cabin), "$where$state$hint。", windows.mapTo(mutableSetOf(), Highlight::window))
}

private fun ambientTool() = McpTool(
    name = "set_ambient_light",
    title = "氛围灯",
    description = "开关氛围灯、换颜色、调亮度。颜色可以用名字（${AMBIENT_COLORS.joinToString("、") { it.first }}）或 #RRGGBB。",
    inputSchema = objectSchema(
        "power" to property("boolean", "开或关"),
        "color" to property("string", "颜色名或 #RRGGBB"),
        "brightness" to property("integer", "亮度百分比", min = 1, max = 100),
    ),
) { world, args ->
    val power = args.bool("power")
    val color = args.string("color")?.let(::ambientColor)
    val brightness = args.int("brightness", 1..100)
    if (power == null && color == null && brightness == null) throw ToolRefusal("至少给出 power、color、brightness 中的一项")
    val old = world.cabin.ambient
    val ambient = old.copy(
        on = power ?: true,
        colorName = color?.first ?: old.colorName,
        color = color?.second ?: old.color,
        brightness = brightness ?: old.brightness,
    )
    val text = if (ambient.on) "氛围灯：${ambient.colorName}，亮度 ${ambient.brightness}%。" else "氛围灯已关闭。"
    ToolOutcome(world.copy(cabin = world.cabin.copy(ambient = ambient)), text, setOf(Highlight.AMBIENT))
}

private fun musicTool() = McpTool(
    name = "play_music",
    title = "音乐",
    description = "放音乐、暂停、继续、切歌、调音量（0–30）。query 可以是歌名、歌手或风格（轻松、摇滚、爵士、lofi、运动…），找不到就按它建一个电台。",
    inputSchema = objectSchema(
        "action" to property("string", "play 播放、pause 暂停、resume 继续、next 下一首", listOf("play", "pause", "resume", "next")),
        "query" to property("string", "想听什么"),
        "volume" to property("integer", "音量", min = 0, max = 30),
    ),
) { world, args ->
    val action = args.choice("action", listOf("play", "pause", "resume", "next")) { musicActionNames(it) }
        ?: if (args.has("query")) "play" else null
    val volume = args.int("volume", 0..30)
    if (action == null && volume == null) throw ToolRefusal("给出 action、query 或 volume")
    val media = world.cabin.media.played(action, args.string("query")).let { if (volume != null) it.copy(volume = volume) else it }
    val text = when {
        media.playing -> "正在播放《${media.title}》- ${media.artist}，音量 ${media.volume}。"
        media.title.isNotEmpty() -> "已暂停《${media.title}》，音量 ${media.volume}。"
        else -> "音量 ${media.volume}。"
    }
    ToolOutcome(world.copy(cabin = world.cabin.copy(media = media)), text, setOf(Highlight.SCREEN))
}

private fun doorTool() = McpTool(
    name = "set_door_lock",
    title = "车门锁",
    description = "锁上或解锁车门。",
    inputSchema = objectSchema("locked" to property("boolean", "true 上锁、false 解锁"), required = listOf("locked")),
) { world, args ->
    val locked = args.bool("locked") ?: throw ToolRefusal("给出 locked")
    ToolOutcome(world.copy(cabin = world.cabin.copy(doorsLocked = locked)), if (locked) "车门已上锁。" else "车门已解锁。", setOf(Highlight.DOOR))
}

internal const val SEAT_HINT = "driver 主驾、passenger 副驾、rear_left 左后、rear_right 右后、front 前排、rear 后排、all 全部，" +
    "speaker 正在说话的人的座位（用户说「我」「我这边」时）、passengers 乘客（不含主驾）、occupied 车上所有人的座位"

/**
 * [name]'s seats: one by id or label, a group (front, rear, all), or who sits where in [cabin]: the speaker, the
 * passengers, everyone aboard.
 */
internal fun ToolArgs.seats(name: String, cabin: Cabin): List<Seat> {
    val said = string(name)?.lowercase() ?: throw ToolRefusal("给出 $name")
    return when {
        said in SPEAKER_WORDS -> listOf(cabin.speaker ?: throw ToolRefusal("现在不知道是谁在说话，换成具体的座位"))
        said in PASSENGER_WORDS -> cabin.passengers.ifEmpty { throw ToolRefusal("车上没有乘客，只有主驾") }
        said in OCCUPIED_WORDS -> Seat.entries.filter { it in cabin.occupants }.ifEmpty { throw ToolRefusal("车上没有人") }
        said in ALL_WORDS -> Seat.entries
        said == "front" || said.contains("前排") -> listOf(Seat.DRIVER, Seat.PASSENGER)
        said == "rear" || said == "back" || said == "后排" || said == "后座" -> listOf(Seat.REAR_LEFT, Seat.REAR_RIGHT)
        else -> listOf(choice(name, Seat.entries) { seatNames(it) }!!)
    }
}

private fun seatNames(seat: Seat): List<String> = listOf(seat.id, seat.label) + when (seat) {
    Seat.DRIVER -> listOf("主驾驶", "驾驶位", "司机", "左前")
    Seat.PASSENGER -> listOf("副驾驶", "右前")
    Seat.REAR_LEFT -> listOf("后排左", "左后座")
    Seat.REAR_RIGHT -> listOf("后排右", "右后座")
}

private fun zoneNames(zone: String): List<String> = when (zone) {
    "driver" -> listOf("driver", "主驾", "主驾驶")
    "passenger" -> listOf("passenger", "副驾", "副驾驶")
    else -> listOf("all", "全部", "两边", "both")
}

internal fun climateModeNames(mode: ClimateMode): List<String> = listOf(mode.id, mode.label) + when (mode) {
    ClimateMode.AUTO -> listOf("自动模式")
    ClimateMode.COOL -> listOf("冷风", "cooling", "降温")
    ClimateMode.HEAT -> listOf("暖风", "heating", "取暖")
    ClimateMode.VENT -> listOf("吹风", "fan", "送风")
}

private fun musicActionNames(action: String): List<String> = when (action) {
    "play" -> listOf("play", "播放", "放")
    "pause" -> listOf("pause", "暂停", "停", "stop")
    "resume" -> listOf("resume", "继续")
    else -> listOf("next", "下一首", "切歌", "换一首")
}

/** A named colour or #RRGGBB, as its name and colour; refused when it is neither. */
private fun ambientColor(said: String): Pair<String, Long> {
    HEX.matchEntire(said.trim())?.let { return said.uppercase() to (0xFF000000 or it.groupValues[1].toLong(16)) }
    val lower = said.lowercase()
    val named = AMBIENT_COLORS.firstOrNull { (name, _, aliases) -> lower == name || lower in aliases }
        ?: AMBIENT_COLORS.firstOrNull { (name, _, aliases) -> lower.contains(name) || aliases.any { it.length > 1 && lower.contains(it) } }
        ?: throw ToolRefusal("不认识颜色「$said」，可以用：${AMBIENT_COLORS.joinToString("、") { it.first }}，或 #RRGGBB")
    return named.first to named.second
}

private data class Track(val title: String, val artist: String, val tags: List<String>)

/** Made-up tracks, so the demo plays nothing real. */
private val TRACKS = listOf(
    Track("山屋晚风", "小博乐队", listOf("轻松", "放松", "chill", "民谣", "安静")),
    Track("林间小路", "Moss & Ember", listOf("清新", "早晨", "acoustic", "吉他")),
    Track("夜行高速", "Neon Pines", listOf("摇滚", "动感", "开车", "rock", "夜")),
    Track("雨天咖啡", "Oat Trio", listOf("爵士", "jazz", "雨", "咖啡")),
    Track("星空露营", "Bothy Lo-fi", listOf("lofi", "lo-fi", "学习", "专注", "睡")),
    Track("清晨跑步", "Trail Beats", listOf("运动", "跑步", "电子", "edm", "嗨")),
)

internal fun Media.played(action: String?, query: String?): Media = when (action) {
    "pause" -> copy(playing = false)
    "resume" -> if (title.isEmpty()) TRACKS.first().let { copy(playing = true, title = it.title, artist = it.artist) } else copy(playing = true)
    "next" -> TRACKS[(TRACKS.indexOfFirst { it.title == title } + 1) % TRACKS.size].let { copy(playing = true, title = it.title, artist = it.artist) }
    "play" -> {
        val track = query?.let { q -> TRACKS.firstOrNull { t -> t.title.contains(q) || q.contains(t.title) || t.artist.equals(q, true) || t.tags.any { q.lowercase().contains(it) } } }
        when {
            track != null -> copy(playing = true, title = track.title, artist = track.artist)
            query != null -> copy(playing = true, title = "「$query」精选", artist = "虚拟电台")
            title.isNotEmpty() -> copy(playing = true)
            else -> TRACKS.first().let { copy(playing = true, title = it.title, artist = it.artist) }
        }
    }
    else -> this
}

internal fun Cabin.climateLine(): String = if (!climate.on) "空调已关闭。" else {
    val temps = if (climate.driverTemp == climate.passengerTemp) climate.driverTemp.deg() else "主驾 ${climate.driverTemp.deg()}、副驾 ${climate.passengerTemp.deg()}"
    "空调开着：$temps，${climate.mode.label}，风量 ${climate.fan}。"
}

internal fun SeatComfort.comfortLine(): String = when {
    heat > 0 -> "加热 $heat 档"
    ventilation > 0 -> "通风 $ventilation 档"
    else -> "加热和通风都关着"
}

internal fun Cabin.summary(userHere: Boolean): String = buildString {
    append(if (userHere) "用户现在在车上。" else "用户现在不在车上（在家里）。")
    append("车上：").append(Seat.entries.filter { it in occupants }.joinToString("、") { it.label }.ifEmpty { "没人" }).append("；")
    append(speaker?.let { "说话的人坐在${it.label}（${it.id}）。" } ?: "不知道是谁在说话。")
    append("车内 ${insideTempC.deg()}，车外 ${drive.outsideTempC.deg()}。")
    append(climateLine())
    append("座椅：").append(Seat.entries.joinToString("，") { "${it.label}${seat(it).comfortLine()}" }).append("。")
    append("车窗：").append(Seat.entries.joinToString("，") { "${it.label}${window(it).let { o -> if (o == 0) "关" else "$o%" }}" }).append("。")
    append(if (ambient.on) "氛围灯${ambient.colorName}，亮度 ${ambient.brightness}%。" else "氛围灯关。")
    append(if (media.playing) "在放《${media.title}》。" else "没有在放音乐。")
    if (nav.active) append("正在导航去「${nav.destination?.name}」，还有 ${nav.remainingMeters.km()}，途经点 ${nav.waypoints.numbered()}。")
    append("电量 ${drive.batteryPct.roundToInt()}%，续航 $rangeKm 公里，")
    append(if (drive.moving) "正在行驶 ${drive.speedKmh.roundToInt()} km/h。" else "停着。")
    append(if (doorsLocked) "车门已锁" else "车门没锁")
    append(if (doorsOpen.isEmpty()) "，车门都关着" else "，开着的车门：${Seat.entries.filter { it in doorsOpen }.joinToString("、") { it.label }}")
    append(if (trunkOpen) "，后备箱开着。" else "，后备箱关着。")
    append(if (screenBrightness == 0) "中控屏熄屏，" else "中控屏亮度 $screenBrightness%，")
    append("音量 ${media.volume}。")
}

internal fun Double.deg(): String = "${oneDp().removeSuffix(".0")}°C"

/** One decimal, with a point whatever the device's locale. */
internal fun Double.oneDp(): String = String.format(java.util.Locale.ROOT, "%.1f", this)

private val SPEAKER_WORDS = setOf("speaker", "me", "my", "mine", "我", "我的", "我这边", "我这里", "说话人", "说话的人")
private val PASSENGER_WORDS = setOf("passengers", "乘客", "所有乘客", "其他人", "别人")
private val OCCUPIED_WORDS = setOf("occupied", "occupants", "有人的座位", "车上的人", "大家", "每个人", "所有人")

internal val ALL_WORDS = setOf("all", "全部", "所有", "全车", "都", "全屋", "全家", "everything")
private val HEX = Regex("#?([0-9a-fA-F]{6})")
