# Accessibility enforcement and battery assessment

## Conclusion and scope

**Retain accessibility-based foreground detection.** The node-cache overhead found below has been
addressed in code; device measurement is still outstanding. It remains the best practical
fit for App Delay's combination of quick launch interception, shared allowances, opening counts,
custom pauses and mid-session interruption on an ordinary personal Android phone. This is an
engineering recommendation, not a measured battery ranking.

If built-in Digital Wellbeing app timers or scheduled Focus mode meet the user's actual needs,
using those instead is simpler and avoids adding this app's service. They are not a programmable,
feature-equivalent replacement for App Delay. No comparative battery result is claimed for them.

This report is a maintained design assessment, not a device certification or implementation
checklist. Evidence reviewed on **2026-09-27**:

- App source at `d68e64f7c80463c5891b2a03031e931e2b420f7e`, re-checked after the countdown bubble
  and the optimizations listed in §3 and §4 (`c987cff`, `910029a`, `ab80586`, `ef2d05d`).
- Current Android/Google documentation and pinned Android 16 AOSP source, referenced below.
- Static inspection of event routing, root retrieval, timers, accounting and persistence.
- `adb devices -l` reported **no attached devices**. No on-device trace, energy measurement,
  permission change, installation or battery experiment was performed. The optimizations are compiled and unit-tested, not device-measured.

**Confidence:** high in the inspected code paths and public API restrictions; moderate in the
architectural preference over low-latency polling; unknown in actual mAh/day and Samsung-specific
framework behavior. AOSP implementation evidence is not proof that One UI behaves identically.

## 1. What must an alternative preserve?

The [requirements](../../specs/001-app-pause-schedules/spec.md) call for:

- An overlay within one second for at least 95% of covered exhausted-rule launches.
- Detecting both entry and exit, including launches outside the home screen.
- Shared foreground-time and opening allowances, with mid-session interruption.
- Daily window boundaries, custom finite pauses, extensions and overlapping rules.
- Normal personal-device installation; no root, managed-device provisioning or anti-bypass layer.

Battery comparisons must preserve these behaviors. Checking usage once every minute is not a
fair replacement for subsecond foreground detection. Conversely, no custom detector is needed
if the product is deliberately reduced to built-in timers or launcher-only friction.

## 2. Alternatives

| Approach | Battery reasoning | Suitability |
| --- | --- | --- |
| Narrow accessibility events plus deadlines | Work follows events rather than a continuous query cadence. Framework cache/event overhead still matters. | Best current fit, subject to the cache investigation below. |
| `UsageStatsManager.queryEvents` polling | Repeated Binder/service queries even when the same app remains foregrounded. Short intervals improve detection latency but increase work. | Possible, not an established battery improvement; adds usage-access and background-execution concerns. |
| Usage-limit/session observers | System callbacks could avoid polling. | Android 16's observer APIs are `@SystemApi` and require privileged permissions or qualifying management authority, not just user-granted usage access. Not a normal-app replacement. |
| Device/profile-owner package suspension | OS enforces suspension without this app continuously placing an overlay. | Requires owner/delegated management privileges and changes behavior: suspended apps cannot launch and notifications are hidden. Not the current personal-device product. |
| Default launcher or app shortcuts | Can add friction before launches routed through it, with little monitoring. | Does not independently cover recents, notifications, deep links or mid-session budgets. |
| VPN/DNS filter | Operates on network traffic, with its own ongoing work. | Cannot block offline app use or implement foreground opening/time semantics. |
| WorkManager or periodic alarms | Suitable for deferrable/background timing, not observing arbitrary foreground transitions. | Does not replace a foreground detector; wakeup alarms would be unnecessary for screen-off enforcement. |
| Root/shell-privileged helpers | May expose additional task/control APIs. | Adds privilege, lifecycle and deployment dependencies; no demonstrated energy advantage for this scope. |
| Built-in Digital Wellbeing | Avoids installing App Delay and uses platform/vendor-integrated features. | Prefer if its app timers/Focus mode suffice; not evidence of equivalent custom rules or a public API available to us. |

