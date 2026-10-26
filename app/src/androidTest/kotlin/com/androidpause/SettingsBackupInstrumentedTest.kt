package com.androidpause

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.androidpause.data.Preferences
import com.androidpause.data.ProfileConfig
import com.androidpause.data.SettingsBackup
import com.androidpause.domain.model.ScheduleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class SettingsBackupInstrumentedTest {
    @Test
    fun portableSettingsRoundTripPreservesProfilesAndExcludesRuntime() {
        val source = ProfileConfig(name = "Evening", packages = setOf("com.example.app"), rules = listOf(rule()))

        val preferences = Preferences(warningsEnabled = false, countdownLastMinuteOnly = true, reentryGraceSeconds = 60)
        val restored = SettingsBackup.decode(SettingsBackup.encode(listOf(source), preferences))

        assertEquals(preferences, restored.preferences)
        assertEquals(1, restored.profiles.size)
        assertEquals("Evening", restored.profiles.single().name)
        assertEquals(setOf("com.example.app"), restored.profiles.single().packages)
        assertEquals(
            0L,
            restored.profiles
                .single()
                .rules
                .single()
                .id,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun futureBackupVersionIsRejected() {
        SettingsBackup.decode("""{"formatVersion":2,"profiles":[]}""")
    }

    @Test
    fun normalizationReportsDuplicatePackageSelections() {
        val backup =
            """{"formatVersion":1,"profiles":[{"name":" Evening ","packages":["com.example.app","com.example.app"],"rules":[{"name":"Rule","windowStart":"09:00","windowEnd":"17:00","timeBudgetSeconds":60,"openingAllowance":null,"pauseDelaySeconds":5,"afterPauseBudgetSeconds":0,"message":"Pause"}]}]}"""

        val preview = SettingsBackup.decode(backup)

        assertEquals(Preferences(), preview.preferences)
        assertTrue(preview.changes.any { it.contains("duplicate") })
        assertEquals(setOf("com.example.app"), preview.profiles.single().packages)
    }

    @Test(expected = IllegalArgumentException::class)
    fun subMinuteTimeBudgetIsRejected() {
        SettingsBackup.decode(
            """{"formatVersion":1,"profiles":[{"name":"Evening","packages":["com.example.app"],"rules":[{"name":"Rule","windowStart":"09:00","windowEnd":"17:00","timeBudgetSeconds":90,"openingAllowance":null,"pauseDelaySeconds":5,"afterPauseBudgetSeconds":0,"message":"Pause"}]}]}""",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeReentryGraceIsRejected() {
        SettingsBackup.decode("""{"formatVersion":1,"preferences":{"reentryGraceSeconds":-1},"profiles":[]}""")
    }

    private fun rule() =
        ScheduleConfig(
            id = 99,
            enabled = true,
            name = "Evening rule",
            windowStart = LocalTime.of(18, 0),
            windowEnd = LocalTime.of(22, 0),
            sharedBudgetSeconds = 600,
            pauseDelaySeconds = 5,
            afterPauseBudgetSeconds = 0,
            message = "Pause",
            packages = setOf("com.example.app"),
        )
}
