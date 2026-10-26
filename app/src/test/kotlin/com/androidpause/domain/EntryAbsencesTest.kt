package com.androidpause.domain

import com.androidpause.domain.model.ScheduleConfig
import com.androidpause.domain.model.ScheduleRuntime
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntryAbsencesTest {
    private val app = "com.example.app"
    private val other = "com.example.other"
    private val grace = 5 * 60_000L
    private val now = ZonedDateTime.parse("2026-10-01T12:00:00+02:00")
    private val schedule =
        ScheduleConfig(
            id = 1,
            enabled = true,
            name = "Rule",
            windowStart = LocalTime.MIDNIGHT,
            windowEnd = LocalTime.MIDNIGHT,
            sharedBudgetSeconds = 600,
            pauseDelaySeconds = 5,
            afterPauseBudgetSeconds = 60,
            message = "",
            packages = setOf(app, other),
            openingAllowance = 3,
        )

    private fun session(
        pkg: String? = app,
        granted: Boolean = false,
    ) = mapOf(
        1L to
            ScheduleRuntime(
                scheduleId = 1,
                consumedBudgetMillis = 0,
                pauseClearedAtMillis = null,
                windowOccurrenceKey = 0,
                consumedOpenings = 1,
                sessionPackage = pkg,
                sessionTimeGranted = granted,
            ),
    )

    @Test
    fun `returning within the grace continues the entry`() {
        val absences = EntryAbsences()
        absences.reconcile(app, 0, grace, session())
        val away = absences.reconcile("browser", 1_000, grace, session())
        assertEquals(app, away[1L]?.sessionPackage)

        val back = absences.reconcile(app, 1_000 + grace - 1, grace, away)
        assertEquals(app, back[1L]?.sessionPackage)
    }

    @Test
    fun `an absence reaching the grace ends the entry and its grant`() {
        val absences = EntryAbsences()
        absences.reconcile(app, 0, grace, session(granted = true))
        absences.reconcile("browser", 1_000, grace, session(granted = true))

        val back = absences.reconcile(app, 1_000 + grace, grace, session(granted = true))
        assertNull(back[1L]?.sessionPackage)
        assertFalse(back[1L]!!.sessionTimeGranted)
    }

    @Test
    fun `absence is measured from the first departure`() {
        val absences = EntryAbsences()
        absences.reconcile(app, 0, grace, session())
        absences.reconcile("browser", 0, grace, session())
        absences.reconcile("maps", grace - 1, grace, session())

        assertNull(absences.reconcile(app, grace, grace, session())[1L]?.sessionPackage)
    }

    @Test
    fun `locking is an absence and a quick unlock continues`() {
        val absences = EntryAbsences()
        absences.reconcile(app, 0, grace, session())
        absences.reconcile("", 0, grace, session())

        assertEquals(app, absences.reconcile(app, 60_000, grace, session())[1L]?.sessionPackage)
    }

    @Test
    fun `zero grace ends the entry on any departure`() {
        val absences = EntryAbsences()
        absences.reconcile(app, 0, 0, session())
        absences.reconcile("browser", 0, 0, session())

        assertNull(absences.reconcile(app, 0, 0, session())[1L]?.sessionPackage)
    }

    @Test
    fun `an entry whose departure is unknown ends`() {
        // A restarted service never saw the app being left.
        val reconciled = EntryAbsences().reconcile("browser", 0, grace, session())
        assertNull(reconciled[1L]?.sessionPackage)
    }

    @Test
    fun `entering another covered app keeps the entry for the engine to judge`() {
        val absences = EntryAbsences()
        absences.reconcile(app, 0, grace, session())

        assertEquals(app, absences.reconcile(other, 1_000, grace, session())[1L]?.sessionPackage)
    }

    @Test
    fun `recorded entries count openings only when requested`() {
        val runtime = session(pkg = null)
        val counted = recordEntry(app, now, listOf(schedule), runtime, countsAsOpening = true)
        val extended = recordEntry(app, now, listOf(schedule), runtime, countsAsOpening = false)

        assertEquals(2, counted[1L]?.consumedOpenings)
        assertEquals(1, extended[1L]?.consumedOpenings)
        assertEquals(app, extended[1L]?.sessionPackage)
    }

    @Test
    fun `a continued entry is not recorded again`() {
        val runtime = session()
        assertTrue(recordEntry(app, now, listOf(schedule), runtime, countsAsOpening = true) === runtime)
    }
}