The public usage-stats API offers historical queries, including filtered queries introduced in
API 35; filtering is not a live foreground listener [S1]. Android 16 source exposes
`registerAppUsageObserver`, `registerUsageSessionObserver` and usage-limit observers as system
APIs. `registerAppUsageObserver` requires `OBSERVE_APP_USAGE` or qualifying profile-owner access;
its documented minimum time limit is one minute [S2]. Granting ordinary Usage Access does not
turn App Delay into Digital Wellbeing.

For illustration, continuous polling every 500 ms means **7,200 queries per screen-on hour**;
every second means 3,600. These are operation counts, **not energy measurements**, and are not
directly comparable to a database write or accessibility event. An ideal periodic sampler also
adds up to one polling interval of detection delay before OS/query/overlay latency. Sparse or
adaptive polling reduces work by accepting latency or requiring another source of transitions.
A foreground service can support ongoing execution but does not supply the missing event API
or make polling free [S3].

Package suspension is restricted to device/profile owners or a suitably delegated package [S4].
Do not provision/reset a personal phone merely to pursue a speculative battery optimization.
Google documents both app timers and scheduled Focus mode as user-facing alternatives [S5].

## 3. Important new finding: the XML event filter is not the full cost

The initial architectural argument—“only window-state events, therefore no content/scroll event
work”—is incomplete once this implementation retrieves the active window root.

### Source-backed chain

1. The service config requests only `typeWindowStateChanged`, with
   `canRetrieveWindowContent="true"` and `notificationTimeout="100"`.
2. `PauseAccessibilityService` uses Kotlin's `rootInActiveWindow` property in
   `resumeCurrentForeground` and configuration-change handling. Those paths cover connection,
   unlock/resume, configuration edits and post-Back recovery. It does **not** explicitly walk
   the node tree or retrieve the root for every window event.
3. Nevertheless, AOSP's no-argument `getRootInActiveWindow()` delegates to the overload with
   **`FLAG_PREFETCH_DESCENDANTS_HYBRID`** [S6]. Reading only `packageName` in application code
   does not mean Android fetched only package metadata. Actual prefetch work depends on the
   window, cache and framework conditions.
4. The server-side `findAccessibilityNodeInfoByAccessibilityId` sets
   **`mUsesAccessibilityCache = true`** [S7]. Cache use makes additional event types relevant.
5. `notifyAccessibilityEvent` dispatches events either because the service requested them **or
   because they are required for cache consistency** [S7]. The cache-critical mask includes
   content changes, scrolling, text changes, clicks, focus and window-list changes [S8]. The service wrapper
   updates the cache before deciding whether to call the application's `onAccessibilityEvent`
   [S6]. An application callback counter therefore misses some framework-delivered events.
6. Cache maintenance can invalidate nodes and refresh a previously cached node [S8]. Costs may
   occur in App Delay, `system_server`, and the app providing accessibility node information.
7. `getRelevantEventTypes()` widens to the same mask while the flag is set, and client updates
   push that mask to every app's `AccessibilityManager` [S7]. Apps then build and send those
   events device-wide. A node query sets the flag without an immediate client update, so the
   widening reaches apps at the next accessibility state change; `setCacheEnabled` updates
   clients immediately.
8. The timeout coalesces many event types, but AOSP explicitly excludes
   `TYPE_WINDOW_CONTENT_CHANGED` from that pending-event coalescing path [S7]. **100 ms is not
   a universal ten-events-per-second cap.**

This was not merely potential: `connect()` and every unlock call `resumeCurrentForeground()`, which
queried the root, so the flag was set from service start onward. Actual event volume and cost on
the target firmware still need observation. Enabling accessibility at all can also change work
performed by other apps; app-UID battery estimates alone cannot establish the incremental cost.

### Implemented mitigation

`activeWindowPackage()` is the only root query. It requests the root without prefetch
(`getRootInActiveWindow(0)`) and then calls `setCacheEnabled(false)`, failing loudly if that
returns false. Disabling after every query is required because each node query sets the server
flag again (S7 line 845); the client also skips prefetch while its cache is disabled [S10]. Between
query and disable, a few milliseconds of cache-critical events can still be routed.

