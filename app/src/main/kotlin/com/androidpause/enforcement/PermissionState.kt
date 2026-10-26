package com.androidpause.enforcement

import android.content.Context
import android.provider.Settings
import android.text.TextUtils

/** OS grants only; accessibility service connection is not verified by this snapshot. */
data class PermissionSnapshot(
    val accessibilityGranted: Boolean = false,
    val overlayGranted: Boolean = false,
) {
    val allGranted: Boolean get() = accessibilityGranted && overlayGranted
}

/**
 * Checks the two OS permissions enforcement requires (FR-018). Absence is reported
 * truthfully so the UI can surface it loudly; never silently assumed granted.
 */
class PermissionState(
    private val context: Context,
    private val serviceClass: Class<*>,
) {
    fun snapshot(): PermissionSnapshot = PermissionSnapshot(isAccessibilityEnabled(), Settings.canDrawOverlays(context))

    private fun isAccessibilityEnabled(): Boolean {
        val expected = "${context.packageName}/${serviceClass.name}"
        val enabledServices =
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false

        val accessibilityOn =
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0,
            ) == 1
        if (!accessibilityOn) return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
