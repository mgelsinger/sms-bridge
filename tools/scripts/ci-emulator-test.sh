#!/usr/bin/env bash
# Runs the emulator end-to-end test while injecting texts into the emulator.
# Called by .github/workflows/android-build.yml inside android-emulator-runner.
set -uo pipefail
cd "$(dirname "$0")/../../android"

adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
# A new system image can still be starting its own apps when boot is reported.
sleep "${EMULATOR_SETTLE_S:-0}"

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

# What the system's message store holds, seen as root, without message text.
{
  echo "== build"; adb shell getprop ro.build.fingerprint; adb shell getprop ro.build.version.sdk
  echo "== default SMS app"; adb shell cmd role get-role-holders android.app.role.SMS
  adb root; sleep 5; adb wait-for-device
  echo "== databases"; adb shell "find /data -name 'mmssms.db' 2>/dev/null"
  for DB in $(adb shell "find /data -name 'mmssms.db' 2>/dev/null" | tr -d '\r'); do
    echo "== $DB"
    echo "-- sms by type, creator, restriction"; adb shell "sqlite3 $DB 'select type, creator, read_restriction, count(*) from sms group by 1, 2, 3'"
    echo "-- newest sms rows"; adb shell "sqlite3 $DB 'select _id, thread_id, type, address, creator, read_restriction from sms order by _id desc limit 12'"
    echo "-- threads"; adb shell "sqlite3 $DB 'select _id, message_count, recipient_ids, read_restriction from threads'"
    echo "-- canonical addresses"; adb shell "sqlite3 $DB 'select * from canonical_addresses'"
  done
} > ../emulator-provider.txt 2>&1 || true
exit "$status"
