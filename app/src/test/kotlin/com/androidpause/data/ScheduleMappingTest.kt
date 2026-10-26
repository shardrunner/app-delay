package com.androidpause.data

import com.androidpause.data.entity.INFINITE_PAUSE_DELAY_SENTINEL
import com.androidpause.domain.model.ScheduleConfig
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** T012 — Room entity <-> domain mapping round-trip (data-model.md). */
class ScheduleMappingTest {
    private val config =
        ScheduleConfig(
            id = 42,
            enabled = false,
            name = "Night",
            windowStart = LocalTime.of(23, 30),
            windowEnd = LocalTime.of(2, 15),
            sharedBudgetSeconds = 600,
            pauseDelaySeconds = 5,
            afterPauseBudgetSeconds = 60,
            message = "Pause",
            packages = setOf("com.a", "com.b"),
        )

    @Test
    fun `absent and zero time allowances stay distinct through persistence mapping`() {
        for (budget in listOf(null, 0, 60)) {
            val source = config.copy(sharedBudgetSeconds = budget, openingAllowance = 3)
            val entity = source.toEntity(profileId = 7)
            assertEquals(budget, entity.sharedBudgetSeconds)
            assertEquals(source, entity.toConfig(source.enabled, source.packages))
        }
    }

    @Test
    fun `finite-delay config survives round-trip`() {
        val entity = config.toEntity(profileId = 7)
        assertEquals(7L, entity.profileId)
        assertEquals(config, entity.toConfig(config.enabled, config.packages))
    }

    @Test
    fun `infinite delay maps to sentinel and back to null`() {
        val infinite = config.copy(pauseDelaySeconds = null)
        val entity = infinite.toEntity(profileId = 7)
        assertEquals(INFINITE_PAUSE_DELAY_SENTINEL, entity.pauseDelaySeconds)
        assertEquals(null, entity.toConfig(infinite.enabled, infinite.packages).pauseDelaySeconds)
    }

    @Test
    fun `local times map to minutes-of-day`() {
        val entity = config.toEntity(profileId = 7)
        assertEquals(23 * 60 + 30, entity.windowStartMinutes)
        assertEquals(2 * 60 + 15, entity.windowEndMinutes)
    }
}
