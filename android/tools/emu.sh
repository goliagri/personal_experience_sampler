#!/usr/bin/env bash
# Day-to-day emulator driver. Headless (WSL2 has no reliable GPU passthrough); look at it via `shot`.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="$(sed -n 's/^sdk.dir=//p' "$HERE/../local.properties")"
export ANDROID_SDK_ROOT="$SDK" ANDROID_HOME="$SDK" PATH="$SDK/emulator:$SDK/platform-tools:$PATH"
AVD=pes; PKG=pes.app; OUT="${EMU_OUT:-$HERE/../../.emu}"; mkdir -p "$OUT"
cmd="${1:-help}"; shift || true
case "$cmd" in
  start)   # boots in background, waits until ready, disables animations
    adb get-state >/dev/null 2>&1 && { echo "already running"; exit 0; }
    nohup emulator -avd $AVD -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect \
      -no-snapshot-save "$@" >"$OUT/emulator.log" 2>&1 &
    adb wait-for-device
    until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
    for s in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global $s 0; done
    adb shell settings put system screen_off_timeout 1800000
    adb shell input keyevent 82 >/dev/null  # dismiss lock
    echo "booted: $(adb shell getprop ro.build.version.release | tr -d '\r')" ;;
  stop)    adb emu kill >/dev/null 2>&1 || true ;;
  install) (cd "$HERE/.." && ./gradlew -q :app:installDebug) && adb shell am start -n $PKG/.MainActivity >/dev/null && echo installed+launched ;;
  launch)  adb shell am start -n $PKG/.MainActivity ;;
  shot)    f="$OUT/${1:-shot}.png"; adb exec-out screencap -p >"$f"; echo "$f" ;;   # view with Read tool
  ui)      adb exec-out uiautomator dump /dev/tty 2>/dev/null | sed 's/></>\n</g' | grep -oE '(text|content-desc|resource-id)="[^"]+"|bounds="[^"]+"' ;;
  tap)     adb shell input tap "$1" "$2" ;;
  type)    adb shell input text "$(printf %s "$*" | sed 's/ /%s/g')" ;;
  key)     adb shell input keyevent "$1" ;;      # BACK, HOME, ENTER...
  log)     adb logcat -d -v time --pid="$(adb shell pidof $PKG | tr -d '\r')" "$@" ;;
  logall)  adb logcat -d -v time "$@" | grep -iE "pes|AndroidRuntime|AlarmManager" ;;
  clearlog) adb logcat -c ;;
  alarms)  adb shell dumpsys alarm | grep -A6 -E "$PKG" ;;
  notifs)  adb shell dumpsys notification --noredact | grep -E "pkg=$PKG|text=|title=" ;;
  doze)    adb shell dumpsys battery unplug; adb shell dumpsys deviceidle force-idle ;;
  undoze)  adb shell dumpsys deviceidle unforce; adb shell dumpsys battery reset ;;
  settime) adb root >/dev/null; adb shell "date -s '$1'"; echo "device now $(adb shell date)" ;;  # e.g. '2026-08-29 14:32:00'
  reset)   adb shell pm clear $PKG ;;
  db)      adb exec-out run-as $PKG cat files/pes.sqlite >"$OUT/pes.db" 2>/dev/null || adb exec-out "su root cat /data/data/$PKG/files/pes.sqlite" >"$OUT/pes.db"; echo "$OUT/pes.db" ;;
  shell)   adb shell "$@" ;;
  *) sed -n '/^case/,/^esac/{s/^  \([a-z]*\)) *#* *\(.*\)$/\1  \2/p}' "$0" | grep -v '^\*' ;;
esac
