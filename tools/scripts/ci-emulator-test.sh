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

# What the system's message store holds, seen as root, without message text.
{
  echo "== build"; adb shell getprop ro.build.fingerprint; adb shell getprop ro.build.version.sdk
  echo "== default SMS app"; adb shell cmd role get-role-holders android.app.role.SMS
  adb root; sleep 5; adb wait-for-device
  DB=/data/user_de/0/com.android.providers.telephony/databases/mmssms.db
  echo "== sms rows"; adb shell "sqlite3 $DB 'select _id, thread_id, type, address, creator, sub_id from sms order by _id desc limit 25'"
  echo "== sms columns"; adb shell "sqlite3 $DB 'pragma table_info(sms)'"
  echo "== threads"; adb shell "sqlite3 $DB 'select * from threads'"
  echo "== thread columns"; adb shell "sqlite3 $DB 'pragma table_info(threads)'"
  echo "== pdu columns"; adb shell "sqlite3 $DB 'pragma table_info(pdu)'"
  echo "== canonical addresses"; adb shell "sqlite3 $DB 'select * from canonical_addresses'"
  echo "== appops"; adb shell appops get io.github.mgelsinger.smsbridge 2>&1 | head -20
} > ../emulator-provider.txt 2>&1 || true
exit "$status"
