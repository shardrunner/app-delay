package com.androidpause.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.androidpause.data.entity.DailyStatsEntity
import com.androidpause.data.entity.PreferenceEntity
import com.androidpause.data.entity.ProfileAppEntity
import com.androidpause.data.entity.ProfileEntity
import com.androidpause.data.entity.ScheduleEntity
import com.androidpause.data.entity.ScheduleUsageEntity

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles")
    suspend fun getAll(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getById(id: Long): ProfileEntity?

    @Insert
    suspend fun insert(profile: ProfileEntity): Long

    @Update
    suspend fun update(profile: ProfileEntity)

    /** Cascades to the profile's apps, rules and their usage. */
    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM profiles")
    suspend fun deleteAll()
}

/** Shared app selection of each profile. */
@Dao
interface ProfileAppDao {
    @Query("SELECT * FROM profile_apps")
    suspend fun getAll(): List<ProfileAppEntity>

    @Insert
    suspend fun insertAll(apps: List<ProfileAppEntity>)

    @Query("DELETE FROM profile_apps WHERE profileId = :profileId")
    suspend fun deleteForProfile(profileId: Long)

    @Transaction
    suspend fun replaceForProfile(
        profileId: Long,
        packages: Set<String>,
    ) {
        deleteForProfile(profileId)
        insertAll(packages.map { ProfileAppEntity(profileId, it) })
    }
}

/** CRUD for rule configuration rows (FR-001). */
@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY id")
    suspend fun getAll(): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun getById(id: Long): ScheduleEntity?

    @Insert
    suspend fun insert(schedule: ScheduleEntity): Long

    @Update
    suspend fun update(schedule: ScheduleEntity): Int

    @Query("DELETE FROM schedules WHERE profileId = :profileId AND id NOT IN (:retained)")
    suspend fun deleteOthers(
        profileId: Long,
        retained: List<Long>,
    )
}

/** Per-schedule runtime usage state (FR-010, FR-011). */
@Dao
interface ScheduleUsageDao {
    @Query("SELECT * FROM schedule_usage")
    suspend fun getAll(): List<ScheduleUsageEntity>

    @Query("SELECT * FROM schedule_usage WHERE scheduleId = :scheduleId")
    suspend fun getById(scheduleId: Long): ScheduleUsageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(usage: List<ScheduleUsageEntity>)

    /** Configuration changes keep consumed allowances but end extensions and current-entry grants. */
    @Query("UPDATE schedule_usage SET pauseClearedAtMillis = NULL, sessionTimeGranted = 0")
    suspend fun clearGrants()
}

@Dao
interface PreferenceDao {
    @Query("SELECT value FROM preferences WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(vararg preferences: PreferenceEntity)

    @Query("DELETE FROM preferences")
    suspend fun clear()
}

/** Per-rule daily statistics; [add] accumulates into the day's row. */
@Dao
interface DailyStatsDao {
    @Query("SELECT * FROM daily_stats WHERE scheduleId = :scheduleId AND epochDay BETWEEN :fromDay AND :toDay")
    suspend fun range(
        scheduleId: Long,
        fromDay: Long,
        toDay: Long,
    ): List<DailyStatsEntity>

    @Upsert
    suspend fun upsert(stats: DailyStatsEntity)

    @Query("DELETE FROM daily_stats WHERE epochDay < :day")
    suspend fun deleteBefore(day: Long)

    @Transaction
    suspend fun add(delta: DailyStatsEntity) {
        val current = range(delta.scheduleId, delta.epochDay, delta.epochDay).singleOrNull()
        upsert(
            current?.copy(
                usageMillis = current.usageMillis + delta.usageMillis,
                openings = current.openings + delta.openings,
                extensions = current.extensions + delta.extensions,
                notNow = current.notNow + delta.notNow,
            ) ?: delta,
        )
    }
}
