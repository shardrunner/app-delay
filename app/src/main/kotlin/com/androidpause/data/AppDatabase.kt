package com.androidpause.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.androidpause.data.entity.DailyStatsEntity
import com.androidpause.data.entity.PreferenceEntity
import com.androidpause.data.entity.ProfileAppEntity
import com.androidpause.data.entity.ProfileEntity
import com.androidpause.data.entity.ScheduleEntity
import com.androidpause.data.entity.ScheduleUsageEntity

/** Room database: profiles, rules and apps, runtime usage, daily statistics and preferences (R7, FR-015). */
@Database(
    entities = [
        ProfileEntity::class,
        ProfileAppEntity::class,
        ScheduleEntity::class,
        ScheduleUsageEntity::class,
        PreferenceEntity::class,
        DailyStatsEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao

    abstract fun profileAppDao(): ProfileAppDao

    abstract fun scheduleDao(): ScheduleDao

    abstract fun scheduleUsageDao(): ScheduleUsageDao

    abstract fun preferenceDao(): PreferenceDao

    abstract fun dailyStatsDao(): DailyStatsDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** Process-wide singleton; foreign-key enforcement enabled for cascade deletes. */
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room
                    .databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "app_pause.db",
                    ).build()
                    .also { instance = it }
            }
    }
}
