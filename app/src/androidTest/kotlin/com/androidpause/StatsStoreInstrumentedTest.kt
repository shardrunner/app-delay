package com.androidpause

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidpause.data.AppDatabase
import com.androidpause.data.StatsStore
import com.androidpause.data.entity.ProfileEntity
import com.androidpause.data.entity.ScheduleEntity
import com.androidpause.domain.model.PauseStats
import com.androidpause.domain.model.RuleStats
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDate

/** Private in-memory database only. */
@RunWith(AndroidJUnit4::class)
class StatsStoreInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var store: StatsStore
    private var scheduleId = 0L
    private val thursday = LocalDate.of(2026, 10, 1)

    @Before
    fun setUp() =
        runBlocking {
            database =
                Room
                    .inMemoryDatabaseBuilder(
                        InstrumentationRegistry.getInstrumentation().targetContext,
                        AppDatabase::class.java,
                    ).build()
            store = StatsStore(database.dailyStatsDao())
            val profileId = database.profileDao().insert(ProfileEntity(name = "Focus", enabled = true))
            scheduleId =
                database.scheduleDao().insert(
                    ScheduleEntity(
                        profileId = profileId,
                        name = "Rule",
                        windowStartMinutes = 0,
                        windowEndMinutes = 0,
                        sharedBudgetSeconds = 60,
                        pauseDelaySeconds = 5,
                        afterPauseBudgetSeconds = 0,
                        message = "",
                        openingAllowance = null,
                    ),
                )
        }

    @After
    fun tearDown() = database.close()

    @Test
    fun statsAccumulatePerDayAndSumOverTheWeek() =
        runBlocking {
            store.add(thursday.minusDays(4), mapOf(scheduleId to RuleStats(notNow = 7)))
            store.add(thursday.minusDays(3), mapOf(scheduleId to RuleStats(usageMillis = 1_000, openings = 1)))
            store.add(thursday, mapOf(scheduleId to RuleStats(usageMillis = 2_000, extensions = 1)))
            store.add(thursday, mapOf(scheduleId to RuleStats(usageMillis = 500, notNow = 1)))

            assertEquals(
                PauseStats(
                    today = RuleStats(usageMillis = 2_500, extensions = 1, notNow = 1),
                    // Sunday belongs to the previous Monday-based week.
                    week = RuleStats(usageMillis = 3_500, openings = 1, extensions = 1, notNow = 1),
                ),
                store.pauseStats(scheduleId, thursday, DayOfWeek.MONDAY),
            )
        }

    @Test
    fun oldDaysArePrunedAndDeletedRulesCascade() =
        runBlocking {
            store.add(thursday.minusDays(15), mapOf(scheduleId to RuleStats(notNow = 1)))
            store.add(thursday, mapOf(scheduleId to RuleStats(notNow = 1)))
            assertEquals(1, database.dailyStatsDao().range(scheduleId, 0, Long.MAX_VALUE).size)

            database.profileDao().deleteAll()
            assertEquals(0, database.dailyStatsDao().range(scheduleId, 0, Long.MAX_VALUE).size)
        }
}
