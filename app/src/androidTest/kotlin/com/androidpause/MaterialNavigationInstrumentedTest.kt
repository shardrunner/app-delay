package com.androidpause

import android.app.KeyguardManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.androidpause.ui.AppTheme
import com.androidpause.ui.MainActivity
import com.androidpause.ui.config.ScheduleDraft
import com.androidpause.ui.config.ScheduleEditScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Navigation smoke tests that never save, delete, or import user configuration. */
@RunWith(AndroidJUnit4::class)
class MaterialNavigationInstrumentedTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun requireUnlockedDevice() {
        device.wakeUp()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse(
            "Unlock the device before running navigation tests",
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked,
        )
    }

    @Test
    fun drawerDestinationsAndSystemBack() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openDrawer()
            click(By.text("Settings"))
            drawerClosed()
            click(By.text("Permissions"))
            click(By.desc("Back"))
            shown(By.text("Floating countdown bubble"))
            shown(By.text("Last minute only"))
            // Back while the drawer is still opening must close it, not leave Settings.
            click(By.desc("Open navigation menu"))
            device.pressBack()
            drawerClosed()
            shown(By.text("Floating countdown bubble"))
            openDrawer()
            click(By.text("Profiles"))
            drawerClosed()
            shown(By.text("Add profile"))
        }
    }

    @Test
    fun editorBackReturnsToParentWithoutSaving() {
        ActivityScenario.launch(MainActivity::class.java).use {
            click(By.text("Add profile"))
            click(By.text("Add rule"))
            // Every editor page has a "Back" button: wait for the destination so the old page's node is not clicked.
            shown(By.text("New rule"))
            click(By.desc("Back"))
            shown(By.text("Edit profile"))
            click(By.text("Select apps (0)"))
            shown(By.text("Select covered apps"))
            click(By.desc("Back"))
            shown(By.text("Edit profile"))
            click(By.text("Add rule"))
            shown(By.text("New rule"))
            // Back cancels an unchanged new rule.
            device.pressBack()
            shown(By.text("Edit profile"))
            click(By.desc("Back"))
            shown(By.text("Add profile"))
        }
    }

    @Test
    fun dirtyRuleToolbarBackRequiresConfirmationAndKeepEditingRetainsInput() {
        ActivityScenario.launch(MainActivity::class.java).use {
            click(By.text("Add profile"))
            click(By.text("Add rule"))
            invalidateRuleBudget()
            click(By.desc("Back"))
            shown(By.text("Discard unsaved changes?"))
            click(By.text("Keep editing"))
            shown(By.clazz("android.widget.EditText").text("x"))
        }
    }

    @Test
    fun dirtyRuleSystemBackDiscardReturnsToProfileNotProfiles() {
        ActivityScenario.launch(MainActivity::class.java).use {
            click(By.text("Add profile"))
            click(By.text("Add rule"))
            invalidateRuleBudget()
            device.pressBack()
            shown(By.text("Discard unsaved changes?"))
            click(By.text("Discard"))
            shown(By.text("Edit profile"))
            click(By.text("Add rule"))
            shown(By.text("Rule 1"))
        }
    }

    @Test
    fun validRuleEditsAreKeptOnBack() {
        ActivityScenario.launch(MainActivity::class.java).use {
            click(By.text("Add profile"))
            click(By.text("Add rule"))
            setRuleField("Rule 1", "Kept rule")
            device.pressBack()
            // Without apps the profile stays an unsaved draft, so this test writes nothing.
            shown(By.text("Edit profile"))
            shown(By.text("Kept rule"))
        }
    }

    @Test
    fun totalBlockTogglePreservesTypedFiniteDelay() {
        val draft = mutableStateOf(ScheduleDraft(name = "Rule", delayInput = "37"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    AppTheme {
                        ScheduleEditScreen(
                            draft = draft.value,
                            onDraftChange = { draft.value = it },
                            onDelete = null,
                            onBack = {},
                        )
                    }
                }
            }
            shown(By.text("New rule"))
            repeat(2) { toggleIndex ->
                scrollTo(By.text("Infinite (total block)")).click()
                device.waitForIdle()
                scenario.onActivity {
                    assertEquals("37", draft.value.delayInput)
                    assertEquals(toggleIndex == 0, draft.value.infinitePause)
                }
            }
        }
    }

    @Test
    fun openingAllowanceLabelTogglesItsCheckbox() {
        ActivityScenario.launch(MainActivity::class.java).use {
            click(By.text("Add profile"))
            click(By.text("Add rule"))
            shown(By.text("New rule"))
            scrollTo(By.text("Opening allowance")).click()
            device.waitForIdle()
            shown(By.text("Shared openings"))
        }
    }

    private fun setRuleField(
        current: String,
        value: String,
    ) {
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText").text(current)), TIMEOUT_MS)
        assertNotNull("Missing rule field: $current", field)
        // Accessibility setText changes the draft without tapping/focusing the field or opening the IME.
        field.text = value
        device.waitForIdle()
    }

    /** An invalid budget cannot be kept, so leaving the rule asks first. */
    private fun invalidateRuleBudget() = setRuleField("10", "x")

    /** Compose marks a container scrollable only while its content overflows, so scrolling is optional. */
    private fun scrollTo(selector: BySelector): UiObject2 {
        device.findObject(By.scrollable(true))?.scrollUntil(Direction.DOWN, Until.findObject(selector))
        val node = device.wait(Until.findObject(selector), TIMEOUT_MS)
        assertNotNull("Missing UI element: $selector", node)
        return node
    }

    /** The scrim's close action exists only while the drawer is settled open. */
    private fun openDrawer() {
        click(By.desc("Open navigation menu"))
        shown(By.desc("Close navigation menu"))
    }

    /** Taps during the closing animation would hit the scrim or moving drawer items. */
    private fun drawerClosed() {
        assertTrue("Drawer did not close", device.wait(Until.gone(By.desc("Close navigation menu")), TIMEOUT_MS))
    }

    private fun click(selector: BySelector) {
        val node = device.wait(Until.findObject(selector), TIMEOUT_MS)
        assertNotNull("Missing UI element: $selector", node)
        node.click()
        device.waitForIdle()
    }

    private fun shown(selector: BySelector) {
        assertNotNull("Missing UI element: $selector", device.wait(Until.findObject(selector), TIMEOUT_MS))
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
