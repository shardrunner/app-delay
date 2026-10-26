package com.androidpause.data

import kotlin.test.Test
import kotlin.test.assertEquals

class InstalledAppsProviderTest {
    @Test
    fun `own package is not selectable`() {
        val apps =
            listOf(
                InstalledApp("com.androidpause", "App Delay"),
                InstalledApp("com.example.browser", "Browser"),
            )

        assertEquals(
            listOf(InstalledApp("com.example.browser", "Browser")),
            selectableInstalledApps(apps, ownPackageName = "com.androidpause"),
        )
    }
}