**Emulator result (API 36 Google Play image, debug builds).** A Perfetto `binder_driver` trace
during a fixed workload (force-stop Settings, open it, 16 list swipes) counted, per run:

| Build | Binder transactions into App Delay | Oneway calls Settings → `system_server` |
| --- | --- | --- |
| Before (`a45643d`) | 243, 243 | 297 |
| After (`c987cff` onward) | 2, 2 | 39 |

Synchronous Settings → `system_server` calls were unchanged (291 each), so the difference is the
accessibility event traffic itself: fresh app processes no longer generate scroll/content events
for App Delay. Without the force-stop, an already-running Settings process kept its earlier mask
and generated no extra events in either build, matching the missing client update above. Connect
and unlock recovery worked on the emulator. These are IPC counts, not energy; One UI may differ,
and configuration-change and Back recovery still need a device check.

Removing content-retrieval permission would avoid root queries entirely but lose the way to
recover the current foreground package without waiting for a new event; that is a correctness
tradeoff. Filtering to selected package names alone would miss exits to unselected apps and could
leave accounting/overlays active.

## 4. Application-level cost audit

Paths below are under `app/src/main/kotlin/com/androidpause/` unless otherwise noted.

| Area | Verified behavior | Interpretation |
| --- | --- | --- |
| `enforcement/ForegroundEventClassifier.kt` | Rejects duplicate-package, own non-main-activity (pause screen, bubble) and transient system/keyboard events before enforcement. Keyboard setting lookup occurs after cheap filters. | Good application-level filtering, but happens after event IPC and does not account for cache-only events. |
| `enforcement/DeviceInteractionObserver.kt` | Screen-off/lock ends tracking; screen-on resumes only when interactive and unlocked. Callbacks fire only on state changes (`ef2d05d`). | Lock-screen wakes no longer repeat the inactive transition, and screen-on plus user-present no longer resume twice. |
| `enforcement/DeadlineMonitor.kt` | A single cancellable coroutine delay per armed monitor. | Suspends rather than busy-waits; not a wakeup alarm or a guarantee of delivery while suspended/killed. |
| `enforcement/PauseAccessibilityService.kt` | Schedules cached until configuration changes; runtime reloaded per transition. No periodic checkpoints; usage is committed at transitions only. | No recurring database work during a stable covered visit. |
| `enforcement/PauseAccessibilityService.kt` (`persist`) | Writes only changed runtime rows. | No unconditional whole-table rewrite; do not overstate persistence cost. |
| `data/ProfileStore.kt`, `data/Daos.kt` | Runtime reads select all usage rows; writes use a batch `INSERT OR REPLACE`. | Per-transition reads grow with stored rules, even when only a subset changes. |
| `enforcement/CountdownBubble.kt`, `CountdownBubbleLabel.kt` | Small draggable overlay; no notifications. Above one minute it shows rounded-up minutes and wakes only when the label changes; a hidden bubble sleeps until the final minute and an opening-only bubble never ticks. | One redraw per minute, then once per second for the final minute. Forced re-evaluations (boundaries, configuration, clock) still recreate it. |
| `ui/overlay/PauseScreen.kt`, `ui/overlay/PauseActivity.kt` | Finite visible pause countdown updates at approximately one-second intervals and ends at zero. As an activity, the pause screen moves the paused app to the background, so its media and rendering stop. | Bounded, short-lived foreground work. Treated as a usability question, not a battery target. |
| `enforcement/DeviceTimeChangeObserver.kt` | Listens for clock, zone and date changes, not minute ticks. | No minute-based re-evaluation loop. |

No app wake-lock, wakeup-alarm, continuous foreground-query loop or network request was found
in the inspected enforcement paths; the manifest has no `INTERNET` permission. This does not
exclude wakeups/work initiated by Android or other apps. A resident bound service is not itself
evidence of continuous CPU consumption.

### Removed: periodic checkpoints

