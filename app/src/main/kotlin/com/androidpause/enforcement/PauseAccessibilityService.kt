package com.androidpause.enforcement

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.androidpause.AppContainer
import com.androidpause.core.Log
import com.androidpause.core.MILLIS_PER_SECOND
import com.androidpause.data.ConfigurationChanges.serialized
import com.androidpause.data.Preferences
import com.androidpause.domain.EntryAbsences
import com.androidpause.domain.RulesEngine
import com.androidpause.domain.ScheduleWindowBoundaries
import com.androidpause.domain.UsageAccounting
import com.androidpause.domain.activeCovering
import com.androidpause.domain.model.PauseOutcome
import com.androidpause.domain.model.RuleStats
import com.androidpause.domain.model.ScheduleConfig
import com.androidpause.domain.model.ScheduleRuntime
import com.androidpause.domain.recordEntry
import com.androidpause.domain.statsDeltas
import com.androidpause.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.WeekFields
import java.util.Locale

private const val FOREGROUND_DEBOUNCE_MILLIS = 500L

/**
 * Event-driven foreground detection + budget accounting (R1/R3, FR-008..FR-015).
 * Tracks covered-app foreground spans, persists runtime, and shows/dismisses the
 * pause screen on launch or mid-session exhaustion.
 * Android lifecycle, pause screen, and accounting coordination intentionally share one serialized owner.
 */
@Suppress("TooManyFunctions", "LargeClass")
class PauseAccessibilityService : AccessibilityService() {
    private lateinit var container: AppContainer
    private lateinit var interactionObserver: DeviceInteractionObserver
    private var connected = false
    private val foregroundActive: Boolean get() =
        connected &&
            ::interactionObserver.isInitialized &&
            interactionObserver.isActive
    private lateinit var midSessionMonitor: DeadlineMonitor
    private lateinit var scheduleBoundaryMonitor: DeadlineMonitor
    private var bubbleJob: Job? = null
    private lateinit var countdownBubble: CountdownBubble
    private lateinit var timeChangeObserver: DeviceTimeChangeObserver
    private val foregroundEventClassifier by lazy {
        ForegroundEventClassifier(
            appPackageName = packageName,
            appActivityClassName = MainActivity::class.java.name,
            keyboardPackage = {
                android.provider.Settings.Secure
                    .getString(contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
                    ?.substringBefore('/')
            },
            isActivity = ::isActivity,
        )
    }
    private var teardownJob: Job? = null
    private var configurationJob: Job? = null
    private var scope = serviceScope()

    private var cachedSchedules: List<ScheduleConfig>? = null
    private var preferences = Preferences()
    private lateinit var entryAbsences: EntryAbsences

    /** Package behind the pause screen, so it can be dismissed when the user leaves. */
    private var shownPause: ShownPause? = null
    private val pausedPackage: String? get() = shownPause?.pkg
    private var lastForegroundPackage: String? = null

    /** Last in-app browser tab seen; kept across screen-off because the active root names no activity. */
    private var inAppBrowserTab: String? = null
    private var pendingForegroundJob: Job? = null
    private var tracked: TrackedForeground? = null
    private val trackedForegroundPackage: String? get() = tracked?.pkg

    override fun onServiceConnected() {
        super.onServiceConnected()
        val previousTeardown = teardownJob
        if (!scope.isActive) scope = serviceScope()
        scope.launch {
            previousTeardown?.join()
            serialized {
                if (::midSessionMonitor.isInitialized) {
                    finishTrackedForegroundIfNeeded("", force = true)
                    PauseScreenHost.dismiss()
                    scheduleBoundaryMonitor.cancel()
                }
                connect()
            }
        }
    }

    private fun connect() {
        connected = true
        Log.initialize(this)
        container = AppContainer(this)
        cachedSchedules = null
        entryAbsences = EntryAbsences()
        midSessionMonitor =
            DeadlineMonitor(scope, ::serialized) { pkg ->
                handleMidSessionDeadline(pkg)
            }
        scheduleBoundaryMonitor =
            DeadlineMonitor(scope, ::serialized) { pkg ->
                handleScheduleBoundary(pkg)
            }
        countdownBubble = CountdownBubble(this)
        if (::timeChangeObserver.isInitialized) timeChangeObserver.unregister()
        timeChangeObserver =
            DeviceTimeChangeObserver(this) {
                scope.launch { serialized { reevaluateAfterTimeChange() } }
            }
        timeChangeObserver.register()
        observeDeviceInteraction()
        observeConfigurationChanges()
        Log.i("PauseAccessibilityService connected")
        resumeCurrentForeground()
    }

    private fun observeDeviceInteraction() {
        if (::interactionObserver.isInitialized) interactionObserver.unregister()
        interactionObserver =
            DeviceInteractionObserver(
                this,
                onInactive = {
                    pendingForegroundJob?.cancel()
                    bubbleJob?.cancel()
                    countdownBubble.dismiss()
                    lastForegroundPackage = null
                    scope.launch {
                        serialized { transitionForeground("", forceReevaluation = true) }
                    }
                },
                onActive = ::resumeCurrentForeground,
            )
        interactionObserver.register()
    }

    private fun observeConfigurationChanges() {
        com.androidpause.data.ConfigurationChanges.beforeChange = {
            finishTrackedForegroundIfNeeded("", force = true)
            clearTrackedForeground()
            cachedSchedules = null
        }
        configurationJob?.cancel()
        configurationJob =
            scope.launch {
                com.androidpause.data.ConfigurationChanges.events.collect {
                    serialized {
                        val pkg =
                            if (foregroundActive) {
                                currentForegroundPackage() ?: lastForegroundPackage
                            } else {
                                ""
                            }
                        if (pkg != null) transitionForeground(pkg, forceReevaluation = true)
                    }
                }
            }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::midSessionMonitor.isInitialized || !foregroundActive) return
        when (
            val decision =
                foregroundEventClassifier.classify(
                    event = event,
                    overlayShowing = PauseScreenHost.isShowing,
                    pausedPackage = pausedPackage,
                    lastForegroundPackage = lastForegroundPackage,
                )
        ) {
            ForegroundEventClassifier.Decision.Ignore -> return
            is ForegroundEventClassifier.Decision.RememberPausedPackage -> {
                pendingForegroundJob?.cancel()
                rememberForeground(decision.pkg)
                // The paused app came back above the pause screen, e.g. from Recents.
                if (!PauseScreenHost.visible) PauseScreenHost.bringToFront(this)
                return
            }
            is ForegroundEventClassifier.Decision.HandleAfterDebounce -> {
                val pkg = decision.pkg
                rememberForeground(pkg)
                pendingForegroundJob?.cancel()
                pendingForegroundJob =
                    scope.launch {
                        delay(FOREGROUND_DEBOUNCE_MILLIS)
                        if (PauseScreenHost.isShowing && lastForegroundPackage == pkg && pausedPackage != pkg) {
                            handleForeground(pkg)
                        }
                    }
            }
            is ForegroundEventClassifier.Decision.HandleNow -> {
                rememberForeground(decision.pkg)
                handleForeground(decision.pkg)
            }
        }
    }

