package cc.noharry.bothy.sample

import java.util.Locale

/**
 * How a leg of a trip goes (owner 2026-10-17: 长途飞机的，高铁的，短途自驾的都要试下), with the time it needs around
 * it: to the airport and there 2 hours ahead, to the station and there in time, or back to the device; and after
 * arriving, out of the airport or station and into town.
 */
internal enum class DebugTripMode(val wire: String, val place: String, val departMinutes: Int, val arriveMinutes: Int) {
    FLIGHT("flight", "机场", 180, 60),
    TRAIN("train", "车站", 90, 30),
    DRIVE("drive", "停车场", 30, 0),
}

/**
 * A leg's mode from its text, else from the trip's other legs (「回程 · 杭州东→上海虹桥 · 待 12306 确认」 after a G205 out
 * is a train too), else a flight. 华夏航空 G5… is a flight: 航 wins over a train-like number.
 */
internal fun DebugCardItem.tripMode(legs: List<DebugCardItem> = emptyList()): DebugTripMode =
    statedTripMode() ?: legs.firstNotNullOfOrNull { it.statedTripMode() } ?: DebugTripMode.FLIGHT

private fun DebugCardItem.statedTripMode(): DebugTripMode? {
    val text = title + " " + detail.orEmpty()
    return when {
        DRIVE.containsMatchIn(text) -> DebugTripMode.DRIVE
        FLIGHT.containsMatchIn(text) -> DebugTripMode.FLIGHT
        TRAIN.containsMatchIn(text) -> DebugTripMode.TRAIN
        else -> null
    }
}

/**
 * A trip card too tight for its travel ([debugTripTimingProblem]) is sent back once per run, with what to change; the
 * next one shows whatever it says. The check reads free text, and a device that refused six revisions showed no plan at
 * all (2026-10-17, a train it took for a flight and the way to the station for a sight).
 */
internal class DebugTripTimingGate {
    private val refused = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun problem(runId: String, card: DebugCard): String? {
        if (card.layout != DebugCardLayout.TRIP) return null
        val problem = debugTripTimingProblem(card.items) ?: return null
        if (refused.size > MAX_REMEMBERED_RUNS) refused.clear()
        return problem.takeIf { refused.add(runId) }
    }
}

/**
 * Whether a trip's days fit its travel (owner 2026-10-17: 返程起飞时间和游玩结束时间基本是贴着的，这种就缺少实用性):
 * the last day's sights end early enough to reach the airport or station and check in, and the first day starts once
 * the traveller is in town. Null when it fits or the times cannot be read; otherwise what to change, for the model.
 */
internal fun debugTripTimingProblem(items: List<DebugCardItem>): String? {
    val legs = items.filter { it.kind == DebugCardItemKind.TRANSPORT }
    // 2026-10-17: a self-drive card came with the way there only.
    if (legs.isNotEmpty() && legs.all { it.title.startsWith(OUTBOUND) }) {
        return "只有去程，没有回程：加一项 kind=transport，title 写「回程 · …」，value 写出发和到达时间，再调用 show_card。"
    }
    val days = items.filter { it.kind == DebugCardItemKind.PLACE }.groupBy { it.group }.values.filter { it.isNotEmpty() }
    if (legs.isEmpty() || days.isEmpty()) return null
    val outbound = legs.firstOrNull { it.title.startsWith(OUTBOUND) } ?: legs.first().takeIf { legs.size > 1 }
    val inbound = legs.firstOrNull { it.title.startsWith(INBOUND) || it.title.startsWith(RETURN) } ?: legs.last().takeIf { legs.size > 1 }
    inbound?.let { leg -> lateDeparture(leg, leg.tripMode(legs), days.last()) }?.let { return it }
    outbound?.let { leg -> earlyStart(leg, leg.tripMode(legs), days.first()) }?.let { return it }
    return null
}