Until `910029a`, a 30-second checkpoint forced a full transition during covered use: two runtime
SELECTs, a changed-row write, timer re-arming and a countdown bubble rebuild, roughly 120 times per
covered hour, including for opening-only rules. The spec no longer guarantees restart durability,
and mid-session deadlines and window boundaries have their own timers, so the checkpoint only
bounded crash loss. Abrupt termination during a covered visit now loses that visit's uncommitted
time; leaving the app, screen-off and orderly teardown still commit it.

### Remaining bounded opportunities

- Each transition loads runtime twice (accounting, then evaluation). Reusing fresh runtime within
  one serialized transition would halve reads; transitions are now event-driven and rare.
- Transitions between unrelated apps still load runtime and evaluate rules. A fast path must
  first finish a previous covered span, clear session grants and preserve upcoming relevant
  window boundaries. Returning early solely because the destination is currently uncovered
  would be incorrect.
- Runtime could be held in memory: the service is effectively its only writer, and configuration
  changes already pass through `ConfigurationChanges.beforeChange` for invalidation. Reads are
  now per transition and usually hit SQLite's page cache, so the gain is small.
- The semi-transparent bubble over full-screen video may prevent the video layer from using a
  hardware overlay plane on some devices, forcing GPU composition. Speculative; measure once with
  video playback. **Last minute only** avoids it.
- With all profiles disabled, the service still receives window-state events and processes
  package changes. Investigate an inactive mode only if this is common; `disableSelf()` is not a
  transparent idle optimization because the user must enable the service again.

None of these remaining opportunities has been implemented or benchmarked.

## 5. Reliability, permissions and distribution

Accessibility is an observation mechanism, not a privileged guarantee of the foreground app.
Window focus, recents, keyboards, dialogs, picture-in-picture, multi-window, lock transitions,
service interruption and vendor changes need device tests. Coalescing and the app's 500 ms
pause-overlay debounce can add latency. Do not increase batching delays merely to save work
without checking the one-second launch target and opening counts.

Google Play permits a range of non-disability accessibility uses, but requires declaration,
approval and prominent in-app disclosure/consent for apps that are not accessibility tools.
Deterministic, human-defined automation is not categorically prohibited; approval is still not
guaranteed [S11]. App Delay should not claim `isAccessibilityTool=true` just to avoid disclosure.
Personal sideloading does not remove Android's sensitive-permission onboarding or OEM lifecycle
issues. These are reasons to assess distribution separately, not evidence polling is cheaper.

The root lookup still transfers the active window's root node, not just its package name, but no
longer prefetches descendants or keeps a node cache. Application code does not record screen text.
See [privacy and permissions](privacy.md) for existing disclosure and data handling.

## 6. Measurement plan

### Safety and tooling