    private fun rememberForeground(pkg: String) {
        lastForegroundPackage = pkg
        inAppBrowserTab = pkg.takeIf { InAppBrowser.packageOf(it) != null }
    }

    /** The active root names no activity: a browser package is still the in-app tab last seen there. */
    private fun foregroundKey(activePackage: String): String =
        inAppBrowserTab?.takeIf { InAppBrowser.packageOf(it) == activePackage } ?: activePackage

    private fun handleForeground(pkg: String) {
        if (pkg != trackedForegroundPackage || !foregroundActive) {
            bubbleJob?.cancel()
            countdownBubble.dismiss()
        }
        pendingForegroundJob?.cancel()
        scope.launch {
            serialized { transitionForeground(pkg) }
        }
    }

    private suspend fun transitionForeground(
        pkg: String,
        forceReevaluation: Boolean = false,
    ) {
        if (!foregroundActive && pkg.isNotEmpty()) return
        val zonedNow = ZonedDateTime.now()
        val nowMillis = zonedNow.toInstant().toEpochMilli()
        val continuing = trackedForegroundPackage == pkg && forceReevaluation
        val endedAt =
            finishTrackedForegroundIfNeeded(
                nextPkg = pkg,
                force = forceReevaluation,
            )
        val continuedAt = endedAt.takeIf { continuing }
        if (PauseScreenHost.isShowing && pkg != pausedPackage) {
            // Screen-off ends a pause without a choice; going anywhere else is "not now".
            if (pkg.isEmpty()) withContext(Dispatchers.Main) { dismissPause() } else leavePause()
        }

        val schedules = schedules()
        val loadedRuntime = loadRuntime(zonedNow, schedules)
        val runtime =
            entryAbsences.reconcile(
                pkg,
                nowMillis,
                preferences.reentryGraceSeconds * MILLIS_PER_SECOND,
                loadedRuntime,
            )
        persist(loadedRuntime, runtime)
        val outcome = RulesEngine.evaluate(pkg, zonedNow, schedules, runtime)
        Log.d("Foreground evaluated: pkg=$pkg outcome=$outcome")
        armWindowBoundary(pkg, zonedNow, schedules)
        if (outcome is PauseOutcome.Restriction) {
            if (!PauseScreenHost.isShowing || shownPause?.outcome != outcome) {
                withContext(Dispatchers.Main) {
                    if (PauseScreenHost.isShowing) dismissPause()
                    showPause(pkg, outcome)
                }
            }
            return
        }

        if (PauseScreenHost.isShowing) withContext(Dispatchers.Main) { dismissPause() }
        val enteredRuntime = recordEntry(pkg, zonedNow, schedules, runtime, countsAsOpening = true)
        persist(runtime, enteredRuntime)
        startTrackingIfNeeded(pkg, zonedNow, schedules, enteredRuntime, continuedAt)
    }

