package com.androidpause.ui

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val installedAtFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * Identifies the build running on the device: version name plus when this APK was
 * installed, in device-local time.
 *
 * Install time rather than build time is what answers "is the newest build on my
 * phone?" — `versionName` is hand-edited and stays identical across rebuilds, while a
 * compiled-in build timestamp would change on every compile and defeat Gradle's build
 * cache.
 */
fun buildInfoLabel(
    versionName: String,
    installedAtMillis: Long,
    zone: ZoneId,
): String {
    val installedAt = Instant.ofEpochMilli(installedAtMillis).atZone(zone)
    return "v$versionName · installed ${installedAtFmt.format(installedAt)}"
}

/** Reads the running APK's version name and install time from the package manager. */
fun buildInfoLabel(context: Context): String {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return buildInfoLabel(
        versionName = info.versionName ?: "unknown",
        installedAtMillis = info.lastUpdateTime,
        zone = ZoneId.systemDefault(),
    )
}
