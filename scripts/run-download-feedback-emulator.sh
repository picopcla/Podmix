#!/bin/sh
set -eu

capture_dir="artifacts/emulator-captures"
mkdir -p "$capture_dir"

adb install -r out/app-ng-debug.apk
adb shell pm grant com.podmix.next.ng android.permission.POST_NOTIFICATIONS
adb shell am start -n com.podmix.next.ng/com.podmix.next.MainActivity

pid=""
socket_found=""
attempt=1
while test "$attempt" -le 120; do
  pid=$(adb shell pidof com.podmix.next.ng | tr -d '\r')
  if test -n "$pid" && adb shell cat /proc/net/unix | grep -q "webview_devtools_remote_$pid"; then
    socket_found=yes
    break
  fi
  sleep 2
  attempt=$((attempt + 1))
done
test "$socket_found" = yes

adb forward tcp:9222 "localabstract:webview_devtools_remote_$pid"
node scripts/test-download-feedback-emulator.mjs

adb install -r out/app-ng-release.apk
adb logcat -c
adb shell am start -n com.podmix.next.ng/com.podmix.next.MainActivity
sleep 15
adb shell pidof com.podmix.next.ng
adb shell dumpsys package com.podmix.next.ng | grep -q 'versionCode=10217'
adb shell dumpsys package com.podmix.next.ng | grep -q 'versionName=1.0.217-ng'
adb logcat -d -b crash > "$capture_dir/crash.txt"
test ! -s "$capture_dir/crash.txt"
adb exec-out screencap -p > "$capture_dir/release-launch.png"
