# Architecture and verification

## Boundaries

| Layer | Responsibility | Source |
| --- | --- | --- |
| Compose UI | Profile/rule drafts, shared app picker, permissions, import/export | `app/src/main/kotlin/com/androidpause/ui/` |
| Domain | Active windows, shared time/opening allowances, overlapping-rule outcomes, usage accounting | `app/src/main/kotlin/com/androidpause/domain/` |
| Persistence | Room entities/DAOs, profile-to-rule mapping, settings serialization | `app/src/main/kotlin/com/androidpause/data/` |
| Android enforcement | Foreground accessibility events, deadlines, pause screen activity, countdown bubble and clock/lifecycle reactions | `app/src/main/kotlin/com/androidpause/enforcement/` |

`AppContainer` wires these together without a dependency-injection framework. Profiles own the enabled state and an app selection shared by their rules; each rule has its own window and allowance state. The domain retains schedule terminology internally. Persisted runtime state avoids treating every process restart as a fresh allowance. In-progress usage is held in memory and committed in milliseconds at transitions: leaving the app, screen-off/lock, deadlines, window boundaries, configuration and clock changes. There are no periodic checkpoints, to save battery. Orderly teardown attempts a best-effort flush, not a durability guarantee. Abrupt termination while a covered app is foregrounded loses that visit's uncommitted time; failed writes can also lose usage. These are lifecycle limitations, not anti-bypass guarantees or measured battery results.

Service transitions, teardown accounting and configuration edits share one serialization queue in
`ConfigurationChanges`: the service flushes tracked usage under the old configuration, the edit
commits, and enforcement re-evaluates the current app. There is no separate service lock or
configuration-write priority; callbacks waiting for this queue remain cancellable. Consumed time and openings are kept; running extensions and current-entry grants end.

## Deliberate tradeoffs

- **One module, explicit boundaries:** enough separation to test decisions without Android, without multiplying build modules for a personal project.
- **Event-driven foreground detection:** accessibility events plus deadline handling avoid continuous polling. Correctness still depends on Android event delivery, permission state and lifecycle behavior; low battery impact is a design goal, not a measured result. The XML event filter does not bound all framework work: active-root retrieval can trigger node prefetch and cache-maintenance events. See the [accessibility and battery assessment](accessibility-and-battery.md) for source evidence, alternatives and a measurement plan.
- **Pure decisions versus platform effects:** deterministic rule tests are fast and useful, but cannot prove pause-screen visibility or actual foreground transitions on Samsung firmware.
- **Local-time windows versus elapsed usage:** foreground accounting and countdown progress use monotonic time; daily windows follow wall-clock/time-zone changes. A boundary is mapped into elapsed time to clip a span. After-pause extensions deliberately retain wall-clock semantics. Clock changes, overnight boundaries, delayed event delivery, lock/unlock and process recreation deserve separate tests.
- **Editor transactions:** a ViewModel owns drafts and persistence; saved-state snapshots retain navigation and raw input across recreation. Completed edits of a valid profile auto-save, and the saved profile is reloaded so new rules keep their IDs; each save ends running extensions (FR-007b), so saving is not per keystroke. Discarding invalid rule edits does not parse them. An in-flight action guard prevents duplicate writes and editing during persistence.
- **Permission status:** the profile ViewModel owns one snapshot of accessibility and overlay grants for the profile list and permission screen. Entry/resume checks are independent of configuration writes and catalog loading. Grants do not verify a live service connection.
- **Cooperative enforcement:** users can disable permissions or profiles. There is no device-owner provisioning, anti-tamper layer or attempt to prevent intentional bypass.
- **Narrow compatibility:** API 36 minimum keeps platform branches small. AndroidX versions are constrained by compile SDK 36; expanding compatibility or raising SDK levels is a separate engineering decision.
- **Release optimization:** R8 and resource shrinking are enabled. A successful debug build is not a substitute for compiling and exercising the minified release.

## Known limitations

- **Re-entry grace is in memory:** departure times are not persisted. After a service restart,
  entries left before it end, so a return counts as a new opening.
- **Bubble position** lasts only while the accessibility service runs; reboots reset it.
- **Statistics attribution:** usage is recorded when it is persisted (on leaving, deadlines,
  boundaries), so a visit crossing midnight counts on the later day. Statistics are per rule,
  kept 14 days and lost on reinstall (never exported).
