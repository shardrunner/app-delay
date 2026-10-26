package com.androidpause

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidpause.data.AppDatabase
import com.androidpause.data.ConfigurationChanges
import com.androidpause.data.Preferences
import com.androidpause.data.ProfileConfig
import com.androidpause.data.ProfileStore
import com.androidpause.data.entity.PreferenceEntity
import com.androidpause.data.entity.ScheduleUsageEntity
import com.androidpause.data.toRuntime
import com.androidpause.domain.model.ScheduleConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class ProfileStoreInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var store: ProfileStore

    @Before
    fun setUp() =
        runBlocking {
            resetConfigurationHook()
            database =
                Room
                    .inMemoryDatabaseBuilder(
                        InstrumentationRegistry.getInstrumentation().targetContext,
                        AppDatabase::class.java,
                    ).build()
            store = ProfileStore(database)
        }

    @After
    fun tearDown() =
        runBlocking {
            resetConfigurationHook()
            if (::database.isInitialized) database.close()
        }

    // The events flow has no replay cache, and these tests leave no collectors or hooks installed.
    private suspend fun resetConfigurationHook() {
        ConfigurationChanges.serialized { ConfigurationChanges.beforeChange = null }
    }

    @Test
    fun preferencesDefaultPersistAndAreReplacedOnImport() =
        runBlocking {
            assertEquals(Preferences(), store.preferences())
            val changed = Preferences(warningsEnabled = false, countdownLastMinuteOnly = true, reentryGraceSeconds = 0)
            store.savePreferences(changed)
            assertEquals(changed, ProfileStore(database).preferences())
            val imported = Preferences(reentryGraceSeconds = 900)
            store.replace(emptyList(), imported)
            assertEquals(imported, store.preferences())
        }

    @Test
    fun failedPreferenceBatchKeepsThePreviousCompleteValue() =
        runBlocking {
            val original = Preferences()
            store.savePreferences(original)
            database.openHelper.writableDatabase.execSQL(
                """
                CREATE TRIGGER reject_grace BEFORE INSERT ON preferences
                WHEN NEW.key = 'reentryGraceSeconds'
                BEGIN SELECT RAISE(ABORT, 'test preference failure'); END
                """.trimIndent(),
            )
            try {
                store.savePreferences(Preferences(false, true, 0))
                fail("The preference batch must fail")
            } catch (expected: android.database.sqlite.SQLiteConstraintException) {
                assertTrue(expected.message.orEmpty().contains("test preference failure"))
            }
            assertEquals(original, store.preferences())
        }

    @Test
    fun newProfileSharesMembershipAndEnabledStateAcrossIndependentRules() =
        runBlocking {
            assertTrue(store.preferences().warningsEnabled)
            val source = profile().copy(name = "  Focus  ", enabled = false)
            val id = store.save(source)
            val saved = store.profiles().single()

            assertTrue(id > 0)
            assertEquals(id, saved.id)
            assertEquals("Focus", saved.name)
            assertEquals(source.packages, saved.packages)
            assertFalse(saved.enabled)
            assertEquals(
                2,
                saved.rules
                    .map { it.id }
                    .toSet()
                    .size,
            )
            saved.rules.forEach { rule ->
                assertTrue(rule.id > 0)
                assertEquals(id, database.scheduleDao().getById(rule.id)?.profileId)
                assertEquals(source.packages, rule.packages)
                assertFalse(rule.enabled)
            }
            assertEquals(setOf(60, null), saved.rules.map { it.sharedBudgetSeconds }.toSet())
            assertEquals(3, saved.rules.single { it.sharedBudgetSeconds == null }.openingAllowance)
            assertTrue(database.scheduleUsageDao().getAll().isEmpty())
        }

    @Test
    fun editRetainsRuntimeForRetainedRulesAndCascadesRemovedRules() =
        runBlocking {
            store.save(profile())
            val original = store.profiles().single()
            store.persistRuntime(original.rules.associate { it.id to usage(it.id).toRuntime() })
            val retained = original.rules.first()
            val removed = original.rules.last()
            val packages = setOf("com.example.replacement")

            store.save(
                original.copy(
                    name = "Edited",
                    enabled = false,
                    packages = packages,
                    rules = listOf(retained.copy(sharedBudgetSeconds = 120), rule("New")),
                ),
            )

            val edited = store.profiles().single()
            assertEquals(edited.rules, store.schedules())
            assertEquals(
                mapOf(
                    retained.id to
                        usage(retained.id)
                            .copy(pauseClearedAtMillis = null, sessionTimeGranted = false)
                            .toRuntime(),
                ),
                store.runtime(),
            )
            assertEquals(original.id, edited.id)
            assertEquals("Edited", edited.name)
            // Configuration changes keep consumed allowances but end extensions and current-entry grants.
            assertEquals(
                usage(retained.id).copy(pauseClearedAtMillis = null, sessionTimeGranted = false),
                database.scheduleUsageDao().getById(retained.id),
            )
            assertEquals(120, edited.rules.single { it.id == retained.id }.sharedBudgetSeconds)
            assertNull(database.scheduleDao().getById(removed.id))
            assertNull(database.scheduleUsageDao().getById(removed.id))
            assertEquals(
                packages,
                database
                    .profileAppDao()
                    .getAll()
                    .map { it.packageName }
                    .toSet(),
            )
            val added = edited.rules.single { it.id != retained.id }
            assertNull(database.scheduleUsageDao().getById(added.id))
            edited.rules.forEach {
                assertEquals(packages, it.packages)
                assertFalse(it.enabled)
            }
        }

    @Test
    fun duplicateHasFreshIdsAndNoRuntimeAndDeletingOriginalDoesNotTouchCopy() =
        runBlocking {
            store.save(profile())
            val original = store.profiles().single()
            store.persistRuntime(original.rules.associate { it.id to usage(it.id).toRuntime() })
            val draft = original.duplicate()
            assertEquals(0L, draft.id)
            assertTrue(draft.rules.all { it.id == 0L && !it.enabled })
            val copyId = store.save(draft)
            val copy = store.profiles().single { it.id == copyId }

            assertTrue(copyId != original.id)
            assertEquals("${original.name} (copy)", copy.name)
            assertFalse(copy.enabled)
            assertEquals(original.packages, copy.packages)
            assertEquals(original.rules.map { it.name }, copy.rules.map { it.name })
            copy.rules.forEach {
                assertTrue(it.id !in original.rules.map { rule -> rule.id })
                assertEquals(copyId, database.scheduleDao().getById(it.id)?.profileId)
                assertNull(database.scheduleUsageDao().getById(it.id))
            }
            assertEquals(
                original.rules
                    .map {
                        usage(
                            it.id,
                        ).copy(pauseClearedAtMillis = null, sessionTimeGranted = false)
                    }.toSet(),
                database.scheduleUsageDao().getAll().toSet(),
            )

            store.delete(original.id)

            assertEquals(listOf(copy), store.profiles())
            assertEquals(
                copy.rules.map { it.id }.toSet(),
                database
                    .scheduleDao()
                    .getAll()
                    .map { it.id }
                    .toSet(),
            )
            assertEquals(copy.rules, store.schedules())
            assertTrue(store.runtime().isEmpty())
            try {
                val deletedRule = original.rules.first().id
                store.persistRuntime(mapOf(deletedRule to usage(deletedRule).toRuntime()))
                fail("A deleted rule must reject stale runtime writes")
            } catch (expected: android.database.sqlite.SQLiteConstraintException) {
                assertTrue(expected.message.orEmpty().contains("FOREIGN KEY"))
            }
            assertTrue(database.scheduleUsageDao().getAll().isEmpty())
            store.delete(copyId)
            assertTrue(database.profileDao().getAll().isEmpty())
            assertTrue(database.scheduleDao().getAll().isEmpty())
            assertTrue(database.profileAppDao().getAll().isEmpty())
            assertTrue(store.schedules().isEmpty())
            assertTrue(store.runtime().isEmpty())
        }

    @Test
    fun invalidSecondReplacementRollsBackConfigurationMembershipRuntimeAndPreferences() =
        runBlocking {
            store.save(profile())
            store.savePreferences(Preferences(warningsEnabled = false))
            database.preferenceDao().put(PreferenceEntity("extra", "preserve"))
            val before = store.profiles()
            before.single().rules.forEach { database.scheduleUsageDao().upsertAll(listOf(usage(it.id))) }
            val apps = database.profileAppDao().getAll().toSet()
            val runtime = database.scheduleUsageDao().getAll().toSet()
            val invalid =
                profile().copy(
                    rules = listOf(rule("Invalid").copy(sharedBudgetSeconds = null, openingAllowance = null)),
                )

            try {
                store.replace(listOf(profile().copy(name = "Valid first"), invalid), Preferences())
                fail("An invalid second profile must abort the entire replacement")
            } catch (expected: IllegalArgumentException) {
                assertEquals("at least one allowance is required", expected.message)
            }

            assertEquals(before, store.profiles())
            assertEquals(apps, database.profileAppDao().getAll().toSet())
            assertEquals(runtime, database.scheduleUsageDao().getAll().toSet())
            assertFalse(store.preferences().warningsEnabled)
            assertEquals("preserve", database.preferenceDao().get("extra"))
        }

    @Test
    fun invalidProfileAndRuleConfigurationsDoNotWriteRows() =
        runBlocking {
            val invalidProfiles =
                listOf(
                    profile().copy(name = " ") to "Profile name is required",
                    profile().copy(rules = emptyList()) to "A profile needs at least one rule",
                    profile().copy(packages = emptySet()) to "schedule must cover at least one app",
                    profile().copy(rules = listOf(rule(" "))) to "name must not be blank",
                    profile().copy(rules = listOf(rule("Negative").copy(openingAllowance = -1))) to
                        "openingAllowance must be >= 0",
                )
            invalidProfiles.forEach { (invalid, message) ->
                try {
                    store.save(invalid)
                    fail("Expected rejection: $message")
                } catch (expected: IllegalArgumentException) {
                    assertEquals(message, expected.message)
                }
                assertTrue(database.profileDao().getAll().isEmpty())
                assertTrue(database.scheduleDao().getAll().isEmpty())
                assertTrue(database.profileAppDao().getAll().isEmpty())
            }
        }

    @Test
    fun successfulReplacementRekeysImportedRulesAndClearsRuntimeAndOldPreferences() =
        runBlocking {
            store.save(profile())
            val original = store.profiles().single()
            original.rules.forEach { database.scheduleUsageDao().upsertAll(listOf(usage(it.id))) }
            store.savePreferences(Preferences(warningsEnabled = false))
            database.preferenceDao().put(PreferenceEntity("extra", "remove"))
            val imported = original.copy(name = "Imported", packages = setOf("com.example.imported"))

            store.replace(listOf(imported), Preferences())

            val saved = store.profiles().single()
            assertTrue(saved.id != original.id)
            assertEquals("Imported", saved.name)
            assertEquals(imported.packages, saved.packages)
            assertEquals(imported.rules.size, saved.rules.size)
            saved.rules.forEach {
                assertEquals(saved.id, database.scheduleDao().getById(it.id)?.profileId)
                assertEquals(imported.packages, it.packages)
                assertTrue(it.id !in original.rules.map { rule -> rule.id })
            }
            assertEquals(
                saved.rules.map { it.id }.toSet(),
                database
                    .scheduleDao()
                    .getAll()
                    .map { it.id }
                    .toSet(),
            )
            assertTrue(database.scheduleUsageDao().getAll().isEmpty())
            assertTrue(store.preferences().warningsEnabled)
            assertEquals("true", database.preferenceDao().get("warningsEnabled"))
            assertNull(database.preferenceDao().get("extra"))
        }

    @Test
    fun savingDeletedProfileWithNewRuleCannotCreateOrphan() =
        runBlocking {
            store.save(profile())
            val stale = store.profiles().single()
            store.delete(stale.id)
            assertRejectedWithoutChanges(stale.copy(rules = listOf(rule("New"))))
        }

    @Test
    fun existingProfileCannotTakeAnotherProfilesRule() =
        runBlocking {
            val firstId = store.save(profile())
            val secondId = store.save(profile().copy(name = "Second"))
            val profiles = store.profiles()
            val first = profiles.single { it.id == firstId }
            val second = profiles.single { it.id == secondId }
            profiles.flatMap { it.rules }.forEach { database.scheduleUsageDao().upsertAll(listOf(usage(it.id))) }
            assertRejectedWithoutChanges(
                first.copy(name = "Changed", rules = listOf(second.rules.first())),
            )
        }

    @Test
    fun newProfileCannotTakeAnExistingRule() =
        runBlocking {
            store.save(profile())
            val original = store.profiles().single()
            original.rules.forEach { database.scheduleUsageDao().upsertAll(listOf(usage(it.id))) }
            assertRejectedWithoutChanges(original.copy(id = 0, name = "Not a duplicate"))
        }

    @Test
    fun missingRuleIsRejectedByPreflightWithoutWrites() =
        runBlocking {
            store.save(profile())
            val original = store.profiles().single()
            original.rules.forEach { database.scheduleUsageDao().upsertAll(listOf(usage(it.id))) }
            assertRejectedWithoutChanges(
                original.copy(name = "Changed", rules = listOf(rule("New"), rule("Missing").copy(id = Long.MAX_VALUE))),
            )
        }

    private suspend fun assertRejectedWithoutChanges(candidate: ProfileConfig) {
        store.savePreferences(Preferences(warningsEnabled = false))
        database.preferenceDao().put(PreferenceEntity("extra", "preserve"))
        val profiles = database.profileDao().getAll().toSet()
        val rules = database.scheduleDao().getAll().toSet()
        val apps = database.profileAppDao().getAll().toSet()
        val runtime = database.scheduleUsageDao().getAll().toSet()

        val failure = runCatching { store.save(candidate) }.exceptionOrNull()

        assertTrue(
            "Invalid ownership must fail explicitly",
            failure is IllegalArgumentException || failure is IllegalStateException,
        )
        // Inspect raw rows too: profiles() hides orphan rules.
        assertEquals(profiles, database.profileDao().getAll().toSet())
        assertEquals(rules, database.scheduleDao().getAll().toSet())
        assertEquals(apps, database.profileAppDao().getAll().toSet())
        assertEquals(runtime, database.scheduleUsageDao().getAll().toSet())
        assertFalse(store.preferences().warningsEnabled)
        assertEquals("preserve", database.preferenceDao().get("extra"))
    }

    private fun profile() =
        ProfileConfig(
            name = "Focus",
            packages = setOf("com.example.one", "com.example.two"),
            rules = listOf(rule("Time"), rule("Openings").copy(sharedBudgetSeconds = null, openingAllowance = 3)),
        )

    private fun rule(name: String) =
        ScheduleConfig(
            id = 0,
            enabled = true,
            name = name,
            windowStart = LocalTime.of(9, 0),
            windowEnd = LocalTime.of(17, 0),
            sharedBudgetSeconds = 60,
            pauseDelaySeconds = 5,
            afterPauseBudgetSeconds = 30,
            message = "Pause",
            // ProfileStore must replace rule-local membership with the profile selection before validation.
            packages = emptySet(),
        )

    private fun usage(id: Long) =
        ScheduleUsageEntity(
            scheduleId = id,
            windowOccurrenceKey = 1700000000000L,
            consumedBudgetMillis = 23_000L,
            pauseClearedAtMillis = 1700000010000L,
            consumedOpenings = 2,
            sessionPackage = "com.example.one",
            sessionTimeGranted = true,
        )
}
