# Build, tests and releases

This is the canonical build guide. Use the committed Gradle wrapper; do not substitute a system Gradle installation.

## Tooling

| Component | Project / CI selection |
| --- | --- |
| Gradle wrapper | 9.7.1 |
| Android Gradle Plugin | 9.3.1 |
| Gradle runtime | CI: Temurin 25.0.2+10.0.LTS |
| Java/Kotlin compilation toolchain | Java 17; CI: Temurin 17.0.18+8 |
| Android SDK | Platform 36, Build Tools 36.0.0, platform-tools |
| CI SDK command-line tools | 13114758 |
| Device/emulator | Android 16 / API 36 or newer |

The wrapper verifies `gradle-9.7.1-bin.zip` with SHA-256 `acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a`. This matches the [official distribution checksum](https://services.gradle.org/distributions/gradle-9.7.1-bin.zip.sha256), fetched and verified against the downloaded distribution before recording the checksum setting.

The compilation target is **not** necessarily the JDK running Gradle. `app/build.gradle.kts` selects `jvmToolchain(17)` and Java 17 compatibility. Earlier local verification used Android Studio's JetBrains JDK 25; CI follows that runtime major and separately installs Java 17. This historical tooling evidence is not validation of the current integration or a claim about the minimum supported JDK.

Install both JDKs and set `JAVA_HOME` to JDK 25 or newer (JDK 27 has also passed the canonical verification). Make JDK 17 discoverable by Gradle, for example by setting `JAVA_HOME_17_X64` to its installation directory and adding `org.gradle.java.installations.fromEnv=JAVA_HOME_17_X64` to your user-level `~/.gradle/gradle.properties`. Android Studio's **Gradle JDK** setting should likewise select the runtime JDK, not just the language target.

Install the SDK packages with Android Studio's SDK Manager or, after accepting Android SDK licenses:

```bash
sdkmanager 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0'
```

Point `ANDROID_HOME` at your SDK, or add `sdk.dir=/absolute/path/to/Android/Sdk` to the gitignored root `local.properties`. Never commit this file. Dependencies and SDK components require network access on first build; the workflow pins key inputs but does not promise offline or bit-for-bit reproducible builds.

## Canonical verification

From the repository root:

```bash
./gradlew --no-daemon --stacktrace --continue \
  :app:testDebugUnitTest :app:lintDebug ktlintCheck detekt \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

- JVM test reports: `app/build/reports/tests/testDebugUnitTest/`; XML results: `app/build/test-results/`.
- Static analysis reports: under `app/build/reports/` (and `build/reports/` where applicable).
- Evaluation APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Without signing configuration, release output is `app/build/outputs/apk/release/app-release-unsigned.apk`. This exercises R8/resource shrinking but **is not installable**.
- Instrumentation compilation builds test APKs; it does not run tests or verify enforcement on a device.

For connected tests, prefer an API 36 emulator. Inspect the selected test classes first,
verify the serial with `adb devices`, then target **only** that emulator; for example:

```bash
export ANDROID_SERIAL=emulator-5554  # replace with your emulator's actual serial
if [[ "$(adb -s "$ANDROID_SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
  echo "Selected device is not an emulator; stopping" >&2
  exit 1
fi
./gradlew :app:installDebug || exit 1
./gradlew -Pandroid.testInstrumentationRunnerArguments.class=com.androidpause.ProfileStoreInstrumentedTest,com.androidpause.ProfileViewModelInstrumentedTest,com.androidpause.SettingsBackupInstrumentedTest,com.androidpause.BackupPersistenceInstrumentedTest :app:connectedDebugAndroidTest
adb -s "$ANDROID_SERIAL" shell pm path com.androidpause | grep -q '^package:' || {
  echo "App Delay is no longer installed; investigate before continuing" >&2
  exit 1
}
```

Do not run the entire connected suite as an initial check: it includes permission-changing tests.
The selected persistence tests use private in-memory databases, but installation still updates
an existing debug APK. Stop if installation or the emulator check fails.

### Personal-device safety

`gradle.properties` sets `android.injected.androidTest.leaveApksInstalledAfterRun=true`
and `android.experimental.testOptions.uninstallIncompatibleApks=false`. Connected tests
therefore retain the app and test APKs after execution; an incompatible installed APK must
cause installation to fail rather than be uninstalled. Do not override these settings on a
personal phone. If installation fails, stop—do not use uninstall or `pm clear` as a workaround.

Instrumentation still installs/updates the debug APK and runs code with access to app data.
Inspect the chosen tests before using a personal device; APK retention does not prevent test
code from changing data or permissions. Use an emulator for destructive tests. In particular,
`EnforcementInstrumentedTest` grants overlay access, so running the entire suite is not a
read-only operation. After a connected run, check
`adb -s "$ANDROID_SERIAL" shell pm path com.androidpause` and confirm the app remains installed. Never uninstall, clear data, reset permissions or wipe a
personal device without explicit confirmation.

Connected-test reports appear under `app/build/reports/androidTests/connected/`. Review
[architecture and verification](architecture.md) before interpreting results.

### Test caveats

Before running UI tests, disable App Delay's accessibility service on the emulator: with it
enabled, UI Automator can see a null or stale window hierarchy. An emulator whose app data came
from an older unreleased schema crashes with a Room identity error; clear that emulator's app
data (with consent) before interpreting UI tests.

The suite does not exercise the accessibility service end to end; enforcement relies on the
[manual checklist](architecture.md#pending-manual-acceptance-on-samsung-galaxy-s24) and
[accessibility and battery](accessibility-and-battery.md) notes.

## CI and evaluation distribution

[Android CI](../../.github/workflows/android.yml) runs on pull requests, pushes to `main`/`master`, and manual dispatch. Actions are commit-pinned, repository permissions are read-only, and no release credentials are used. One job runs the verification command, uploads reports even on failure, and uploads the debug APK only after successful verification. Artifact retention is 14 days. There is no automatic GitHub Release publication.

A separate `persistence` job boots a fresh API 36 Google APIs x86_64 emulator on a hosted Ubuntu runner, selects only `emulator-5554`, and checks boot completion, API level and device isolation before installation. It uses the same Java/SDK setup and unchanged APK-retention settings. Installation failures stop test execution; an always-run retention check follows any installation attempt. Connected reports/results and emulator diagnostics are uploaded even on failure.

AVD creation and emulator launch share an explicit `ANDROID_AVD_HOME` under the runner's
temporary directory. `scripts/boot-ci-emulator.sh` verifies the definition file and native
emulator discovery before launch, then monitors process liveness while polling boot completion
with bounded ADB probes. An exited emulator fails startup with its log instead of waiting for
the six-minute boot deadline. Diagnostics include the shared directory, creation output, AVD
listing and emulator log. Startup regression tests use fake SDK tools and do not access devices;
CI runs them in `verify`, and they can be run locally with:

```bash
python3 -m unittest discover -s scripts/tests -v
```

The job runs only this persistence allowlist (not enforcement/navigation or permission-changing tests):

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew --no-daemon --stacktrace \
  -Porg.gradle.java.installations.fromEnv=JAVA_HOME_17_X64,JAVA_HOME_25_X64 \
  -Pandroid.testInstrumentationRunnerArguments.class=com.androidpause.ProfileStoreInstrumentedTest,com.androidpause.ProfileViewModelInstrumentedTest,com.androidpause.SettingsBackupInstrumentedTest,com.androidpause.BackupPersistenceInstrumentedTest \
  :app:connectedDebugAndroidTest
```

These tests use private in-memory databases; ViewModel tests supply permission snapshots without
changing OS grants or launching activities. Check the latest CI run for emulator boot, hosted-runner runtime and instrumentation results. A successful persistence job does not establish enforcement or personal-device validation. Official SDK catalogs currently list the selected API 36 image and Linux emulator; SDK package revisions can change between runs.

Download the `evaluation-debug-apk` artifact from a successful trusted run. Debug signing is only for evaluation; keys may differ between fresh runners and prevent in-place updates. See [installation](install.md) before switching builds. The workflow deliberately does not distribute the unsigned release output.

## Maintainer: produce a signed release locally

This is a manual process, not evidence that a release already exists.

1. Select and record the intended commit. Run verification and real-device checks. Before a subsequent public update, ensure `versionCode` is increased in the source build configuration (currently 1, version name `0.1.0`).
2. Use a maintainer-owned keystore stored outside the repository, with a secure backup. Keep the same signing identity for updates. Do not upload it or its passwords as Actions artifacts or include it in bug reports.
3. Add the existing supported configuration to root `local.properties` (gitignored). Use an absolute path; preserve any `sdk.dir` entry:

   ```properties
   release.storeFile=/absolute/path/to/release.jks
   release.storePassword=<private store password>
   release.keyAlias=<private key alias>
   release.keyPassword=<private key password>
   ```

   Java properties escaping applies, especially for backslashes. All four entries are required once `release.storeFile` is present; incomplete configuration fails rather than silently falling back to unsigned output. Avoid exposing this file in terminal captures.

4. Run `./gradlew :app:assembleRelease`. With valid signing configuration the expected output is `app/build/outputs/apk/release/app-release.apk`.
5. Verify the actual APK before installation/distribution:

   ```bash
   "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs \
     app/build/outputs/apk/release/app-release.apk
   sha256sum app/build/outputs/apk/release/app-release.apk
   ```

6. Test this signed, minified APK on the target device, including an update from the previous signed version when one exists. Debug-to-release switching normally requires export/uninstall/reinstall because signatures differ.
7. Only after validation, manually prepare a release with the signed APK, checksum, source commit, version, tested device/OS and known limitations. Archive R8 mapping privately (`app/build/outputs/mapping/release/mapping.txt`) for diagnostics. Never label an unsigned APK or untested debug artifact as stable.

Release credentials, key generation and publishing are intentionally outside CI. Maintainer-owned keys, real screenshots/demo captures, and completed device checks are still required for a public stable release.