    /** Cached until [com.androidpause.data.ConfigurationChanges.beforeChange] invalidates it. */
    private suspend fun schedules(): List<ScheduleConfig> =
        cachedSchedules ?: container.profileStore.schedules().also {
            preferences = container.profileStore.preferences()
            cachedSchedules = it
        }

    private suspend fun finishTrackedForegroundIfNeeded(
        nextPkg: String,
        force: Boolean = false,
    ): Long? {
        val current = tracked ?: return null
        if (current.pkg == nextPkg && !force) return null

        val endedElapsedMillis = SystemClock.elapsedRealtime()
        val usageTime = current.accountingTime
        withContext(NonCancellable) {
            val schedules = schedules()
            val runtime = loadRuntime(usageTime, schedules)
            val updated =
                UsageAccounting.applyForegroundSpan(
                    pkg = current.pkg,
                    spanMillis = current.span.usageMillis(endedElapsedMillis),
                    now = usageTime,
                    schedules = schedules,
                    runtime = runtime,
                )
            persist(runtime, updated)
            clearTrackedForeground()
        }
        return endedElapsedMillis
    }

    private suspend fun loadRuntime(
        now: ZonedDateTime,
        schedules: List<ScheduleConfig>,
    ): Map<Long, ScheduleRuntime> {
        val runtime = container.profileStore.runtime()
        val reset = UsageAccounting.resetExpiredWindows(now, schedules, runtime)
        persist(runtime, reset)
        return reset
    }

    private fun startTrackingIfNeeded(
        pkg: String,
        now: ZonedDateTime,
        schedules: List<ScheduleConfig>,
        runtime: Map<Long, ScheduleRuntime>,
        continuedAt: Long? = null,
    ) {
        if (!foregroundActive) return
        if (schedules.activeCovering(pkg, now).isEmpty()) {
            clearTrackedForeground()
            return
        }
        if (trackedForegroundPackage == pkg) return
        val nextInterrupt = RulesEngine.nextInterrupt(pkg, now, schedules, runtime)

        tracked =
            TrackedForeground(
                pkg = pkg,
                span =
                    ForegroundSpan(
                        startedElapsedMillis = continuedAt ?: SystemClock.elapsedRealtime(),
                        startedWallMillis =
                            if (continuedAt !=
                                null
                            ) {
                                now.toInstant().toEpochMilli()
                            } else {
                                System.currentTimeMillis()
                            },
                        boundaryWallMillis = ScheduleWindowBoundaries.next(pkg, now, schedules),
                    ),
                accountingTime = now,
            )
        val writeDelay = continuedAt?.let { SystemClock.elapsedRealtime() - it } ?: 0L
        Log.d("Tracking foreground: pkg=$pkg nextInterrupt=$nextInterrupt")
        if (nextInterrupt != null) {
            midSessionMonitor.arm(pkg, (nextInterrupt.remainingMillis - writeDelay).coerceAtLeast(0L))
        }
        startCountdownBubble(
            nextInterrupt?.remainingMillis,
            RulesEngine.remainingOpenings(pkg, now, schedules, runtime),
        )
    }

