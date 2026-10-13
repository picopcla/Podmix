#!/bin/sh
set -eu

capture_dir="artifacts/emulator-captures"
mkdir -p "$capture_dir"
python3 -m http.server 8765 --directory "$capture_dir" > "$capture_dir/http-server.log" 2>&1 &
fixture_server_pid=$!
trap 'kill "$fixture_server_pid" 2>/dev/null || true' EXIT

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
adb shell am force-stop com.podmix.next.ng
adb shell am start -n com.podmix.next.ng/com.podmix.next.MainActivity --es podmixSourceId source-entities-release --es podmixEpisodeId episode-entities-release
sleep 15
adb shell pidof com.podmix.next.ng
adb shell dumpsys package com.podmix.next.ng | grep -q 'versionCode=10218'
adb shell dumpsys package com.podmix.next.ng | grep -q 'versionName=1.0.218-ng'
node scripts/android-ui-text.mjs assert 'Find Your Harmony & Friends'
node scripts/android-ui-text.mjs tap 'Find Your Harmony & Friends'
sleep 3
node scripts/android-ui-text.mjs assert 'Mark Sherry, BiXX & Sue McLaren'
node scripts/android-ui-text.mjs assert 'Believe & Receive'
adb exec-out screencap -p > "$capture_dir/release-entities-tracklist.png"
node scripts/android-ui-text.mjs tap 'Believe & Receive'
sleep 5
adb exec-out screencap -p > "$capture_dir/release-entities-mini-player.png"
adb shell input swipe 540 1700 540 1100 450
sleep 2
node scripts/android-ui-text.mjs assert 'Lecteur en cours'
adb exec-out screencap -p > "$capture_dir/release-entities-full-player.png"
adb logcat -d -b crash > "$capture_dir/crash.txt"
test ! -s "$capture_dir/crash.txt"
adb exec-out screencap -p > "$capture_dir/release-launch.png"