private fun lateDeparture(leg: DebugCardItem, mode: DebugTripMode, lastDay: List<DebugCardItem>): String? {
    // A drive may give only when it sets off (「16:30 出发」).
    val departs = leg.value?.let { CLOCK.find(it) }?.value?.minutes() ?: return null
    val last = lastDay.filterNot { it.isTripTransfer() }.mapNotNull { stop -> stop.endMinutes()?.let { stop to it } }.maxByOrNull { it.second } ?: return null
    if (departs - last.second >= mode.departMinutes) return null
    val ending = "回程 ${departs.clock()} 出发，${lastDay.first().group.orEmpty()}最后一项「${last.first.title}」${last.second.clock()} 才结束，"
    return if (mode == DebugTripMode.DRIVE) {
        ending + "留不出回${mode.place}取车的时间（至少 ${mode.departMinutes.hours()} 小时）。把最后一天的安排提前或删掉，或晚一点出发，再调用 show_card。"
    } else {
        ending + "留不出去${mode.place}和提前到达的时间（至少 ${mode.departMinutes.hours()} 小时）。把最后一天的安排提前或删掉，" +
            "在最后加一项「去${mode.place}」，或换晚一点的班次，再调用 show_card。"
    }
}

private fun earlyStart(leg: DebugCardItem, mode: DebugTripMode, firstDay: List<DebugCardItem>): String? {
    if (mode.arriveMinutes == 0) return null
    val arrives = leg.value?.let { TIMES.find(it) }?.groupValues?.get(2)?.minutes() ?: return null
    val first = firstDay.filterNot { it.isTripTransfer() }.firstNotNullOfOrNull { stop -> stop.startMinutes()?.let { stop to it } } ?: return null
    if (first.second - arrives >= mode.arriveMinutes) return null
    return "去程 ${arrives.clock()} 到达，${firstDay.first().group.orEmpty()}第一项「${first.first.title}」${first.second.clock()} 就开始，" +
        "来不及从${mode.place}进城（至少 ${mode.arriveMinutes.hours()} 小时）。把第一天的安排往后排，再调用 show_card。"
}

/** Getting to or from the airport, station, hotel or car: no sight, so it neither ends a day nor gets its photo. */
internal fun DebugCardItem.isTripTransfer(): Boolean = TRANSFER.containsMatchIn(title)

private fun DebugCardItem.startMinutes(): Int? = value?.let { CLOCK.find(it) }?.value?.minutes()

/** The end of a range ("14:00–16:30"), else a lone time read as when the stop starts plus an hour. */
private fun DebugCardItem.endMinutes(): Int? {
    val clocks = value?.let { text -> CLOCK.findAll(text).map { it.value.minutes() }.toList() }.orEmpty()
    return when {
        clocks.size >= 2 -> clocks[1]
        clocks.size == 1 -> clocks[0] + LONE_TIME_MINUTES
        else -> null
    }
}

private fun String.minutes(): Int = split(':').let { (hours, minutes) -> hours.toInt() * 60 + minutes.toInt() }

private fun Int.clock(): String = "%d:%02d".format(this / 60, this % 60)

private fun Int.hours(): String = if (this % 60 == 0) "${this / 60}" else String.format(Locale.ROOT, "%.1f", this / 60.0)

private const val OUTBOUND = "去程"
private const val INBOUND = "回程"
private const val RETURN = "返程"
private val TIMES = Regex("""(\d{1,2}:\d{2})\D{0,6}?(?:→|->|—|–|-|~|至)\D{0,4}?(\d{1,2}:\d{2})""")
private val CLOCK = Regex("""\d{1,2}:\d{2}""")
// A train number stands alone, not after a letter (Han too) or digit, so 「浦东T1」 is a terminal. Lookarounds, not \b:
// the device's ICU and the JVM (JDK 19+) disagree on \b next to Chinese.
private val TRAIN = Regex("""高铁|动车|火车|列车|12306|[一二]等座|商务座|硬座|[硬软]卧|(?<![\p{L}\p{N}_])[GDCKZT]\d{1,5}(?![\p{L}\p{N}_])""")
/** A flight number can look like a train (华夏航空 G5…). */
private val FLIGHT = Regex("""航|机场|飞""")
private val DRIVE = Regex("""自驾|开车|驾车|自己开""")
/** The way to the airport, station or car itself is no sight that has to end early. */
private val TRANSFER = Regex("""机场|车站|高铁站|火车站|送机|送站|退房|入住|放行李|取车|停车场|换乘|集散|返程|回程|回家|[去往到赶].{0,10}站|(站|机场)\s*(→|->)|(→|->)[^，,]{0,12}(酒店|民宿|宾馆|站|机场)""")
private const val LONE_TIME_MINUTES = 60
private const val MAX_REMEMBERED_RUNS = 64
