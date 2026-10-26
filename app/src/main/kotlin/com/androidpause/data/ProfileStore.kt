package com.androidpause.data

import androidx.room.withTransaction
import com.androidpause.data.entity.PreferenceEntity
import com.androidpause.data.entity.ProfileEntity
import com.androidpause.domain.ScheduleValidation
import com.androidpause.domain.model.ScheduleConfig
import com.androidpause.domain.model.ScheduleRuntime

/** A profile owns one shared app selection; its rules retain independent runtime. */
data class ProfileConfig(
    val id: Long = 0,
    val name: String = "",
    val enabled: Boolean = true,
    val packages: Set<String> = emptySet(),
    val rules: List<ScheduleConfig> = emptyList(),
) {
    fun duplicate(): ProfileConfig =
        copy(
            id = 0,
            name = "$name (copy)",
            enabled = false,
            rules = rules.map { it.copy(id = 0, enabled = false) },
        )
}

class ProfileStore(
    private val database: AppDatabase,
) {
    suspend fun profiles(): List<ProfileConfig> =
        database.withTransaction {
            val apps = database.profileAppDao().getAll().groupBy({ it.profileId }, { it.packageName })
            val rules = database.scheduleDao().getAll().groupBy { it.profileId }
            database.profileDao().getAll().map { profile ->
                val packages = apps[profile.id].orEmpty().toSet()
                ProfileConfig(
                    profile.id,
                    profile.name,
                    profile.enabled,
                    packages,
                    rules[profile.id].orEmpty().map { it.toConfig(profile.enabled, packages) },
                )
            }
        }

    /** All rules of all profiles (enabled or not), for enforcement. */
    suspend fun schedules(): List<ScheduleConfig> = profiles().flatMap { it.rules }

    /** Current per-rule runtime usage state. */
    suspend fun runtime(): Map<Long, ScheduleRuntime> =
        database.scheduleUsageDao().getAll().associate { it.scheduleId to it.toRuntime() }

    /** Upserts supplied runtime rows only; rows of a deleted rule are rejected by the foreign key. */
    suspend fun persistRuntime(runtime: Map<Long, ScheduleRuntime>) {
        database.scheduleUsageDao().upsertAll(runtime.values.map { it.toEntity() })
    }

    suspend fun save(profile: ProfileConfig): Long =
        ConfigurationChanges.mutate { database.withTransaction { saveInTransaction(profile) } }

    private suspend fun saveInTransaction(profile: ProfileConfig): Long {
        require(profile.name.isNotBlank()) { "Profile name is required" }
        require(profile.rules.isNotEmpty()) { "A profile needs at least one rule" }
        profile.rules.forEach { ScheduleValidation.validate(it.copy(packages = profile.packages)) }
        if (profile.id != 0L) {
            checkNotNull(database.profileDao().getById(profile.id)) { "Profile no longer exists" }
        }
        profile.rules.filter { it.id != 0L }.forEach { rule ->
            val stored = checkNotNull(database.scheduleDao().getById(rule.id)) { "Rule no longer exists" }
            check(stored.profileId == profile.id) { "Rule belongs to another profile" }
        }
        val id =
            if (profile.id == 0L) {
                database.profileDao().insert(ProfileEntity(name = profile.name.trim(), enabled = profile.enabled))
            } else {
                database.profileDao().update(ProfileEntity(profile.id, profile.name.trim(), profile.enabled))
                profile.id
            }
        database.profileAppDao().replaceForProfile(id, profile.packages)
        database.scheduleDao().deleteOthers(id, profile.rules.map { it.id })
        profile.rules.forEach { rule ->
            if (rule.id == 0L) {
                database.scheduleDao().insert(rule.toEntity(id))
            } else {
                check(database.scheduleDao().update(rule.toEntity(id)) == 1) { "Rule no longer exists" }
            }
        }
        database.scheduleUsageDao().clearGrants()
        return id
    }

    suspend fun delete(id: Long) {
        ConfigurationChanges.mutate {
            database.withTransaction {
                database.profileDao().delete(id)
                database.scheduleUsageDao().clearGrants()
            }
        }
    }

    suspend fun preferences(): Preferences {
        val dao = database.preferenceDao()
        val defaults = Preferences()
        return Preferences(
            warningsEnabled = dao.get(WARNINGS_KEY)?.toBooleanStrict() ?: defaults.warningsEnabled,
            countdownLastMinuteOnly = dao.get(LAST_MINUTE_KEY)?.toBooleanStrict() ?: defaults.countdownLastMinuteOnly,
            reentryGraceSeconds = dao.get(REENTRY_GRACE_KEY)?.toInt() ?: defaults.reentryGraceSeconds,
        )
    }

    suspend fun savePreferences(preferences: Preferences) {
        ConfigurationChanges.mutate { putPreferences(preferences) }
    }

    private suspend fun putPreferences(preferences: Preferences) {
        database.preferenceDao().put(
            PreferenceEntity(WARNINGS_KEY, preferences.warningsEnabled.toString()),
            PreferenceEntity(LAST_MINUTE_KEY, preferences.countdownLastMinuteOnly.toString()),
            PreferenceEntity(REENTRY_GRACE_KEY, preferences.reentryGraceSeconds.toString()),
        )
    }

    suspend fun replace(
        profiles: List<ProfileConfig>,
        preferences: Preferences,
    ) {
        ConfigurationChanges.mutate {
            database.withTransaction {
                database.profileDao().deleteAll()
                database.preferenceDao().clear()
                profiles.forEach {
                    saveInTransaction(it.copy(id = 0, rules = it.rules.map { rule -> rule.copy(id = 0) }))
                }
                putPreferences(preferences)
            }
        }
    }
}

private const val WARNINGS_KEY = "warningsEnabled"
private const val LAST_MINUTE_KEY = "countdownLastMinuteOnly"
private const val REENTRY_GRACE_KEY = "reentryGraceSeconds"

/** Returning to a covered app within five minutes continues its current entry (FR-009). */
private const val DEFAULT_REENTRY_GRACE_SECONDS = 300

/** User preferences; absent ones take these defaults. All are exported. */
data class Preferences(
    /** Shows the countdown bubble. */
    val warningsEnabled: Boolean = true,
    val countdownLastMinuteOnly: Boolean = false,
    val reentryGraceSeconds: Int = DEFAULT_REENTRY_GRACE_SECONDS,
) {
    init {
        require(reentryGraceSeconds >= 0) { "reentryGraceSeconds must not be negative" }
    }
}
