package cc.noharry.bothy.protocol.view

import cc.noharry.agent.appserver.protocol.view.CardItem

/**
 * How a leg of a trip goes (owner 2026-10-17: 长途飞机的，高铁的，短途自驾的都要试下), with the time it needs around
 * it: to the airport and there 2 hours ahead, to the station and there in time, or back to the device; and after
 * arriving, out of the airport or station and into town.
 */
enum class TripMode(val wire: String, val place: String, val departMinutes: Int, val arriveMinutes: Int) {
    FLIGHT("flight", "机场", 180, 60),
    TRAIN("train", "车站", 90, 30),
    DRIVE("drive", "停车场", 30, 0),
}

/**
 * A leg's mode from its text, else from the trip's other legs (「回程 · 杭州东→上海虹桥 · 待 12306 确认」 after a G205 out
 * is a train too), else a flight. 华夏航空 G5… is a flight: 航 wins over a train-like number.
 */
fun CardItem.tripMode(legs: List<CardItem> = emptyList()): TripMode =
    statedTripMode() ?: legs.firstNotNullOfOrNull { it.statedTripMode() } ?: TripMode.FLIGHT

private fun CardItem.statedTripMode(): TripMode? {
    val text = title + " " + detail.orEmpty()
    return when {
        DRIVE.containsMatchIn(text) -> TripMode.DRIVE
        FLIGHT.containsMatchIn(text) -> TripMode.FLIGHT
        TRAIN.containsMatchIn(text) -> TripMode.TRAIN
        else -> null
    }
}

private val TRAIN = Regex("""高铁|动车|火车|列车|12306|[一二]等座|商务座|硬座|[硬软]卧|(?<![\p{L}\p{N}_])[GDCKZT]\d{1,5}(?![\p{L}\p{N}_])""")

/** A flight number can look like a train (华夏航空 G5…). */
private val FLIGHT = Regex("""航|机场|飞""")

private val DRIVE = Regex("""自驾|开车|驾车|自己开""")
