package com.musabgpt.m4.scheduler

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime

class TaskTimingTest {
    @Test fun dailyRetainsWallClockAcrossSpringAndAutumnTransitions() {
        for (previous in listOf("2026-03-28T09:00:00+00:00[Europe/London]", "2026-10-24T09:00:00+01:00[Europe/London]")) {
            val before = ZonedDateTime.parse(previous)
            val next = TaskTiming.next("daily", before.toInstant().toEpochMilli(), before.toInstant().toEpochMilli(), "Europe/London")
            val expected = before.plusDays(1)
            assertEquals(expected.toInstant().toEpochMilli(), next)
            assertEquals(9, expected.hour)
        }
    }
    @Test fun missedHourlyWindowsSkipBacklogWithoutMovingTheAnchor() {
        assertEquals(10_801_000L, TaskTiming.next("hourly", 1000L, 10_000_000L, "Etc/UTC"))
        assertEquals(1000L, TaskTiming.next("hourly", 1000L, 999L, "Etc/UTC"))
    }
    @Test fun weeklyAndMonthlyKeepTheLocalAnchor() {
        val january = ZonedDateTime.parse("2026-01-31T09:00:00+00:00[Europe/London]").toInstant().toEpochMilli()
        val february = TaskTiming.next("monthly", january, january, "Europe/London", monthDay = 31)
        val march = TaskTiming.next("monthly", february, february, "Europe/London", monthDay = 31)
        assertEquals(ZonedDateTime.parse("2026-02-28T09:00:00+00:00[Europe/London]").toInstant().toEpochMilli(), february)
        assertEquals(ZonedDateTime.parse("2026-03-31T09:00:00+01:00[Europe/London]").toInstant().toEpochMilli(), march)
        assertEquals(january + 7 * 86400000L, TaskTiming.next("weekly", january, january, "Europe/London"))
    }
    @Test fun intervalSkipsOldWindows() {
        assertEquals(1_801_000L, TaskTiming.next("interval", 1000, 1_000_000, "Etc/UTC", intervalMinutes = 15))
    }
}
