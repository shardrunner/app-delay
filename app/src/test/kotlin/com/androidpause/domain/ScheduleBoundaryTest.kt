package com.androidpause.domain

import com.androidpause.domain.model.ScheduleConfig
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ScheduleBoundaryTest {
    private val pkg = "com.example.app"

    private fun schedule(
        start: LocalTime,
        end: LocalTime,
    ) = ScheduleConfig(
        id = 1,
        enabled = true,
        name = "schedule",
        windowStart = start,
        windowEnd = end,
        sharedBudgetSeconds = 600,
        pauseDelaySeconds = 15,
        afterPauseBudgetSeconds = 120,
        message = "Take a break",
        packages = setOf(pkg),
    )

    @Test
    fun `active schedule returns its end as the next boundary`() {
        val now = ZonedDateTime.of(2026, 6, 10, 12, 0, 0, 0, ZoneId.of("Europe/Berlin"))

        val boundary =
            assertNotNull(
                ScheduleWindowBoundaries.next(
                    pkg,
                    now,
                    listOf(schedule(LocalTime.of(9, 0), LocalTime.of(17, 0))),
                ),
            )

        assertEquals(Duration.ofHours(5).toMillis(), boundary - now.toInstant().toEpochMilli())
    }

    @Test
    fun `inactive schedule returns its next local start`() {
        val now = ZonedDateTime.of(2026, 6, 10, 8, 30, 0, 0, ZoneId.of("Europe/Berlin"))

        val boundary =
            assertNotNull(
                ScheduleWindowBoundaries.next(
                    pkg,
                    now,
                    listOf(schedule(LocalTime.of(9, 0), LocalTime.of(17, 0))),
                ),
            )

        assertEquals(Duration.ofMinutes(30).toMillis(), boundary - now.toInstant().toEpochMilli())
    }

    @Test
    fun `spring gap ends at first valid local instant`() {
        val zone = ZoneId.of("Europe/Berlin")
        val now = ZonedDateTime.of(2026, 3, 29, 1, 30, 0, 0, zone)

        val boundary =
            assertNotNull(
                ScheduleWindowBoundaries.next(
                    pkg,
                    now,
                    listOf(schedule(LocalTime.of(1, 0), LocalTime.of(2, 30))),
                ),
            )

        assertEquals(Duration.ofMinutes(30).toMillis(), boundary - now.toInstant().toEpochMilli())
        assertEquals(LocalTime.of(3, 0), Instant.ofEpochMilli(boundary).atZone(zone).toLocalTime())
    }

    @Test
    fun `fall overlap uses later occurrence for an end boundary`() {
        val zone = ZoneId.of("Europe/Berlin")
        val now = ZonedDateTime.of(2026, 10, 25, 1, 30, 0, 0, zone)

        val boundary =
            assertNotNull(
                ScheduleWindowBoundaries.next(
                    pkg,
                    now,
                    listOf(schedule(LocalTime.of(1, 0), LocalTime.of(2, 30))),
                ),
            )

        assertEquals(Duration.ofHours(2).toMillis(), boundary - now.toInstant().toEpochMilli())
    }

    @Test
    fun `fall overlap remains active between the repeated end times`() {
        val zone = ZoneId.of("Europe/Berlin")
        val firstOccurrence =
            ZonedDateTime.ofLocal(
                java.time.LocalDateTime.of(2026, 10, 25, 2, 45),
                zone,
                ZoneOffset.ofHours(2),
            )

        val active =
            ScheduleWindowBoundaries.isActive(
                schedule(LocalTime.of(1, 0), LocalTime.of(2, 30)),
                firstOccurrence,
            )

        kotlin.test.assertTrue(active)
    }

    @Test
    fun `same local schedule follows the supplied device zone`() {
        val schedule = schedule(LocalTime.of(9, 0), LocalTime.of(17, 0))
        val berlinNow = ZonedDateTime.of(2026, 6, 10, 16, 0, 0, 0, ZoneId.of("Europe/Berlin"))
        val newYorkNow = ZonedDateTime.of(2026, 6, 10, 16, 0, 0, 0, ZoneId.of("America/New_York"))

        val berlinBoundary = assertNotNull(ScheduleWindowBoundaries.next(pkg, berlinNow, listOf(schedule)))
        val newYorkBoundary = assertNotNull(ScheduleWindowBoundaries.next(pkg, newYorkNow, listOf(schedule)))

        assertEquals(Duration.ofHours(1).toMillis(), berlinBoundary - berlinNow.toInstant().toEpochMilli())
        assertEquals(Duration.ofHours(1).toMillis(), newYorkBoundary - newYorkNow.toInstant().toEpochMilli())
    }
}
