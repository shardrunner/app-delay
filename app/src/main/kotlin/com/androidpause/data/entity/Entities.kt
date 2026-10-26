package com.androidpause.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Sentinel stored for an infinite (total-block) pause delay (data-model.md). */
const val INFINITE_PAUSE_DELAY_SENTINEL: Int = -1

/** A profile owns the enabled state and app selection shared by all of its rules. */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean,
)

/** Many-to-many membership: an app may belong to multiple profiles (FR-002, FR-014). */
@Entity(
    tableName = "profile_apps",
    primaryKeys = ["profileId", "packageName"],
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ProfileAppEntity(
    val profileId: Long,
    val packageName: String,
)

/**
 * One rule of a profile.
 *
 * End before start crosses midnight; equal start/end spans 24 hours (FR-003).
 * Times stored as minutes-of-day (0..1439) for trivial comparison.
 */
@Entity(
    tableName = "schedules",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId")],
)
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val name: String,
    val windowStartMinutes: Int,
    val windowEndMinutes: Int,
    val sharedBudgetSeconds: Int?,
    /** [INFINITE_PAUSE_DELAY_SENTINEL] means infinite/total block (FR-005, FR-012). */
    val pauseDelaySeconds: Int,
    val afterPauseBudgetSeconds: Int,
    val message: String,
    val openingAllowance: Int?,
)

/**
 * Per-schedule runtime enforcement state for the current window occurrence.
 * One row per schedule; in-progress time can be lost on abrupt termination.
 */
@Entity(
    tableName = "schedule_usage",
    foreignKeys = [
        ForeignKey(
            entity = ScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["scheduleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ScheduleUsageEntity(
    @PrimaryKey val scheduleId: Long,
    val windowOccurrenceKey: Long,
    val consumedBudgetMillis: Long,
    val pauseClearedAtMillis: Long?,
    val consumedOpenings: Int = 0,
    val sessionPackage: String? = null,
    val sessionTimeGranted: Boolean = false,
)

@Entity(tableName = "preferences")
data class PreferenceEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/** Per-rule activity of one local day; old days are pruned (pause-screen statistics). */
@Entity(
    tableName = "daily_stats",
    primaryKeys = ["scheduleId", "epochDay"],
    foreignKeys = [
        ForeignKey(
            entity = ScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["scheduleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DailyStatsEntity(
    val scheduleId: Long,
    val epochDay: Long,
    val usageMillis: Long,
    val openings: Int,
    val extensions: Int,
    val notNow: Int,
)
