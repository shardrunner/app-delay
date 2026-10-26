<!-- SPECKIT START -->
Active design: `specs/001-app-pause-schedules/plan.md` (requirements in `spec.md`,
persistence in `data-model.md`).
<!-- SPECKIT END -->

## Steering

- Personal, cooperative-use app: solve normal-use problems simply, not adversarial bypasses.
- Keep enduring behavior in `specs/001-app-pause-schedules/`, user/maintainer guidance in
  `docs/public/`, and only living upgrade notes in `docs/ai/`. Do not retain dated handoffs or
  completed task ledgers.
  Build, testing and export details: `docs/public/development.md` and `architecture.md`.
- Unreleased Room schema remains v1: regenerate `app/schemas/` after entity changes and warn that
  existing installs must be reinstalled. After the maintainer declares a release, add a version
  bump, migration and device test (see `data-model.md`).
- Prefer an emulator for instrumentation and pin `ANDROID_SERIAL`. Before running selected tests,
  check their data/permission effects; after a connected run, verify the app remains installed.
  Never clear, uninstall, reset permissions or wipe a personal device without explicit consent.
  Do not override the APK-retention settings in `gradle.properties`; on install failure stop and ask.
