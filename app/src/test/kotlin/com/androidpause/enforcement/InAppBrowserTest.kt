package com.androidpause.enforcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppBrowserTest {
    @Test
    fun `custom tab activities of chromium and firefox browsers are tabs`() {
        listOf(
            "org.chromium.chrome.browser.customtabs.CustomTabActivity",
            "org.chromium.chrome.browser.customtabs.TranslucentCustomTabActivity",
            "org.mozilla.fenix.customtabs.ExternalAppBrowserActivity",
        ).forEach { assertTrue(it, InAppBrowser.isTab(it)) }
    }

    @Test
    fun `the browser itself is not a tab`() {
        listOf(
            "org.chromium.chrome.browser.ChromeTabbedActivity",
            "org.mozilla.fenix.HomeActivity",
            "org.mozilla.fenix.settings.account.AuthCustomTabActivity",
        ).forEach { assertFalse(it, InAppBrowser.isTab(it)) }
    }

    @Test
    fun `tab keys never equal a package and map back to it`() {
        val key = InAppBrowser.key("com.android.chrome")
        assertTrue(key != "com.android.chrome")
        assertEquals("com.android.chrome", InAppBrowser.packageOf(key))
        assertEquals(null, InAppBrowser.packageOf("com.android.chrome"))
    }
}
