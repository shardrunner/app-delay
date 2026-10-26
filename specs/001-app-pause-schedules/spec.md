# App Delay: profiles and rules

**Status:** Current product requirements. Android 16 (API 36) or newer; personal, cooperative use.

App Delay adds friction to selected apps during daily local-time windows. The UI calls each
schedule a **rule**; code and persistence use **schedule** (`ScheduleConfig`, `schedules`).
This document specifies intended behavior, not proof of device validation. See
[architecture and pending acceptance](../../docs/public/architecture.md) for evidence and limits.

## Configuration

- **FR-001, FR-002, FR-007, FR-015:** Users can create, edit, delete and enable/disable
  locally saved profiles. A profile owns its name, enabled state and one selection of app package
  names shared by all its rules. Rules have no independent enabled flag or app list. One app can
  belong to multiple profiles; disabled profiles do not enforce anything. Missing apps remain
  selected, marked Unavailable and removable; reinstalling the package restores coverage.
- **FR-007c:** Editing auto-saves: a valid profile (name, at least one app and one rule) is saved
  when an edit completes (leaving the app picker, a rule or the profile; deleting or duplicating
  a rule; toggling enabled), not per keystroke. Leaving a rule keeps valid edits. Leaving a rule
  with invalid values or a profile that cannot be saved asks before discarding.
- **FR-007a:** Duplicating a saved profile opens an unsaved draft with copied apps and rules,
  new IDs, name suffixed ` (copy)`, `enabled = false`, and no copied runtime usage.
- **FR-003:** Each rule has a name and one daily local-time window, with an exclusive end.
  Overnight windows cross midnight; equal start and end denote a 24-hour window. No weekday
  selection. An occurrence starts with fresh time and opening allowances.
- **FR-004:** A rule has a shared foreground-time allowance (whole minutes, including zero), an
  opening allowance (successful foreground entries, including zero), or both. At least one is
  required. The selected apps share *each rule's* allowance; different rules keep separate usage.
- **FR-005, FR-005a, FR-006:** Each rule configures a finite pause delay (including zero) or
  an infinite block, a nonnegative after-pause duration, and a custom pause message.
- **FR-007b:** Saving or deleting configuration retains consumed time and openings of surviving
  rules, but ends running after-pause extensions and current-entry grants. Import has different
  replacement semantics (below).

## Enforcement

- **FR-008–FR-011:** While a profile is enabled and a rule window is active, foreground time
  across its selected apps counts toward that rule's time allowance. The pause screen itself
  does not count. The first N successful foreground entries use an opening allowance of N;
  switching selected apps starts another entry. Duplicate events, transient keyboard/system
  windows and other packages' non-activity windows (popups, gesture pads, edge panels) preserve
  an entry. A browser's in-app tab opened by another app (Custom Tab, e.g. Chrome's
  `customtabs.CustomTabActivity`) is not the browser: covering a browser pauses only the
  browser itself, and an in-app tab is an uncovered foreground. Leaving for another app, Home/Overview or locking ends it unless the user returns to
  the same app within the re-entry grace (Settings; default 5 minutes, measured from leaving;
  Off ends it at once). Entries admitted by clearing a pause are not openings. If
  either configured allowance is exhausted, the rule can pause or block, including mid-session
  when a time allowance runs out. An allowance of zero restricts the first applicable entry.
  A still-available time allowance retains its deadline even after a pause for exhausted
  openings is cleared.
- **FR-003a, FR-016:** End of window immediately ends that rule's enforcement and
  dismisses its pause screen unless another active rule still restricts the app. Windows follow the
  device's current time zone and DST; clock/time-zone/date changes cause re-evaluation. The same
  daily window resets both allowances at its next occurrence.
- **FR-012, FR-013, FR-013a, FR-013b, FR-017:** Infinite delay never offers a proceed action.
  Finite delay enables Extend only after it elapses. Clearing a finite pause grants a positive
  after-pause extension until its *wall-clock deadline*, even if the user leaves and re-enters
  a covered app; if one is foregrounded when it expires, re-pause. With both allowances,
  an independently available time allowance may expire sooner and interrupt the extension.
  A zero extension grants only the current foreground entry and re-pauses on the next entry.
  Leaving an uncleared pause does not grant access; relaunching restarts its delay. System
  Home/Overview and gesture apps (e.g. One Hand Operation +) work as on any app; "Not now"
  (Abort) and Back on the pause screen go Home.
- **FR-014:** Among active covering rules the most restrictive outcome wins:
  block > pause > allow. Among pausing rules the longest delay wins, with its message and a
  deterministic tie break. An active block overrides all finite pauses.
- **FR-006a, FR-013c:** The pause screen shows the effective rule's message, its usage (openings
  used of allowed; screen time in minutes, the exhausted value(s) in red) and window end, and
  that rule's local statistics for today and the current locale week: pauses left without extending ("Not now"),
  extensions, covered screen time and openings. "Not now" (Abort) is always available and
  briefly confirms the week's count before going Home; Extend shows the delay as `m:ss` and is
  disabled until permitted. Statistics are derived from persisted usage changes, kept 14 days
  and never exported.
  While a covered app stays foregrounded with an upcoming interruption or an active
  opening-limit rule, an optional opaque floating bubble, draggable for the service's lifetime
  (other touches reach the app), shows remaining openings (e.g. `3x`, excluding the current
  entry, lowest across active covering rules) beside the earliest countdown: whole minutes
  rounded up above one minute (e.g. `3x · 12m`), `m:ss` rounded up at one minute and below.
  It turns orange with one opening left or within five minutes, red with none left or in the
  final minute. Default: entire countdown; Settings can limit it to the final minute, which
  also hides opening counts until then. Hide on leaving the app, lock/screen-off, pause/block
  screen, service disconnect, or a reached deadline. Bubble replaces notifications.
- **FR-018, FR-018a, FR-019:** Missing accessibility or overlay permission is visible in the
  app; the bubble also requires overlay access, not notification permission. Use events and active-session deadlines,
  not continuous high-power polling. Low battery impact is a goal, not an established result.

## Settings and data

Settings exposes permission status, bubble enablement and last-minute-only preference, re-entry
grace, installed version, and
portable JSON export/import. Export format v1 contains configuration and preferences, not usage,
IDs or Android permissions. Import previews changes, requires explicit confirmation, and
transactionally replaces configuration **and resets usage**; unsupported format versions fail.
See [export format](../../docs/public/architecture.md#settings-export-format),
[data model](data-model.md) and [privacy/OS backup](../../docs/public/privacy.md).

## Acceptance goals (not yet device-certified)

- A launch under an exhausted rule shows the pause screen within 1 second in at least 95% of
  observed launches; no missed enforcement for covered launches once the required permissions
  and service are active. An infinite block never offers a path through the pause screen.
- Combined foreground time and openings are counted per rule without double counting; window
  reset, overlap and mid-session deadlines behave as described above.
- A first profile can be configured in under three minutes. Idle battery impact is negligible
  under normal use, to be measured rather than inferred from event-driven design.

The app is local to one device/user: no account, remote management or anti-tamper promises.
See the [manual acceptance checklist](../../docs/public/architecture.md#pending-manual-acceptance-on-samsung-galaxy-s24)
for OS-specific scenarios not established by JVM tests.
