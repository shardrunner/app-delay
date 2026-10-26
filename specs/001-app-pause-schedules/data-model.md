# Data Model: App Delay Schedules

**Feature**: 001-app-pause-schedules | **Date**: 2026-05-30 | **Updated**: 2026-09-27

Persistence: Room (SQLite), schema version 1. This app is unreleased: when entities change,
regenerate and commit `app/schemas/` without a migration, and tell existing installers to
reinstall (which removes local data). After the maintainer declares a release, change the schema
version and add a migration and device-executed migration test for schema changes. Times use
device-local `LocalTime` (windows) and epoch millis (timestamps). Android cloud backup and device
transfer include the complete database.

The domain keeps "schedule" terminology; the UI calls a schedule a **rule**.

## Entities

### Profile (config)

| Field | Type | Rules |
|-------|------|-------|
| id | Long (PK, autogen) | |
| name | String | non-empty |
| enabled | Boolean | disabled → none of its rules has any effect (FR-007) |

### ProfileApp (config, join)

Many-to-many: an app may belong to multiple profiles (FR-002, FR-014). All rules of a profile
share this selection.

| Field | Type | Rules |
|-------|------|-------|
| profileId | Long (FK → Profile.id, cascade delete) | |
| packageName | String | package id; retained even when the app is unavailable |

PK = (profileId, packageName).

### Schedule / rule (config)

| Field | Type | Rules |
|-------|------|-------|
| id | Long (PK, autogen) | |
| profileId | Long (FK → Profile.id, cascade delete) | |
| name | String | non-empty |
| windowStart / windowEnd | minutes of day | end exclusive; `end < start` crosses midnight; equality is a 24-hour occurrence (FR-003) |
| sharedBudgetSeconds | Int? | null = unused; otherwise ≥ 0, whole minutes; 0 restricts the first entry (FR-004, FR-009); at least one of time or opening allowance is required |
| openingAllowance | Int? | ≥ 0 foreground entries per occurrence; null = unused |
| pauseDelaySeconds | Int | ≥ 0, or `-1` sentinel = total block (FR-005, FR-012) |
| afterPauseBudgetSeconds | Int | ≥ 0; positive = wall-clock grace; 0 = current entry only (FR-005a, FR-013b) |
| message | String | shown on the pause screen (FR-006) |

Applies daily, every day of the week. The domain `ScheduleConfig` also carries
its profile's `enabled` state and packages, filled in when loading.

### ScheduleUsage (runtime state)

Per-rule enforcement state stored for the current window occurrence. In-progress usage may be
lost on abrupt termination; see [lifecycle limitations](../../docs/public/architecture.md#boundaries).

| Field | Type | Meaning |
|-------|------|---------|
| scheduleId | Long (PK, FK → Schedule.id, cascade delete) | one row per rule |
| windowOccurrenceKey | Long | epoch-day of the DST-resolved window start; reset trigger |
| consumedBudgetMillis | Long | covered foreground time this occurrence (FR-010, FR-011) |
| consumedOpenings | Int | successful covered foreground entries this occurrence |
| sessionPackage | String? | package of the current covered entry |
| sessionTimeGranted | Boolean | zero-extension pause cleared for the current entry |
| pauseClearedAtMillis | Long? | epoch ms when a finite pause cleared; after-pause anchor (FR-013b) |

- **Occurrence reset**: when the computed occurrence key differs from the stored one, all counters,
  the session and the extension anchor reset.
- **Leaving an app** keeps its session; the service remembers in memory when it was left.
  Returning after the re-entry grace (or when the departure was not observed, e.g. after a
  service restart) clears `sessionPackage` and `sessionTimeGranted` before evaluation.
- **Clearing a pause** admits the entry (`sessionPackage`) without counting an opening.
- **Configuration change** (profile save or delete): consumed time and openings are kept;
  extensions (`pauseClearedAtMillis`) and current-entry grants (`sessionTimeGranted`) are cleared.
- **Import** replaces all configuration; cascades remove all usage.

### Preference

String key/value rows. `warningsEnabled` controls the countdown bubble and defaults to true
when absent. `countdownLastMinuteOnly` defaults to false (entire countdown).
`reentryGraceSeconds` defaults to 300. All preferences are exported/imported; none changes the
Room schema.

### DailyStats (statistics)

Per-rule activity of one local day for the pause screen; rows older than 14 days are pruned and
never exported. PK = (scheduleId, epochDay); schedule deletion cascades.

| Field | Type | Meaning |
|-------|------|---------|
| usageMillis | Long | increases of `consumedBudgetMillis` persisted that day |
| openings | Int | increases of `consumedOpenings` |
| extensions | Int | new `pauseClearedAtMillis` anchors (cleared pauses) |
| notNow | Int | pauses left without extending, by any route except screen-off |

## Relationships

```
Profile 1 ──< ProfileApp
Profile 1 ──< Schedule 1 ──1 ScheduleUsage
                 Schedule 1 ──< DailyStats
```

## Rules engine (pure, unit-tested)

Input: package, `ZonedDateTime` now, schedules and runtime. Output: `PauseOutcome`.

1. Find enabled schedules covering the package whose DST-resolved window is active.
2. An enabled time allowance is exhausted when consumed milliseconds reach its configured seconds;
   an opening allowance is exhausted once its successful entries are used up, except during the
   already admitted entry. Either exhaustion can restrict; a current-entry time grant or unexpired
   positive extension can temporarily allow use without resetting the counters. A still-available
   time allowance retains its deadline even after opening exhaustion.
3. Restricting with infinite delay → `Block`; otherwise `Pause` unless a positive extension is
   still running. Re-entry during that extension is allowed until its wall-clock deadline,
   unless a still-available time allowance expires earlier.
4. Combine by severity `Block > Pause > Allow` (FR-014); among pauses the longest delay wins and the
   shortest extension applies.