    private suspend fun handleMidSessionDeadline(pkg: String) {
        if (!foregroundActive || trackedForegroundPackage != pkg) return

        val zonedNow = ZonedDateTime.now()
        Log.d("Mid-session deadline reached: pkg=$pkg")
        finishTrackedForegroundIfNeeded(pkg, force = true)
        val schedules = schedules()
        val currentRuntime = loadRuntime(zonedNow, schedules)
        val outcome = RulesEngine.evaluate(pkg, zonedNow, schedules, currentRuntime)
        armWindowBoundary(pkg, zonedNow, schedules)
        if (outcome is PauseOutcome.Restriction) {
            withContext(Dispatchers.Main) { showPause(pkg, outcome) }
            return
        }

        startTrackingIfNeeded(pkg, zonedNow, schedules, currentRuntime)
    }

    private fun armWindowBoundary(
        pkg: String,
        now: ZonedDateTime,
        schedules: List<ScheduleConfig>,
    ) {
        val boundary = if (foregroundActive) ScheduleWindowBoundaries.next(pkg, now, schedules) else null
        if (boundary == null) {
            scheduleBoundaryMonitor.cancel()
            return
        }
        scheduleBoundaryMonitor.arm(
            pkg = pkg,
            delayMillis = (boundary - now.toInstant().toEpochMilli()).coerceAtLeast(0L),
        )
        Log.d("Armed window boundary: pkg=$pkg boundary=${Instant.ofEpochMilli(boundary)}")
    }

    /** Span accounting already stops at the boundary; re-evaluate to apply or end the rule's window. */
    private suspend fun handleScheduleBoundary(pkg: String) {
        if (pkg != pausedPackage && pkg != trackedForegroundPackage && pkg != lastForegroundPackage) return
        transitionForeground(
            pkg = pkg,
            forceReevaluation = true,
        )
    }

    private suspend fun reevaluateAfterTimeChange() {
        val pkg = pausedPackage ?: trackedForegroundPackage ?: lastForegroundPackage ?: return
        transitionForeground(
            pkg = pkg,
            forceReevaluation = true,
        )
    }

    /** Without a timed interruption the bubble shows only the remaining openings and never ticks. */
    private fun startCountdownBubble(
        interruptMillis: Long?,
        remainingOpenings: Int?,
    ) {
        bubbleJob?.cancel()
        countdownBubble.dismiss()
        if (!preferences.warningsEnabled || (interruptMillis == null && remainingOpenings == null)) return
        val (pkg, span) = tracked ?: return
        bubbleJob =
            scope.launch {
                while (foregroundActive && trackedForegroundPackage == pkg && !PauseScreenHost.isShowing) {
                    val remaining = interruptMillis?.let { span.remainingMillis(it, SystemClock.elapsedRealtime()) }
                    // Database work can suspend a transition while a newer app event arrives.
                    // Finish accounting for the old app without recreating its bubble.
                    if (remaining == 0L || lastForegroundPackage != pkg) break
                    countdownBubble.update(
                        countdownBubbleState(remaining, preferences.countdownLastMinuteOnly, remainingOpenings),
                    )
                    if (remaining == null) awaitCancellation()
                    delay(countdownBubbleNextUpdateMillis(remaining, preferences.countdownLastMinuteOnly))
                }
                countdownBubble.dismiss()
            }
    }

    private suspend fun showPause(
        pkg: String,
        outcome: PauseOutcome.Restriction,
    ) {
        if (!foregroundActive) return
        val info = outcome.info
        val stats =
            container.statsStore.pauseStats(
                info.scheduleId,
                LocalDate.now(),
                WeekFields.of(Locale.getDefault()).firstDayOfWeek,
            )
        clearTrackedForeground()
        shownPause = ShownPause(pkg, outcome)
        PauseScreenHost.show(
            this,
            PauseRequest(
                info = info,
                stats = stats,
                delaySeconds = (outcome as? PauseOutcome.Pause)?.delaySeconds,
                onProceed = { scope.launch { serialized { onPauseCleared(pkg) } } },
                onNotNow = ::leaveForHome,
            ),
        )
    }

    private suspend fun onPauseCleared(pkg: String) {
        if (!foregroundActive) return
        val zonedNow = ZonedDateTime.now()
        val schedules = schedules()
        val runtime = loadRuntime(zonedNow, schedules)
        val updated = UsageAccounting.clearPause(pkg, zonedNow, schedules, runtime)
        val enteredRuntime = recordEntry(pkg, zonedNow, schedules, updated, countsAsOpening = false)
        persist(runtime, enteredRuntime)
        Log.d("Pause cleared: pkg=$pkg")
        withContext(Dispatchers.Main) { dismissPause() }
        startTrackingIfNeeded(pkg, zonedNow, schedules, enteredRuntime)
    }

