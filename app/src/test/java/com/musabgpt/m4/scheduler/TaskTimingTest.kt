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
}
