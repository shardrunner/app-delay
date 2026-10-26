package com.androidpause.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidpause.R
import kotlinx.coroutines.launch

@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
    }
}

/** Only top-level destinations expose the drawer; editors retain their own back/unsaved-change flow. */
@Composable
fun AppNavigationDrawer(
    settingsSelected: Boolean,
    enabled: Boolean,
    onProfiles: () -> Unit,
    onSettings: () -> Unit,
    content: @Composable (openDrawer: () -> Unit) -> Unit,
) {
    val state = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = state,
        gesturesEnabled = enabled,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    stringResource(R.string.app_name),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                Destination("Profiles", !settingsSelected) {
                    scope.launch { state.close() }
                    onProfiles()
                }
                Destination("Settings", settingsSelected) {
                    scope.launch { state.close() }
                    onSettings()
                }
            }
        },
    ) {
        content { scope.launch { state.open() } }
    }
    // isOpen stays false while the drawer animates open; Back must not reach the page underneath.
    BackHandler(state.targetValue == DrawerValue.Open) { scope.launch { state.close() } }
}

@Composable
private fun ColumnScope.Destination(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(label) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

@Composable
fun MenuButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(R.drawable.ic_menu), contentDescription = "Open navigation menu")
    }
}
