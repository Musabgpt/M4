package com.musabgpt.m4.scheduler

import java.time.Instant
import java.time.ZoneId

/** Daily jobs retain local wall time across DST; missed windows are not replayed. */
object TaskTiming {
    fun next(type: String, previous: Long, now: Long, timezone: String, intervalMinutes: Long = 60, monthDay: Int = 0): Long {
        require(type in setOf("hourly", "daily", "weekly", "monthly", "interval", "condition_watch", "on_ci_event"))
        if (type in setOf("hourly", "interval", "condition_watch", "on_ci_event")) {
            val interval = (if (type == "hourly") 60 else intervalMinutes.coerceAtLeast(15)) * 60000
            val missed = if (previous <= now) (now - previous) / interval + 1 else 0
            return previous + missed * interval
        }
        val zone = ZoneId.of(timezone)
        var next = Instant.ofEpochMilli(previous).atZone(zone)
        val day = if (monthDay in 1..31) monthDay else next.dayOfMonth
        while (next.toInstant().toEpochMilli() <= now) next = when (type) {
            "monthly" -> next.plusMonths(1).let { it.withDayOfMonth(day.coerceAtMost(it.toLocalDate().lengthOfMonth())) }
            "weekly" -> next.plusWeeks(1)
            else -> next.plusDays(1)
        }
        return next.toInstant().toEpochMilli()
    }
}
