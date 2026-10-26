# App Delay implementation plan

**Status:** Current design map, not a task ledger. Requirements: [spec.md](spec.md).

## Approach

A single-module Kotlin/Compose Android app (API 36 minimum and target) adds cooperative friction
for selected apps. A profile owns enabled state and one app selection; its rules (`ScheduleConfig`
in code) each own a daily local-time window, optional shared time/opening allowances (at least
one), pause delay, after-pause extension and message. When multiple rules cover an app, the most
restrictive outcome wins. Settings provides countdown bubble controls and portable configuration export/import.

| Boundary | Implementation and reason |
| --- | --- |
| Pure domain | `app/src/main/kotlin/com/androidpause/domain/` holds window resolution, usage and outcome decisions. JVM tests exercise it without Android. |
| Persistence | `data/` uses Room v1 for profiles, app membership, rules and per-rule runtime; `ConfigurationChanges` coordinates edits with enforcement. [Data model and schema policy](data-model.md). |
| Platform | `enforcement/` receives foreground accessibility events, manages deadlines, an optional draggable countdown bubble and starts the Compose pause screen as its own `PauseActivity` task. No continuous idle polling; permissions and lifecycle limit reliability. |
| UI | `ui/` edits profile/rule drafts, shows status and settings, handles import/export and renders the pause screen. |

`AppContainer` wires the packages without a DI framework. Keep pure decisions separate from
Android effects rather than adding Gradle modules for a personal app. AndroidX, Compose, Room and
coroutines supply the required platform functionality; new dependencies need clear value.
JDK 17 compiles the app; Gradle runs on a separate configured runtime (see
[build guide](../../docs/public/development.md)).

## Navigation and remaining work

- [Requirements and acceptance goals](spec.md) define behavior; [data model](data-model.md)
  defines ownership, usage, and pre-release schema policy.
- [Research](research.md) records technical decisions and alternatives. The current internal
  contracts are the code in `domain/RulesEngine.kt`, `domain/model/Models.kt`, `data/ProfileStore.kt`
  and `enforcement/PauseAccessibilityService.kt`; do not treat old design sketches as literal APIs.
- [Public architecture](../../docs/public/architecture.md) documents tradeoffs, export format,
  test coverage limits and the **pending** Galaxy S24 manual acceptance checklist.
  [Development](../../docs/public/development.md) is the only maintained build/test procedure.
- [User enhancement backlog](../../USER_ENHANCEMENTS.md) tracks requested additions, separately
  from completed implementation work. Git history retains the original task/checklist ledger.

No elapsed-battery measurement or complete device certification is claimed by this plan. Do not
infer those outcomes from earlier checked tasks or successful compilation.
