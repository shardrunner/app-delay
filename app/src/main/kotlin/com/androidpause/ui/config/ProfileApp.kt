package com.androidpause.ui.config

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.androidpause.AppContainer
import com.androidpause.core.SECONDS_PER_MINUTE
import com.androidpause.data.ProfileConfig
import com.androidpause.ui.AppNavigationDrawer
import com.androidpause.ui.BackButton
import com.androidpause.ui.MenuButton
import com.androidpause.ui.buildInfoLabel
import com.androidpause.ui.formatTime
import com.androidpause.ui.permissions.PermissionScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
// The route host renders state; the ViewModel owns editor transactions and persistence.
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun ProfileApp(container: AppContainer) {
    val context = LocalContext.current
    val model: ProfileViewModel =
        viewModel(
            factory =
                viewModelFactory {
                    initializer {
                        ProfileViewModel(
                            container.profileStore,
                            container.installedAppsProvider::listLaunchableApps,
                            container.permissionState::snapshot,
                            createSavedStateHandle(),
                        )
                    }
                },
        )
    var page by model::page
    val profiles = model.profiles
    val apps = model.apps
    var draft by model::draft
    var ruleDraft by model::ruleDraft
    val ruleIndex = model.ruleIndex
    var discard by model::discard
    var deleteProfile by model::deleteProfile
    var error by model::error
    var notice by model::notice
    var preview by model::preview
    val preferences = model.preferences
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        model.refresh()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.refresh() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    BackHandler(model.busy || page != Page.PROFILES) { if (!model.busy) model.back() }

    val export =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) model.export(context.applicationContext.contentResolver, uri)
        }
    val import =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) model.import(context.applicationContext.contentResolver, uri)
        }
    when (page) {
        Page.PERMISSIONS -> PermissionScreen(model.permissions, model::back)
        Page.APPS ->
            AppPickerScreen(
                apps,
                draft.packages,
                model::selectPackage,
                model::back,
            )
        Page.RULE ->
            ScheduleEditScreen(
                ruleDraft,
                { ruleDraft = it },
                if (ruleIndex >= 0) ({ model.deleteRule(ruleIndex) }) else null,
                model::back,
            )
        else ->
            AppNavigationDrawer(
                settingsSelected = page == Page.SETTINGS,
                enabled = page == Page.PROFILES || page == Page.SETTINGS,
                onProfiles = { page = Page.PROFILES },
                onSettings = { page = Page.SETTINGS },
            ) { openDrawer ->
                Scaffold(topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                when (page) {
                                    Page.PROFILE -> "Edit profile"
                                    Page.SETTINGS -> "Settings"
                                    else -> "App Delay"
                                },
                            )
                        },
                        navigationIcon = {
                            if (page == Page.PROFILE) BackButton(model::back) else MenuButton(openDrawer)
                        },
                    )
                }) { padding ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        when (page) {
                            Page.PROFILES -> {
                                Text(
                                    if (model.permissions.allGranted) {
                                        "Required permissions granted — service connection not verified"
                                    } else {
                                        "Required permissions missing — enforcement is off"
                                    },
                                )
                                if (!model.permissions.allGranted) {
                                    Button(onClick = { page = Page.PERMISSIONS }) { Text("Review permissions") }
                                }
                                Text("${profiles.count { it.enabled }} of ${profiles.size} profiles enabled")
                                Button(onClick = { model.edit(ProfileConfig()) }) { Text("Add profile") }
                                if (profiles.isEmpty()) {
                                    Text(
                                        "Create a profile to share app selections across daily, " +
                                            "daytime and evening rules.",
                                    )
                                }
                                profiles.forEach { profile ->
                                    Card(Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(16.dp)) {
                                            Text(profile.name, style = MaterialTheme.typography.titleMedium)
                                            Text("${profile.rules.size} rules · ${profile.packages.size} apps")
                                            Row {
                                                TextButton(onClick = { model.edit(profile) }) { Text("Edit") }
                                                TextButton(
                                                    onClick = { model.edit(profile.duplicate()) },
                                                ) { Text("Duplicate") }
                                                Switch(
                                                    checked = profile.enabled,
                                                    onCheckedChange = { model.setEnabled(profile, it) },
                                                    modifier =
                                                        Modifier.semantics {
                                                            contentDescription = "Enable profile ${profile.name}"
                                                        },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Page.PROFILE -> {
                                Text(
                                    if (model.editorDraftValid) {
                                        "Changes are saved automatically."
                                    } else {
                                        "Saved automatically once it has a name, an app and a rule."
                                    },
                                )
                                OutlinedTextField(
                                    draft.name,
                                    { draft = draft.copy(name = it) },
                                    label = { Text("Profile name") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                ListItem(
                                    headlineContent = { Text("Enabled") },
                                    trailingContent = {
                                        Switch(
                                            checked = draft.enabled,
                                            onCheckedChange = model::setDraftEnabled,
                                            modifier =
                                                Modifier.semantics {
                                                    contentDescription = "Enable this profile"
                                                },
                                        )
                                    },
                                )
                                Button(onClick = {
                                    page = Page.APPS
                                    model.refresh()
                                }) { Text("Select apps (${draft.packages.size})") }
                                draft.packages.sorted().forEach { pkg ->
                                    Text(
                                        apps.find { it.packageName == pkg }?.label ?: "$pkg — Unavailable",
                                    )
                                }
                                draft.rules.forEachIndexed { index, rule ->
                                    Card(Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text(rule.name, style = MaterialTheme.typography.titleMedium)
                                            val config = rule.toConfig(draft.enabled, draft.packages)
                                            val window =
                                                "${formatTime(
                                                    context,
                                                    rule.windowStart,
                                                )}–${formatTime(context, rule.windowEnd)}"
                                            val allowances =
                                                listOfNotNull(
                                                    config.sharedBudgetSeconds?.let {
                                                        "${it / SECONDS_PER_MINUTE} minutes"
                                                    },
                                                    config.openingAllowance?.let { "$it openings" },
                                                )
                                            Text((listOf(window) + allowances).joinToString(" · "))
                                            Row {
                                                TextButton(onClick = {
                                                    model.beginRule(index)
                                                }) { Text("Edit") }
                                                TextButton(
                                                    onClick = { model.duplicateRule(index) },
                                                ) { Text("Duplicate") }
                                                TextButton(onClick = { model.deleteRule(index) }) { Text("Delete") }
                                            }
                                        }
                                    }
                                }
                                Button(onClick = {
                                    model.beginRule(-1)
                                }) { Text("Add rule") }
                                if (draft.id != 0L) {
                                    TextButton(onClick = { deleteProfile = true }) { Text("Delete profile") }
                                }
                            }
                            Page.SETTINGS -> {
                                Button(onClick = { page = Page.PERMISSIONS }) { Text("Permissions") }
                                ListItem(
                                    headlineContent = { Text("Floating countdown bubble") },
                                    supportingContent = { Text("Only on apps with an active countdown") },
                                    trailingContent = {
                                        Switch(
                                            preferences.warningsEnabled,
                                            { model.updatePreferences(preferences.copy(warningsEnabled = it)) },
                                        )
                                    },
                                )
                                ListItem(
                                    headlineContent = { Text("Last minute only") },
                                    supportingContent = { Text("Off: show throughout the entire countdown") },
                                    trailingContent = {
                                        Switch(
                                            preferences.countdownLastMinuteOnly,
                                            { model.updatePreferences(preferences.copy(countdownLastMinuteOnly = it)) },
                                        )
                                    },
                                )
                                ReentryGraceSetting(preferences.reentryGraceSeconds) {
                                    model.updatePreferences(preferences.copy(reentryGraceSeconds = it))
                                }
                                Button(
                                    onClick = { export.launch("app-pause-settings.json") },
                                ) { Text("Export settings") }
                                Button(
                                    onClick = { import.launch(arrayOf("application/json", "text/plain")) },
                                ) { Text("Import settings") }
                                Text(
                                    "Import replaces profiles and resets usage after confirmation. " +
                                        "Permissions remain controlled by Android.",
                                )
                                Text(buildInfoLabel(context))
                            }
                            else -> Unit
                        }
                    }
                }
            }
    }
    if (model.busy) {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("Please wait") },
            text = { CircularProgressIndicator() },
            confirmButton = {},
        )
        return
    }
    if (discard) {
        AlertDialog(onDismissRequest = { discard = false }, title = {
            Text(
                "Discard unsaved changes?",
            )
        }, text = {
            Text(
                if (page == Page.RULE) {
                    "Some values are invalid, so this rule can't be saved."
                } else {
                    "A profile needs a name, at least one app and one rule to be saved."
                },
            )
        }, confirmButton = {
            TextButton(onClick = {
                model.confirmDiscard()
            }) { Text("Discard") }
        }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
    }
    if (deleteProfile) {
        AlertDialog(onDismissRequest = {
            deleteProfile = false
        }, title = { Text("Delete profile and its usage?") }, confirmButton = {
            TextButton(onClick = {
                model.confirmDeleteProfile()
            }) { Text("Delete") }
        }, dismissButton = { TextButton(onClick = { deleteProfile = false }) { Text("Cancel") } })
    }
    preview?.let { pending ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text("Replace settings?") }, text = {
            Text(
                "${pending.profiles.size} profiles, ${pending.ruleCount} rules. " +
                    "Existing configuration and usage will be replaced.\n" +
                    pending.changes.joinToString("\n"),
            )
        }, confirmButton = {
            TextButton(onClick = {
                model.restore(pending)
            }) { Text("Replace and reset usage") }
        }, dismissButton = { TextButton(onClick = { preview = null }) { Text("Cancel") } })
    }
    (error ?: notice)?.let { message ->
        AlertDialog(onDismissRequest = {
            error = null
            notice = null
        }, text = { Text(message) }, confirmButton = {
            TextButton(onClick = {
                error = null
                notice = null
            }) { Text("OK") }
        })
    }
}
