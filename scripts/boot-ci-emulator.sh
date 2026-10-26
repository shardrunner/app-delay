#!/usr/bin/env bash
# CI-only startup: create and discover the AVD before waiting for Android.
set -euo pipefail

: "${ANDROID_HOME:?ANDROID_HOME must point to the CI SDK}"
: "${ANDROID_AVD_HOME:?ANDROID_AVD_HOME must point to an isolated CI directory}"
: "${ANDROID_SERIAL:?ANDROID_SERIAL must select the CI emulator}"

avd_name=persistence-api36
boot_timeout=${EMULATOR_BOOT_TIMEOUT_SECONDS:-360}
[[ "$boot_timeout" =~ ^[1-9][0-9]*$ ]] || {
  echo 'EMULATOR_BOOT_TIMEOUT_SECONDS must be a positive integer' >&2
  exit 1
}
mkdir -p "$ANDROID_AVD_HOME" ci-diagnostics

fail() {
  echo "::error::$*" >&2
  if [[ -f ci-diagnostics/emulator.log ]]; then
    tail -n 200 ci-diagnostics/emulator.log >&2
  fi
  exit 1
}

printf 'ANDROID_AVD_HOME=%s\n' "$ANDROID_AVD_HOME" | tee ci-diagnostics/avd-home.txt
printf 'no\n' | avdmanager create avd --name "$avd_name" \
  --package 'system-images;android-36;google_apis;x86_64' \
  2>&1 | tee ci-diagnostics/avd-creation.log
[[ -f "$ANDROID_AVD_HOME/$avd_name.ini" ]] || \
  fail "AVD definition is missing: $ANDROID_AVD_HOME/$avd_name.ini"
"$ANDROID_HOME/emulator/emulator" -list-avds | tee ci-diagnostics/avd-list.txt
grep -Fxq "$avd_name" ci-diagnostics/avd-list.txt || \
  fail "Emulator cannot discover $avd_name in $ANDROID_AVD_HOME"

nohup "$ANDROID_HOME/emulator/emulator" "@$avd_name" \
  -port 5554 -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swiftshader_indirect > ci-diagnostics/emulator.log 2>&1 &
emulator_pid=$!
deadline=$((SECONDS + boot_timeout))
while (( SECONDS < deadline )); do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then
    exit_code=0
    wait "$emulator_pid" || exit_code=$?
    fail "Emulator exited before boot completed (exit code $exit_code)"
  fi
  # Offline ADB is normal during startup. Bound each probe so process death
  # cannot be hidden behind an indefinitely blocking wait-for-device.
  boot_completed=$(timeout 5 adb -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') || boot_completed=
  if [[ "$boot_completed" == 1 ]]; then
    kill -0 "$emulator_pid" 2>/dev/null || fail 'Emulator exited at boot completion'
    echo "Emulator boot completed: $ANDROID_SERIAL"
    exit 0
  fi
  sleep 2
done
fail "Emulator boot timed out after $boot_timeout seconds"
