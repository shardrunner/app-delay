# Install and configure

## Get an evaluation APK

Requires Android 16 / API 36 or newer. The current manual-test device is a Samsung Galaxy S24; testing is ongoing, not certification.

1. In this repository's **Actions → Android CI**, choose a successful run of a trusted default-branch commit. Confirm the commit before downloading; pull-request artifacts can contain contributor changes.
2. Download `evaluation-debug-apk` from the run's artifacts (GitHub may require sign-in), unzip it, and transfer `app-debug.apk` to the phone. Artifacts expire after 14 days; if none is available, [build from source](development.md).
3. Open the APK. Android may ask you to allow installation from that browser or file manager under **Install unknown apps**. Grant only to the source you trust, install, then revoke that installation permission.

This APK is debuggable and signed with an automatically generated development key. It is **not a stable release**. CI runs may use different debug keys, so an update can fail with a signature mismatch. Debug and release builds use the same application ID (`com.androidpause`) and cannot coexist. Export settings before uninstalling to switch builds; uninstalling deletes local data, and import resets usage. Do not mistake `app-release-unsigned.apk` for an installable APK.

**Unreleased database changes require a reinstall.** The current Room v1 schema stores an unused
time allowance as null, replacing the earlier enabled flag plus amount. Existing installations
with that older schema cannot be upgraded in place. Export settings using the old build before
reinstalling; uninstalling removes local usage and statistics. JSON v1 exports remain compatible,
but importing them resets usage. Do not restore an old-schema database backup into this build.

Samsung Auto Blocker, device management or other OS protections may prohibit sideloading. Follow your device's current official instructions and organizational policy; do not disable protections just to try this app. Installation and settings labels vary across One UI versions.

## Permissions

Open App Delay and use its permission/setup controls, or find these in system Settings:

1. **Accessibility → Installed apps/services → App Delay:** enable foreground detection. Review Android's sensitive-access warning before proceeding.
2. **Apps → Special access → Appear on top / Display over other apps → App Delay:** lets the pause screen open over covered apps and shows the countdown bubble.
3. **App Delay → Settings:** the optional floating countdown bubble replaces notifications and uses overlay access. It appears on covered foreground apps with an active timer or opening limit, for the entire countdown by default; enable **Last minute only** to shorten this. It shows remaining openings and whole minutes (e.g. `3x · 12m`), then `m:ss` in the final minute, turning orange on the last opening or within five minutes and red when no opening is left or in the final minute. Drag it aside if it covers something. Accessibility and overlay access are both required for enforcement.

For sideloaded apps Android may disable accessibility with a **Restricted setting** message. On supported devices, **Settings → Apps → App Delay → ⋮ → Allow restricted settings** permits you to return to Accessibility and enable the service, sometimes after authentication. Only do this for a build you trust. The option is not guaranteed to appear: install source, Android/One UI version and managed-device policy affect it. Consult current [Android restricted-settings guidance](https://support.google.com/android/answer/12623953) if your UI differs. App Delay cannot grant these permissions itself.

See [privacy and permission details](privacy.md). To stop enforcement, disable the profile or accessibility service; this is a cooperative tool, not tamper-proof parental control.

## First profile

1. Choose **Add profile**, name it, and select the apps it covers. That selection is shared by all rules in the profile.
2. Add a rule and choose its local-time window (overnight windows are supported).
3. Configure a time allowance, opening allowance, or both. Allowances are shared across covered apps **within that rule**, not a separate budget for every app. Separate rules keep separate allowance state.
4. Choose the pause delay, any after-pause time and a message; an infinite pause blocks until the applicable restriction ends. Changes save automatically once the profile has a name, an app and a rule.
5. For an initial check, use a short window and small allowance on a nonessential app. Open it, exhaust the allowance, and check the pause, exit and extension behavior. Do not treat this one check as device validation.

When rules overlap, any exhausted applicable rule can restrict access. Opening allowances measure foreground entries, not taps on a launcher icon; app switching and system windows need real-device testing.

Settings offers JSON export and import. Import previews changes and requires confirmation before replacing profiles and resetting usage. Exports do not grant Android permissions or preserve runtime usage. Keep a private export before experimenting with builds.

## If pauses do not appear

Check the profile is enabled, the current local time is inside a rule's window, the intended app is selected, and an allowance is actually exhausted. Recheck accessibility and overlay access after installation, restart or OS changes. If behavior remains inconsistent, record Android/One UI version, build commit and reproducible steps without sharing private exports or logs publicly. Background/lifecycle behavior on the S24 is still being tested.
