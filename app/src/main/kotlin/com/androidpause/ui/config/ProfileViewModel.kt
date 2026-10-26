package com.androidpause.ui.config

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidpause.data.BackupPreview
import com.androidpause.data.InstalledApp
import com.androidpause.data.Preferences
import com.androidpause.data.ProfileConfig
import com.androidpause.data.ProfileStore
import com.androidpause.data.SettingsBackup
import com.androidpause.enforcement.PermissionSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Main-thread action gate: claim before launching, release even when the scope is cancelled. */
internal class ProfileActionGate {
    var busy by mutableStateOf(false)
        private set

    fun launch(
        scope: CoroutineScope,
        action: suspend () -> Unit,
    ) {
        if (busy) return
        busy = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                coroutineContext.ensureActive()
                action()
            } finally {
                busy = false
            }
        }
    }
}

/** Owns the editor transaction and persistence, independently of composition lifetime. */
@Suppress("TooManyFunctions") // One focused owner for the profile editor and its persistence actions.
internal class ProfileViewModel(
    private val store: ProfileStore,
    private val listApps: () -> List<InstalledApp>,
    private val readPermissions: () -> PermissionSnapshot,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private var editor by mutableStateOf(savedState.get<ProfileEditorState>("editor") ?: ProfileEditorState())

    private val actions = ProfileActionGate()
    val busy: Boolean get() = actions.busy

    private fun update(value: ProfileEditorState) {
        if (!busy) completeEditor(value)
    }

    private fun completeEditor(value: ProfileEditorState) {
        editor = value
        savedState["editor"] = value
    }

    var page: Page
        get() = editor.page
        set(value) {
            if (!busy && value == Page.PERMISSIONS) permissions = readPermissions()
            update(editor.copy(page = value))
        }

    var ruleDraft: ScheduleDraft
        get() = editor.ruleDraft
        set(value) = update(editor.copy(ruleDraft = value))

    var ruleIndex: Int
        get() = editor.ruleIndex
        set(value) = update(editor.copy(ruleIndex = value))

    var discard: Boolean
        get() = editor.discard
        set(value) = update(editor.copy(discard = value))

    var deleteProfile: Boolean
        get() = editor.deleteProfile
        set(value) = update(editor.copy(deleteProfile = value))

    var draft: ProfileSnapshot
        get() = editor.draft
        set(value) = update(editor.copy(draft = value))

    val original: ProfileSnapshot
        get() = editor.original

    val editorDraftValid: Boolean
        get() = editor.draftValid

    var profiles by mutableStateOf(emptyList<ProfileConfig>())
        private set
    var apps by mutableStateOf(emptyList<InstalledApp>())
        private set
    var preferences by mutableStateOf(Preferences())
        private set
    var permissions by mutableStateOf(PermissionSnapshot())
        private set
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var preview by mutableStateOf<BackupPreview?>(null)

    fun beginRule(index: Int) = update(editor.beginRule(index))

    fun deleteRule(index: Int) = commit(editor.deleteRule(index))

    fun duplicateRule(index: Int) = commit(editor.duplicateRule(index))

    fun setDraftEnabled(enabled: Boolean) = commit(editor.copy(draft = draft.copy(enabled = enabled)))

    fun selectPackage(
        packageName: String,
        selected: Boolean,
    ) = update(editor.selectPackage(packageName, selected))

    fun back() {
        when (page) {
            Page.PROFILES -> Unit
            Page.PROFILE ->
                when {
                    editor.needsSave -> commit(editor.copy(page = Page.PROFILES))
                    draft != original -> discard = true
                    else -> page = Page.PROFILES
                }
            Page.RULE -> commit(editor.leaveRule())
            Page.APPS -> commit(editor.copy(page = Page.PROFILE))
            Page.SETTINGS -> page = Page.PROFILES
            Page.PERMISSIONS -> page = Page.SETTINGS
        }
    }

    fun confirmDiscard() = update(editor.confirmDiscard())

    fun edit(profile: ProfileConfig) {
        val snapshot = ProfileSnapshot.from(profile)
        update(ProfileEditorState(page = Page.PROFILE, draft = snapshot, original = snapshot))
    }

    private suspend fun reload() {
        profiles = store.profiles()
        preferences = store.preferences()
    }

    private var refreshJob: Job? = null

    @Suppress("TooGenericExceptionCaught")
    private suspend fun reportFailure(action: suspend () -> Unit) {
        try {
            action()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = failure.message ?: "Operation failed"
        }
    }

    private fun perform(action: suspend () -> Unit) {
        if (busy) return
        refreshJob?.cancel()
        actions.launch(viewModelScope) { reportFailure(action) }
    }

    fun refresh() {
        permissions = readPermissions()
        if (busy) return
        refreshJob?.cancel()
        refreshJob =
            viewModelScope.launch {
                reportFailure {
                    reload()
                    apps = withContext(Dispatchers.IO) { listApps() }
                }
            }
    }

    fun setEnabled(
        profile: ProfileConfig,
        enabled: Boolean,
    ) = perform {
        store.save(profile.copy(enabled = enabled))
        reload()
    }

    /**
     * Auto-save: a completed edit of a valid profile is saved at once; an invalid profile stays a
     * draft. The saved profile is reloaded so new rules keep their IDs (and usage) on later saves.
     */
    private fun commit(next: ProfileEditorState) {
        if (next.discard || !next.needsSave) return update(next)
        perform {
            val id = store.save(next.draft.toProfile())
            val savedProfiles = store.profiles()
            val saved = ProfileSnapshot.from(savedProfiles.single { it.id == id })
            completeEditor(
                if (next.page == Page.PROFILES) ProfileEditorState() else next.copy(draft = saved, original = saved),
            )
            profiles = savedProfiles
            preferences = store.preferences()
        }
    }

    fun confirmDeleteProfile() {
        if (page != Page.PROFILE || draft.id == 0L) return
        val id = draft.id
        perform {
            store.delete(id)
            completeEditor(ProfileEditorState())
            reload()
        }
    }

    fun updatePreferences(value: Preferences) =
        perform {
            store.savePreferences(value)
            preferences = value
        }

    fun restore(pending: BackupPreview) =
        perform {
            store.replace(pending.profiles, pending.preferences)
            preview = null
            reload()
            notice = "Settings restored"
        }

    fun export(
        resolver: ContentResolver,
        uri: Uri,
    ) = perform {
        val content =
            SettingsBackup.encode(store.profiles(), store.preferences())
        withContext(Dispatchers.IO) {
            requireNotNull(resolver.openOutputStream(uri)) { "Cannot open export document" }
                .bufferedWriter()
                .use { it.write(content) }
        }
        notice = "Settings exported"
    }

    fun import(
        resolver: ContentResolver,
        uri: Uri,
    ) = perform {
        val text =
            withContext(Dispatchers.IO) {
                requireNotNull(resolver.openInputStream(uri)) { "Cannot open backup" }
                    .bufferedReader()
                    .use { it.readText() }
            }
        preview = SettingsBackup.decode(text)
    }
}
