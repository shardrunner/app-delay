package com.androidpause.domain

import com.androidpause.domain.model.ScheduleConfig
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** T011 — schedule config validation (FR-001/004/005/005a, data-model validation summary). */
class ScheduleValidationTest {
    private fun config(
        name: String = "Evening",
        sharedBudgetSeconds: Int = 600,
        pauseDelaySeconds: Int? = 5,
        afterPauseBudgetSeconds: Int = 60,
    ) = ScheduleConfig(
        id = 0,
        enabled = true,
        name = name,
        windowStart = LocalTime.of(23, 0),
        windowEnd = LocalTime.of(7, 0),
        sharedBudgetSeconds = sharedBudgetSeconds,
        pauseDelaySeconds = pauseDelaySeconds,
        afterPauseBudgetSeconds = afterPauseBudgetSeconds,
        message = "Take a break",
        packages = setOf("com.example.app"),
    )

    @Test
    fun `absent time allowance needs openings while zero remains a configured allowance`() {
        val openingsOnly = config().copy(sharedBudgetSeconds = null, openingAllowance = 3)
        assertEquals(openingsOnly, ScheduleValidation.validate(openingsOnly))
        assertFailsWith<IllegalArgumentException> {
            ScheduleValidation.validate(openingsOnly.copy(openingAllowance = null))
        }
        ScheduleValidation.validate(config(sharedBudgetSeconds = 0))
    }

    @Test
    fun `valid config passes`() {
        ScheduleValidation.validate(config())
    }

    @Test
    fun `time budget must be whole minutes`() {
        val e =
            assertFailsWith<IllegalArgumentException> { ScheduleValidation.validate(config(sharedBudgetSeconds = 90)) }
        assertTrue(e.message!!.contains("whole minutes"))
    }

    @Test
    fun `blank name rejected`() {
        val e = assertFailsWith<IllegalArgumentException> { ScheduleValidation.validate(config(name = "  ")) }
        assertTrue(e.message!!.contains("name", ignoreCase = true))
    }

    @Test
    fun `negative shared budget rejected`() {
        assertFailsWith<IllegalArgumentException> { ScheduleValidation.validate(config(sharedBudgetSeconds = -1)) }
    }

    @Test
    fun `zero shared budget allowed`() {
        ScheduleValidation.validate(config(sharedBudgetSeconds = 0))
    }

    @Test
    fun `negative finite pause delay rejected`() {
        assertFailsWith<IllegalArgumentException> { ScheduleValidation.validate(config(pauseDelaySeconds = -5)) }
    }

    @Test
    fun `infinite pause delay allowed`() {
        ScheduleValidation.validate(config(pauseDelaySeconds = null))
    }

    @Test
    fun `negative after-pause budget rejected`() {
        assertFailsWith<IllegalArgumentException> { ScheduleValidation.validate(config(afterPauseBudgetSeconds = -1)) }
    }

    @Test
    fun `zero after-pause budget allowed`() {
        ScheduleValidation.validate(config(afterPauseBudgetSeconds = 0))
    }

    @Test
    fun `empty package set rejected`() {
        val cfg = config().copy(packages = emptySet())
        assertFailsWith<IllegalArgumentException> { ScheduleValidation.validate(cfg) }
    }

    @Test
    fun `validate returns the same config when valid`() {
        val cfg = config()
        assertEquals(cfg, ScheduleValidation.validate(cfg))
    }
}
