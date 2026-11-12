package cc.noharry.agent.appserver.plans

import cc.noharry.agent.appserver.protocol.PlanEnd
import cc.noharry.agent.appserver.protocol.PlanTrigger
import cc.noharry.agent.appserver.protocol.WatchOp
import cc.noharry.agent.appserver.runtime.modelText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// A plan's trigger and state in the words the model reads (and the plan tool answers with), in the persona's language;
// the screens word plans in their own language themselves.

/** The trigger in words, as the model sees it: 「今天 16:30」「工作日 08:00」「最低价低于 800 元」, "today 16:30". */
internal fun PlanTrigger.describe(now: Long, zone: ZoneId): String = when (this) {
    is PlanTrigger.Once -> describeMoment(atMillis, now, zone)
    is PlanTrigger.Repeat -> "${describeDays(days)} ${clock(minuteOfDay)}"
    is PlanTrigger.Watch -> buildString {
        append(watch.text)
        if (holdSeconds > 0) append(modelText(" 持续 $holdSeconds 秒", " for $holdSeconds s"))
        append(if (repeat) modelText("，每次", ", each time") else modelText("，一次", ", once"))
    }
}

/** 「今天 16:30」「明天 08:00」「后天 08:00」「10月20日 周二 08:00」; "today 16:30", "Tue Oct 20 08:00". */
internal fun describeMoment(atMillis: Long, now: Long, zone: ZoneId): String {
    val at = Instant.ofEpochMilli(atMillis).atZone(zone)
    val days = at.toLocalDate().toEpochDay() - Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
    val day = when (days) {
        0L -> modelText("今天", "today")
        1L -> modelText("明天", "tomorrow")
        2L -> modelText("后天", "the day after tomorrow")
        -1L -> modelText("昨天", "yesterday")
        else -> modelText(
            "${at.monthValue}月${at.dayOfMonth}日 ${PLAN_WEEKDAYS[at.dayOfWeek.value - 1]}",
            "${ENGLISH_WEEKDAYS[at.dayOfWeek.value - 1]} ${at.format(ENGLISH_DATE)}",
        )
    }
    return "$day ${at.format(CLOCK_FORMAT)}"
}

private fun describeDays(days: Set<Int>): String = when (days) {
    (1..WEEK_DAYS.toInt()).toSet() -> modelText("每天", "every day")
    (1..5).toSet() -> modelText("工作日", "weekdays")
    setOf(6, 7) -> modelText("周末", "weekends")
    else -> modelText(
        "每" + days.sorted().joinToString("、") { PLAN_WEEKDAYS[it - 1] },
        "every " + days.sorted().joinToString(", ") { ENGLISH_WEEKDAYS[it - 1] },
    )
}

/** 「已完成」「已错过」, "done", "missed". */
internal val PlanEnd.label: String
    get() = when (this) {
        PlanEnd.DONE -> modelText("已完成", "done")
        PlanEnd.MISSED -> modelText("已错过", "missed")
    }

/** 「超过」「低于」…, "above", "below"…: how a watch compares, in a sentence. */
internal val WatchOp.words: String
    get() = when (this) {
        WatchOp.ABOVE -> modelText("超过", "above")
        WatchOp.BELOW -> modelText("低于", "below")
        WatchOp.EQUALS -> modelText("是", "is")
        WatchOp.NOT_EQUALS -> modelText("不是", "is not")
        WatchOp.CONTAINS -> modelText("包含", "contains")
        WatchOp.CHANGES -> modelText("变化", "changes")
    }

private fun clock(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / MINUTES_PER_HOUR, minuteOfDay % MINUTES_PER_HOUR)

/** 周一 … 周日, by ISO day number minus one. */
internal val PLAN_WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val ENGLISH_WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val ENGLISH_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH)
private const val WEEK_DAYS = 7L
private const val MINUTES_PER_HOUR = 60
