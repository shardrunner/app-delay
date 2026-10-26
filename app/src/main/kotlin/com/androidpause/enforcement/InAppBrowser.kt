package com.androidpause.enforcement

/**
 * A browser tab another app opened a link in (Custom Tab), e.g. Chrome's
 * `org.chromium.chrome.browser.customtabs.CustomTabActivity` (also Chromium-based browsers) or
 * Firefox's `org.mozilla.fenix.customtabs.ExternalAppBrowserActivity`.
 *
 * It runs in the browser's package but is not the browser: it gets its own foreground key, which
 * no profile can cover, so pausing a browser leaves links opened from other apps usable.
 */
internal object InAppBrowser {
    private const val KEY_SUFFIX = "#in-app-browser"

    fun isTab(activityClassName: String): Boolean = ".customtabs." in activityClassName

    fun key(pkg: String): String = pkg + KEY_SUFFIX

    /** The browser package of a tab key, or null for an ordinary package. */
    fun packageOf(key: String): String? = key.takeIf { it.endsWith(KEY_SUFFIX) }?.removeSuffix(KEY_SUFFIX)
}
