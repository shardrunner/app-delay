package com.androidpause.ui.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidpause.R
import com.androidpause.enforcement.PermissionSnapshot
import com.androidpause.ui.BackButton

/**
 * Shows enforcement and bubble permissions with links to system settings. Surfaces missing
 * permissions loudly (FR-018/FR-018a); the host refreshes the shared snapshot
 * on entry and resume (after returning from settings).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreen(
    permissions: PermissionSnapshot,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val (accessibilityOk, overlayOk) = permissions

    Scaffold(topBar = {
        TopAppBar(title = {
            Text("Permissions")
        }, navigationIcon = { BackButton(onBack) })
    }) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PermissionStatusSummary(accessibilityOk = accessibilityOk, overlayOk = overlayOk)
            PermissionCards(
                context = context,
                accessibilityOk = accessibilityOk,
                overlayOk = overlayOk,
            )
        }
    }
}

@Composable
private fun PermissionStatusSummary(
    accessibilityOk: Boolean,
    overlayOk: Boolean,
) {
    if (!accessibilityOk || !overlayOk) {
        Text(
            "Enforcement is OFF until both permissions are granted.",
            color = MaterialTheme.colorScheme.error,
        )
    } else {
        Text("Required permissions granted. Service connection is not verified here.")
    }

    Text("The countdown bubble uses overlay access; no notification permission is needed.")
}

@Composable
private fun PermissionCards(
    context: Context,
    accessibilityOk: Boolean,
    overlayOk: Boolean,
) {
    PermissionCard(
        title = "Accessibility service",
        description = stringResource(R.string.accessibility_service_description),
        granted = accessibilityOk,
        onGrant = { context.openAccessibilitySettings() },
    )
    Text("In Accessibility settings, select App Delay under installed/downloaded apps and enable it.")
    Text(
        "If Android blocks this with Restricted settings, open App info, " +
            "use its menu to Allow restricted settings, then return to Accessibility. " +
            "Only do this for an installation you trust.",
    )
    Button(onClick = {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
    }) { Text("Open App info") }
    PermissionCard(
        title = "Display over other apps",
        description = "Lets the pause / block screen open over covered apps and shows the countdown bubble.",
        granted = overlayOk,
        onGrant = { context.openOverlaySettings() },
    )
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit,
    statusText: String = if (granted) "Granted ✓" else "Not granted ✗",
    actionLabel: String = "Grant",
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
            Text(
                statusText,
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            if (!granted) {
                Button(onClick = onGrant) { Text(actionLabel) }
            }
        }
    }
}

private fun Context.openAccessibilitySettings() {
    startActivity(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun Context.openOverlaySettings() {
    startActivity(
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
