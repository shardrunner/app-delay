package com.androidpause.enforcement

import android.view.accessibility.AccessibilityEvent

/**
 * Small policy object for accessibility foreground events.
 *
 * Keeps framework-specific event filtering out of the service body so realistic
 * Android instrumentation can cover the routing rules without bootstrapping a
 * full bound accessibility service.
 */
class ForegroundEventClassifier(
    private val appPackageName: String,
    private val appActivityClassName: String,
    private val keyboardPackage: () -> String?,
    private val isActivity: (pkg: String, className: String) -> Boolean,
) {
    /** Component lookups are IPCs; an app has few activity classes, so results are kept. */
    private val activityWindows = HashMap<String, Boolean>()

    sealed interface Decision {
        data object Ignore : Decision

        data class RememberPausedPackage(
            val pkg: String,
        ) : Decision

        data class HandleAfterDebounce(
            val pkg: String,
        ) : Decision

        data class HandleNow(
            val pkg: String,
        ) : Decision
    }

    /**
     * Classify one accessibility event into the service action it requires.
     *
     * Rules mirror the service behavior: ignore irrelevant/overlay/duplicate churn,
     * treat the configuration activity as a real foreground change, remember the
     * paused package while the overlay is up, and debounce changes while showing.
     * Popups, gesture pads and panels of other packages raise window events too; only an
     * activity (or system Recents) is a real foreground change. An in-app browser tab is reported
     * under its own [InAppBrowser] key, not as the browser package.
     */
    fun classify(
        event: AccessibilityEvent?,
        overlayShowing: Boolean,
        pausedPackage: String?,
        lastForegroundPackage: String?,
    ): Decision {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return Decision.Ignore
        }

        val pkg = event.packageName?.toString() ?: return Decision.Ignore
        val className = event.className?.toString()
        val subject = if (className != null && InAppBrowser.isTab(className)) InAppBrowser.key(pkg) else pkg
        if (isChurn(subject, className, pausedPackage, lastForegroundPackage)) return Decision.Ignore
        if (subject == pausedPackage) return Decision.RememberPausedPackage(subject)
        if (!isForegroundWindow(pkg, className)) return Decision.Ignore
        return if (overlayShowing) {
            Decision.HandleAfterDebounce(subject)
        } else {
            Decision.HandleNow(subject)
        }
    }

    /** Own non-activity windows, repeats, and IME/system UI windows; cheapest checks first. */
    private fun isChurn(
        pkg: String,
        className: String?,
        pausedPackage: String?,
        lastForegroundPackage: String?,
    ): Boolean =
        (pkg == appPackageName && className != appActivityClassName) ||
            (pkg == lastForegroundPackage && pkg != pausedPackage) ||
            isTransientSystemWindow(pkg, className)

    /** Read the current IME only after cheap event/own-package/duplicate filtering. */
    private fun isTransientSystemWindow(
        pkg: String,
        className: String?,
    ): Boolean {
        if (pkg == SYSTEM_UI_PACKAGE && className?.contains("Recents") != true) return true
        return pkg == keyboardPackage()
    }

    /** System Recents passed [isTransientSystemWindow]; other packages need an activity class. */
    private fun isForegroundWindow(
        pkg: String,
        className: String?,
    ): Boolean =
        when (pkg) {
            appPackageName, SYSTEM_UI_PACKAGE -> true
            else -> className != null && activityWindows.getOrPut("$pkg/$className") { isActivity(pkg, className) }
        }
}

private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
