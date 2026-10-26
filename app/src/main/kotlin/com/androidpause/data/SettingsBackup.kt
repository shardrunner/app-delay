package com.androidpause.data

import com.androidpause.domain.ScheduleValidation
import com.androidpause.domain.model.ScheduleConfig
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime

/** Portable v1 format. Runtime, database identities and system permissions are never exported. */
data class BackupPreview(
    val profiles: List<ProfileConfig>,
    val preferences: Preferences,
    val changes: List<String>,
) {
    val ruleCount: Int get() = profiles.sumOf { it.rules.size }
}

object SettingsBackup {
    fun encode(
        profiles: List<ProfileConfig>,
        preferences: Preferences,
    ): String =
        JSONObject()
            .apply {
                put("formatVersion", 1)
                put(
                    "preferences",
                    JSONObject()
                        .put("warningsEnabled", preferences.warningsEnabled)
                        .put("countdownLastMinuteOnly", preferences.countdownLastMinuteOnly)
                        .put("reentryGraceSeconds", preferences.reentryGraceSeconds),
                )
                put(
                    "profiles",
                    JSONArray(
                        profiles.map { profile ->
                            JSONObject().apply {
                                put("name", profile.name)
                                put("enabled", profile.enabled)
                                put("packages", JSONArray(profile.packages.sorted()))
                                put("rules", JSONArray(profile.rules.map(::encodeRule)))
                            }
                        },
                    ),
                )
            }.toString(2)

    private fun encodeRule(rule: ScheduleConfig): JSONObject =
        JSONObject().apply {
            put("name", rule.name)
            put("windowStart", rule.windowStart.toString())
            put("windowEnd", rule.windowEnd.toString())
            put("timeBudgetSeconds", rule.sharedBudgetSeconds ?: JSONObject.NULL)
            put("openingAllowance", rule.openingAllowance ?: JSONObject.NULL)
            put("pauseDelaySeconds", rule.pauseDelaySeconds ?: JSONObject.NULL)
            put("afterPauseBudgetSeconds", rule.afterPauseBudgetSeconds)
            put("message", rule.message)
        }

    fun decode(text: String): BackupPreview {
        val root = JSONObject(text)
        require(root.requiredInt("formatVersion") == 1) { "Unsupported backup format version" }
        val changes = mutableListOf<String>()
        val profilesJson = root.getJSONArray("profiles")
        val profiles = (0 until profilesJson.length()).map { decodeProfile(profilesJson.getJSONObject(it), changes) }
        val preferences = if (root.has("preferences")) root.getJSONObject("preferences") else null
        if (preferences == null || !preferences.has("warningsEnabled")) changes += "Defaulted warnings to enabled"
        val defaults = Preferences()
        return BackupPreview(
            profiles,
            Preferences(
                warningsEnabled =
                    preferences?.optionalBoolean("warningsEnabled", defaults.warningsEnabled)
                        ?: defaults.warningsEnabled,
                countdownLastMinuteOnly =
                    preferences?.optionalBoolean("countdownLastMinuteOnly", defaults.countdownLastMinuteOnly)
                        ?: defaults.countdownLastMinuteOnly,
                reentryGraceSeconds = preferences?.nullableInt("reentryGraceSeconds") ?: defaults.reentryGraceSeconds,
            ),
            changes,
        )
    }

    private fun decodeProfile(
        p: JSONObject,
        changes: MutableList<String>,
    ): ProfileConfig {
        val name = normalized(p.requiredString("name"), "profile name", changes)
        val packageJson = p.getJSONArray("packages")
        val packageList =
            (0 until packageJson.length()).map {
                val value = packageJson.get(it)
                require(value is String) { "Package must be a string" }
                normalized(value, "package", changes)
            }
        require(packageList.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) }) {
            "Invalid package name"
        }
        val packages = packageList.toSet()
        if (packages.size != packageList.size) changes += "Removed duplicate packages in $name"
        val enabled = p.optionalBoolean("enabled", true)
        if (!p.has("enabled")) changes += "Defaulted enabled to true for $name"
        val rulesJson = p.getJSONArray("rules")
        require(rulesJson.length() > 0) { "$name must contain at least one rule" }
        val rules =
            (0 until rulesJson.length()).map {
                decodeRule(
                    rulesJson.getJSONObject(it),
                    enabled,
                    packages,
                    changes,
                )
            }
        return ProfileConfig(name = name, enabled = enabled, packages = packages, rules = rules)
    }

    private fun decodeRule(
        r: JSONObject,
        enabled: Boolean,
        packages: Set<String>,
        changes: MutableList<String>,
    ): ScheduleConfig {
        require(r.has("timeBudgetSeconds")) { "Missing timeBudgetSeconds" }
        require(r.has("pauseDelaySeconds")) { "Missing pauseDelaySeconds" }
        val time = r.nullableInt("timeBudgetSeconds")
        return ScheduleValidation
            .validate(
                ScheduleConfig(
                    id = 0,
                    enabled = enabled,
                    name = normalized(r.requiredString("name"), "rule name", changes),
                    windowStart = LocalTime.parse(r.requiredString("windowStart")),
                    windowEnd = LocalTime.parse(r.requiredString("windowEnd")),
                    sharedBudgetSeconds = time,
                    pauseDelaySeconds = r.nullableInt("pauseDelaySeconds"),
                    afterPauseBudgetSeconds = r.requiredInt("afterPauseBudgetSeconds"),
                    message = r.requiredString("message"),
                    packages = packages,
                    openingAllowance = r.nullableInt("openingAllowance"),
                ),
            ).also {
                require(
                    it.windowStart.second == 0 &&
                        it.windowStart.nano == 0 &&
                        it.windowEnd.second == 0 &&
                        it.windowEnd.nano == 0,
                ) { "Windows must use whole minutes" }
            }
    }

    private fun normalized(
        value: String,
        label: String,
        changes: MutableList<String>,
    ): String =
        value.trim().also {
            require(it.isNotEmpty()) { "$label must not be blank" }
            if (it != value) changes += "Trimmed $label"
        }

    private fun JSONObject.requiredString(key: String): String {
        val value = get(key)
        require(value is String) { "$key must be a string" }
        return value
    }

    private fun JSONObject.requiredInt(key: String): Int {
        val value = get(key)
        require(
            value is Number && value.toDouble() == value.toInt().toDouble(),
        ) { "$key must be a whole number in range" }
        return value.toInt()
    }

    private fun JSONObject.nullableInt(key: String): Int? = if (!has(key) || isNull(key)) null else requiredInt(key)

    private fun JSONObject.optionalBoolean(
        key: String,
        default: Boolean,
    ): Boolean {
        if (!has(key)) return default
        val value = get(key)
        require(value is Boolean) { "$key must be true or false" }
        return value
    }
}
