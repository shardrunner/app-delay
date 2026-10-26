package com.androidpause.ui.config

import com.androidpause.data.InstalledApp
import kotlin.test.Test
import kotlin.test.assertEquals

class AppPickerModelTest {
    private val apps =
        listOf(
            InstalledApp("com.example.calendar", "Calendar"),
            InstalledApp("com.example.camera", "Camera"),
            InstalledApp("org.example.maps", "Maps"),
        )

    @Test
    fun `selected apps are partitioned ahead of unselected apps`() {
        val result = appPickerApps(apps, selected = setOf("org.example.maps"), query = "")

        assertEquals(listOf("Maps"), result.selected.map { it.label })
        assertEquals(listOf("Calendar", "Camera"), result.unselected.map { it.label })
        assertEquals(3, result.shownCount)
    }

    @Test
    fun `query matches labels and package names case insensitively`() {
        val labelMatch = appPickerApps(apps, selected = emptySet(), query = "  MAPS ")
        val packageMatch = appPickerApps(apps, selected = emptySet(), query = "EXAMPLE.CAM")

        assertEquals(listOf("Maps"), labelMatch.unselected.map { it.label })
        assertEquals(listOf("Camera"), packageMatch.unselected.map { it.label })
    }

    @Test
    fun `selected count includes selections hidden by the query`() {
        val result =
            appPickerApps(
                apps,
                selected = setOf("com.example.calendar", "org.example.maps"),
                query = "camera",
            )

        assertEquals(2, result.selectedCount)
        assertEquals(emptyList<InstalledApp>(), result.selected)
        assertEquals(listOf("Camera"), result.unselected.map { it.label })
        assertEquals(1, result.shownCount)
    }
}
