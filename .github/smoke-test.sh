#!/usr/bin/env bash
# Smoke test on an Android emulator: the app must survive start-up and a killed WebView
# renderer, and must show the crash report (with "Продолжить") after a Java crash.
set -uo pipefail
PKG=xyz.nekruz.cast
OUT=smoke
mkdir -p "$OUT"

fail() {
  echo "::error::$*"
  adb logcat -d > "$OUT/logcat.txt"
  shot "zz-failure"
  exit 1
}
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
alive() { [ -n "$(adb shell pidof "$PKG" | tr -d '\r')" ]; }
dump_ui() { adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb shell cat /sdcard/ui.xml > "$OUT/ui.xml"; }

adb logcat -c
# No system "app has stopped" dialog on top of the crash report.
adb shell settings put global show_first_crash_dialog 0
adb shell settings put secure show_first_crash_dialog_dev_option 0
adb install -r cast.apk || fail "install failed"

echo "== start"
adb shell am start -W -n "$PKG/.MainActivity"
sleep 30
alive || fail "app died after start"
shot 1-started

echo "== kill the WebView renderer"
adb root > /dev/null; sleep 5; adb wait-for-device
renderers=$(adb shell "ps -A -o PID,NAME" | tr -d '\r' | grep -i sandboxed_process | awk '{print $1}')
[ -n "$renderers" ] || fail "no WebView renderer process found"
adb shell kill -9 $renderers
sleep 20
alive || fail "app died after the renderer was killed"
adb logcat -d | grep -q "WebView renderer gone" || fail "onRenderProcessGone was not called"
shot 2-after-renderer-kill

echo "== Java crash"
# By PID: "am crash <package>" may hit the WebView renderer, which also runs under our package.
main_pid=$(adb shell pidof "$PKG" | tr -d '\r')
adb shell am crash "$main_pid"
sleep 5
if alive; then fail "am crash did not crash the app"; fi
adb shell am start -W -n "$PKG/.MainActivity"
sleep 6
shot 3-crash-report
dump_ui
grep -q 'class="android.widget.Button"' "$OUT/ui.xml" || fail "crash report screen not shown"
grep -q 'Exception' "$OUT/ui.xml" || fail "crash report has no stack trace"

bounds=$(grep -o 'text="Продолжить"[^>]*class="android.widget.Button"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' "$OUT/ui.xml" | grep -o '\[[0-9,]*\]\[[0-9,]*\]' | tr '[],' '   ')
read -r x1 y1 x2 y2 <<< "$bounds"
[ -n "${y2:-}" ] || fail "Продолжить button not found"
adb shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
sleep 20
alive || fail "app died after Продолжить"
dump_ui
if grep -q 'class="android.widget.Button"' "$OUT/ui.xml"; then fail "crash report still shown after Продолжить"; fi
shot 4-after-continue

echo "== restart: no stale report"
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity"
sleep 8
dump_ui
if grep -q 'class="android.widget.Button"' "$OUT/ui.xml"; then fail "an old report was shown again"; fi
alive || fail "app died after restart"

adb logcat -d > "$OUT/logcat.txt"
echo "Smoke test passed"