Use an emulator for destructive/permission-mutating correctness tests, but a physical phone for
energy results. Obtain confirmation before installing/updating builds or changing accessibility,
profiles or device power settings on a personal phone. Pin `ANDROID_SERIAL`; follow
[development safety guidance](development.md#personal-device-safety). Do not clear app data,
uninstall, reset permissions, reset battery statistics or change charging state as an incidental
part of profiling. Traces/bugreports can contain package names and other private information;
keep raw artifacts private and redact shared results.

Use short Perfetto/system traces to explain CPU scheduling, Binder activity, timer cadence and
I/O, followed by longer untraced energy runs. Tracing and diagnostic counters can perturb the
result. Counter instrumentation should be bounded and identical across compared builds; avoid
per-event verbose logs. Accessibility UI automation can itself add accessibility work and, more
importantly, **`UiAutomation` suppresses other accessibility services by default**. An automated
enforcement run must obtain it with `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` and verify that
the driver does not subsequently reconnect without that flag [S15]. Check actual service
connection and enforcement during each enabled-service run; a Settings toggle alone is not
proof. Keep the driver identical between variants and include manual/uninstrumented runs.

Google's Power Profiler documentation identifies Pixel 6+ for ODPM power rails; **do not promise
those measurements on the Galaxy S24**. Check actual capabilities. Charge-counter/battery-gauge
measurements may be available but are coarser. AndroidX `PowerMetric` has capability checks and
a battery fallback; its battery mode changes charging behavior, so it is not a read-only default
for a personal device [S12, S13]. If no suitable energy sensor is available, report CPU/IPC
results only and arrange longer controlled discharge runs or appropriate external measurement.
Battery Historian is no longer actively maintained; Google recommends newer tracing/power tools
[S14]. Batterystats can still be supporting evidence, not an exact allocation of all overhead.

### Variants and workloads

Compare separately, using the same signing identity, configuration and release optimizations:

- **A:** service disabled baseline, changed manually only with consent.
- **B:** build before the optimizations (`a45643d`): prefetching root query with the cache left
  on, 30-second checkpoints and a per-second bubble.
- **C:** current build. To attribute savings, optionally build B plus only the cache change.
- A polling prototype only if earlier results leave a material concern; use the same detection
  target and screen-off behavior rather than giving polling an easier workload.

For each variant, exercise:

| Workload | Suggested initial duration | What it isolates |
| --- | --- | --- |
| Screen off, no interaction | 6–8 hours | Idle drain and unexpected recurring work. |
| Screen on in an uncovered static app | 30–60 minutes | Resident service/framework baseline. |
| Uncovered app scrolling/typing | 30 minutes | Cache-critical content/scroll event overhead outside selected apps. |
| Covered app with ample time budget, bubble visible | 30–60 minutes | Bubble redraw cadence and absence of recurring runtime I/O. |
| Scripted app switching, pauses and extensions | At least 100 covered launches plus deadline cases | Latency, missed events, opening correctness and bubble churn. |
| Enabled service with all profiles disabled | 30 minutes | Disabled-profile overhead compared with A and B. |

Keep screen brightness/refresh rate, app content, radio conditions, OS/build, temperature, initial
charge range, other accessibility services and background sync comparable. Do not silently
disable personal services or notifications. Prefer local/static workloads for causal comparison;
separately repeat realistic normal use. Avoid comparing unrestricted scrolling with an overlay
that stops the target app doing work: reduced target-app workload is not detector efficiency.

Counterbalance run order (for example A–B then B–A), repeat at least three paired runs as an
initial check, and extend duration/repetitions if measurement resolution or variance dominates.
Use the same charging/connection conditions; USB charging and wireless ADB activity can distort
discharge measurements. Confirm a monitoring approach with the owner rather than manipulating
battery-service state. A service that was killed or failed to enforce is not a low-power success.

Record:

- APK commit/hash and build type, phone model/chipset, Android/One UI build, configuration and
  measurement-tool capabilities.
- Whole-device charge/energy delta per unit time and run-to-run spread, where measurable; do
  not invent a mAh conversion from battery percentage or compare app battery percentages alone.
- App and relevant framework/target-app CPU time, Binder traffic, root queries/cache work,
  runtime reads/writes, bubble window adds/removes and redraws, and wakeups where the
  trace permits. Distinguish timer callbacks while awake from suspend-exiting wakeups.
- Foreground-to-overlay p50/p95/max latency and missed covered launches; deadline overshoot;
  correct accounting/overlay dismissal across navigation, unlock and configuration changes.
- Screen-off recurrence, service liveness and whether the expected full workload actually ran.

For discharge counters, compare `(start charge − end charge) / elapsed hours` across matched
runs; report paired differences and uncertainty, not a single battery-percentage screenshot.
Agree a numerical definition of “negligible idle impact” before acceptance—the current spec
has no threshold. If differences are below measurement resolution, report **not resolved by this
experiment**, not zero cost. Preserve the existing launch/correctness requirements regardless
of any energy improvement.

## 7. Decision rules

1. **Do not replace accessibility with polling based on the service label alone.** There is no
   measured result supporting that change, and it introduces repeated queries and execution
   constraints while retaining custom enforcement complexity.
2. **Verify the cache mitigation on the target phone first.** It addressed the strongest
   framework-level finding, which generated work even in unselected apps. Savings remain
   unquantified, and One UI may differ from AOSP.
3. **Keep recurring work event-driven.** Do not reintroduce periodic checkpoints or per-second
   redraws without a measured need.
4. **Keep recovery and correctness intact.** Lower event counts, fewer writes or a sleeping
   process do not count as improvements if launches/deadlines are missed.
5. **Revisit the product before privileges.** Built-in Focus mode/timers may be enough for a
   simpler personal workflow. Device-owner/root designs require explicit scope changes.

## Sources

Official documentation was retrieved during this investigation. AOSP links are pinned to
`99b01a65cc4c104933788b3143285ab6bae65827` from `android16-release`; downloaded files were checked
against that commit. These describe Android 16 reference behavior, not verified Samsung code.

- **S1:** [UsageStatsManager public API](https://developer.android.com/reference/android/app/usage/UsageStatsManager) — historical queries and API 35 filtered queries.
- **S2:** [AOSP UsageStatsManager](https://github.com/aosp-mirror/platform_frameworks_base/blob/99b01a65cc4c104933788b3143285ab6bae65827/core/java/android/app/usage/UsageStatsManager.java#L929-L1146) — system-only observer contracts and permissions.
- **S3:** [Android background execution restrictions](https://developer.android.com/develop/background-work/background-tasks/bg-work-restrictions) — execution constraints; no foreground-detection API supplied by an ongoing service.
- **S4:** [DevicePolicyManager](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setPackagesSuspended(android.content.ComponentName,%20java.lang.String[],%20boolean)) — package suspension authority and effects.
- **S5:** [Manage time with Digital Wellbeing](https://support.google.com/android/answer/9346420?hl=en) — app timers and scheduled Focus mode.
- **S6:** [AOSP AccessibilityService](https://github.com/aosp-mirror/platform_frameworks_base/blob/99b01a65cc4c104933788b3143285ab6bae65827/core/java/android/accessibilityservice/AccessibilityService.java) — `getRootInActiveWindow` (lines 1121–1142), `setCacheEnabled` (2434–2456), wrapper event routing (2989–3003).
- **S7:** [AOSP AbstractAccessibilityServiceConnection](https://github.com/aosp-mirror/platform_frameworks_base/blob/99b01a65cc4c104933788b3143285ab6bae65827/services/accessibility/java/com/android/server/accessibility/AbstractAccessibilityServiceConnection.java) — relevant event mask (519–522), node-query cache flag (830–845), event dispatch/coalescing (1801–1840), cache setter (2634–2647).
- **S8:** [AOSP AccessibilityCache](https://github.com/aosp-mirror/platform_frameworks_base/blob/99b01a65cc4c104933788b3143285ab6bae65827/core/java/android/view/accessibility/AccessibilityCache.java) — cache-critical mask (61–72), event handling and node refresh (211–317).
- **S9:** [AccessibilityService API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#setCacheEnabled(boolean)) and [AccessibilityServiceInfo timeout](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#notificationTimeout) — public cache/prefetch control and IPC/coalescing rationale.
- **S10:** [AOSP AccessibilityInteractionClient](https://github.com/aosp-mirror/platform_frameworks_base/blob/99b01a65cc4c104933788b3143285ab6bae65827/core/java/android/view/accessibility/AccessibilityInteractionClient.java#L611-L674) — cache handling and prefetch suppression.
- **S11:** [Google Play AccessibilityService policy](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en) — eligibility, declarations, disclosure/consent and deterministic automation.
- **S12:** [Android Studio Power Profiler](https://developer.android.com/studio/profile/power-profiler) — ODPM/Pixel support, whole-device attribution and battery-gauge alternatives; page updated March 2026.
- **S13:** [AndroidX PowerMetric](https://developer.android.com/reference/kotlin/androidx/benchmark/macro/PowerMetric) — capability checks and battery-mode charging effects.
- **S14:** [Battery profiling guidance](https://developer.android.com/topic/performance/power/setup-battery-historian) — Battery Historian maintenance warning and recommended alternatives.
- **S15:** [AOSP UiAutomation](https://github.com/aosp-mirror/platform_frameworks_base/blob/99b01a65cc4c104933788b3143285ab6bae65827/core/java/android/app/UiAutomation.java#L171-L186) — default accessibility-service suppression and the flag required to preserve services during automation.
