# App Delay

> **Disclaimer:** This project is vibe coded for personal use. Use at your own risk.

A personal Android tool for adding a deliberate pause before distracting apps—not a lock-down system. Built for cooperative use: you remain in control of permissions and settings.

## What it does

- Organizes **profiles** with a shared app selection and multiple time-window rules.
- Supports shared foreground-time budgets and opening allowances across selected apps, including overnight windows.
- Shows a pause with a custom message when an allowance is exhausted; supports timed pauses, extensions and blocking until the window ends.
- Applies the most restrictive applicable rule when rules overlap.
- Offers profile duplication, an optional draggable countdown bubble, and JSON settings import/export.

**Android 16 (API 36) or newer is required.**

## Try it

[Installation and setup](docs/public/install.md) explains evaluation APKs, sideloading, permissions and a first profile. The `evaluation-debug-apk` artifact from a successful CI run is the evaluation download path. This is a **debug build**, not a signed stable release. No published stable release is claimed here.

For source builds, use [Build, tests and releases](docs/public/development.md).

## Engineering overview

A single-module Kotlin app uses Jetpack Compose for configuration, Room for persistent configuration/runtime state, and an accessibility service plus a pause-screen activity for foreground enforcement. Pure Kotlin rule evaluation is separated from Android lifecycle and permission handling. The engineering focus is state modeling, time-based decisions, persistence, and OS integration.

[Architecture and verification](docs/public/architecture.md) covers boundaries, tradeoffs and what tests can—and cannot—prove.

For outstanding device checks and known limitations, see the
[pending acceptance checklist](docs/public/architecture.md#pending-manual-acceptance-on-samsung-galaxy-s24),
[known limitations](docs/public/architecture.md#known-limitations) and
[test caveats](docs/public/development.md#test-caveats).
Compilation alone does not establish device behavior.

## Privacy

The app declares **no Internet permission** and has no account or app-operated backend. Accessibility observes foreground package/window information; its system permission can access window content. Configuration and usage state live locally, but Android **cloud backup and device transfer are allowed**. Exported settings contain personal app selections, schedules and messages—treat exports as private. See [Privacy and permissions](docs/public/privacy.md).

## License

[MIT](LICENSE) · Copyright © 2026 Michael Brunner
