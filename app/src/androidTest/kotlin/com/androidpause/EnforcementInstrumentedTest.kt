package com.androidpause

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.androidpause.domain.model.PauseInfo
import com.androidpause.domain.model.PauseStats
import com.androidpause.enforcement.BubbleLevel
import com.androidpause.enforcement.BubbleState
import com.androidpause.enforcement.CountdownBubble
import com.androidpause.enforcement.PauseRequest
import com.androidpause.enforcement.PauseScreenHost
import com.androidpause.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalTime

/** Instrumented pause-screen and countdown-overlay coverage; grants overlay access. */
@RunWith(AndroidJUnit4::class)
class EnforcementInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    private var scenario: ActivityScenario<MainActivity>? = null
    private var bubble: CountdownBubble? = null

    @Before
    fun setUp() {
        instrumentation.uiAutomation
            .executeShellCommand(
                "appops set ${targetContext.packageName} SYSTEM_ALERT_WINDOW allow",
            ).close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.onActivity {
            bubble?.dismiss()
        }
        scenario?.close()
    }

    @Test
    fun countdownBubbleUpdatesInPlaceIsOpaqueAndDoesNotTakeFocus() {
        lateinit var view: android.widget.TextView
        scenario!!.onActivity { activity ->
            bubble = CountdownBubble(activity)
            bubble!!.update(BubbleState("3x", BubbleLevel.NORMAL))
            view =
                android.view.inspector.WindowInspector
                    .getGlobalWindowViews()
                    .filterIsInstance<android.widget.TextView>()
                    .single { it.text == "3x" }
            val params = view.layoutParams as android.view.WindowManager.LayoutParams
            assertTrue(params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            // Touchable so it can be dragged; a wrap-content window leaves other touches to the app.
            assertEquals(0, params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            assertEquals(1f, params.alpha)
            val background = view.background as android.graphics.drawable.GradientDrawable
            val normalColor = background.color!!.defaultColor
            bubble!!.update(BubbleState("1:00", BubbleLevel.CRITICAL))
            assertEquals("1:00", view.text.toString())
            assertTrue(background.color!!.defaultColor != normalColor)
            bubble!!.update(null)
            bubble!!.dismiss()
        }
        // removeView detaches the window in a later main-thread message.
        instrumentation.waitForIdleSync()
        assertFalse(
            android.view.inspector.WindowInspector
                .getGlobalWindowViews()
                .contains(view),
        )
    }

    @Test
    fun pauseActivityRendersRequestRoutesBackToNotNowAndEndsWhenDismissed() {
        val message = "pause-activity-test-message"
        val notNow = java.util.concurrent.CountDownLatch(1)
        PauseScreenHost.show(
            targetContext,
            PauseRequest(
                info =
                    PauseInfo(
                        scheduleId = 1,
                        message = message,
                        sharedBudgetSeconds = 60,
                        consumedBudgetSeconds = 60,
                        afterPauseBudgetSeconds = 60,
                        windowEnd = LocalTime.of(17, 0),
                    ),
                stats = PauseStats(),
                delaySeconds = 0,
                onProceed = {},
                onNotNow = { notNow.countDown() },
            ),
        )
        val text = By.textContains(message)
        assertTrue(device.wait(Until.hasObject(text), 5_000))

        device.pressBack()
        assertTrue(notNow.await(5, java.util.concurrent.TimeUnit.SECONDS))

        PauseScreenHost.dismiss()
        assertTrue(device.wait(Until.gone(text), 5_000))
    }
}
