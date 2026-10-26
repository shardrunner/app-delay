package com.androidpause.ui.config

import com.androidpause.data.ProfileConfig
import java.io.Serializable

internal enum class Page { PROFILES, PROFILE, RULE, APPS, SETTINGS, PERMISSIONS }

/** Only editor data is saved; installed apps and persisted profile lists are reloaded. */
internal data class ProfileSnapshot(
    val id: Long = 0,
    val name: String = "",
    val enabled: Boolean = true,
    val packages: Set<String> = emptySet(),
    val rules: List<ScheduleDraft> = emptyList(),
) : Serializable {
    fun toProfile(): ProfileConfig =
        ProfileConfig(
            id,
            name,
            enabled,
            packages,
            rules.map {
                it.toConfig(enabled, packages)
            },
        )

    companion object {
        private const val serialVersionUID: Long = 1L

        fun from(profile: ProfileConfig): ProfileSnapshot =
            ProfileSnapshot(
                profile.id,
                profile.name,
                profile.enabled,
                profile.packages,
                profile.rules.map(ScheduleDraft::from),
            )
    }
}

internal data class ProfileEditorState(
    val page: Page = Page.PROFILES,
    val draft: ProfileSnapshot = ProfileSnapshot(),
    val original: ProfileSnapshot = ProfileSnapshot(),
    val ruleDraft: ScheduleDraft = ScheduleDraft(),
    val ruleIndex: Int = -1,
    val discard: Boolean = false,
    val deleteProfile: Boolean = false,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }

    fun beginRule(index: Int): ProfileEditorState =
        copy(
            page = Page.RULE,
            ruleIndex = index,
            ruleDraft =
                if (index < 0) ScheduleDraft(name = "Rule ${draft.rules.size + 1}") else draft.rules[index],
        )

    val ruleDirty: Boolean
        get() = ruleDraft != beginRule(ruleIndex).ruleDraft

    /** The store accepts a profile only with a name, apps and rules. */
    val draftValid: Boolean
        get() = draft.name.isNotBlank() && draft.packages.isNotEmpty() && draft.rules.isNotEmpty()

    /** A valid never-saved profile (e.g. a duplicate) is saved even without edits. */
    val needsSave: Boolean
        get() = draftValid && (draft != original || draft.id == 0L)

    /** Back applies valid rule edits; invalid ones need a discard confirmation. */
    fun leaveRule(): ProfileEditorState =
        when {
            !ruleDirty -> cancelRule()
            ruleDraft.canSave -> saveRule(ruleDraft)
            else -> copy(discard = true)
        }

    fun deleteRule(index: Int): ProfileEditorState =
        cancelRule().copy(draft = draft.copy(rules = draft.rules.filterIndexed { i, _ -> i != index }))

    fun duplicateRule(index: Int): ProfileEditorState {
        val rule = draft.rules[index]
        return copy(draft = draft.copy(rules = draft.rules + rule.copy(id = 0, name = "${rule.name} (copy)")))
    }

    fun confirmDiscard(): ProfileEditorState =
        if (page == Page.RULE) cancelRule().copy(discard = false) else copy(page = Page.PROFILES, discard = false)

    // Cancellation intentionally never parses or converts the rule draft.
    fun cancelRule(): ProfileEditorState = copy(page = Page.PROFILE, ruleDraft = ScheduleDraft(), ruleIndex = -1)

    fun saveRule(rule: ScheduleDraft): ProfileEditorState {
        require(rule.canSave) { "Correct rule errors before saving" }
        val rules = draft.rules.toMutableList()
        if (ruleIndex < 0) rules.add(rule) else rules[ruleIndex] = rule
        return copy(
            page = Page.PROFILE,
            draft = draft.copy(rules = rules),
            ruleDraft = ScheduleDraft(),
            ruleIndex = -1,
        )
    }

    fun selectPackage(
        packageName: String,
        selected: Boolean,
    ): ProfileEditorState =
        copy(
            draft = draft.copy(packages = if (selected) draft.packages + packageName else draft.packages - packageName),
        )
}