- **In-app browser tabs** are recognized by a `.customtabs.` activity package (Chromium-based
  browsers, Firefox). Other browsers' in-app tabs count as the browser. Links that apps open as
  a plain browser intent open the full browser and are paused with it. Installed web apps that
  run as Trusted Web Activities use the same tab activity and are therefore never covered.
- **Pause screen start:** the pause activity appears after the covered app's window event, so the
  app can be visible briefly; display-over-apps permission stays required to guarantee the
  background activity start.

## Settings export format

Exports use `formatVersion: 1` with `profiles` and `preferences`. Profiles contain name, enabled, package strings and rules. Rules contain name, `windowStart`/`windowEnd` (minute precision), nullable `timeBudgetSeconds` (a multiple of 60), nullable `openingAllowance`, nullable `pauseDelaySeconds` (null = infinite block), `afterPauseBudgetSeconds` and message. At least one allowance is required. Database IDs, usage, statistics and permission grants are never exported.

On import, names and packages are trimmed and duplicate packages removed; message text is preserved. Missing profile `enabled` and `warningsEnabled` default to true, a missing `openingAllowance` to null. `warningsEnabled` controls the bubble; `countdownLastMinuteOnly` defaults to false (entire countdown); `reentryGraceSeconds` (non-negative) defaults to 300. Unknown additive fields are ignored; invalid required values and other format versions are rejected. Import shows a preview, then replaces all configuration and resets usage in one transaction.

## Test strategy and limits

JVM tests in `app/src/test/` cover rule evaluation, overlapping windows, clock changes, usage accounting, opening allowances, draft validation and selected enforcement helpers. These are the fast feedback loop for state-machine decisions.

Instrumented tests in `app/src/androidTest/` cover profile CRUD and cascades, replacement rollback/reset, atomic preference writes, ViewModel permission refresh and successive autosave readback, portable settings, daily statistics, pause screen show/Back/dismiss and event classification. They need an attached device/emulator. Compiling these tests only checks that their code builds; it proves neither persistence correctness at runtime nor full end-to-end permission and overlay behavior. The unreleased app starts at Room schema v1 without migrations; the exported schema in `app/schemas/` is the baseline for future migrations. Automated cross-app accessibility-service end-to-end coverage is still missing.

Lint checks Android issues; ktlint checks formatting; detekt checks Kotlin static-analysis rules. They do not certify UX, privacy, energy use or device compatibility. See the [canonical commands and report locations](development.md).

### Pending manual acceptance on Samsung Galaxy S24

Record build commit, Android/One UI version and actual result for each check; no pass is asserted here.

- First install, restricted settings, accessibility/overlay grants, denial and later revocation.
- Shared app selection; auto-save/discard, duplication, rule/profile enabling, rotation and activity recreation.
- Time-only, opening-only and combined allowances; switching covered apps; overlapping and overnight rules.
- Mid-session exhaustion, pause/exit/extension, infinite block and optional countdown bubble: entire-countdown/default and last-minute-only modes, `3x · 12m` above one minute, opening-only `3x`, timer at/below one minute, orange/red warning levels, and no bubble on Home/unrelated apps. Verify it can be dragged, touches beside it reach the app, and lock/disconnect removes it.
- Window end, wall-clock/time-zone changes, lock/unlock, process restart and reboot.
- Import replacement/reset and export round trip; OS backup/transfer on available devices.
- Pause screen: system Back, Home, Recents and One Hand Operation + side gestures leave it; Not now
  shows the weekly count; Extend returns to the app's previous state; the app returning from
  Recents brings the pause screen back; stats for today/this week update.
- Re-entry grace: returning within the grace (e.g. from a browser link) keeps the opening; after
  it, or with Off, a new opening counts.
- Covered browser: opening it directly pauses; a link opened in its in-app tab from another app
  does not, including after screen off/on; "Open in browser" from the tab pauses.
- Large text, TalkBack, readable pause screen and navigation away from a pause.
- Signed minified APK behavior, observed timing and battery impact over normal use.

Real screenshots and a short demonstration should come from these runs, with private app selections/messages redacted. They are maintainer follow-up assets, not generated proof of correctness.
