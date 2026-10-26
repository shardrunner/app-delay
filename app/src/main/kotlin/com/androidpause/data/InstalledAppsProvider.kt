package com.androidpause.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** A user-selectable installed app. */
data class InstalledApp(
    val packageName: String,
    val label: String,
)

/**
 * Lists launchable installed apps for the schedule app-picker (R6).
 *
 * Uses a launcher-intent query; stores only the package name in schedules.
 */
class InstalledAppsProvider(
    private val context: Context,
) {
    /** All apps with a launcher entry, sorted by display label. */
    fun listLaunchableApps(): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        com.androidpause.core.Log
            .i("listLaunchableApps: resolved=${resolved.size}")
        val apps =
            selectableInstalledApps(
                apps =
                    resolved.map { info ->
                        InstalledApp(
                            packageName = info.activityInfo.packageName,
                            label = info.loadLabel(pm).toString(),
                        )
                    },
                ownPackageName = context.packageName,
            )
        com.androidpause.core.Log
            .i("listLaunchableApps: returning=${apps.size}")
        return apps
    }
}

internal fun selectableInstalledApps(
    apps: List<InstalledApp>,
    ownPackageName: String,
): List<InstalledApp> =
    apps
        .filterNot { it.packageName == ownPackageName }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
