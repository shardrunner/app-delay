package com.androidpause

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidpause.data.AppDatabase
import com.androidpause.data.ConfigurationChanges
import com.androidpause.data.InstalledApp
import com.androidpause.data.Preferences
import com.androidpause.data.ProfileStore
import com.androidpause.domain.model.ScheduleRuntime
import com.androidpause.enforcement.PermissionSnapshot
import com.androidpause.ui.config.Page
import com.androidpause.ui.config.ProfileSnapshot
import com.androidpause.ui.config.ProfileViewModel
import com.androidpause.ui.config.ScheduleDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Private in-memory database and supplied OS snapshots: no activity launch or permission changes. */
@RunWith(AndroidJUnit4::class)
class ProfileViewModelInstrumentedTest {
    @Test
    fun permissionsRefreshOnEntryAndResumeEvenWhenCatalogLoadingFails() =
        runBlocking {
            var grants = PermissionSnapshot(true, false)
            withModel(listApps = { error("Catalog unavailable") }, readPermissions = { grants }) { model, _ ->
                model.refresh()
                assertEquals(grants, model.permissions)
                await { model.error != null }
                assertEquals("Catalog unavailable", model.error)

                grants = PermissionSnapshot(true, true)
                model.page = Page.PERMISSIONS
                assertTrue(model.permissions.allGranted)
                // The host calls refresh on resume, including when permissions were revoked in settings.
                grants = PermissionSnapshot(false, true)
                model.refresh()
                assertEquals(grants, model.permissions)
                assertFalse(model.permissions.allGranted)
            }
        }

    @Test
    fun permissionsRefreshWhilePersistenceIsBusy() =
        runBlocking {
            var grants = PermissionSnapshot()
            withModel(readPermissions = { grants }) { model, _ ->
                val release = CompletableDeferred<Unit>()
                ConfigurationChanges.beforeChange = { release.await() }
                try {
                    model.updatePreferences(Preferences(warningsEnabled = false))
                    assertTrue(model.busy)
                    grants = PermissionSnapshot(true, true)
                    model.refresh()
                    assertEquals(grants, model.permissions)
                    assertTrue(model.busy)
                } finally {
                    release.complete(Unit)
                }
                await { !model.busy }
                assertFalse(model.preferences.warningsEnabled)
                assertNull(model.error)
            }
        }

    @Test
    fun consecutiveAutosavesKeepGeneratedRuleIdsAndUsage() =
        runBlocking {
            withModel { model, store ->
                model.page = Page.PROFILE
                model.draft =
                    ProfileSnapshot(
                        name = "Focus",
                        packages = setOf("com.example.app"),
                        rules = listOf(ScheduleDraft(name = "Rule")),
                    )
                model.setDraftEnabled(false)
                await { !model.busy }
                assertNull(model.error)
                val saved = store.profiles().single()
                val ruleId = saved.rules.single().id
                assertTrue(ruleId > 0)
                assertEquals(
                    ruleId,
                    model.draft.rules
                        .single()
                        .id,
                )
                assertEquals(saved, model.profiles.single())
                val usage = ScheduleRuntime(ruleId, 42_000, null, 20_000, consumedOpenings = 2)
                store.persistRuntime(mapOf(ruleId to usage))

                model.beginRule(0)
                model.ruleDraft = model.ruleDraft.copy(name = "Edited")
                model.back()
                await { !model.busy }
                assertNull(model.error)
                assertEquals(
                    ruleId,
                    model.draft.rules
                        .single()
                        .id,
                )
                assertEquals(
                    "Edited",
                    model.profiles
                        .single()
                        .rules
                        .single()
                        .name,
                )
                assertEquals(model.profiles.single(), store.profiles().single())
                assertEquals(usage, store.runtime().getValue(ruleId))
            }
        }

    private suspend fun withModel(
        listApps: () -> List<InstalledApp> = { emptyList() },
        readPermissions: () -> PermissionSnapshot = { PermissionSnapshot() },
        block: suspend (ProfileViewModel, ProfileStore) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val store = ProfileStore(database)
        val owner = ViewModelStore()
        try {
            withContext(Dispatchers.Main) {
                ConfigurationChanges.serialized { ConfigurationChanges.beforeChange = null }
                val model = ProfileViewModel(store, listApps, readPermissions, SavedStateHandle())
                owner.put("profile", model)
                try {
                    block(model, store)
                } finally {
                    owner.clear()
                    ConfigurationChanges.serialized { ConfigurationChanges.beforeChange = null }
                }
            }
        } finally {
            database.close()
        }
    }

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }
}
