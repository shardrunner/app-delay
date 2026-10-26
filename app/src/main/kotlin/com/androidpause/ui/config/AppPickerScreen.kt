package com.androidpause.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.androidpause.data.InstalledApp
import com.androidpause.ui.BackButton

/** Searchable full-screen app picker with selected apps shown first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerScreen(
    installedApps: List<InstalledApp>,
    selected: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val apps = remember(installedApps, selected, query) { appPickerApps(installedApps, selected, query) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select covered apps") },
                navigationIcon = {
                    BackButton(onBack)
                },
            )
        },
    ) { padding ->
        AppPickerContent(
            query = query,
            selectedCount = apps.selectedCount,
            shownCount = apps.shownCount,
            selectedApps = apps.selected,
            unselectedApps = apps.unselected,
            onQueryChange = { query = it },
            onToggle = onToggle,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun AppPickerContent(
    query: String,
    selectedCount: Int,
    shownCount: Int,
    selectedApps: List<InstalledApp>,
    unselectedApps: List<InstalledApp>,
    onQueryChange: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("Search apps") },
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                Text("$selectedCount selected • $shownCount shown")
            },
        )

        if (shownCount == 0) {
            Text("No apps match \"$query\".")
        } else {
            AppSectionList(
                selectedApps = selectedApps,
                unselectedApps = unselectedApps,
                onToggle = onToggle,
            )
        }
    }
}

@Composable
private fun AppSectionList(
    selectedApps: List<InstalledApp>,
    unselectedApps: List<InstalledApp>,
    onToggle: (String, Boolean) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (selectedApps.isNotEmpty()) {
            item {
                Text(
                    text = "Selected",
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
            }
            items(selectedApps, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    checked = true,
                    onToggle = { onToggle(app.packageName, it) },
                )
            }
        }
        if (unselectedApps.isNotEmpty()) {
            item {
                Text(
                    text = if (selectedApps.isNotEmpty()) "All apps" else "Apps",
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            items(unselectedApps, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    checked = false,
                    onToggle = { onToggle(app.packageName, it) },
                )
            }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(app.label) },
        supportingContent = { Text(app.packageName) },
        leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle),
    )
}

internal data class AppPickerApps(
    val selected: List<InstalledApp>,
    val unselected: List<InstalledApp>,
    val selectedCount: Int,
) {
    val shownCount: Int = selected.size + unselected.size
}

internal fun appPickerApps(
    installedApps: List<InstalledApp>,
    selected: Set<String>,
    query: String,
): AppPickerApps {
    val normalizedQuery = query.trim().lowercase()
    val filtered =
        (
            installedApps +
                (selected - installedApps.map { it.packageName }.toSet()).map {
                    InstalledApp(it, "$it — Unavailable")
                }
        ).filter { app ->
            normalizedQuery.isBlank() ||
                app.label.lowercase().contains(normalizedQuery) ||
                app.packageName.lowercase().contains(normalizedQuery)
        }
    return AppPickerApps(
        selected = filtered.filter { it.packageName in selected },
        unselected = filtered.filterNot { it.packageName in selected },
        selectedCount = selected.size,
    )
}
