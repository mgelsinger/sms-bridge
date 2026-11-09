#!/usr/bin/env bash
# Runs the emulator end-to-end test while injecting texts into the emulator.
# Called by .github/workflows/android-build.yml inside android-emulator-runner.
set -uo pipefail
cd "$(dirname "$0")/../../android"

adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0

# A text every 5 seconds for up to 20 minutes, so one lands after the test is listening.
(
  for i in $(seq 1 240); do
    sleep 5
    adb emu sms send 5551234 "hello from ci $i" >/dev/null 2>&1 || true
  done
) &
injector=$!

./gradlew --no-daemon connectedDebugAndroidTest 2>&1 | tee ../emulator.log
status=${PIPESTATUS[0]}
kill "$injector" 2>/dev/null || true

adb logcat -d -s SmsBridgeTest:I SmsBridge:I AndroidRuntime:E TestRunner:E > ../emulator-logcat.txt 2>&1 || true
exit "$status"