    /** End the pause screen; its activity finishes and the app below (if any) shows again. */
    private fun dismissPause() {
        PauseScreenHost.dismiss()
        shownPause = null
    }

    /**
     * Persists changed runtime rows and adds the resulting usage, openings and extensions to today's
     * stats. Configuration deletion owns row removal, not accounting.
     */
    private suspend fun persist(
        previous: Map<Long, ScheduleRuntime>,
        updated: Map<Long, ScheduleRuntime>,
    ) {
        val changed = updated.filter { (id, runtime) -> previous[id] != runtime }
        if (changed.isEmpty()) return
        container.profileStore.persistRuntime(changed)
        container.statsStore.add(LocalDate.now(), statsDeltas(previous, changed))
    }

    /** Not now and Back go Home; the launcher's event then ends tracking as usual. */
    private fun leaveForHome() {
        scope.launch {
            serialized {
                val home =
                    PauseScreenHost.isShowing &&
                        withContext(Dispatchers.Main) { performGlobalAction(GLOBAL_ACTION_HOME) }
                if (home) leavePause()
            }
        }
    }

    /** Ends a pause the user left without extending, by any route, and counts it as "not now". */
    private suspend fun leavePause() {
        val scheduleId = shownPause?.outcome?.info?.scheduleId
        withContext(Dispatchers.Main) { dismissPause() }
        if (scheduleId != null) container.statsStore.add(LocalDate.now(), mapOf(scheduleId to RuleStats(notNow = 1)))
    }

    private fun clearTrackedForeground() {
        midSessionMonitor.cancel()
        bubbleJob?.cancel()
        countdownBubble.dismiss()
        tracked = null
    }

    private fun resumeCurrentForeground() {
        if (!foregroundActive) {
            handleForeground("")
            return
        }
        val pkg = foregroundKey(activeWindowPackage() ?: return)
        if (pkg == packageName) return
        rememberForeground(pkg)
        handleForeground(pkg)
    }

    /** The pause screen belongs to this app; behind it, the paused app is still the subject. */
    private fun currentForegroundPackage(): String? =
        activeWindowPackage()?.let {
            if (it == packageName && PauseScreenHost.visible) pausedPackage ?: it else foregroundKey(it)
        }

    /**
     * Root only, no prefetch. Any node query makes the framework route cache-critical events
     * (content changes, scrolls, text, clicks, focus) from every app to this service; turning the
     * unused cache back off keeps delivery to the configured window-state events.
     */
    private fun activeWindowPackage(): String? =
        getRootInActiveWindow(0)?.packageName?.toString().also {
            check(setCacheEnabled(false)) { "accessibility cache could not be disabled" }
        }

    private fun isActivity(
        pkg: String,
        className: String,
    ): Boolean =
        try {
            packageManager.getActivityInfo(ComponentName(pkg, className), PackageManager.ComponentInfoFlags.of(0))
            true
        } catch (_: PackageManager.NameNotFoundException) {
            Log.d("Ignoring non-activity window: $pkg/$className")
            false
        }

    override fun onInterrupt() {
        // No-op: nothing to interrupt for event-driven detection.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        com.androidpause.data.ConfigurationChanges.beforeChange = null
        // Cancel queued callbacks immediately; the detached flush below survives this cancellation.
        scope.cancel()
        if (!::midSessionMonitor.isInitialized) return super.onUnbind(intent)
        interactionObserver.unregister()
        pendingForegroundJob?.cancel()
        midSessionMonitor.cancel()
        bubbleJob?.cancel()
        countdownBubble.dismiss()
        scheduleBoundaryMonitor.cancel()
        timeChangeObserver.unregister()
        PauseScreenHost.dismiss()
        shownPause = null
        configurationJob?.cancel()
        // A single detached flush outlives the cancelled service callbacks; reconnect awaits its job.
        teardownJob =
            CoroutineScope(Dispatchers.Main.immediate).launch {
                serialized { finishTrackedForegroundIfNeeded("", force = true) }
            }
        return super.onUnbind(intent)
    }

    private fun serviceScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The covered app being measured; its usage counts toward the occurrence active at [accountingTime]. */
    private data class TrackedForeground(
        val pkg: String,
        val span: ForegroundSpan,
        val accountingTime: ZonedDateTime,
    )

    private data class ShownPause(
        val pkg: String,
        val outcome: PauseOutcome.Restriction,
    )
}
