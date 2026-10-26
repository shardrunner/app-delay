package com.androidpause.ui.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val apps = setOf("com.example.app")

class ScheduleDraftTest {
    @Test
    fun `invalid raw numeric input blocks conversion without normalization`() {
        for (text in listOf("", "-1", "1.5", "12x", "999999999999999999999")) {
            val draft = ScheduleDraft(budgetInput = text)
            assertFalse(draft.numbersValid)
            assertFailsWith<IllegalArgumentException> { draft.toConfig(true, apps) }
        }
    }

    @Test
    fun `disabling time allowance ignores invalid budget and reenabling restores validation`() {
        for (text in listOf("", "-1", "12x", "999999999999999999999")) {
            val draft =
                ScheduleDraft(
                    name = "Openings only",
                    budgetInput = text,
                    openingAllowanceEnabled = true,
                    openingsInput = "3",
                )
            assertFalse(draft.canSave)
            val disabled = draft.copy(timeBudgetEnabled = false)
            assertTrue(disabled.canSave)
            assertEquals(null, disabled.toConfig(true, apps).sharedBudgetSeconds)
            assertEquals(3, disabled.toConfig(true, apps).openingAllowance)
            assertEquals(
                disabled.toConfig(true, apps),
                ScheduleDraft.from(disabled.toConfig(true, apps)).toConfig(true, apps),
            )
            assertEquals(text, disabled.budgetInput)
            assertFalse(disabled.copy(timeBudgetEnabled = true).canSave)
            assertFalse(disabled.copy(openingAllowanceEnabled = false).canSave)
        }
    }

    @Test
    fun `minute conversion respects the integer boundary`() {
        val maxMinutes = Int.MAX_VALUE / 60
        val valid = ScheduleDraft(budgetInput = maxMinutes.toString())
        assertTrue(valid.numbersValid)
        assertEquals(maxMinutes * 60, valid.toConfig(true, apps).sharedBudgetSeconds)
        assertFalse(valid.copy(budgetInput = (maxMinutes + 1).toString()).numbersValid)
    }

    @Test
    fun `all numeric fields reject overflow and empty input`() {
        assertFalse(ScheduleDraft(delayInput = "").numbersValid)
        assertFalse(ScheduleDraft(afterPauseInput = "2147483648").numbersValid)
        assertFalse(ScheduleDraft(openingAllowanceEnabled = true, openingsInput = "-2").numbersValid)
    }

    @Test
    fun `copy uses current raw input for every numeric value`() {
        val changed =
            ScheduleDraft()
                .copy(
                    budgetInput = "21",
                    delayInput = "17",
                    afterPauseInput = "43",
                    openingAllowanceEnabled = true,
                    openingsInput = "8",
                ).toConfig(true, apps)
        assertEquals(1260, changed.sharedBudgetSeconds)
        assertEquals(17, changed.pauseDelaySeconds)
        assertEquals(43, changed.afterPauseBudgetSeconds)
        assertEquals(8, changed.openingAllowance)
        assertEquals(changed, ScheduleDraft.from(changed).toConfig(true, apps))
    }

    @Test
    fun `localized numeric fields convert to config`() {
        val config =
            ScheduleDraft(
                budgetInput = "١٢",
                delayInput = "۳۷",
                afterPauseInput = "４３",
                openingAllowanceEnabled = true,
                openingsInput = "٨",
            ).toConfig(true, apps)

        assertEquals(720, config.sharedBudgetSeconds)
        assertEquals(37, config.pauseDelaySeconds)
        assertEquals(43, config.afterPauseBudgetSeconds)
        assertEquals(8, config.openingAllowance)
    }

    @Test
    fun `localized digits respect bounds and reject non decimal input`() {
        assertEquals(Int.MAX_VALUE, boundedNumber("٢١٤٧٤٨٣٦٤٧"))
        assertEquals(null, boundedNumber("٢١٤٧٤٨٣٦٤٨"))
        assertEquals(12, boundedNumber("１２", maximum = 12))
        assertEquals(null, boundedNumber("１３", maximum = 12))
        for (text in listOf("٣٫٧", "١٬٠٠٠", " ٣", "+٣", "²")) {
            assertEquals(null, boundedNumber(text), text)
        }
    }
}
