# SDK and dependency constraints

Living upgrade notes; [version catalog](../../gradle/libs.versions.toml) is the source of
current pins. Verify upstream requirements again before upgrading rather than treating a
previous "latest" list as evergreen.

- The current device target and `minSdk`/`targetSdk`/`compileSdk` are API 36. AndroidX Core,
  Lifecycle and Compose BOM versions in the catalog are held below lines that first required
  `compileSdk 37` when checked in August 2026. A dependency with that requirement fails with
  `requires libraries and applications that depend on it to compile against version 37 or later
  of the Android APIs`. Raising `compileSdk` does not itself opt into a new `targetSdk` runtime;
  reconsider all three dependencies and platform availability together when upgrading.
- Kotlin and KSP versions must remain compatible for Room code generation. Before lifting the
  Kotlin 2.3.21 / KSP 2.3.11 pins, check for a matching KSP release rather than assuming one
  exists.
- detekt is pinned to a 2.0.0 alpha; upgrade it separately to isolate rule changes.

Device-test safety and build commands live in [development](../public/development.md), not in
historical test-result notes here. The app is unreleased and has **no Room migration tests**;
see the [schema policy](../../specs/001-app-pause-schedules/data-model.md).
