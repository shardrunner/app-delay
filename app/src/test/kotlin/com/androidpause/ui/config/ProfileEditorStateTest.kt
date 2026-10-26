package com.androidpause.ui.config

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProfileEditorStateTest {
    private val initial =
        ProfileEditorState(
            page = Page.PROFILE,
            draft =
                ProfileSnapshot(
                    id = 12,
                    name = "Evening",
                    packages = setOf("app.one"),
                    rules = listOf(ScheduleDraft(id = 42, name = "Rule")),
                ),
        )

    @Test
    fun `cancel new or existing invalid rule discards edits`() {
        for (index in listOf(-1, 0)) {
            val editing = initial.beginRule(index)
            val invalidRules =
                listOf(
                    editing.ruleDraft.copy(budgetInput = "999999999999999999"),
                    editing.ruleDraft.copy(delayInput = "not a number"),
                    editing.ruleDraft.copy(afterPauseInput = ""),
                    editing.ruleDraft.copy(openingAllowanceEnabled = true, openingsInput = "-1"),
                )
            for (rule in invalidRules) {
                val cancelled = editing.copy(ruleDraft = rule).cancelRule()
                assertEquals(initial.draft, cancelled.draft)
                assertEquals(Page.PROFILE, cancelled.page)
                assertEquals(-1, cancelled.ruleIndex)
                assertEquals(ScheduleDraft(), cancelled.ruleDraft)
            }
        }
    }

    @Test
    fun `save commits rule once and rules inherit profile packages`() {
        for (index in listOf(-1, 0)) {
            val editing = initial.selectPackage("app.two", true).beginRule(index)
            val saved = editing.saveRule(editing.ruleDraft.copy(name = "Changed", budgetInput = "20"))
            assertEquals(if (index < 0) 2 else 1, saved.draft.rules.size)
            val rule =
                saved.draft
                    .toProfile()
                    .rules
                    .last()
            assertEquals(setOf("app.one", "app.two"), rule.packages)
            assertEquals(1200, rule.sharedBudgetSeconds)
            assertEquals(Page.PROFILE, saved.page)
        }
    }

    @Test
    fun `applying total block to parent draft preserves finite delay when reopened`() {
        val editing = initial.beginRule(0)
        val blocked = editing.ruleDraft.copy(delayInput = "37", infinitePause = true)

        val applied = editing.saveRule(blocked)
        val reopened = applied.beginRule(0).ruleDraft.copy(infinitePause = false)

        assertEquals("37", reopened.delayInput)
        assertEquals(37, reopened.toConfig(true, setOf("app.one")).pauseDelaySeconds)
        assertEquals(initial.original, applied.original)
    }

    @Test
    fun `dirty rule discard returns to parent without changing profile`() {
        val editing = initial.beginRule(0)
        assertEquals(false, editing.ruleDirty)
        val changed = editing.copy(ruleDraft = editing.ruleDraft.copy(delayInput = ""), discard = true)
        assertEquals(true, changed.ruleDirty)
        val discarded = changed.confirmDiscard()
        assertEquals(Page.PROFILE, discarded.page)
        assertEquals(initial.draft, discarded.draft)
        assertEquals(false, discarded.discard)
    }

    @Test
    fun `parent edits preserve raw disabled rule inputs`() {
        val editing = initial.beginRule(0)
        val applied =
            editing.saveRule(
                editing.ruleDraft.copy(infinitePause = true, delayInput = "0037", afterPauseInput = ""),
            )
        val renamed = applied.copy(draft = applied.draft.copy(name = "Renamed"))
        val reopened = renamed.beginRule(0).ruleDraft
        assertEquals("0037", reopened.delayInput)
        assertEquals("", reopened.afterPauseInput)
        assertEquals(false, reopened.copy(infinitePause = false).canSave)
    }

    @Test
    fun `only a valid changed or never saved profile needs saving`() {
        val saved = initial.copy(original = initial.draft)
        assertEquals(false, saved.needsSave)
        assertEquals(true, saved.selectPackage("app.two", true).needsSave)
        assertEquals(false, saved.selectPackage("app.one", false).draftValid)
        assertEquals(false, ProfileEditorState(page = Page.PROFILE).needsSave)
        val duplicate = ProfileSnapshot(name = "Copy", packages = setOf("app.one"), rules = initial.draft.rules)
        assertEquals(true, ProfileEditorState(page = Page.PROFILE, draft = duplicate, original = duplicate).needsSave)
    }

    @Test
    fun `leaving a rule applies valid edits and asks before dropping invalid ones`() {
        val editing = initial.beginRule(0)
        assertEquals(initial.cancelRule(), editing.leaveRule())
        val renamed = editing.copy(ruleDraft = editing.ruleDraft.copy(name = "Renamed")).leaveRule()
        assertEquals(Page.PROFILE, renamed.page)
        assertEquals(
            "Renamed",
            renamed.draft.rules
                .single()
                .name,
        )
        val invalid = editing.copy(ruleDraft = editing.ruleDraft.copy(budgetInput = "x")).leaveRule()
        assertEquals(Page.RULE, invalid.page)
        assertEquals(true, invalid.discard)
    }

    @Test
    fun `rule delete and duplicate return to the profile`() {
        val duplicated = initial.duplicateRule(0)
        assertEquals(listOf("Rule", "Rule (copy)"), duplicated.draft.rules.map { it.name })
        assertEquals(
            0L,
            duplicated.draft.rules
                .last()
                .id,
        )
        val deleted = duplicated.beginRule(0).deleteRule(0)
        assertEquals(Page.PROFILE, deleted.page)
        assertEquals(listOf("Rule (copy)"), deleted.draft.rules.map { it.name })
        assertEquals(-1, deleted.ruleIndex)
    }

    @Test
    fun `localized decimal digits parse without accepting punctuation`() {
        assertEquals(37, boundedNumber("٣٧"))
        assertEquals(null, boundedNumber("٣.٧"))
    }

    @Test
    fun `save refuses invalid rule instead of converting stale valid value`() {
        val editing = initial.beginRule(0)
        assertFailsWith<IllegalArgumentException> {
            editing.saveRule(editing.ruleDraft.copy(afterPauseInput = ""))
        }
        assertEquals(initial.draft, editing.draft)
    }

    @Test
    fun `profile picker changes remain independent of cancelled rule`() {
        val selected = initial.selectPackage("app.two", true)
        assertEquals(selected.draft, selected.beginRule(-1).cancelRule().draft)
    }

    @Test
    fun `saved snapshot round trip preserves identities navigation and invalid input`() {
        val editing =
            initial
                .copy(original = initial.draft)
                .beginRule(0)
                .copy(page = Page.APPS, discard = true)
        val state =
            editing.copy(
                deleteProfile = true,
                ruleDraft =
                    editing.ruleDraft.copy(
                        windowStart = LocalTime.of(21, 17, 23),
                        windowEnd = LocalTime.of(6, 42, 11),
                        budgetInput = "00021",
                        delayInput = "not a number",
                        afterPauseInput = "",
                        openingAllowanceEnabled = true,
                        openingsInput = "999999999999999999",
                    ),
            )
        val bytes =
            ByteArrayOutputStream()
                .also { output ->
                    ObjectOutputStream(output).use { it.writeObject(state) }
                }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as ProfileEditorState }
        assertEquals(state, restored)
        assertEquals(
            42L,
            restored.draft
                .toProfile()
                .rules
                .single()
                .id,
        )
        assertEquals(state.original, restored.cancelRule().original)
        assertEquals(Page.APPS, restored.page)
        assertEquals(0, restored.ruleIndex)
        assertEquals(LocalTime.of(21, 17, 23), restored.ruleDraft.windowStart)
    }

    @Test
    fun `serialization restores every route`() {
        for (page in Page.entries) {
            val state = initial.beginRule(0).copy(page = page)
            val bytes =
                ByteArrayOutputStream()
                    .also { output ->
                        ObjectOutputStream(output).use { it.writeObject(state) }
                    }.toByteArray()
            val restored =
                ObjectInputStream(ByteArrayInputStream(bytes)).use {
                    it.readObject() as ProfileEditorState
                }
            assertEquals(state, restored)
        }
    }
}
