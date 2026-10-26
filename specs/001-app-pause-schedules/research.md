# Design decisions: App Delay

Rationale for the current [requirements](spec.md) and [implementation plan](plan.md). For
verified behavior and known limitations see [architecture](../../docs/public/architecture.md).

## Detection and presentation

- **Accessibility service:** Listen for foreground window changes rather than polling
  `UsageStatsManager`. Android rebinds an enabled service after reboot; delivery timing and
  availability still depend on OS and permissions. `getRunningTasks` is restricted/deprecated.
  Retain this approach over low-latency usage polling, but do not infer total framework cost from
  the XML event filter: root retrieval can enable descendant prefetch and cache-maintenance events.
  See the [battery assessment](../../docs/public/accessibility-and-battery.md) for Android 16 source
  evidence, alternatives, optimization candidates and the unperformed device measurement plan.
- **Pause screen:** an Activity in its own task (`singleTask`, empty affinity, excluded from
  Recents), started by the bound accessibility service, which is exempt from background
  Activity-launch restrictions. A full-screen `TYPE_APPLICATION_OVERLAY` was used first: it sat
  above gesture apps' edge windows, so Samsung One Hand Operation + never saw its swipes
  (InputDispatcher delivered them to the overlay), and system Back reached it only while it
  held focus. As an Activity, Back/Home/Recents and gesture apps behave as on any app; if the
  paused app returns above it (e.g. from Recents), the service brings it forward again. The
  countdown bubble remains a small, non-focusable, draggable overlay. Display-over-apps
  permission stays required: it also guarantees the background activity start, while the
  accessibility-service exemption is not yet verified on a device. Notification permission is
  not needed.
- **Installed apps:** Query launchable packages through `PackageManager`, without requesting
  `QUERY_ALL_PACKAGES`. Persist package names in a profile's shared app selection; keep missing
  selections so they become active again if reinstalled.

## Time and state

- **Window boundaries:** Daily device-local windows are resolved to instants. On DST fall-back,
  prefer the later occurrence for ambiguous ends; on spring-forward, use the first valid instant
  after a gap. End is exclusive. Equal start/end is a 24-hour occurrence. Compare the stored
  occurrence key at evaluation for lazy resets instead of scheduling daily jobs. While a relevant
  app is foregrounded, a one-shot timer handles boundaries; clock/time-zone/date changes trigger
  recalculation.
- **Allowance accounting:** Foreground spans across a profile's apps contribute to every active
  covering rule's own counters. Time is stored in milliseconds; successful entries are counted
  separately. A mid-session deadline interrupts at exhaustion. Usage is committed at transitions,
  not periodically; abrupt termination loses the current visit's time. See the
  [lifecycle limitations](../../docs/public/architecture.md#boundaries).
- **After-pause grace:** A positive extension runs from the moment a finite pause clears to a
  wall-clock deadline, including while outside the covered app; re-entry before expiry is
  permitted unless an independently available time allowance runs out sooner. Zero grants only
  the current foreground entry. The anchor and current-entry grant are stored with rule usage;
  configuration changes end them without discarding consumed allowances.
- **Persistence:** Room stores profiles, profile-to-app membership, rules and per-rule usage;
  relational membership and transactional configuration edits make it a better fit than flat
  preferences. The app is unreleased and schema v1 is the current baseline; see
  [data model](data-model.md) for schema policy and entities.
- **Overlap:** Evaluate active covering rules in a pure Kotlin engine. Block > pause > allow;
  among pausing rules the longest delay supplies the message, with deterministic ties. The
  [domain model](../../app/src/main/kotlin/com/androidpause/domain/model/Models.kt) and
  [rules engine](../../app/src/main/kotlin/com/androidpause/domain/RulesEngine.kt) are the
  current API contracts, not duplicated signature sketches here.

Event-driven detection, conditional timers and no idle polling are energy-design choices, **not**
a battery measurement or a guarantee of enforcement on a particular device.
