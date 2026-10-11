package com.musabgpt.m4.scheduler

import java.time.Instant
import java.time.ZoneId

/** Daily jobs retain local wall time across DST; missed windows are not replayed. */
object TaskTiming {
    fun next(type: String, previous: Long, now: Long, timezone: String): Long {
        require(type == "hourly" || type == "daily")
        if (type == "hourly") {
            val missed = if (previous <= now) (now - previous) / 3600000 + 1 else 0
            return previous + missed * 3600000
        }
        val zone = ZoneId.of(timezone)
        var next = Instant.ofEpochMilli(previous).atZone(zone)
        while (next.toInstant().toEpochMilli() <= now) next = next.plusDays(1)
        return next.toInstant().toEpochMilli()
    }
}
