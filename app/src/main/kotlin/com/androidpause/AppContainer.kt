package com.androidpause

import android.content.Context
import com.androidpause.data.AppDatabase
import com.androidpause.data.InstalledAppsProvider
import com.androidpause.data.ProfileStore
import com.androidpause.data.StatsStore
import com.androidpause.enforcement.PauseAccessibilityService
import com.androidpause.enforcement.PermissionState

/**
 * Minimal manual dependency container (YAGNI — no DI framework). Provides the
 * shared singletons used across the UI and enforcement layers.
 */
class AppContainer(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.get(appContext)

    val profileStore = ProfileStore(database)

    val installedAppsProvider = InstalledAppsProvider(appContext)

    val permissionState =
        PermissionState(appContext, PauseAccessibilityService::class.java)

    val statsStore = StatsStore(database.dailyStatsDao())
}
