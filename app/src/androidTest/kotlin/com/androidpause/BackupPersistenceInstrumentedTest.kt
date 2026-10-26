package com.androidpause

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidpause.data.AppDatabase
import com.androidpause.data.Preferences
import com.androidpause.data.ProfileConfig
import com.androidpause.data.ProfileStore
import com.androidpause.data.SettingsBackup
import com.androidpause.data.entity.PreferenceEntity
import com.androidpause.data.entity.ScheduleUsageEntity
import com.androidpause.domain.model.ScheduleConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class BackupPersistenceInstrumentedTest {
    @Test
    fun fullFixturePreservesEveryPortableFieldThroughDatabaseAndExport() =
        verifyRoundTrip(
            "v1-full.json",
            ProfileConfig(
                name = "Daily focus",
                packages = setOf("org.example.reader", "org.example.unavailable"),
                rules =
                    listOf(
                        rule("Daily allowance", "Take a break.\nChoose deliberately.").copy(openingAllowance = 3),
                        rule("Evening openings", "Done for today.").copy(
                            windowStart = LocalTime.of(18, 0),
                            windowEnd = LocalTime.of(23, 0),
                            sharedBudgetSeconds = null,
                            openingAllowance = 2,
                            pauseDelaySeconds = null,
                            afterPauseBudgetSeconds = 0,
                        ),
                    ),
            ),
            emptyList(),
        )

    @Test
    fun defaultsFixtureNormalizesOnlyDocumentedFieldsThroughDatabaseAndExport() =
        verifyRoundTrip(
            "v1-defaults.json",
            ProfileConfig(
                name = "Focus",
                packages = setOf("org.example.reader"),
                rules = listOf(rule("Daily", "  Preserve this text.  ")),
            ),
            listOf(
                "Trimmed profile name",
                "Trimmed package",
                "Removed duplicate packages in Focus",
                "Defaulted enabled to true for Focus",
                "Trimmed rule name",
                "Defaulted warnings to enabled",
            ),
        )

    private fun verifyRoundTrip(
        fixture: String,
        expected: ProfileConfig,
        changes: List<String>,
    ) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val text =
            instrumentation.context.assets
                .open("backups/$fixture")
                .bufferedReader()
                .use { it.readText() }
        val preview = SettingsBackup.decode(text)
        val normalized = expected.copy(rules = expected.rules.map { it.copy(packages = expected.packages) })
        assertEquals(listOf(normalized), preview.profiles)
        assertEquals(Preferences(), preview.preferences)
        assertEquals(changes, preview.changes)
        val database =
            Room
                .inMemoryDatabaseBuilder(instrumentation.targetContext, AppDatabase::class.java)
                .build()
        try {
            val store = ProfileStore(database)
            store.save(normalized.copy(name = "Old configuration"))
            val old = store.profiles().single()
            old.rules.forEach {
                database.scheduleUsageDao().upsertAll(
                    listOf(ScheduleUsageEntity(it.id, 123L, 12, 456L, 2, "org.example.reader", true)),
                )
            }
            database.preferenceDao().put(PreferenceEntity("extra", "remove"))
            store.savePreferences(Preferences(warningsEnabled = false))

            store.replace(preview.profiles, preview.preferences)
            val saved = store.profiles().single()
            assertTrue(saved.id != old.id)
            assertTrue(saved.rules.all { rule -> rule.id !in old.rules.map { it.id } })
            assertEquals(normalized, saved.copy(id = 0, rules = saved.rules.map { it.copy(id = 0) }))
            assertTrue(database.scheduleUsageDao().getAll().isEmpty())
            assertNull(database.preferenceDao().get("extra"))
            assertTrue(store.preferences().warningsEnabled)

            // Exercise false preferences and disabled profiles as well as the fixture defaults.
            store.save(saved.copy(enabled = false))
            store.savePreferences(Preferences(warningsEnabled = false))
            val exported = SettingsBackup.encode(store.profiles(), store.preferences())
            val root = JSONObject(exported)
            assertEquals(setOf("formatVersion", "preferences", "profiles"), root.keys().asSequence().toSet())
            val profile = root.getJSONArray("profiles").getJSONObject(0)
            assertEquals(setOf("name", "enabled", "packages", "rules"), profile.keys().asSequence().toSet())
            val rules = profile.getJSONArray("rules")
            for (index in 0 until rules.length()) {
                assertEquals(
                    setOf(
                        "name",
                        "windowStart",
                        "windowEnd",
                        "timeBudgetSeconds",
                        "openingAllowance",
                        "pauseDelaySeconds",
                        "afterPauseBudgetSeconds",
                        "message",
                    ),
                    rules
                        .getJSONObject(index)
                        .keys()
                        .asSequence()
                        .toSet(),
                )
            }
            val decoded = SettingsBackup.decode(exported)
            val disabled = normalized.copy(enabled = false, rules = normalized.rules.map { it.copy(enabled = false) })
            assertEquals(listOf(disabled), decoded.profiles)
            assertEquals(false, decoded.preferences.warningsEnabled)
            assertTrue(decoded.changes.isEmpty())
            store.replace(decoded.profiles, decoded.preferences)
            val restored = store.profiles().single()
            assertEquals(disabled, restored.copy(id = 0, rules = restored.rules.map { it.copy(id = 0) }))
            assertEquals(false, store.preferences().warningsEnabled)
            assertTrue(database.scheduleUsageDao().getAll().isEmpty())
        } finally {
            database.close()
        }
    }

    private fun rule(
        name: String,
        message: String,
    ) = ScheduleConfig(
        id = 0,
        enabled = true,
        name = name,
        windowStart = LocalTime.MIDNIGHT,
        windowEnd = LocalTime.MIDNIGHT,
        sharedBudgetSeconds = 600,
        pauseDelaySeconds = 5,
        afterPauseBudgetSeconds = 60,
        message = message,
        packages = emptySet(),
    )
}
