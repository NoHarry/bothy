package cc.noharry.agent.appserver.protocol

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// A plan's trigger in the words the model reads (and the plan tool answers with); the 计划 page words it in the
// screen's language itself.

/** The trigger in words, as the model sees it: 「今天 16:30」「工作日 08:00」「最低价低于 800 元」; the page has [describeOnScreen]. */
fun PlanTrigger.describe(now: Long, zone: ZoneId): String = when (this) {
    is PlanTrigger.Once -> describeMoment(atMillis, now, zone)
    is PlanTrigger.Repeat -> "${describeDays(days)} ${clock(minuteOfDay)}"
    is PlanTrigger.Watch -> buildString {
        append(watch.text)
        if (holdSeconds > 0) append(" 持续 $holdSeconds 秒")
        append(if (repeat) "，每次" else "，一次")
    }
}

/** 「今天 16:30」「明天 08:00」「后天 08:00」「10月20日 周二 08:00」. */
fun describeMoment(atMillis: Long, now: Long, zone: ZoneId): String {
    val at = Instant.ofEpochMilli(atMillis).atZone(zone)
    val days = at.toLocalDate().toEpochDay() - Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
    val day = when (days) {
        0L -> "今天"
        1L -> "明天"
        2L -> "后天"
        -1L -> "昨天"
        else -> "${at.monthValue}月${at.dayOfMonth}日 ${PLAN_WEEKDAYS[at.dayOfWeek.value - 1]}"
    }
    return "$day ${at.format(CLOCK_FORMAT)}"
}

private fun describeDays(days: Set<Int>): String = when (days) {
    (1..WEEK_DAYS.toInt()).toSet() -> "每天"
    (1..5).toSet() -> "工作日"
    setOf(6, 7) -> "周末"
    else -> "每" + days.sorted().joinToString("、") { PLAN_WEEKDAYS[it - 1] }
}

private fun clock(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / MINUTES_PER_HOUR, minuteOfDay % MINUTES_PER_HOUR)

/** 周一 … 周日, by ISO day number minus one. */
val PLAN_WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private const val WEEK_DAYS = 7L
private const val MINUTES_PER_HOUR = 60
