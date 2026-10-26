package com.androidpause

import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.androidpause.enforcement.ForegroundEventClassifier
import com.androidpause.enforcement.InAppBrowser
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Framework event objects only: no activity launch, app data edits, or permission changes. */
@RunWith(AndroidJUnit4::class)
class ForegroundEventEnergyInstrumentedTest {
    @Test
    fun irrelevantEventsDoNotReadKeyboardSettings() {
        val classifier =
            classifier(keyboard = { error("unexpected settings read") }, isActivity = {
                _,
                _,
                ->
                error("unexpected lookup")
            })
        val events =
            listOf(
                AccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED),
                windowEvent("self"),
                windowEvent("current"),
                windowEvent("com.android.systemui"),
            )
        events.forEach {
            assertEquals(ForegroundEventClassifier.Decision.Ignore, classifier.classify(it, false, null, "current"))
        }
    }

    @Test
    fun missingPackageIsIgnoredAndOwnMainActivityEndsCoveredTracking() {
        val classifier = classifier(keyboard = { null })
        assertEquals(
            ForegroundEventClassifier.Decision.Ignore,
            classifier.classify(windowEvent(null), false, null, null),
        )
        assertEquals(
            ForegroundEventClassifier.Decision.HandleNow("self"),
            classifier.classify(windowEvent("self", "Main"), false, null, "covered"),
        )
    }

    @Test
    fun otherActivitiesAreDebouncedOnlyWhilePaused() {
        val classifier = classifier(keyboard = { null })
        assertEquals(
            ForegroundEventClassifier.Decision.HandleAfterDebounce("other"),
            classifier.classify(windowEvent("other", "Activity"), true, null, null),
        )
        assertEquals(
            ForegroundEventClassifier.Decision.HandleNow("other"),
            classifier.classify(windowEvent("other", "Activity"), false, null, null),
        )
    }

    @Test
    fun keyboardChangesAreNotHiddenByAStaleCache() {
        var keyboard = "first.ime"
        val classifier = classifier(keyboard = { keyboard })
        assertEquals(
            ForegroundEventClassifier.Decision.Ignore,
            classifier.classify(windowEvent(keyboard), false, null, "current"),
        )
        keyboard = "second.ime"
        assertEquals(
            ForegroundEventClassifier.Decision.Ignore,
            classifier.classify(windowEvent(keyboard), false, null, "current"),
        )
        assertEquals(
            ForegroundEventClassifier.Decision.HandleNow("first.ime"),
            classifier.classify(windowEvent("first.ime"), false, null, "current"),
        )
    }

    @Test
    fun recentsAndPausedPackageStillReachRouting() {
        val classifier = classifier(keyboard = { "ime" })
        assertEquals(
            ForegroundEventClassifier.Decision.HandleAfterDebounce("com.android.systemui"),
            classifier.classify(windowEvent("com.android.systemui", "RecentsActivity"), true, "app", "app"),
        )
        for (previous in listOf(null, "app")) {
            assertEquals(
                ForegroundEventClassifier.Decision.RememberPausedPackage("app"),
                classifier.classify(windowEvent("app"), true, "app", previous),
            )
        }
    }

    @Test
    fun nonActivityWindowsAreIgnoredWithCachedLookups() {
        val lookups = mutableListOf<String>()
        val classifier =
            classifier(keyboard = { "ime" }, isActivity = { pkg, name ->
                lookups += "$pkg/$name"
                name == "Activity"
            })
        repeat(2) {
            assertEquals(
                ForegroundEventClassifier.Decision.Ignore,
                classifier.classify(windowEvent("gesture.pad", "Panel"), it == 1, "app", "app"),
            )
        }
        assertEquals(
            ForegroundEventClassifier.Decision.HandleNow("other"),
            classifier.classify(windowEvent("other", "Activity"), false, null, "app"),
        )
        assertEquals(listOf("gesture.pad/Panel", "other/Activity"), lookups)
    }

    @Test
    fun inAppBrowserTabsAreNotTheBrowser() {
        val classifier = classifier(keyboard = { "ime" })
        val tab = "org.chromium.chrome.browser.customtabs.CustomTabActivity"
        val browser = "org.chromium.chrome.browser.ChromeTabbedActivity"
        val tabKey = InAppBrowser.key("chrome")
        assertEquals(
            ForegroundEventClassifier.Decision.HandleNow(tabKey),
            classifier.classify(windowEvent("chrome", tab), false, null, "app"),
        )
        // "Open in browser" stays in the browser package but is a real foreground change.
        assertEquals(
            ForegroundEventClassifier.Decision.HandleNow("chrome"),
            classifier.classify(windowEvent("chrome", browser), false, null, tabKey),
        )
        assertEquals(
            ForegroundEventClassifier.Decision.HandleNow(tabKey),
            classifier.classify(windowEvent("chrome", tab), false, null, "chrome"),
        )
        assertEquals(
            ForegroundEventClassifier.Decision.HandleAfterDebounce(tabKey),
            classifier.classify(windowEvent("chrome", tab), true, "chrome", "chrome"),
        )
    }

    private fun classifier(
        keyboard: () -> String?,
        isActivity: (String, String) -> Boolean = { _, _ -> true },
    ) = ForegroundEventClassifier("self", "Main", keyboard, isActivity)

    private fun windowEvent(
        pkg: String?,
        name: String = "Window",
    ): AccessibilityEvent =
        AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = pkg
            className = name
        }
}
